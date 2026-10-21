package dev.myavalon.game;

import dev.myavalon.MyAvalonMod;
import dev.myavalon.stats.AvalonStats;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.PlaySlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameOption;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.sound.ModSounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.client.gui.components.Tooltip;

/**
 * The Resistance: Avalon, played on Charta's public game API.
 *
 * <h2>What is reused and what is written here</h2>
 *
 * <p>Card movement, the drag/drop payloads, the carry slot, the audio, the history panel and the table
 * block are all Charta's. Nothing about them is reimplemented. What this class owns is the hidden-role
 * state machine: who is on which side, what each phase waits for, how a vote and a quest are counted,
 * and what the assassin does at the end.
 *
 * <h2>Giving the hidden-role game a physical form</h2>
 *
 * <p>Every seat is dealt a private hand of five cards and it never leaves the table:
 *
 * <pre>
 *   role card        one of Merlin / Percival / Loyalist / Assassin / ... — the identity, face up to
 *                    its owner and a card back to everybody else
 *   yes / no         the two vote cards, played face up onto the pile when the table votes
 *   success / fail   the two quest cards, played face up onto the pile by the quest team
 * </pre>
 *
 * <p>That mapping is what lets a hidden-role game run on a card table without a single new UI
 * primitive: identity is a card the owner can look at, a vote is a card dropped on the pile, and a
 * sabotage is a card dropped on the pile. Playing <em>is</em> the interaction, so the screen inherits
 * drag, hover, sounds and animation from Charta for free.
 *
 * <p>Cards are handed back after each vote and each quest, so a player always holds exactly one of each
 * pair while not acting; {@link #giveBack} does that in one place. The one asymmetry is deliberate:
 * a good seat may not play its fail card ({@link #canPlay}), because a good player sabotaging is never
 * correct — so the fail card is simply greyed out for them.
 *
 * <h2>Phases</h2>
 *
 * <p>{@code DEALING -> PROPOSE -> VOTE -> QUEST -> (PROPOSE ... | ASSASSIN | OVER)}. {@link #runGame()}
 * is the single entry point and dispatches on the phase, so the whole flow is readable in one place and
 * every transition simply re-enters it.
 *
 * <h2>Two kinds of turn, and one phase that is neither</h2>
 *
 * <p>A quest card is a <em>card</em> play: the actor drops a card on the pile, which is Charta's normal
 * play path. A proposal or the assassination is an <em>action</em> turn: nothing leaves the hand, so the
 * actor answers with an empty {@link GamePlay} whose {@code slot} field carries the chosen team mask
 * ({@link #PROPOSE}) or the chosen seat ({@link #ASSASSIN}). Everything runs through one registration,
 * {@code afterPlay}, and a bot reaches all of it through the same {@code getBestPlay} Charta already
 * calls — so there is exactly one engine, not two.
 *
 * <p>Neither the vote nor the quest is a <em>turn</em>, though. Both open for a set of seats at once
 * ({@link #beginVoting}, {@link #beginQuest}) and both end when that set drains, which is why neither has
 * a "next" to hand the table to.
 *
 * <p>A vote is neither. The whole table is asked at once ({@link #beginVoting}) and the round ends when
 * the last card is in, because that is how the printed game takes it — everybody puts a fist out together
 * and every answer counts. {@code currentPlayer} therefore stays on the leader throughout the phase and
 * nothing but {@link #canPlay} and the bot driver in {@link #tickVoters} depends on it.
 *
 * <h2>Seats versus players</h2>
 *
 * <p>{@code seat} is always an index into {@code players}, and the ring order is the roster order:
 * Charta hands the game its players already ordered clockwise around the table
 * ({@code CardTableBlockEntity.getOrderedPlayers}). "The seat after the leader" is therefore a plain
 * {@code +1} on that ring, and every place that names a side reads the same value on both sides.
 */
public class AvalonGame extends Game<AvalonGame, AvalonMenu> {

    /** Seats the table can hold. Charta's own games cap at eight; Avalon's printed board goes to ten. */
    public static final int MAX_SEATS = 10;

    /** Hard cap on consecutive rejected proposals; the fifth rejection hands the game to evil. */
    public static final int MAX_REJECTS = 5;

    /** Ticks the finished vote / quest stays on the table before it is swept and the next phase starts. */
    private static final int LINGER = 22;
    /**
     * Ticks a resolved quest stays up. Longer than {@link #LINGER} because the client plays the reveal
     * there: the played cards are face-downbacks that get cut, gathered, held, spread and only then turned
     * over, and that has to finish while the pile is still on the table.
     *
     * <p>Kept in step with {@code AvalonScreen.REVEAL_MS} — currently {@code 2680}ms, so 54 ticks — and then
     * given a beat to read the finished spread before the pile is swept. It was 46 ticks to a 1980ms act,
     * and when the act grew the flip was the leg that fell off the end: the phase moved on with the cards
     * still face-down, so the row was rearranged but never turned over.
     *
     * <p>The beat after the act — 3720ms of it, i.e. the whole face-up row sitting there — is the part that
     * is actually being read, so it is the part worth a generous number: five turned-over cards is the one
     * piece of evidence a quest leaves behind.
     */
    private static final int QUEST_LINGER = 128;
    /**
     * A bot seat's own beat before it votes, in ticks: {@code MIN} plus a random spread. The lower bound
     * keeps the whole table from answering on the same frame -- several votes landing together read as a
     * single pop rather than as a vote.
     */
    private static final int VOTE_DELAY_MIN = 6;
    private static final int VOTE_DELAY_SPREAD = 18;
    /**
     * Ticks the final result banner stays up before the reveal. Kept short: the winner line and the
     * settlement already read on the table, and 90 ticks (~4.5 s) made every match end on a long dead
     * pause. 50 leaves the banner up long enough to read without stalling.
     */
    private static final int RESULT_LINGER = 50;

    // -----------------------------------------------------------------------------------------
    // Container-data layout. Values are small ints, so one slot each is enough and no custom packet
    // is needed for state: Charta already ships the data-slot channel.
    // -----------------------------------------------------------------------------------------

    public static final int SYNC_PHASE = 0;
    public static final int SYNC_QUEST = 1;
    public static final int SYNC_LEADER = 2;
    public static final int SYNC_REJECTS = 3;
    public static final int SYNC_TEAM = 4;
    public static final int SYNC_PENDING = 5;
    public static final int SYNC_VOTED = 6;
    public static final int SYNC_YES = 7;
    public static final int SYNC_NO = 8;
    public static final int SYNC_QUESTED = 9;
    public static final int SYNC_FAILS = 10;
    public static final int SYNC_TEAM_SIZE = 11;
    public static final int SYNC_ASSA_SELF = 12;
    public static final int SYNC_TARGET = 13;
    public static final int SYNC_WINNER = 14;
    public static final int SYNC_KNOWN = 15;
    public static final int SYNC_ROLE = 16;
    /** One slot per seat: the role it was dealt, filled in only once the game is over. */
    public static final int SYNC_ROLE_BASE = 17;
    /** One slot per quest: {@code 0} pending, {@code 1} succeeded, {@code 2} sabotaged. */
    public static final int SYNC_MISSION_BASE = SYNC_ROLE_BASE + MAX_SEATS;
    /**
     * One slot: an id unique to the current deal.
     *
     * <p>Exists because a client cannot otherwise tell "the next game" from "the same game with the
     * screen reopened". A screen and its menu are both rebuilt every time a player closes the table and
     * opens it again — an inventory press, an E press, a walk away and back — while the address of the
     * live game object on the client changes with them, so nothing stable survives to hang a once-per-game
     * flag on. This does: it comes down the same data channel as everything else and only ever moves on a
     * fresh deal.
     */
    public static final int SYNC_MATCH = SYNC_MISSION_BASE + AvalonRole.QUESTS;
    /**
     * One slot: {@code 1} once the game has declared itself over, i.e. once the finished table has been held
     * for its full linger and the block is about to sweep it.
     *
     * <p>What the client closes its own window on. A finished table is not worth sitting in front of: the
     * reveal and the report have been read, the report is in the chat, and the seats want the world back —
     * so the screen shuts itself instead of waiting for an Esc. Sent as a slot rather than inferred because
     * the hold is the server's business and a client guessing at it would close early.
     */
    public static final int SYNC_OVER = SYNC_MATCH + 1;
    /**
     * One slot: the seats that have answered "no" (or abstained) in the running vote, as a bitmask.
     *
     * <p>A mask rather than one slot per seat because the tally line wants the names and nothing else does:
     * it is what the table reads to see who is holding the round up, and it is the only per-seat vote fact
     * the client cannot derive -- the two counters say how many, this says which.
     */
    public static final int SYNC_NO_VOTERS = SYNC_OVER + 1;
    /**
     * One slot per seat, two counters packed into it: how many times that seat voted a team carrying evil
     * down, and how many teams with no evil on them it proposed.
     *
     * <p>Mirrored rather than recomputed because the report names both and the report is drawn on the
     * <em>client</em> -- see {@link #summaryLines}. A byte each is plenty: five quests mean five proposals,
     * and a seat cannot vote down more teams than were proposed.
     */
    public static final int SYNC_STATS_BASE = SYNC_NO_VOTERS + 1;
    /**
     * Two slots: how many of each role this deal put on the table, four bits per role ordinal.
     *
     * <p>Public information at the start of a game -- the printed setup names every identity in play --
     * but the client cannot derive it: the identity setup is an option, the cunning-assassin rule can
     * drop Oberon, and a short table pads itself with loyalists. Counts top out at {@code MAX_SEATS},
     * well inside a nibble, but eight nibbles are 32 bits and a container data slot is a <b>short</b>:
     * the value arrives through {@code ClientboundContainerSetDataPacket}, which writes and reads it as
     * a signed 16-bit number. So the census is split across two slots and the client reassembles it.
     */
    public static final int SYNC_CENSUS = SYNC_STATS_BASE + MAX_SEATS;
    /** The upper four role ordinals' counts; see {@link #SYNC_CENSUS}. */
    public static final int SYNC_CENSUS_HIGH = SYNC_CENSUS + 1;
    /** One slot: {@code 1} while the designated-quest rule is on. */
    public static final int SYNC_DESIGNATED = SYNC_CENSUS_HIGH + 1;
    /**
     * One slot: the quests the leader may pick right now, as a bitmask.
     *
     * <p>Mirrored rather than derived because the lock on the fifth quest depends on how many quests have
     * <em>succeeded</em>, which is a rule fact rather than a property of the board. The screen draws the
     * pickable slots from this exactly as the server validates them.
     */
    public static final int SYNC_OPEN_QUESTS = SYNC_DESIGNATED + 1;
    public static final int SYNC_SIZE = SYNC_OPEN_QUESTS + 1;

    /**
     * Dealing pace, in ticks. The table sits still for {@link #DEAL_SETTLE} ticks (the stack of face-down
     * cards appears in the middle) and then one seat is dealt every {@link #DEAL_STEP} ticks.
     *
     * <p>{@code AvalonScreen} reads the same two constants to fly each card from the middle to its seat,
     * timed so the flight lands exactly when the server hands that seat its hand -- so the two have to be
     * changed together.
     */
    public static final int DEAL_SETTLE = 10;
    public static final int DEAL_STEP = 5;
    /** Beat between the identities being dealt and the ability-card stack appearing. */
    public static final int DEAL_PAUSE = 12;
    /** Gap between two ability cards. Four per seat, dealt one after another. */
    public static final int DEAL_FEATURE_STEP = 4;

    /** Action codes the client sends instead of playing a card. See {@link #submitAction}. */
    public static final int TOGGLE_BASE = 0;
    public static final int PICK_BASE = 16;
    public static final int CONFIRM = 32;

    public enum Phase {
        DEALING,
        PROPOSE,
        VOTE,
        QUEST,
        ASSASSIN,
        OVER
    }

    /** Who won: {@code 0} undecided, {@code 1} good, {@code 2} evil. */
    public static final int SIDE_NONE = 0;
    public static final int SIDE_GOOD = 1;
    public static final int SIDE_EVIL = 2;

    private final GameOption.Bool ENABLE_BOTS = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.enable_bots"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.enable_bots.description")
    );

    /** Seats to fill the table to. Humans always win over this number, bots make up the difference. */
    private final GameOption.Number SEATS = new SteppedOption(
            AvalonRole.MIN_PLAYERS, AvalonRole.MIN_PLAYERS, MAX_SEATS,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.seats"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.seats.description")
    );

    private final GameOption.Number BOT_STRENGTH = new SteppedOption(
            AvalonStrength.NORMAL.ordinal(), 0, AvalonStrength.values().length - 1,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.bot_strength"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.bot_strength.description")
    );

    private final GameOption.Bool CUNNING_ASSASSIN = new GameOption.Bool(
            true,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.cunning_assassin"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.cunning_assassin.description")
    );

    /**
     * Which identities are in play: a button that opens {@code AvalonRolesScreen}.
     *
     * <p>Stored in the one byte an option has room for, and read as a mask rather than as a number:
     * {@link AvalonRole#CUSTOM_FLAG} marks "this mask <em>is</em> the setup" and the optional roles' bits sit
     * below it. Clear means the printed table, which is why a fresh game needs no tables copied anywhere.
     *
     * <p>A button rather than something that edits in place, because this is the one option with more than a
     * slider's worth of state: it wants a screen with a row per role and the hints to hand.
     */
    private final GameOption<Integer> ROLES = new GameOption<>(0,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.roles"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.roles.description")) {

        @Override
        protected byte toByte(Integer value) {
            return (byte) (int) value;
        }

        @Override
        protected Integer fromByte(byte value) {
            // Through an AND, not a cast: a mask with the custom flag set arrives as a negative byte.
            return value & 0xFF;
        }

        @Override
        public Widget getWidget(Consumer<Integer> consumer, net.minecraft.client.gui.Font font, int width, int height, boolean showcase) {
            net.minecraft.client.gui.components.Button button = net.minecraft.client.gui.components.Button.builder(
                            net.minecraft.network.chat.Component.translatable("rule.myavalon.roles"),
                            b -> {
                                net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
                                // The consumer rides along into the sub-screen: the option's own one is
                                // package-private and out of reach, and the screen has to be told when the
                                // set changes -- it is what re-checks its own buttons, and without it an
                                // identity edit left Save greyed and the setup was gone at the next table.
                                minecraft.setScreen(new AvalonRolesScreen(minecraft.screen, AvalonGame.this,
                                        consumer));
                            })
                    .bounds(0, 0, width, height)
                    .build();
            button.active = !showcase;
            return new Widget(button);
        }
    };

    /**
     * The printed optional rule: the leader names the quest as well as the team.
     *
     * <p>With it on, a proposal starts with no quest at all; the leader clicks a slot on the board and
     * the team size, the two-fail quest and the fifth-quest lock all come from <em>that</em> slot rather
     * than from a running counter. A completed quest is out of the running for the rest of the game, so
     * the five slots are a pool instead of an order. See {@link #openQuestMask()}.
     */
    private final GameOption.Bool DESIGNATED_QUESTS = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.designated_quests"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.designated_quests.description")
    );

    /**
     * How many chairs the table is set for, whether or not the roster has been rebuilt to match.
     *
     * <p>The seat slider writes its value straight into {@link #SEATS} — {@code setRawOptions}, and with it
     * {@link #rebuildRoster}, only runs when the options screen is <em>entered</em> or when a game is
     * started. So a screen opened from the options screen and reading {@code players.size()} sees the table
     * as it was before the player touched the slider, which is exactly the bug the identity setup used to
     * have: turning the seats up left its count line, and the set it was sizing, where they were.
     *
     * <p>Humans floor the number for the same reason {@code rebuildRoster} targets {@code max(seats,
     * humans)}: the slider adds bots to a table, never removes a person from it.
     */
    public int tableSize() {
        int humans = 0;
        for (CardPlayer player : players) {
            if (!isBot(player)) {
                humans++;
            }
        }
        return Math.max(AvalonRole.MIN_PLAYERS, Math.min(MAX_SEATS, Math.max(SEATS.get(), humans)));
    }

    /** The identity setup, as the mask described on {@link #ROLES}. */
    public int roleMask() {
        return ROLES.get();
    }

    public void setRoleMask(int mask) {
        ROLES.set(mask);
    }

    /** Staging pile used while dealing. Not added as a slot, so it never renders. */
    private final List<Card> staging = new ArrayList<>();

    /** The pile votes and quest cards are played onto. Slot 0, so it is the drop target. */
    private final PlaySlot pile;

    private final Random random = new Random();

    private Phase phase = Phase.DEALING;
    /**
     * The quest the table is on.
     *
     * <p>Classic play reads it as a cursor into the five slots; with {@link #designatedQuests()} the
     * leader writes it directly and it is {@code -1} between proposals, because the next quest has not
     * been named yet. Every consumer that loops over <em>finished</em> quests must therefore walk the
     * mission array rather than a prefix of it -- see {@code AvalonBots}.
     */
    private int quest;
    private int leaderSeat;
    private int rejects;
    private int seat;

    /**
     * Process-wide deal counter, and this game's own id off it.
     *
     * <p>Static rather than per-game so that two tables in one session can never hand out the same id: a
     * per-object counter would make every table's first game id {@code 1}, and a player who had already
     * seen game {@code 1} at one table would then be shown nothing at the next one. See {@link #SYNC_MATCH}.
     */
    private static int matchSerial;
    private int matchId;

    /** Seats this game was dealt a role to, indexed by seat. Empty until the deal finishes. */
    private final List<AvalonRole> roles = new ArrayList<>();
    /** Four bits per role ordinal: how many of that role this deal put on the table. */
    private int roleCensus;
    private int merlinSeat = -1;
    private int morganaSeat = -1;
    private int assassinSeat = -1;

    /** Set once the finished game has been written to the server's record, so a repeated reveal is free. */
    private boolean statsRecorded;
    /** {@code true} only once a side has actually won; an abandoned table stays false and records nothing. */
    private boolean completed;

    /** The team the leader has put forward, as a seat bitmask; confirmed at the end of PROPOSE. */
    private int teamMask;
    /** The leader's in-progress selection while proposing, or the assassin's pick. Mirrored live. */
    private int pendingMask;
    /** Seat the assassin has picked, or {@code -1}. */
    private int pendingTarget = -1;

    /** Which quests succeeded ({@code 1}) or were sabotaged ({@code 2}); {@code 0} while unplayed. */
    private final int[] missions = new int[AvalonRole.QUESTS];

    /** The team sent on each quest, as a seat mask; {@code 0} for quests that have not run. */
    private final int[] questTeams = new int[AvalonRole.QUESTS];

    /** The card each seat played in the running vote / quest, so it can be handed straight back. */
    private final java.util.Map<Integer, Card> voteCards = new java.util.HashMap<>();
    private final java.util.Map<Integer, Card> questCards = new java.util.HashMap<>();


    /**
     * Seats the running vote is still waiting on.
     *
     * <p>A vote is taken all at once, so this is a <em>set</em> and never a queue: every seat is asked
     * at the same moment (see {@link #beginVoting}), the order answers arrive in is the order the table
     * happened to click, and the round is over when the set empties.
     */
    private final java.util.Set<Integer> awaitingVotes = new java.util.LinkedHashSet<>();
    /**
     * Team members who still owe a quest card.
     *
     * <p>The counterpart of {@link #awaitingVotes} for the quest, which is no longer a turn either: the team
     * is registered all at once and the quest resolves when this drains. See {@code beginQuest}.
     */
    private final java.util.Set<Integer> awaitingQuest = new java.util.LinkedHashSet<>();
    /** Per team member, ticks left before our own driver lets a bot put its card down. */
    private int[] questDelay = new int[0];

    /**
     * Per seat, the ticks left before our own driver lets a bot cast its vote.
     *
     * <p>Every seat is asked at once, so nothing else paces the bots: Charta's {@code AutoPlayer} only
     * answers on the turn of the single current player, and a simultaneous vote has no such turn. The
     * countdown is what still makes five bots answer like five people instead of one clap.
     */
    private int[] voterDelay = new int[0];

    private final java.util.Set<Integer> voted = new java.util.LinkedHashSet<>();
    private int yesVotes;
    private final java.util.Set<Integer> quested = new java.util.LinkedHashSet<>();
    private int questFails;

    /**
     * Per seat, how many times it voted down a team that turned out to hold an evil seat.
     *
     * <p>The one thing the summary at the end can say about judgement rather than about the deal: a "no"
     * against a team carrying evil is the closest thing this game has to a correct read, and counting it
     * costs nothing since the vote resolution already has both the team and the roles in hand.
     */
    private int[] sharpNo = new int[AvalonRole.MAX_PLAYERS];

    /**
     * Per seat, how many teams it proposed that held no evil seat at all.
     *
     * <p>The other half of the same idea as {@link #sharpNo}: one is a seat reading a team the leader built,
     * this one is a seat building a team nobody can sabotage. Counted on the way into the vote rather than
     * on the way out, because whether the table approved it is a second question -- a clean team that got
     * voted down was still picked right, and the leader is the one who picked it.
     */
    private int[] cleanTeams = new int[AvalonRole.MAX_PLAYERS];

    private int winner = SIDE_NONE;

    private boolean awaiting;
    private long turnToken;
    private int lingerTicks;
    @Nullable
    private Runnable afterLinger;

    public AvalonGame(List<CardPlayer> players, Deck deck) {
        // A mutable copy: rebuildRoster() adds bot seats in place, and the roster is the ring order.
        super(new ArrayList<>(players), deck);
        this.pile = addSlot(new PileSlot(this, new LinkedList<>()));
    }

    // ---------------------------------------------------------------------------------------------
    // Roster and options
    // ---------------------------------------------------------------------------------------------

    /**
     * Seat of {@code player}, by identity.
     *
     * <p>Deliberately not {@code List.indexOf}: {@link AvalonBot#equals} answers {@code true} for any
     * real player so the table block keeps the game alive, which would make {@code indexOf} report the
     * first real seat for every bot.
     */
    public int getSeat(@Nullable CardPlayer player) {
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i) == player) {
                return i;
            }
        }
        return -1;
    }

    public CardPlayer playerAt(int seat) {
        return players.get(Math.floorMod(seat, players.size()));
    }

    /**
     * Seats the current player and remembers the index.
     *
     * <p>The base class only keeps the {@link CardPlayer}, and {@code indexOf} cannot be trusted to
     * recover the index because {@link AvalonBot#equals} answers {@code true} for any real seat (see
     * {@link #getSeat}). Every phase transition goes through here, so this is the one place the seat
     * index is written and {@link #getSeatOfCurrentPlayer} can hand it out for the data-slot mirror.
     */
    @Override
    public void setCurrentPlayer(int index) {
        if (players.isEmpty()) {
            return;
        }
        this.seat = Math.floorMod(index, players.size());
        super.setCurrentPlayer(this.seat);
    }

    /** Seat {@code offset} seats clockwise from {@code from}, on the roster ring. */
    public int seatFrom(int from, int offset) {
        int size = players.size();
        return size == 0 ? 0 : Math.floorMod(from + offset, size);
    }

    private static boolean isBot(@Nullable CardPlayer player) {
        return player instanceof AvalonBot || (player != null && player.getEntity() == null);
    }

    /**
     * Fills the empty chairs with bots from the current option, up to the chosen table size.
     *
     * <p>Called from {@link #setRawOptions}, i.e. right after construction on the server <em>and</em> on
     * the client, which is what keeps the two rosters identical — the layout, the ring order and every
     * mirrored seat index all depend on both sides agreeing about how many chairs there are.
     */
    private void rebuildRoster() {
        if (players.isEmpty()) {
            // Template games (option previews) stay player-less on purpose.
            return;
        }
        // Strip every bot from a previous pass -- but never a roster that is entirely bots: the debug
        // harness builds its table from negative entity ids alone, and stripping those would leave nobody
        // at the table.
        //
        // Every bot rather than just the tail, which is what this did first and which quietly broke the
        // seat slider: bots are appended, so a trailing strip is enough <em>until</em> the roster order is
        // anything but humans-then-bots -- and it is, since the roster is the ring and the ring is rotated
        // to put the viewer first. A bot left in the middle survived every rebuild, and because the target
        // is {@code max(seats, players.size())} a surviving bot also raised the floor: turning the seats
        // down did nothing at all, turning them up added one bot per press, and the numbering restarted
        // from one each pass so the names came out gapped.
        if (players.stream().anyMatch(player -> !isBot(player))) {
            players.removeIf(AvalonGame::isBot);
        }
        if (!ENABLE_BOTS.get()) {
            return;
        }
        AvalonStrength strength = strength();
        int target = Math.min(MAX_SEATS, Math.max(SEATS.get(), players.size()));
        int number = 1;
        while (players.size() < target) {
            players.add(new AvalonBot(number++, strength));
        }
    }

    @Override
    public void setRawOptions(byte[] options) {
        super.setRawOptions(options);
        rebuildRoster();
    }

    public AvalonStrength strength() {
        return AvalonStrength.of(BOT_STRENGTH.get());
    }

    /**
     * A whole-number rule whose slider moves in whole steps.
     *
     * <h2>Why this is not just {@code GameOption.Number}</h2>
     *
     * <p>Because the stock one is unusable for a setting like "how many chairs", and in a way that reads
     * as the control being broken rather than as anything being wrong with the value.
     *
     * <p>Charta's slider derives its own position from the value on every change
     * ({@code slider.setValue(normalise(value))} in the option's consumer) and snaps it back there. Two
     * consequences, both gone here. <b>One:</b> the keyboard step is one <em>pixel</em> of the track —
     * vanilla does {@code value + 1 / (width - 8)} — and against a consumer that immediately rewrites the
     * position from the value, a one-pixel nudge does not change the integer at all, so the position is
     * reset to exactly where it started. Pressing right on the seats slider therefore does <em>nothing</em>,
     * however many times it is pressed. <b>Two:</b> the position is authored as {@code value / (max - min)}
     * with {@code min} subtracted from the value first, which lands on a grid point only when the value is
     * exactly on one — so the knob has to be dragged past the next grid line before the number moves at all.
     *
     * <p>Here the position and the value are exact inverses on the grid, a drag or a click picks the
     * <em>nearest</em> whole step rather than the one below, and left/right move exactly one step. The two
     * settle in one pass instead of fighting.
     */
    private static final class SteppedOption extends GameOption.Number {

        private final int low;
        private final int high;

        /**
         * Our own copy of the callback, because {@code GameOption.consumer} has no modifier: Charta's
         * {@code Number} lives in the same package as {@code GameOption} and can assign it, and a subclass
         * out here cannot. Overriding the two setters covers every path that changes an option — the
         * widget, {@code setRawOptions}, the reset button — so nothing can slip past it.
         */
        @Nullable
        private Consumer<Integer> watcher;

        private SteppedOption(int value, int low, int high, net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component description) {
            super(value, low, high, title, description);
            this.low = low;
            this.high = high;
        }

        @Override
        public void set(Integer value) {
            super.set(value);
            notifyWatcher(value);
        }

        @Override
        public void setValue(byte value) {
            super.setValue(value);
            notifyWatcher(this.get());
        }

        private void notifyWatcher(int value) {
            if (watcher != null) {
                watcher.accept(value);
            }
        }

        @Override
        public Widget getWidget(Consumer<Integer> consumer, net.minecraft.client.gui.Font font, int width, int height, boolean showcase) {
            StepSlider slider = new StepSlider(width, height, label(this.get()), position(this.get()), low, high);
            slider.sink = this::set;
            this.watcher = value -> {
                slider.setPosition(position(value));
                slider.setLabel(label(value));
                consumer.accept(value);
            };
            slider.setTooltip(net.minecraft.client.gui.components.Tooltip.create(this.getDescription()));
            slider.active = !showcase;
            return new Widget(slider);
        }

        private net.minecraft.network.chat.Component label(int value) {
            return this.getTitle().copy().append(": ").append(Integer.toString(value));
        }

        /** Where the knob sits for a value; {@link #nearest} is its inverse, so the two agree at once. */
        private double position(int value) {
            return high == low ? 0.0D : (value - low) / (double) (high - low);
        }

        /** The value a knob position means: the step it is nearest to, not merely the one it is past. */
        private int nearest(double position) {
            return net.minecraft.util.Mth.clamp((int) Math.round(net.minecraft.util.Mth.lerp(position, low, high)), low, high);
        }
    }

    /**
     * The slider {@link SteppedOption} drives: whole steps, keyboard included.
     *
     * <p>A class of its own rather than an anonymous one because of two access quirks it has to work
     * around. {@code AbstractSliderButton.setValue(double)} is private and unreachable from outside, so
     * the position has to be written through a method of our own — which means the option's callback needs
     * a type to call it on. And {@code applyValue} is the only hook the superclass offers, which is what
     * makes a drag and a keypress arrive at the same place.
     */
    private static final class StepSlider extends net.minecraft.client.gui.components.AbstractSliderButton {

        private final int low;
        private final int high;
        @Nullable
        private Consumer<Integer> sink;

        private StepSlider(int width, int height, net.minecraft.network.chat.Component label, double position, int low, int high) {
            super(0, 0, width, height, label, position);
            this.low = low;
            this.high = high;
        }

        /** The superclass's private {@code setValue}, re-done: assign, re-label, apply. */
        private void setPosition(double position) {
            if (position == this.value) {
                return;
            }
            this.value = position;
            this.updateMessage();
            this.applyValue();
        }

        private void setLabel(net.minecraft.network.chat.Component label) {
            this.setMessage(label);
        }

        /** The label is written by the option on every change, so this has nothing left to do. */
        @Override
        protected void updateMessage() {
        }

        @Override
        protected void applyValue() {
            if (sink != null) {
                sink.accept(net.minecraft.util.Mth.clamp((int) Math.round(net.minecraft.util.Mth.lerp(this.value, low, high)), low, high));
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            super.onClick(mouseX, mouseY);
            // Clicking a slider is what tells a player it can be driven from the keyboard, so it has to be
            // what focuses it too; otherwise the arrows have nothing to act on.
            this.setFocused(true);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            int step = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
            if (step == 0 || !this.isFocused() || sink == null) {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            // One whole step, not vanilla's one pixel: see SteppedOption.
            sink.accept(net.minecraft.util.Mth.clamp(Math.round((float) net.minecraft.util.Mth.lerp(this.value, low, high)) + step, low, high));
            return true;
        }
    }

    @Override
    public Predicate<Deck> getDeckPredicate() {
        return deck -> !deck.getSuits().isEmpty() && deck.getSuits().containsAll(List.of(
                AvalonCards.GOOD, AvalonCards.EVIL, AvalonCards.VOTE, AvalonCards.QUEST));
    }

    @Override
    public Predicate<Card> getCardPredicate() {
        // Called from the super constructor, so it must not touch instance state.
        return AvalonCards::isAvalonSuit;
    }

    @Override
    public int getMinPlayers() {
        return ENABLE_BOTS.get() ? 1 : AvalonRole.MIN_PLAYERS;
    }

    @Override
    public int getMaxPlayers() {
        return MAX_SEATS;
    }

    @Override
    public Optional<net.minecraft.network.chat.Component> playerPredicate(List<CardPlayer> players) {
        if (!ENABLE_BOTS.get() && (players.size() < AvalonRole.MIN_PLAYERS || players.size() > MAX_SEATS)) {
            return Optional.of(net.minecraft.network.chat.Component.translatable("message.myavalon.seat_count",
                    AvalonRole.MIN_PLAYERS, MAX_SEATS));
        }
        return Optional.empty();
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of(ENABLE_BOTS, SEATS, BOT_STRENGTH, CUNNING_ASSASSIN, ROLES, DESIGNATED_QUESTS);
    }

    /** Whether this table plays the designated-quest optional rule. */
    public boolean designatedQuests() {
        return DESIGNATED_QUESTS.get();
    }

    @Override
    public AvalonMenu createMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory, AbstractCardMenu.Definition definition) {
        return new AvalonMenu(containerId, inventory, definition);
    }

    /**
     * A hand slot that hands out one card per click.
     *
     * <p>Charta's default {@code removeAll()} says "a click takes this card and everything after it",
     * which would drop the whole fan on the pile at once.
     */
    @Override
    protected GameSlot createPlayerHand(CardPlayer player) {
        return new GameSlot(player.hand()) {
            @Override
            public boolean removeAll() {
                return false;
            }
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Dealing
    // ---------------------------------------------------------------------------------------------

    @Override
    public void startGame() {
        pile.clear();
        staging.clear();
        // The deal is a queue of ticks played by {@code Game.tick} while {@code isGameReady} is false, and
        // that queue is <em>not</em> part of the state the block resets. A start on a game that still had
        // one running (a restart, a debug harness driving several games on one instance, anything that
        // starts a deal twice) therefore used to replay the old queue's tail against the freshly filled
        // staging list -- and since a queued deal action reads {@code staging.get(index)} when it runs, the
        // same seat was handed the same card twice. Six cards in a fan is what that looks like.
        scheduledActions.clear();
        voted.clear();
        quested.clear();
        awaitingVotes.clear();
        roles.clear();
        missionsReset();
        awaiting = false;
        turnToken++;
        lingerTicks = 0;
        afterLinger = null;
        // With the designated-quest rule there is no "first quest": the first leader names one.
        quest = designatedQuests() ? -1 : 0;
        rejects = 0;
        teamMask = 0;
        pendingMask = 0;
        pendingTarget = -1;
        yesVotes = 0;
        questFails = 0;
        java.util.Arrays.fill(sharpNo, 0);
        java.util.Arrays.fill(cleanTeams, 0);
        winner = SIDE_NONE;
        roleCensus = 0;
        statsRecorded = false;
        completed = false;
        merlinSeat = -1;
        morganaSeat = -1;
        assassinSeat = -1;
        phase = Phase.DEALING;
        matchId = ++matchSerial;

        for (CardPlayer player : players) {
            player.resetPlay();
            getPlayerHand(player).clear();
            getCensoredHand(null, player).clear();
        }

        dealRoles();
        leaderSeat = players.isEmpty() ? 0 : random.nextInt(players.size());

        // The cards each seat holds for the whole game. Scheduled one seat at a time so the table
        // animates the deal instead of filling the fans on the first tick; the hand is sorted as it is
        // built, so the role is always the leftmost card and the pairs stay together.
        List<Card> hand = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            hand.add(AvalonCards.roleCard(roles.get(i)));
            hand.add(AvalonCards.voteCard(true));
            hand.add(AvalonCards.voteCard(false));
            hand.add(AvalonCards.questCard(true));
            hand.add(AvalonCards.questCard(false));
        }
        staging.addAll(hand);
        // A beat of stillness first, so the middle card stack is on screen before anything moves.
        beat(DEAL_SETTLE);

        // Act one: one identity card per seat, thrown out of the middle stack.
        for (int seatIndex = 0; seatIndex < players.size(); seatIndex++) {
            dealOne(seatIndex, seatIndex * 5);
            beat(DEAL_STEP - 1);
        }

        // A breath while the identity stack clears, then the ability-card stack takes its place.
        beat(DEAL_PAUSE);

        // Act two: each seat's four ability cards, one after another off the pile.
        for (int seatIndex = 0; seatIndex < players.size(); seatIndex++) {
            for (int ability = 1; ability <= 4; ability++) {
                dealOne(seatIndex, seatIndex * 5 + ability);
                beat(DEAL_FEATURE_STEP - 1);
            }
        }

        isGameReady = false;
        isGameOver = false;
        setCurrentPlayer(leaderSeat);
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.game_started", players.size(),
                AvalonRole.goodCount(players.size(), roleMask()),
                AvalonRole.evilCount(players.size(), roleMask())));
    }

    /** Queues {@code ticks} no-op actions; {@code tick()} drains one per tick while dealing. */
    private void beat(int ticks) {
        for (int i = 0; i < ticks; i++) {
            scheduledActions.add(() -> {
            });
        }
    }

    /** Queues handing one staged card to a seat, with the draw sound. */
    private void dealOne(int owner, int stagingIndex) {
        scheduledActions.add(() -> {
            CardPlayer player = playerAt(owner);
            player.playSound(ModSounds.CARD_DRAW.get());
            give(player, staging.get(stagingIndex));
        });
    }

    private void missionsReset() {
        java.util.Arrays.fill(missions, 0);
        java.util.Arrays.fill(questTeams, 0);
        voteCards.clear();
        questCards.clear();
    }

    /**
     * Hands out the roles for this table size.
     *
     * <p>Merlin, Percival, the Assassin and Morgana are always seated and the rest are filled by
     * {@link AvalonRole#compose}; the whole list is then shuffled across the seats, so which chair gets
     * Merlin is decided here and nowhere else. Oberon is dropped when the cunning-assassin rule is off,
     * because Oberon only exists to hide from the other evil seats — without that rule the seat would be
     * indistinguishable from a plain Minion.
     */
    private void dealRoles() {
        List<AvalonRole> composed = new ArrayList<>(AvalonRole.compose(players.size(), roleMask()));
        if (!CUNNING_ASSASSIN.get()) {
            composed.remove(AvalonRole.OBERON);
            while (composed.size() > players.size()) {
                composed.remove(AvalonRole.LOYALIST);
            }
        }
        while (composed.size() < players.size()) {
            composed.add(AvalonRole.LOYALIST);
        }
        while (composed.size() > players.size()) {
            composed.remove(composed.size() - 1);
        }
        Collections.shuffle(composed, random);
        roles.addAll(composed);
        for (int i = 0; i < roles.size(); i++) {
            switch (roles.get(i)) {
                case MERLIN -> merlinSeat = i;
                case MORGANA -> morganaSeat = i;
                case ASSASSIN -> assassinSeat = i;
                default -> {
                }
            }
        }
        // The roster the opening curtain names. Counts, not seats: this is public setup information.
        roleCensus = 0;
        for (AvalonRole role : roles) {
            int shift = role.ordinal() * 4;
            int count = ((roleCensus >>> shift) & 0xF) + 1;
            roleCensus = (roleCensus & ~(0xF << shift)) | (Math.min(0xF, count) << shift);
        }
    }

    /** Adds a card to a hand and keeps the face-down mirror the rest of the table sees in step. */
    private void give(CardPlayer player, Card card) {
        card.flip();
        card.flip();
        getPlayerHand(player).add(card);
        getCensoredHand(null, player).add(new Card());
    }

    /** Removes a card from a hand and its mirror blank; {@code false} if it was not there to begin with. */
    private boolean take(CardPlayer player, Card card) {
        if (!getPlayerHand(player).remove(card)) {
            return false;
        }
        GameSlot censored = censoredHands.get(player);
        if (censored != null && !censored.isEmpty()) {
            censored.removeLast();
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Flow
    // ---------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (lingerTicks > 0) {
            if (--lingerTicks == 0 && afterLinger != null) {
                Runnable action = afterLinger;
                afterLinger = null;
                action.run();
            }
            return;
        }
        super.tick();
        // Charta drives a bot only on the turn of the single current player (AutoPlayer.tick), and a
        // simultaneous vote has no such thing -- every seat is asked at once. So the bot half of a vote
        // round is advanced here, each seat on its own countdown; a real seat answers with its own click
        // and is simply skipped.
        if (isGameReady && !isGameOver && phase == Phase.VOTE) {
            tickVoters();
        }
        if (isGameReady && !isGameOver && phase == Phase.QUEST) {
            tickQuesters();
        }
    }

    private void linger(int ticks, Runnable action) {
        this.afterLinger = action;
        this.lingerTicks = ticks;
    }

    @Override
    public void runGame() {
        if (!isGameReady || isGameOver || awaiting || currentPlayer == null) {
            return;
        }
        switch (phase) {
            case DEALING -> beginProposal();
            case PROPOSE, ASSASSIN -> awaitAction();
            case QUEST -> {
                // Nothing to arm: the whole team was registered the moment the quest opened, exactly as a
                // vote round opens for the whole table. See beginQuest.
            }
            case VOTE -> {
                // Nothing to arm: the round was opened for the whole table the moment the team was
                // confirmed, and `awaiting` keeps this method out until it resolves.
            }
            case OVER -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Proposal
    // ---------------------------------------------------------------------------------------------

    private void beginProposal() {
        phase = Phase.PROPOSE;
        pendingMask = 0;
        teamMask = 0;
        if (designatedQuests()) {
            // The previous quest is done and the next slot has not been named by this leader yet. A
            // rejected proposal starts here too, which is the rule: every attempt names its own quest.
            quest = -1;
            MyAvalonMod.LOGGER.info("designated proposal: leader={} open={} missions={}",
                    leaderSeat, Integer.toBinaryString(openQuestMask()), java.util.Arrays.toString(missions));
        }
        setCurrentPlayer(leaderSeat);
        table(designatedQuests()
                ? net.minecraft.network.chat.Component.translatable("message.myavalon.proposal_started_designated",
                        playerAt(leaderSeat).getColoredName())
                : net.minecraft.network.chat.Component.translatable("message.myavalon.proposal_started", quest + 1,
                        playerAt(leaderSeat).getColoredName(), teamSize()));
        awaitAction();
    }

    /** The acting seat either proposes a team or picks the assassination target. */
    private void awaitAction() {
        CardPlayer actor = currentPlayer;
        int actorSeat = seat;
        if (actor == null) {
            return;
        }
        long token = ++turnToken;
        awaiting = true;
        actor.afterPlay(play -> {
            if (staleCallback(token)) {
                return;
            }
            awaiting = false;
            actor.resetPlay();
            if (phase == Phase.PROPOSE && actorSeat == leaderSeat) {
                confirmTeam(play == null ? pendingMask : play.slot());
            } else if (phase == Phase.ASSASSIN && actorSeat == assassinSeat) {
                confirmAssassination(play == null ? pendingTarget : play.slot());
            }
        });
    }

    /** A menu click or a screen button: toggles a seat, picks a target, or confirms. */
    public void submitAction(CardPlayer player, int action) {
        if (isGameOver || !awaiting || player != currentPlayer) {
            return;
        }
        int from = getSeat(player);
        if (from < 0) {
            return;
        }
        if (phase == Phase.PROPOSE && from == leaderSeat) {
            if (designatedQuests() && action >= PICK_BASE && action < PICK_BASE + AvalonRole.QUESTS) {
                int picked = action - PICK_BASE;
                MyAvalonMod.LOGGER.info("quest pick {} from seat {}: open={} accepted={}", picked + 1, from,
                        Integer.toBinaryString(openQuestMask()), isQuestOpen(picked));
                selectQuest(picked, picked != quest);
            } else if (action >= TOGGLE_BASE && action < TOGGLE_BASE + MAX_SEATS) {
                int target = action - TOGGLE_BASE;
                if (target < players.size()) {
                    pendingMask ^= 1 << target;
                }
            } else if (action == CONFIRM && hasQuest() && Integer.bitCount(pendingMask) == teamSize()) {
                player.play(new GamePlay(List.of(), pendingMask));
            }
            return;
        }
        if (phase == Phase.ASSASSIN && from == assassinSeat) {
            if (action >= PICK_BASE && action < PICK_BASE + MAX_SEATS) {
                int target = action - PICK_BASE;
                if (target < players.size() && target != assassinSeat) {
                    pendingTarget = target;
                }
            } else if (action == CONFIRM && pendingTarget >= 0) {
                player.play(new GamePlay(List.of(), pendingTarget));
            }
        }
    }

    private void confirmTeam(int mask) {
        if (phase != Phase.PROPOSE) {
            return;
        }
        if (designatedQuests()) {
            // A bot names its quest in the high half of the slot it answers with; a human named theirs
            // through a separate click, so the high half is zero and the current choice stands. The low
            // ten bits are always the team mask (MAX_SEATS).
            int picked = (mask >>> 16) - 1;
            if (picked >= 0 && picked < AvalonRole.QUESTS && isQuestOpen(picked)) {
                selectQuest(picked, picked != quest);
            } else if (!hasQuest()) {
                int fallback = firstOpenQuest();
                if (fallback < 0) {
                    return;
                }
                selectQuest(fallback, true);
            }
            mask &= (1 << players.size()) - 1;
        }
        // A bot or a confused client can hand over a mask of the wrong size; fill it out rather than
        // refusing, so a turn is never lost to a bookkeeping slip.
        teamMask = mask & ((1 << players.size()) - 1);
        if (Integer.bitCount(teamMask) != teamSize()) {
            teamMask = pad(teamMask);
        }
        pendingMask = teamMask;
        countCleanTeam();
        phase = Phase.VOTE;
        voted.clear();
        yesVotes = 0;
        voteCards.clear();
        table(designatedQuests()
                ? net.minecraft.network.chat.Component.translatable("message.myavalon.team_proposed_designated",
                        quest + 1, playerAt(leaderSeat).getColoredName(), namesOf(teamMask))
                : net.minecraft.network.chat.Component.translatable("message.myavalon.team_proposed",
                        namesOf(teamMask), playerAt(leaderSeat).getColoredName()));
        beginVoting();
    }

    /** Credits the leader with a team nobody on it can sabotage -- see {@link #cleanTeams}. */
    private void countCleanTeam() {
        if (leaderSeat < 0 || leaderSeat >= cleanTeams.length) {
            return;
        }
        for (int seat = 0; seat < players.size(); seat++) {
            if ((teamMask & (1 << seat)) != 0 && roleAt(seat) != null && roleAt(seat).isEvil()) {
                return;
            }
        }
        cleanTeams[leaderSeat]++;
    }

    /**
     * Names the quest the current proposal is for. The leader's board click and a bot's encoded answer
     * both end up here.
     *
     * <p>Switching to a smaller quest trims the team rather than dropping the selection: the seats the
     * leader already marked stay marked, from the low seat up, and the rest are taken back. Growing the
     * quest leaves the selection alone, because the leader still has to fill it.
     *
     * @param announce whether this is a fresh public choice ({@code true}) or the server catching up with
     *                 an answer the table is already about to see ({@code false})
     */
    private void selectQuest(int index, boolean announce) {
        if (!designatedQuests() || phase != Phase.PROPOSE || !isQuestOpen(index)) {
            return;
        }
        quest = index;
        pendingMask = trimTo(pendingMask, teamSize());
        if (announce) {
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.quest_selected",
                    playerAt(leaderSeat).getColoredName(), index + 1));
        }
    }

    /** The quests this proposal may pick: unplayed, with the fifth gated on two successes. */
    public int openQuestMask() {
        if (!designatedQuests()) {
            return 0;
        }
        int successes = countMissions(1);
        int mask = 0;
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (missions[i] != 0) {
                continue;
            }
            // The printed rule: quest five may only be chosen once two other quests have succeeded.
            // With five slots and a three-of-either ending this can never leave the table stuck.
            if (i == AvalonRole.QUESTS - 1 && successes < 2) {
                continue;
            }
            mask |= 1 << i;
        }
        // A proposal must always have somewhere to go. The gate above can only tighten the pool, so an
        // empty mask with slots left means the gate has locked the table out; fall back to "every
        // unplayed slot" rather than leaving the leader with nothing to click.
        if (mask == 0) {
            for (int i = 0; i < AvalonRole.QUESTS; i++) {
                if (missions[i] == 0) {
                    mask |= 1 << i;
                }
            }
        }
        return mask;
    }

    private boolean isQuestOpen(int index) {
        return index >= 0 && index < AvalonRole.QUESTS && (openQuestMask() & (1 << index)) != 0;
    }

    private int firstOpenQuest() {
        int open = openQuestMask();
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if ((open & (1 << i)) != 0) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the current proposal has named a quest at all. Classic play always has one. */
    public boolean hasQuest() {
        return quest >= 0 && quest < AvalonRole.QUESTS;
    }

    /** Team size for an arbitrary slot, without touching the current choice. */
    public int teamSizeFor(int index) {
        return AvalonRole.teamSize(players.size(), index);
    }

    /** How many of the five quests are no longer pending. */
    public int resolvedQuests() {
        int count = 0;
        for (int mission : missions) {
            if (mission != 0) {
                count++;
            }
        }
        return count;
    }

    /** Drops the highest marked seats until the mask fits {@code size}, leaving the selection order intact. */
    private int trimTo(int mask, int size) {
        int result = mask;
        for (int i = players.size() - 1; Integer.bitCount(result) > size && i >= 0; i--) {
            if ((result & (1 << i)) != 0) {
                result &= ~(1 << i);
            }
        }
        return result;
    }

    /** Grows or trims a mask to exactly the quest's team size, keeping the low seats. */
    private int pad(int mask) {
        int result = mask;
        int wanted = teamSize();
        for (int i = 0; Integer.bitCount(result) < wanted && i < players.size(); i++) {
            result |= 1 << i;
        }
        for (int i = players.size() - 1; Integer.bitCount(result) > wanted && i >= 0; i--) {
            if ((result & (1 << i)) != 0) {
                result &= ~(1 << i);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // The quest: the team is asked at once
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens a quest: one live registration per team member, all of them at the same moment.
     *
     * <h2>Why this is not the sequential engine either</h2>
     *
     * <p>A quest card used to be handed around the team — {@code currentPlayer} advanced through a queue and
     * every wait was an {@code awaitCard}. But nothing in the game makes the order matter and the table reads
     * better without it: the team puts its cards down together, the way it does in the printed game, and the
     * reveal gets to shuffle a row that is already complete instead of one that is still filling.
     *
     * <p>Only the team is registered; everybody else is simply not asked. That is also what keeps the phase
     * honest: a seat's card is legal exactly while it is in {@link #awaitingQuest}.
     */
    private void beginQuest() {
        phase = Phase.QUEST;
        quested.clear();
        questFails = 0;
        questTeams[quest] = teamMask;
        awaitingQuest.clear();
        for (int i = 0; i < players.size(); i++) {
            int member = seatFrom(leaderSeat, i);
            if ((teamMask & (1 << member)) != 0) {
                awaitingQuest.add(member);
            }
        }
        // Holds runGame() off for the length of the round, exactly as the vote does: there is no "next"
        // turn to hand the table to, so nothing may re-enter the phase while cards are coming in.
        awaiting = true;
        long token = ++turnToken;
        for (int member : awaitingQuest) {
            playerAt(member).afterPlay(play -> castQuestCard(token, member, play));
        }
        questDelay = new int[players.size()];
        for (int member : awaitingQuest) {
            questDelay[member] = VOTE_DELAY_MIN + random.nextInt(VOTE_DELAY_SPREAD);
        }
        table(designatedQuests()
                ? net.minecraft.network.chat.Component.translatable("message.myavalon.quest_started_designated",
                        quest + 1, namesOf(teamMask), teamSize(),
                        AvalonRole.needsTwoFails(players.size(), quest) ? 2 : 1)
                : net.minecraft.network.chat.Component.translatable("message.myavalon.quest_started", quest + 1,
                        namesOf(teamMask), teamSize(), AvalonRole.needsTwoFails(players.size(), quest) ? 2 : 1));
    }

    /** Advances the bot half of a running quest: one member per tick, once its countdown is spent. */
    private void tickQuesters() {
        for (int member : List.copyOf(awaitingQuest)) {
            CardPlayer player = playerAt(member);
            if (!isBot(player)) {
                continue;   // a real seat answers with its own click, whenever it likes
            }
            if (member < questDelay.length && questDelay[member] > 0) {
                questDelay[member]--;
                continue;
            }
            player.play(getBestPlay(player));
        }
    }

    /**
     * One member's card: what it played, or {@code null} when it somehow answered without one.
     *
     * <p>Shaped exactly like {@link #castVote}, including the double-answer guard — the same race exists
     * here, since Charta's own bot tick fires for {@code currentPlayer} (still the leader) while
     * {@link #tickQuesters} is doing the same.
     */
    private void castQuestCard(long token, int member, @Nullable GamePlay play) {
        CardPlayer player = playerAt(member);
        if (quested.contains(member)) {
            player.resetPlay();
            return;
        }
        if (staleCallback(token) || phase != Phase.QUEST) {
            return;
        }
        player.resetPlay();
        awaitingQuest.remove(member);
        Card card = play == null || play.cards().isEmpty() ? null : play.cards().getLast();
        if (card != null) {
            player.playSound(ModSounds.CARD_PLAY.get());
            // A real player's card already travelled hand -> carried -> pile through the slot click
            // payload, so it is gone from the hand and on the pile by now; the removal is a harmless
            // no-op for them and the only way the card moves for a bot. The debug harness sits in the
            // middle: it drives a real seat without a click, which the boolean catches.
            boolean movedByHand = take(player, card);
            if (isBot(player) || movedByHand) {
                pile.add(card);
            }
        }
        recordQuestCard(member, card);
    }

    // ---------------------------------------------------------------------------------------------
    // The vote: every seat is asked at once
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens a simultaneous vote: one live registration per seat, all of them at the same moment.
     *
     * <h2>Why the vote is not a turn</h2>
     *
     * <p>A proposal is the leader's call and a quest card is handed around the team, so both are
     * genuinely sequential and keep the {@code currentPlayer} engine. A vote is not: the printed game
     * has everybody put a fist out and every card counts, and running it one seat at a time only invited
     * the table to read each answer as it landed. So the whole table is registered at once, and the round
     * is over when the last answer is in — {@link #awaitingVotes} is what says how many are still out.
     *
     * <h2>The two halves of "at once"</h2>
     *
     * <p>A real seat answers by dropping a vote card on the pile, which was already ungated from the turn
     * order for this phase — see {@link #canPlay} and {@link PileSlot#canInsertCard}. A bot seat has no
     * click, and the one thing that used to drive it ({@code AutoPlayer.tick}) only ever fires for
     * {@code currentPlayer}; so the bots are driven from {@link #tickVoters} instead, on the staggered
     * countdown in {@link #voterDelay}.
     *
     * <h2>One token, not one per seat</h2>
     *
     * <p>{@link #turnToken} is bumped once for the round and all the registrations carry it, so the
     * phase checks in {@link #castVote} are what retire them; a token per seat would need a map that
     * outlives its use.
     */
    private void beginVoting() {
        awaitingVotes.clear();
        for (int i = 0; i < players.size(); i++) {
            awaitingVotes.add(i);
        }
        // Holds runGame() off for the length of the round, exactly as a single-seat turn did: there is no
        // "next" to hand the table to, so nothing may re-enter the phase while answers are coming in.
        awaiting = true;
        long token = ++turnToken;
        for (int i = 0; i < players.size(); i++) {
            int voter = i;
            playerAt(i).afterPlay(play -> castVote(token, voter, play));
        }
        voterDelay = new int[players.size()];
        for (int i = 0; i < voterDelay.length; i++) {
            voterDelay[i] = VOTE_DELAY_MIN + random.nextInt(VOTE_DELAY_SPREAD);
        }
    }

    /** Advances the bot half of a running vote round: one seat per tick, once its countdown is spent. */
    private void tickVoters() {
        for (int voter : List.copyOf(awaitingVotes)) {
            CardPlayer player = playerAt(voter);
            if (!isBot(player)) {
                continue;   // a real seat answers with its own click, whenever it likes
            }
            if (voter < voterDelay.length && voterDelay[voter] > 0) {
                voterDelay[voter]--;
                continue;
            }
            // An empty play is a legal answer here, and counts as an abstention -- the same thing a null
            // play meant when the vote was taken one seat at a time.
            player.play(getBestPlay(player));
        }
    }

    /**
     * One seat's answer in the running vote: the card that was played, or {@code null} for an abstention.
     */
    private void castVote(long token, int voter, @Nullable GamePlay play) {
        CardPlayer player = playerAt(voter);
        if (voted.contains(voter)) {
            // A second answer for a seat this round already has. Charta's own bot tick can hand one over
            // while tickVoters is doing the same, so this is a normal race, not a bug to report.
            player.resetPlay();
            return;
        }
        if (staleCallback(token) || phase != Phase.VOTE) {
            return;
        }
        player.resetPlay();
        awaitingVotes.remove(voter);
        Card card = play == null || play.cards().isEmpty() ? null : play.cards().getLast();
        if (card != null) {
            player.playSound(ModSounds.CARD_PLAY.get());
            // Same two paths as the quest: a real player's card is already on the pile (it travelled
            // hand -> carried -> pile through the click payload), a bot's still has to be moved.
            boolean movedByHand = take(player, card);
            if (isBot(player) || movedByHand) {
                pile.add(card);
            }
        }
        recordVote(voter, card);
    }

    /**
     * Credits every "no" cast against a team that was carrying evil.
     *
     * <p>Counted whether or not the team went through: voting a team down and being outvoted is still the
     * read being right, and the summary is about reads, not about outcomes.
     */
    private void tallySharpOpposition() {
        boolean carrying = false;
        for (int seat = 0; seat < players.size(); seat++) {
            if ((teamMask & (1 << seat)) != 0 && roleAt(seat) != null && roleAt(seat).isEvil()) {
                carrying = true;
                break;
            }
        }
        if (!carrying) {
            return;
        }
        for (int voter : voted) {
            if (voter < sharpNo.length && AvalonCards.isNo(voteCards.get(voter))) {
                sharpNo[voter]++;
            }
        }
    }

    private void recordVote(int voter, @Nullable Card card) {
        boolean yes = AvalonCards.isYes(card);
        voted.add(voter);
        if (card != null) {
            voteCards.put(voter, card);
        }
        if (yes) {
            yesVotes++;
        }
        table(net.minecraft.network.chat.Component.translatable(yes ? "message.myavalon.voted_yes" : "message.myavalon.voted_no",
                playerAt(voter).getColoredName()).withStyle(net.minecraft.ChatFormatting.GRAY));
        if (awaitingVotes.isEmpty()) {
            awaiting = false;
            resolveVote();
        }
    }

    private void recordQuestCard(int member, @Nullable Card card) {
        quested.add(member);
        if (card != null) {
            questCards.put(member, card);
        }
        if (!AvalonCards.isSuccess(card)) {
            // Which includes answering with no card at all: an abstention is a fail, exactly as it was when
            // the card was handed around and a seat could time out.
            questFails++;
        }
        if (awaitingQuest.isEmpty()) {
            awaiting = false;
            resolveQuest();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Vote resolution
    // ---------------------------------------------------------------------------------------------

    private void resolveVote() {
        int no = voted.size() - yesVotes;
        boolean approved = yesVotes > no;
        tallySharpOpposition();
        // The pile is turned over, read, and handed back: the cards are reusable, only their face
        // matters. Done before the linger so the sweep animates the pile away rather than dropping it
        // out from under the next phase.
        if (!approved) {
            rejects++;
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.team_rejected", yesVotes, no)
                    .withStyle(net.minecraft.ChatFormatting.RED));
        } else {
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.team_approved", yesVotes, no)
                    .withStyle(net.minecraft.ChatFormatting.GREEN));
        }
        afterLinger = () -> {
            clearPile();
            giveBackVotes();
            voted.clear();
            yesVotes = 0;
            if (winner != SIDE_NONE) {
                endGame();
                return;
            }
            if (approved) {
                // The counter is a run of consecutive rejections, not a game-long budget: an approved
                // team clears it. Kept cumulative it reads wrong against the "5 in a row" rule and,
                // worse, an evil leader that good keeps voting down eats the whole budget.
                rejects = 0;
                beginQuest();
                return;
            }
            if (rejects >= MAX_REJECTS) {
                endedByRedAct = true;
                finish(SIDE_EVIL, "message.myavalon.evil_by_rejects");
                return;
            }
            leaderSeat = seatFrom(leaderSeat, 1);
            beginProposal();
        };
        lingerTicks = LINGER;
    }

    private void resolveQuest() {
        boolean sabotaged = questFails >= (AvalonRole.needsTwoFails(players.size(), quest) ? 2 : 1);
        missions[quest] = sabotaged ? 2 : 1;
        String outcomeKey = sabotaged
                ? (designatedQuests() ? "message.myavalon.quest_failed_designated" : "message.myavalon.quest_failed")
                : (designatedQuests() ? "message.myavalon.quest_succeeded_designated" : "message.myavalon.quest_succeeded");
        table(net.minecraft.network.chat.Component.translatable(outcomeKey, quest + 1, questFails)
                .withStyle(sabotaged ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GREEN));
        afterLinger = () -> {
            clearPile();
            giveBackQuest();
            quested.clear();
            questFails = 0;
            // Three of either decides the game, and a five-quest board always reaches three before it runs
            // out: this is where good's third success has to end it, not at the end of the fifth quest. The
            // success side kept on playing until then, so a good sweep of the first three quests carried on
            // into a fourth (or fifth) round with the game already won.
            if (countMissions(2) >= 3) {
                endedByRedAct = true;
                finish(SIDE_EVIL, "message.myavalon.evil_by_quests");
                return;
            }
            if (countMissions(1) >= 3) {
                goodSurvivedTheQuests();
                return;
            }
            if (!designatedQuests()) {
                // Classic play walks the board left to right; the designated rule leaves the pool open
                // and lets the next leader name whichever slot is still available.
                quest++;
                if (quest >= AvalonRole.QUESTS) {
                    // Unreachable while the two counts above are checked, and kept as the exit that cannot
                    // run out of rounds: a table that ever got here would still be decided rather than stuck.
                    goodSurvivedTheQuests();
                    return;
                }
            }
            leaderSeat = seatFrom(leaderSeat, 1);
            beginProposal();
        };
        lingerTicks = QUEST_LINGER;
    }

    /**
     * Good has won the quests: Merlin survived them, and the assassin gets the one shot the rules give evil
     * before the win is handed over. Without an assassin at the table (a setup with three evil seats, or an
     * odd role set) there is nobody to take the shot and good simply wins.
     */
    private void goodSurvivedTheQuests() {
        if (assassinSeat >= 0 && roles.get(assassinSeat) == AvalonRole.ASSASSIN) {
            beginAssassination();
        } else {
            finish(SIDE_GOOD, "message.myavalon.good_by_quests");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Assassination
    // ---------------------------------------------------------------------------------------------

    private void beginAssassination() {
        phase = Phase.ASSASSIN;
        pendingTarget = -1;
        setCurrentPlayer(assassinSeat);
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.assassin_awakes", playerAt(assassinSeat).getColoredName()));
        awaitAction();
    }

    private void confirmAssassination(int target) {
        if (phase != Phase.ASSASSIN || target < 0 || target >= players.size()) {
            return;
        }
        pendingTarget = target;
        phase = Phase.OVER;
        boolean hit = target == merlinSeat;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.assassin_stabbed",
                playerAt(assassinSeat).getColoredName(), playerAt(target).getColoredName()));
        if (hit) {
            finish(SIDE_EVIL, "message.myavalon.evil_by_assassin");
        } else {
            finish(SIDE_GOOD, "message.myavalon.good_by_assassin");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Resolving the pile
    // ---------------------------------------------------------------------------------------------

    private void clearPile() {
        pile.clear();
        players.forEach(CardPlayer::resetPlay);
    }

    /** Hands every seat the exact vote card it played back, so the next proposal starts from the pair. */
    private void giveBackVotes() {
        giveBack(voteCards);
    }

    private void giveBackQuest() {
        giveBack(questCards);
    }

    /**
     * Hands back every card the round took off a hand.
     *
     * <p>The guard is the repair for a card that never left: a seat whose answer was recorded while its
     * card was still in its hand would come out of the round one card <em>up</em>, and a fan with six cards
     * in it is what that looks like. If the hand already holds it, the copy the round recorded is dropped
     * -- the pile is swept immediately afterwards, so nothing is stranded on the table by doing so.
     *
     * <p>Compared by {@code equals}, which for a card is its suit, rank and face: a seat never holds two of
     * the same card, so "already there" can only mean the round's copy.
     */
    private void giveBack(Map<Integer, Card> cards) {
        cards.forEach((seat, card) -> {
            CardPlayer player = playerAt(seat);
            if (player != null && getPlayerHand(player).stream().noneMatch(held -> held.equals(card))) {
                give(player, card);
            }
        });
        cards.clear();
    }

    private int countMissions(int kind) {
        int count = 0;
        for (int value : missions) {
            if (value == kind) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------------------------------
    // Card validity
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether {@code player} may put {@code play} down.
     *
     * <h2>A vote is open to the whole table, a quest card is not</h2>
     *
     * <p>The two card phases differ in exactly one way: the vote is taken all at once, so <em>every</em>
     * seat still to answer is a legal actor — gating it on {@code currentPlayer} is what used to make the
     * table vote one at a time — while a quest card is still handed around the team in turn. The seat is
     * resolved from the player rather than read off the turn, which the vote phase no longer maintains
     * per seat.
     */
    @Override
    public boolean canPlay(CardPlayer player, GamePlay play) {
        if (phase != Phase.VOTE && phase != Phase.QUEST) {
            return false;
        }
        if (!isGameReady || isGameOver || lingerTicks > 0) {
            return false;
        }
        int from = getSeat(player);
        if (from < 0) {
            return false;
        }
        List<Card> cards = play.cards();
        if (cards.size() != 1) {
            return false;
        }
        Card card = cards.getLast();
        if (!getCardPredicate().test(card)) {
            return false;
        }
        boolean held = getPlayerHand(player).stream().anyMatch(c -> c.equals(card))
                || getFullHand(player).anyMatch(c -> c.equals(card));
        if (!held) {
            return false;
        }
        if (phase == Phase.VOTE) {
            // Any seat whose answer is still outstanding may put its vote card on the pile; a seat that
            // has already answered may not vote twice while the pile is still being read.
            return !voted.contains(from) && AvalonCards.isVote(card);
        }
        // A quest: any member of the team whose card is still outstanding, exactly as a vote allows any
        // seat whose answer is still outstanding. The two phases are the same shape now; what differs is
        // only who is asked.
        if (quested.contains(from) || (teamMask & (1 << from)) == 0) {
            return false;
        }
        if (!AvalonCards.isQuest(card)) {
            return false;
        }
        AvalonRole role = roleAt(from);
        return role == null || !role.isGood() || AvalonCards.isSuccess(card);
    }

    @Override
    public @Nullable GamePlay getBestPlay(CardPlayer player) {
        int from = getSeat(player);
        if (from < 0) {
            return null;
        }
        return switch (phase) {
            case PROPOSE -> from == leaderSeat
                    ? AvalonBots.chooseProposal(this, from)
                    : null;
            case VOTE -> AvalonBots.chooseVote(this, from);
            case QUEST -> AvalonBots.chooseQuest(this, from);
            case ASSASSIN -> from == assassinSeat
                    ? new GamePlay(List.of(), AvalonBots.chooseTarget(this, from))
                    : null;
            default -> null;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // End of the game
    // ---------------------------------------------------------------------------------------------

    private void finish(int side, String messageKey) {
        completed = side != SIDE_NONE;
        announce(side, messageKey);
        // Written here rather than at the reveal: the result is already final, and a table packed up
        // during the result linger never reaches the reveal at all. The guard makes a repeat free.
        recordStats();
        linger(RESULT_LINGER, this::reveal);
    }

    /**
     * {@link #finish} for a table that was packed up mid-game rather than won.
     *
     * <p>No {@code RESULT_LINGER} here, and that is the whole difference: the beat exists so the winner
     * line and the settlement can be read before the board turns itself over, and a game that was abandoned
     * has neither -- what the seats want is the reveal. Skipping it is the fix for the pause a player saw
     * after standing up from their chair, where the block's {@code endGame} arrives mid-round and the
     * result then sat still for two and a half seconds before appearing in the middle of the screen.
     */
    private void abort() {
        completed = false;
        announce(SIDE_NONE, "message.myavalon.aborted");
        reveal();
    }

    /** Ends the game on {@code side}: the phase, the winner and the one chat line that says so. */
    private void announce(int side, String messageKey) {
        winner = side;
        phase = Phase.OVER;
        awaiting = false;
        table(net.minecraft.network.chat.Component.translatable(messageKey));
    }

    /**
     * Announces the outcome to every seat, then holds the finished table for a while.
     *
     * <h2>Why {@code isGameOver} is not set here and now</h2>
     *
     * <p>It is the signal Charta's table block watches to tear the game down: the tick after it goes true,
     * the block entity resets every slot and drops the game, so the table is swept bare. Setting it with the
     * announcement therefore wiped the board out from under the reveal it had just started — the row of
     * role cards turned face-up, and everything else, was cleared before anyone had read it.
     *
     * <p>So the announcement is followed by one more linger, and the game only declares itself over at the
     * end of it. The table stays held for {@link #REVEAL_LINGER} ticks, and the menu outlives even that (see
     * {@code AvalonMenu.stillValid}), so the roster in the chat stays readable after the sweep.
     */
    private void reveal() {
        if (isGameOver) {
            return;
        }
        boolean goodWon = winner == SIDE_GOOD;
        // The sabotage ending announces itself: the clients fade the whole screen red over the winner's name
        // and then close the table, so a title here would be a second announcement fighting the first --
        // and the reveal it decorates is what that red act is about to cover anyway.
        if (!endedByRedAct) {
            for (int i = 0; i < players.size(); i++) {
                CardPlayer player = players.get(i);
                boolean good = roles.get(i).isGood();
                boolean won = goodWon == good;
                net.minecraft.network.chat.Component title = winner == SIDE_NONE
                        // No side won: "evil wins" would be the default branch of the comparison below,
                        // which is exactly the title an interrupted table used to hand everybody.
                        ? net.minecraft.network.chat.Component.translatable("message.myavalon.aborted")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW)
                        : net.minecraft.network.chat.Component.translatable(goodWon
                                        ? (good ? "message.myavalon.good_wins" : "message.myavalon.good_wins_evil")
                                        : (good ? "message.myavalon.evil_wins_good" : "message.myavalon.evil_wins"))
                                .withStyle(won ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
                net.minecraft.network.chat.Component subtitle = net.minecraft.network.chat.Component.translatable("message.myavalon.you_were",
                        net.minecraft.network.chat.Component.translatable(roles.get(i).getTranslationKey()));
                player.sendTitle(title, subtitle);
            }
        }
        // Through a template rather than a "=" glued together in code, so the join reads as a sentence in
        // every language and a renamed role renames itself here too.
        net.minecraft.network.chat.MutableComponent roster = net.minecraft.network.chat.Component.empty();
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) {
                roster.append(", ");
            }
            roster.append(net.minecraft.network.chat.Component.translatable("message.myavalon.reveal_pair",
                    players.get(i).getName(), net.minecraft.network.chat.Component.translatable(roles.get(i).getTranslationKey())));
        }
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.reveal", roster));
        postSummary();
        linger(endedByRedAct ? RED_ACT_LINGER : REVEAL_LINGER, () -> isGameOver = true);
    }

    /**
     * Writes every real seat's result to the server's record, once per finished game.
     *
     * <p>Only a game with a winner counts: an abandoned table has no side to credit, and recording it
     * would make every walk-away a loss for somebody. A table with even one AI chair is skipped whole --
     * the requirement is that records are compared between human tables, and a bot's mistakes (or its
     * perfect knowledge of the deal) would make a padded game a different game.
     *
     * <p>Bots are identified by their own class rather than by {@code isBot}: the latter also treats a
     * seat whose entity is gone as a bot, which is a disconnected human, not an AI chair.
     */
    private void recordStats() {
        // Unfinished tables never count: no side to credit means nobody won or lost, and a table picked
        // up mid-round is the same fact even if an earlier announcement had set a winner.
        if (statsRecorded || !completed || winner == SIDE_NONE) {
            return;
        }
        statsRecorded = true;
        for (CardPlayer player : players) {
            if (player instanceof AvalonBot) {
                return;
            }
        }
        boolean goodWon = winner == SIDE_GOOD;
        for (int i = 0; i < players.size() && i < roles.size(); i++) {
            CardPlayer player = players.get(i);
            if (player.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // The winner is a side; whether this seat won is the side it was dealt compared with it.
                AvalonStats.record(serverPlayer.getServer(), serverPlayer, roles.get(i),
                        goodWon == roles.get(i).isGood());
            }
        }
    }

    /**
     * Set when the game ended on evil's third sabotaged quest or on the fifth rejected team.
     *
     * <p>The endings whose announcement belongs to the client: it fades the whole screen red over the
     * winner's name, so this side leaves the titles to it. Neither has an act of its own the way the knife's
     * does, and a win by arithmetic deserves saying out loud rather than a title over a live-looking board.
     */
    private boolean endedByRedAct;

    /**
     * Ticks the finished table is held after the red announcement.
     *
     * <p>Short, and timed to land inside the client's red act ({@code EV_TOTAL_MS}, about 5.6 s from the
     * moment the phase reaches {@code OVER}): what expires here is the block sweeping the table, and under
     * the act's frost and wash nobody can see it happen. Waiting the full {@link #REVEAL_LINGER} would put
     * that sweep on screen after the report has already faded in.
     */
    private static final int RED_ACT_LINGER = 50;

    /**
     * The end-of-game report, one chat line at a time.
     *
     * <p>Line by line rather than one multi-line component, so each line can carry its own colour and the
     * chat's own wrapping never has to be reasoned about.
     *
     * <p>Everything here is derived from state the game already holds: the roles it dealt, the missions it
     * recorded, and the "no" votes it counted as they happened ({@link #sharpNo}).
     */
    private void postSummary() {
        List<Component> names = new ArrayList<>();
        for (CardPlayer player : players) {
            names.add(player.getName());
        }
        for (Component line : summaryLines(names, roles, missions, sharpNo, cleanTeams, winner)) {
            summaryLine(line);
        }
    }

    /**
     * The report, one component per line, exactly as the chat prints it.
     *
     * <p>Static and pure so the <em>client</em> can build the same lines for the panel it fades in over the
     * finished table: every ingredient is either on both sides already -- the seats, the missions, the
     * revealed roles, the winner -- or mirrored for this purpose alone ({@code SYNC_STATS_BASE}). One
     * builder read twice beats two versions that agree by luck: the panel and the chat cannot drift apart,
     * because they are the same list.
     */
    public static List<Component> summaryLines(List<Component> names, List<AvalonRole> roles, int[] missions,
                                               int[] sharpNo, int[] cleanTeams, int winner) {
        List<Component> lines = new ArrayList<>();
        boolean goodWon = winner == SIDE_GOOD;
        boolean aborted = winner == SIDE_NONE;
        // The winner line is the first thing anybody reads; an interrupted game must not open its own
        // report by declaring evil the winner just because `0 != SIDE_GOOD`.
        lines.add(Component.translatable(aborted
                        ? "status.myavalon.aborted"
                        : goodWon ? "status.myavalon.good_wins" : "status.myavalon.evil_wins")
                .withStyle(ChatFormatting.BOLD)
                .withStyle(style -> style.withColor(aborted ? SUMMARY_ABORTED : goodWon ? SUMMARY_GOOD : SUMMARY_EVIL)));

        for (int i = 0; i < roles.size(); i++) {
            boolean evil = roles.get(i).isEvil();
            lines.add(Component.translatable("message.myavalon.summary_role", names.get(i),
                            Component.translatable(roles.get(i).getTranslationKey()))
                    .withStyle(style -> style.withColor(evil ? SUMMARY_EVIL : SUMMARY_GOOD)));
        }

        int succeeded = 0;
        int failed = 0;
        for (int mission : missions) {
            if (mission == 1) {
                succeeded++;
            } else if (mission == 2) {
                failed++;
            }
        }
        lines.add(Component.translatable("message.myavalon.summary_quest_success", succeeded)
                .withStyle(style -> style.withColor(SUMMARY_GOOD)));
        lines.add(Component.translatable("message.myavalon.summary_quest_fail", failed)
                .withStyle(style -> style.withColor(SUMMARY_EVIL)));

        // Two tables of judgement, one about reading a team and one about building it, printed the same way.
        rankInto(lines, names, roles, sharpNo, "message.myavalon.summary_ranking");
        rankInto(lines, names, roles, cleanTeams, "message.myavalon.summary_clean_teams");
        return lines;
    }

    /**
     * One ranking block appended to {@code lines}: a header, then every seat in order with its count.
     *
     * <p>Ranked by count and by seat where two seats tie -- a stable order, so the same table always prints
     * the same way and a seat that scored nothing is still named rather than dropped. Each line is coloured
     * by the seat's faction, which is the only thing that makes a good seat's count comparable with an evil
     * one's: an evil seat that builds clean teams is telling the table something different.
     */
    private static void rankInto(List<Component> lines, List<Component> names, List<AvalonRole> roles,
                                 int[] counts, String headerKey) {
        lines.add(Component.translatable(headerKey).withStyle(style -> style.withColor(SUMMARY_GOOD)));
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> counts[b] - counts[a] != 0 ? counts[b] - counts[a] : a - b);
        for (int place = 0; place < order.length; place++) {
            int seat = order[place];
            boolean evil = roles.get(seat).isEvil();
            lines.add(Component.translatable("message.myavalon.summary_rank", place + 1,
                            names.get(seat), counts[seat])
                    .withStyle(style -> style.withColor(evil ? SUMMARY_EVIL : SUMMARY_GOOD)));
        }
    }

    /**
     * One summary line, to every seat's <em>chat</em>.
     *
     * <p>The game's own channel is {@code table(...)}, and that one ends up in the table's history panel
     * rather than in anybody's chat ({@code CardPlayPayload} appends to {@code LOCAL_HISTORY} and stops
     * there), so the report was only ever visible to a player who opened the history screen -- and not at
     * all to the seats who had already stood up, whose chat is the only thing still in front of them. A
     * system message goes to the player's own chat bar, which is what "broadcast the summary" means.
     *
     * <p>Colours ride the line itself, so there is no grey root to work around here the way {@link #table}
     * needed one.
     */
    private void summaryLine(net.minecraft.network.chat.Component line) {
        for (CardPlayer player : players) {
            if (player.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(line);
            }
        }
    }

    /** The summary's two colours: good blue, evil red, matching the reveal's own pair. */
    private static final int SUMMARY_GOOD = 0x6FA8FF;
    private static final int SUMMARY_EVIL = 0xFF6B6B;
    /** Neither side: the report of a table that was packed up mid-round. */
    private static final int SUMMARY_ABORTED = 0xFFE97F;

    /**
     * Ticks the finished table is held before the game declares itself over and the block sweeps it. Long
     * enough to read every seat's card and the roster off the chat on a ten-player table.
     */
    private static final int REVEAL_LINGER = 180;

    @Override
    public void endGame() {
        if (isGameOver) {
            return;
        }
        if (phase == Phase.OVER) {
            if (lingerTicks == 0) {
                reveal();
            }
            return;
        }
        abort();
    }

    // ---------------------------------------------------------------------------------------------
    // Accessors used by the menu, the bots and the screen
    // ---------------------------------------------------------------------------------------------

    public Phase getPhase() {
        return phase;
    }

    public PlaySlot getPile() {
        return pile;
    }

    public int getQuest() {
        return quest;
    }

    public int getLeaderSeat() {
        return leaderSeat;
    }

    public int getTeamMask() {
        return teamMask;
    }

    public int getPendingMask() {
        return pendingMask;
    }

    public int getRejects() {
        return rejects;
    }

    public int getVotedMask() {
        return maskOf(voted);
    }

    public int getYesVotes() {
        return yesVotes;
    }

    public int getQuestMask() {
        return maskOf(quested);
    }

    public int getQuestFails() {
        return questFails;
    }

    public int getAssassinSeat() {
        return assassinSeat;
    }

    public int getPendingTarget() {
        return pendingTarget;
    }

    public int getWinner() {
        return winner;
    }

    /**
     * The seats that have answered "no" this vote, as a bitmask — what {@link #SYNC_NO_VOTERS} mirrors.
     *
     * <p>Derived rather than kept: the two facts it needs are already here, one seat per entry, and a field
     * would be a third thing to clear on every round.
     */
    private int noVoterMask() {
        int mask = 0;
        for (int voter : voted) {
            // No card at all counts as a no as well, exactly as {@link #recordVote} counts it: the tally
            // line has to agree with the two counters it sits next to.
            if (!AvalonCards.isYes(voteCards.get(voter))) {
                mask |= 1 << voter;
            }
        }
        return mask;
    }

    public int getSeatOfCurrentPlayer() {
        return seat;
    }

    public int teamSize() {
        // Before the leader names a quest there is no size to fill, and answering 0 is what keeps the
        // confirm button and the team counter from inviting a team that has nowhere to go.
        if (designatedQuests() && !hasQuest()) {
            return 0;
        }
        return teamSizeFor(quest);
    }

    public int seatCount() {
        return players.size();
    }

    /** Seats starting at {@code localSeat} and going clockwise, i.e. the ring as that viewer sees it. */
    public int[] viewRing(int localSeat) {
        int size = players.size();
        int[] ring = new int[size];
        for (int i = 0; i < size; i++) {
            ring[i] = seatFrom(localSeat, i);
        }
        return ring;
    }

    /** {@code true} when {@code seat} was on the team that ran quest {@code index}. */
    public boolean wasOnQuest(int index, int seat) {
        return index >= 0 && index < questTeams.length && (questTeams[index] & (1 << seat)) != 0;
    }

    /** Fail cards this quest needs to be considered sabotaged: one, or two on the printed hard quest. */
    public int requiredFails() {
        return AvalonRole.needsTwoFails(players.size(), quest) ? 2 : 1;
    }

    /** How many evil seats are on the team currently on the table. */
    public int teamEvilCount() {
        int count = 0;
        for (int seat = 0; seat < players.size(); seat++) {
            AvalonRole role = roleAt(seat);
            if ((teamMask & (1 << seat)) != 0 && role != null && role.isEvil()) {
                count++;
            }
        }
        return count;
    }

    public int missionOf(int index) {
        return index >= 0 && index < missions.length ? missions[index] : 0;
    }

    public int getMissionCount(int kind) {
        return countMissions(kind);
    }

    public boolean isBotSeat(int seat) {
        return seat >= 0 && seat < players.size() && isBot(players.get(seat));
    }

    /** The role dealt to {@code seat}, or {@code null} before the deal. */
    @Nullable
    public AvalonRole roleAt(int seat) {
        return seat >= 0 && seat < roles.size() ? roles.get(seat) : null;
    }

    /** {@code true} while the viewer is the seat that has to act, and acting means a card. */
    public boolean isCardPhase() {
        return phase == Phase.VOTE || phase == Phase.QUEST;
    }

    /**
     * Seats whose faction {@code seat} knows from the deal.
     *
     * <p>This is the whole information asymmetry of the game, in one method: Merlin sees every evil
     * seat except Mordred, Percival sees the indistinguishable Merlin/Morgana pair, evil seats see each
     * other but not Oberon, and Oberon and the plain loyalists see nobody.
     */
    public int knownMask(int seat) {
        AvalonRole role = roleAt(seat);
        if (role == null) {
            return 0;
        }
        int known = 0;
        switch (role) {
            case MERLIN -> {
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i).isEvil() && roles.get(i) != AvalonRole.MORDRED) {
                        known |= 1 << i;
                    }
                }
            }
            case PERCIVAL -> {
                if (merlinSeat >= 0) {
                    known |= 1 << merlinSeat;
                }
                if (morganaSeat >= 0) {
                    known |= 1 << morganaSeat;
                }
            }
            case ASSASSIN, MORGANA, MORDRED, MINION -> {
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i).isEvil() && roles.get(i) != AvalonRole.OBERON) {
                        known |= 1 << i;
                    }
                }
            }
            default -> {
            }
        }
        return known;
    }

    private int maskOf(java.util.Set<Integer> seats) {
        int mask = 0;
        for (int seat : seats) {
            mask |= 1 << seat;
        }
        return mask;
    }

    private net.minecraft.network.chat.Component namesOf(int mask) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < players.size(); i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(players.get(i).getName().getString());
        }
        return net.minecraft.network.chat.Component.literal(builder.toString());
    }

    private boolean staleCallback(long token) {
        if (token == turnToken) {
            return false;
        }
        MyAvalonMod.LOGGER.warn("stale turn callback ({} superseded by {}) - ignored", token, turnToken);
        return true;
    }

    public List<Card> pileCards() {
        List<Card> cards = new ArrayList<>();
        for (Card card : pile.getCards()) {
            cards.add(card);
        }
        return cards;
    }

    // ---------------------------------------------------------------------------------------------
    // Mirroring
    // ---------------------------------------------------------------------------------------------

    /** Value of one mirrored data slot, as seen by {@code viewer}. */
    public int syncValue(int index, @Nullable CardPlayer viewer) {
        if (index >= SYNC_MISSION_BASE && index < SYNC_MISSION_BASE + AvalonRole.QUESTS) {
            return missions[index - SYNC_MISSION_BASE];
        }
        if (index >= SYNC_STATS_BASE && index < SYNC_STATS_BASE + MAX_SEATS) {
            int at = index - SYNC_STATS_BASE;
            return at < sharpNo.length ? (sharpNo[at] << 8) | cleanTeams[at] : 0;
        }
        if (index >= SYNC_ROLE_BASE && index < SYNC_ROLE_BASE + MAX_SEATS) {
            int at = index - SYNC_ROLE_BASE;
            return phase == Phase.OVER && at < roles.size() ? roles.get(at).ordinal() + 1 : 0;
        }
        int from = getSeat(viewer);
        return switch (index) {
            case SYNC_PHASE -> phase.ordinal();
            case SYNC_QUEST -> quest;
            case SYNC_LEADER -> leaderSeat;
            case SYNC_REJECTS -> rejects;
            case SYNC_TEAM -> teamMask;
            case SYNC_PENDING -> pendingMask;
            case SYNC_VOTED -> getVotedMask();
            case SYNC_YES -> yesVotes;
            case SYNC_NO -> voted.size() - yesVotes;
            case SYNC_QUESTED -> getQuestMask();
            case SYNC_FAILS -> questFails;
            case SYNC_TEAM_SIZE -> teamSize();
            case SYNC_ASSA_SELF -> from >= 0 && from == assassinSeat ? 1 : 0;
            case SYNC_TARGET -> pendingTarget;
            case SYNC_WINNER -> winner;
            case SYNC_KNOWN -> from < 0 ? 0 : knownMask(from);
            case SYNC_MATCH -> matchId;
            case SYNC_OVER -> isGameOver ? 1 : 0;
            case SYNC_NO_VOTERS -> noVoterMask();
            // Masked to 16 bits: the data-slot packet is a short, and a high half with its top bit set
            // would otherwise arrive sign-extended as a negative int.
            case SYNC_CENSUS -> roleCensus & 0xFFFF;
            case SYNC_CENSUS_HIGH -> (roleCensus >>> 16) & 0xFFFF;
            case SYNC_DESIGNATED -> designatedQuests() ? 1 : 0;
            case SYNC_OPEN_QUESTS -> openQuestMask();
            case SYNC_ROLE -> {
                AvalonRole role = from < 0 ? null : roleAt(from);
                yield role == null ? 0 : role.ordinal() + 1;
            }
            default -> 0;
        };
    }

    /**
     * The pile, overridden to always report itself empty so its widget stays out of the way.
     *
     * <p>It also owns the one place Charta's own turn gate has to step aside. {@code PlaySlot} accepts a
     * dropped card with {@code player == game.getCurrentPlayer() && game.canPlay(...)}, and that identity
     * test is what a simultaneous vote has no answer for: five seats are all legal actors at once, and
     * "the current player" is a single seat the vote phase does not maintain. So the gate is dropped here
     * and the whole decision is left to {@link AvalonGame#canPlay}, which is now the only thing that
     * knows which phases are open to whom — a vote to every seat still to answer, a quest card to the seat
     * on turn, and nothing at all while the table is proposing or the assassin is choosing.
     */
    private static final class PileSlot extends PlaySlot {

        private final AvalonGame game;

        private PileSlot(AvalonGame game, List<Card> cards) {
            super(game, cards, 0f, 0f, 0f, 0f, null);
            this.game = game;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean canInsertCard(CardPlayer player, List<Card> cards, int index) {
            return game.canPlay(player, new GamePlay(cards, getIndex()));
        }
    }

    /** Convenience for the screen: {@code myavalon:avalon}'s deck id, for the pile's card lookups. */
    public static net.minecraft.resources.ResourceLocation gameId() {
        return ChartaMod.id("avalon");
    }

}
