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
     * Three slots: how many of each role this deal put on the table, four bits per role ordinal.
     *
     * <p>Public information at the start of a game -- the printed setup names every identity in play --
     * but the client cannot derive it: the identity setup is an option, the cunning-assassin rule can
     * drop Oberon, and a short table pads itself with loyalists. Counts top out at {@code MAX_SEATS},
     * well inside a nibble, but ten nibbles are 40 bits and a container data slot is a <b>short</b>:
     * the value arrives through {@code ClientboundContainerSetDataPacket}, which writes and reads it as
     * a signed 16-bit number. So the census is split across three slots and the client reassembles it.
     */
    public static final int SYNC_CENSUS = SYNC_STATS_BASE + MAX_SEATS;
    /** Bits 16-31 of the census; see {@link #SYNC_CENSUS}. */
    public static final int SYNC_CENSUS_HIGH = SYNC_CENSUS + 1;
    /** Bits 32-39 of the census, which is all the two Lancelot nibbles need. */
    public static final int SYNC_CENSUS_TOP = SYNC_CENSUS_HIGH + 1;
    /** One slot: {@code 1} while the designated-quest rule is on. */
    public static final int SYNC_DESIGNATED = SYNC_CENSUS_TOP + 1;
    /**
     * One slot: the quests the leader may pick right now, as a bitmask.
     *
     * <p>Mirrored rather than derived because the lock on the fifth quest depends on how many quests have
     * <em>succeeded</em>, which is a rule fact rather than a property of the board. The screen draws the
     * pickable slots from this exactly as the server validates them.
     */
    public static final int SYNC_OPEN_QUESTS = SYNC_DESIGNATED + 1;
    /** The viewer's plot cards, as a bitmask over {@link AvalonPlot} ordinals. */
    public static final int SYNC_PLOT_HAND = SYNC_OPEN_QUESTS + 1;
    /** The viewer's plot cards whose window is open right now, same bit layout. */
    public static final int SYNC_PLOT_CAN_USE = SYNC_PLOT_HAND + 1;
    /** The most cards one leader can draw in a round; one slot each while distributing. */
    public static final int MAX_PLOT_DRAW = 3;
    /** One slot per drawn card: its ordinal + 1, or {@code 0} once it has been handed out. */
    public static final int SYNC_PLOT_DRAW_BASE = SYNC_PLOT_CAN_USE + 1;
    /** {@code 1} while the No Confidence window is open. */
    public static final int SYNC_VETO = SYNC_PLOT_DRAW_BASE + MAX_PLOT_DRAW;
    /** The seat forced to play its quest card face up, or {@code -1}. */
    public static final int SYNC_SPOTLIGHT = SYNC_VETO + 1;
    /** The seat holding the Lady of the Lake, or {@code -1} when the rule is off. */
    public static final int SYNC_LADY_HOLDER = SYNC_SPOTLIGHT + 1;
    /** How many of the three Lady uses are gone. */
    public static final int SYNC_LADY_USES = SYNC_LADY_HOLDER + 1;
    /** {@code 1} while the local viewer is the Lady holder and owes the table a choice. */
    public static final int SYNC_LADY_ACTIVE = SYNC_LADY_USES + 1;
    /** Seats the local Lady holder may examine, as a bitmask. */
    public static final int SYNC_LADY_ELIGIBLE = SYNC_LADY_ACTIVE + 1;
    /**
     * The last loyalty reveal shown to this viewer: the subject's seat and faction.
     *
     * <p>Per-viewer because the reveal is secret -- two seats' mirrors say different things -- and
     * stamped so the client can tell a new reveal from the same one redrawn on the next frame.
     */
    public static final int SYNC_REVEAL_SEAT = SYNC_LADY_ELIGIBLE + 1;
    public static final int SYNC_REVEAL_FACTION = SYNC_REVEAL_SEAT + 1;
    public static final int SYNC_REVEAL_STAMP = SYNC_REVEAL_FACTION + 1;
    /** {@code 1} when the loyalty-card rule is on and reveals are drawn as cards. */
    public static final int SYNC_LOYALTY = SYNC_REVEAL_STAMP + 1;
    /** The seat holding the Royal Sword, or {@code -1}. */
    public static final int SYNC_SWORD = SYNC_LOYALTY + 1;
    /** Seats the sword may be given to (SWORD) or turned on (SWING), as a bitmask. */
    public static final int SYNC_SWORD_ELIGIBLE = SYNC_SWORD + 1;
    /** {@code 1} while the Lancelot rule is on and at least one wolf is seated. */
    public static final int SYNC_LANCELOT = SYNC_SWORD_ELIGIBLE + 1;
    /** {@code 1} while both wolves are on their other side -- public, because the drew card is. */
    public static final int SYNC_LANCELOT_SWAP = SYNC_LANCELOT + 1;
    /**
     * The seat the Angel examined last, or {@code -1}. Public on purpose: the act is announced to the
     * table anyway ({@code message.myavalon.lady_used}), and the reveal card has to fly to the middle
     * for every client -- face down for all but the holder, whose own mirror carries the answer.
     */
    public static final int SYNC_LADY_TARGET = SYNC_LANCELOT_SWAP + 1;
    /** {@code 1} when the King's Sword rule is on. Public: the client gates Confirm on it. */
    public static final int SYNC_EXCALIBUR = SYNC_LADY_TARGET + 1;
    /**
     * Who played the {@code i}-th card in the pile row, as seat + 1 ({@code 0} past the end). Public:
     * the quest cards lie face down in play order, and both the "who played this" tooltip and the
     * sword's card-click need the pair up.
     */
    public static final int SYNC_QUEST_ORDER_BASE = SYNC_EXCALIBUR + 1;
    public static final int SYNC_QUEST_ORDER_SLOTS = 5;
    /** {@code 1} when the card-tracker panel option is on. */
    public static final int SYNC_TRACKER = SYNC_QUEST_ORDER_BASE + SYNC_QUEST_ORDER_SLOTS;
    /** The seat whose quest card the King's Sword last turned over, or {@code -1}. */
    public static final int SYNC_SWORD_TARGET = SYNC_TRACKER + 1;
    /** The seat the holder is currently pointing the sword at, or {@code -1}. */
    public static final int SYNC_SWORD_AIM = SYNC_SWORD_TARGET + 1;
    /** {@code 1} when the Angel rule is on. The client's tracker must not invent one when it is off. */
    public static final int SYNC_LADY_RULE = SYNC_SWORD_AIM + 1;
    /** {@code 1} when the plot-card rule is on (shown on the opening act's rules line). */
    public static final int SYNC_PLOT_RULE = SYNC_LADY_RULE + 1;
    public static final int SYNC_SIZE = SYNC_PLOT_RULE + 1;

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
    /** Royal Sword: the leader's hand-out and the holder's flip, plus a pass. */
    public static final int SWORD_ASSIGN_BASE = 512;
    public static final int SWORD_STRIKE_BASE = 640;
    public static final int SWORD_PASS = SWORD_STRIKE_BASE + 15;
    /** Takes a placed King's Sword back off a member during the proposal (the leader keeps it in hand). */
    public static final int SWORD_RETRIEVE = SWORD_ASSIGN_BASE + MAX_SEATS;
    /**
     * The holder's aim, announced while they are looking at a card: {@code SWORD_AIM_BASE + seat}, and
     * {@link #SWORD_AIM_CLEAR} to put the sword back down without a target. Public because the table has
     * to be able to read "X has pointed the sword at Y" while the holder decides.
     */
    public static final int SWORD_AIM_BASE = 660;
    public static final int SWORD_AIM_CLEAR = SWORD_AIM_BASE + MAX_SEATS;
    /**
     * Plot-card action blocks. One int carries both the card and the seat it was aimed at, in one
     * click-pair, so the server never has to remember a half-finished selection per player.
     *
     * <p>The low four bits are the seat and {@link #NO_TARGET} means "no seat needed"; the card ordinal
     * sits above them. The two blocks are wide enough for every ordinal and every seat, and far enough
     * apart that a malformed value cannot be mistaken for the other kind of action.
     */
    public static final int PLOT_GIVE_BASE = 1024;
    public static final int PLOT_USE_BASE = 2048;
    /** Lady inspections: {@code LADY_BASE + seat}. */
    public static final int LADY_BASE = 4096;
    public static final int LADY_SKIP = LADY_BASE + 15;
    public static final int NO_TARGET = 15;

    public enum Phase {
        DEALING,
        PROPOSE,
        VOTE,
        QUEST,
        ASSASSIN,
        OVER,
        /** Plot cards are being handed out at the start of a quest round. Leader only. */
        PLOTS,
        /** The Lady of the Lake holder is choosing somebody to examine. */
        LADY,
        /** A team was approved; holders of "No Confidence" get a beat to veto it. */
        VETO,
        /** The leader is giving the Royal Sword to a member of the confirmed team. */
        SWORD,
        /** The Royal Sword holder may flip one team member's quest card before the reveal. */
        SWING
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
     * The plot-card module (谋略卡).
     *
     * <p>At the start of every quest round the leader draws one card per two seats past four and hands
     * each to a different player; everybody sees who got what. A card's own window decides when it can be
     * played. See {@link AvalonPlot}.
     */
    private final GameOption.Bool PLOT_CARDS = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.plot_cards"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.plot_cards.description")
    );

    /**
     * The Lady of the Lake (湖上夫人).
     *
     * <p>One public token, starting with the seat to the right of the first leader. After the second,
     * third and fourth quests its holder examines one player's faction and then hands the token over.
     * A seat that has examined somebody can never be examined itself, which is what keeps the three uses
     * from being spent on the same two people.
     */
    private final GameOption.Bool LADY_OF_THE_LAKE = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.lady_of_the_lake"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.lady_of_the_lake.description")
    );

    /**
     * The Royal Sword (王族之剑, Excalibur).
     *
     * <p>The leader must give the sword to a member of the confirmed team, and after the quest cards are
     * down but before they are turned over the holder may name one other member: that card is flipped and
     * the holder is told what it originally was. The use and its target are public; the card itself is
     * not. The loyalty-card module is implied by the Lady and the plot cards and needs no switch of its
     * own -- every loyalty answer is drawn as a card while one of those rules is on.
     */
    private final GameOption.Bool EXCALIBUR = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.excalibur"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.excalibur.description")
    );

    /**
     * The Lancelot pair (兰斯洛特).
     *
     * <p>Two plain seats are dealt as the two wolves: a loyalist becomes the good wolf, a minion the evil
     * one. A five-card loyalty deck -- three "no change", two "switch" -- is turned over after the second,
     * third and fourth quests; a switch card puts <em>both</em> wolves on their other side. Their current
     * side is what decides whether they may play a fail card, how the bots treat them, and which side they
     * are credited with at the end. Their dealt role, and what Merlin and the other evil seats know about
     * them, stays frozen at the deal.
     */
    /** The compact on-screen card tracker; on by default, and purely a display. */
    private final GameOption.Bool TRACKER = new GameOption.Bool(
            true,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.tracker"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.tracker.description")
    );

    private final GameOption.Bool LANCELOT = new GameOption.Bool(
            false,
            net.minecraft.network.chat.Component.translatable("rule.myavalon.lancelot"),
            net.minecraft.network.chat.Component.translatable("rule.myavalon.lancelot.description")
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
    /**
     * Four bits per role ordinal: how many of that role this deal put on the table.
     *
     * <p>A {@code long} since the Lancelot pair took the roster to ten roles: forty bits no longer fit in
     * an int, and the client reassembles the three 16-bit slots this is mirrored through.
     */
    private long roleCensus;
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
    /** Seats whose cards are in the pile, in the order they landed: the pile's own index order. */
    private final java.util.List<Integer> questPlayOrder = new java.util.ArrayList<>();
    /** The seat whose card the sword last turned over, public for the tracker. */
    private int swordFlipTarget = -1;
    /** The seat the holder is pointing the sword at while looking at a card, public. */
    private int swordAim = -1;
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

    // ---------------------------------------------------------------------------------------------
    // Optional modules: plot cards, the Lady of the Lake and the loyalty cards
    // ---------------------------------------------------------------------------------------------

    /** The cards not drawn yet, shuffled once per game. */
    private final List<AvalonPlot> plotDeck = new ArrayList<>();
    /** What each seat holds, indexed by seat. Public information, but only a seat knows its own hand. */
    private final List<List<AvalonPlot>> plotHands = new ArrayList<>();
    /** The cards the leader has drawn this round and not handed out yet. */
    private final List<AvalonPlot> plotDrawn = new ArrayList<>();
    /** Parallel to {@link #plotDrawn}: the recipient seat, or {@code -1} while still in the leader's hand. */
    private final List<Integer> plotDrawnTo = new ArrayList<>();
    /** {@code true} until this quest round's draw has happened; a rejected team must not redraw. */
    private boolean plotDrawPending;
    /** Ticks before a bot leader hands its drawn cards out. */
    private int plotDelay;
    /** One "Strong Leader" per proposal is enough; a second would only shuffle the same chairs. */
    private boolean strongLeaderUsed;
    /** Ticks left in the No Confidence window. */
    private int vetoTicks;
    /** Ticks before the bot holders make up their mind about a veto. */
    private int vetoBotDelay;
    /** The seat holding the Lady of the Lake, or {@code -1}. */
    private int ladyHolder = -1;
    /** How many of the three inspections are gone. */
    private int ladyUses;
    /** Seats that have used the Lady; the rule says they can never be examined themselves. */
    private int ladySpentMask;
    /** Ticks before a bot Lady holder makes its choice. */
    private int ladyDelay;
    /** The seat examined by the last Angel use, public. */
    private int ladyLastTarget = -1;
    /** How long the reveal card owns the table before the next proposal opens. See {@link #ladyRevealTicks}. */
    private static final int LADY_REVEAL_TICKS = 120;
    /**
     * Ticks the reveal card is on screen. While this counts down the phase stays {@code LADY}, but no
     * holder may act ({@code SYNC_LADY_ACTIVE} is suppressed); when it reaches zero the next proposal
     * opens. 120 ticks is the client's 5.6 s card with a little room on top.
     */
    private int ladyRevealTicks;
    /** The seat forced to play its quest card face up, or {@code -1}. */
    private int spotlightSeat = -1;
    /** The seat whose quest card was played face up this quest, or {@code -1}. */
    private int openCardSeat = -1;
    /** The seat holding the Royal Sword, or {@code -1}. */
    private int swordHolder = -1;
    /** Ticks before a bot leader gives the sword away. */
    /** Ticks left in the sword holder's flip window. */
    private int swingTicks;
    /** Ticks before a bot holder decides whether to use the sword. */
    private int swingBotDelay;
    /** Whether the sword was used this quest; the holder gets one decision. */
    private boolean swordUsed;
    /** Per seat, ticks before a bot tries its plot cards during a proposal / quest. */
    private int[] plotUseDelay = new int[0];
    private boolean[] plotUseTried = new boolean[0];
    /** Per viewer: the last loyalty reveal that viewer was shown. */
    private final int[] revealSeatFor = new int[MAX_SEATS];
    private final int[] revealFactionFor = new int[MAX_SEATS];
    private final int[] revealStampFor = new int[MAX_SEATS];
    private int revealSerial;

    // ---------------------------------------------------------------------------------------------
    // Optional module: the two Lancelots
    // ---------------------------------------------------------------------------------------------

    /** The good wolf's seat, or {@code -1} when the rule is off or the setup had no plain loyalist. */
    private int lancelotGoodSeat = -1;
    /** The evil wolf's seat, or {@code -1} when the rule is off or the setup had no plain minion. */
    private int lancelotEvilSeat = -1;
    /**
     * The loyalty deck: three cards that change nothing and two that turn both wolves over. Shuffled once
     * per game; the top card is turned after the second, third and fourth quests.
     */
    private final List<Boolean> loyaltyDeck = new ArrayList<>();
    /** How many loyalty cards have been turned over so far (at most three). */
    private int loyaltyDrawn;
    /** Whether both wolves are on their other side right now. */
    private boolean lancelotSwapped;

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

    /** The bots always play at the hard strength: the rule was removed from the options. */
    public AvalonStrength strength() {
        return AvalonStrength.HARD;
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
        // A custom identity set is only playable if it still deals the printed good/evil split (and, with
        // the wolf rule on, still has the two plain seats the wolves take over). Refusing here is what
        // makes "the setup is unusable" a fact rather than a hint -- the tile and the Play button both
        // read this predicate.
        int seats = tableSize();
        if (AvalonRole.isCustom(roleMask())
                && !AvalonRole.setupMatches(seats, roleMask(), true, LANCELOT.get())) {
            return Optional.of(net.minecraft.network.chat.Component.translatable(
                    "roles.myavalon.invalid_start",
                    AvalonRole.goodCount(seats), AvalonRole.evilCount(seats)));
        }
        return Optional.empty();
    }

    @Override
    public List<GameOption<?>> getOptions() {
        return List.of(ENABLE_BOTS, SEATS, ROLES, DESIGNATED_QUESTS,
                PLOT_CARDS, LADY_OF_THE_LAKE, EXCALIBUR, LANCELOT, TRACKER);
    }

    /** Whether this table plays the plot-card optional rule. */
    public boolean plotCards() {
        return PLOT_CARDS.get();
    }

    /** Always on: the rule was folded into the game rather than left as an option. */
    public boolean cunningAssassin() {
        return true;
    }

    /** Whether this table plays the Lady of the Lake optional rule. */
    public boolean ladyOfTheLake() {
        return LADY_OF_THE_LAKE.get();
    }

    /** Whether this table plays the Royal Sword optional rule. */
    public boolean excalibur() {
        return EXCALIBUR.get();
    }

    /** Whether the client shows the compact card tracker. */
    public boolean tracker() {
        return TRACKER.get();
    }

    /** Whether this table plays the Lancelot expansion -- and actually seated a wolf. */
    public boolean lancelot() {
        return LANCELOT.get() && (lancelotGoodSeat >= 0 || lancelotEvilSeat >= 0);
    }

    /** The rule is on, whether or not this particular setup had a plain seat to wolf-ify. */
    public boolean lancelotOption() {
        return LANCELOT.get();
    }

    /** Whether both wolves are on their other side right now. Public: the loyalty draws say so. */
    public boolean lancelotSwapped() {
        return lancelotSwapped;
    }

    /**
     * The side {@code seat} plays for now -- the dealt faction, except for a wolf, whose side is flipped
     * by the loyalty deck. Everything that asks "is this seat evil?" about the <em>present</em> state goes
     * through here; the deal-time questions (what Merlin was shown) still read {@link #roleAt}.
     */
    public int currentSide(int seat) {
        AvalonRole role = roleAt(seat);
        if (role == null) {
            return SIDE_NONE;
        }
        boolean evil = role.isEvil();
        if (role == AvalonRole.LANCELOT_GOOD || role == AvalonRole.LANCELOT_EVIL) {
            evil = lancelotSwapped ? !evil : evil;
        }
        return evil ? SIDE_EVIL : SIDE_GOOD;
    }

    /** Whether {@code seat} is on evil's side right now. */
    public boolean isEvilNow(int seat) {
        return currentSide(seat) == SIDE_EVIL;
    }

    /** Whether {@code seat} is on good's side right now. */
    public boolean isGoodNow(int seat) {
        return currentSide(seat) == SIDE_GOOD;
    }

    /**
     * Whether loyalty answers are presented as the blue and red loyalty cards.
     *
     * <p>Always true whenever a loyalty answer can happen: the card is part of the Lady and the plot
     * cards, not a rule of its own. A reveal is never shown as a character card, because that would put
     * Merlin's identity on the table.
     */
    public boolean loyaltyCards() {
        return ladyOfTheLake() || plotCards();
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
        plotDeck.clear();
        plotHands.clear();
        plotDrawn.clear();
        plotDrawnTo.clear();
        plotDrawPending = false;
        plotDelay = 0;
        strongLeaderUsed = false;
        vetoTicks = 0;
        vetoBotDelay = -1;
        ladyHolder = -1;
        ladyUses = 0;
        ladySpentMask = 0;
        ladyLastTarget = -1;
        ladyRevealTicks = 0;
        swordFlipTarget = -1;
        ladyDelay = 0;
        spotlightSeat = -1;
        openCardSeat = -1;
        swordHolder = -1;
        swingTicks = 0;
        swingBotDelay = -1;
        swordUsed = false;
        lancelotGoodSeat = -1;
        lancelotEvilSeat = -1;
        loyaltyDeck.clear();
        loyaltyDrawn = 0;
        lancelotSwapped = false;
        java.util.Arrays.fill(revealSeatFor, -1);
        java.util.Arrays.fill(revealFactionFor, 0);
        java.util.Arrays.fill(revealStampFor, 0);
        revealSerial = 0;
        phase = Phase.DEALING;
        matchId = ++matchSerial;

        for (CardPlayer player : players) {
            player.resetPlay();
            getPlayerHand(player).clear();
            getCensoredHand(null, player).clear();
            plotHands.add(new ArrayList<>());
        }

        dealRoles();
        leaderSeat = players.isEmpty() ? 0 : random.nextInt(players.size());

        plotUseDelay = new int[players.size()];
        plotUseTried = new boolean[players.size()];
        java.util.Arrays.fill(plotUseDelay, -1);
        plotDrawPending = plotCards();
        if (plotCards()) {
            plotDeck.addAll(AvalonPlot.deckFor(players.size()));
            Collections.shuffle(plotDeck, random);
        }
        if (ladyOfTheLake()) {
            // The printed rule hands the token to the player on the first leader's right. The ring runs
            // clockwise, so "right" is the seat before the leader in that order.
            ladyHolder = seatFrom(leaderSeat, -1);
        }

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

    /**
     * Queues handing one staged card to a seat.
     *
     * <p>No sound here: a bot has no player to hear it from, so the server-side draw sound went silent
     * for every bot seat and left holes in the deal's rhythm. The client plays one draw sound per card
     * that lands in its own animation instead (see {@code AvalonScreen.soundDealLandings}), which sounds
     * the same for every seat and cannot be split by who happens to be a player.
     */
    private void dealOne(int owner, int stagingIndex) {
        scheduledActions.add(() -> give(playerAt(owner), staging.get(stagingIndex)));
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
        while (composed.size() < players.size()) {
            composed.add(AvalonRole.LOYALIST);
        }
        while (composed.size() > players.size()) {
            composed.remove(composed.size() - 1);
        }
        if (LANCELOT.get()) {
            // The wolves take over one plain seat each. Deliberately only the plain ones: Percival and
            // Morgana carry knowledge of their own, and turning one of them into a wolf would quietly
            // change a different rule. A custom set with no loyalist or no minion simply gets one wolf,
            // or none -- the option is then announced as unused rather than padded with a role nobody chose.
            replaceRole(composed, AvalonRole.LOYALIST, AvalonRole.LANCELOT_GOOD);
            replaceRole(composed, AvalonRole.MINION, AvalonRole.LANCELOT_EVIL);
        }
        Collections.shuffle(composed, random);
        roles.addAll(composed);
        for (int i = 0; i < roles.size(); i++) {
            switch (roles.get(i)) {
                case MERLIN -> merlinSeat = i;
                case MORGANA -> morganaSeat = i;
                case ASSASSIN -> assassinSeat = i;
                case LANCELOT_GOOD -> lancelotGoodSeat = i;
                case LANCELOT_EVIL -> lancelotEvilSeat = i;
                default -> {
                }
            }
        }
        if (lancelot()) {
            // Three cards that change nothing and two that switch both wolves: shuffled, then read one at
            // a time after the second, third and fourth quests.
            loyaltyDeck.add(false);
            loyaltyDeck.add(false);
            loyaltyDeck.add(false);
            loyaltyDeck.add(true);
            loyaltyDeck.add(true);
            Collections.shuffle(loyaltyDeck, random);
        }
        // The roster the opening curtain names. Counts, not seats: this is public setup information.
        roleCensus = 0L;
        for (AvalonRole role : roles) {
            int shift = role.ordinal() * 4;
            long count = ((roleCensus >>> shift) & 0xFL) + 1;
            roleCensus = (roleCensus & ~(0xFL << shift)) | (Math.min(0xFL, count) << shift);
        }
    }

    /** Swaps the first {@code from} in {@code roles} for {@code to}; a no-op when it is not there. */
    private static void replaceRole(List<AvalonRole> roles, AvalonRole from, AvalonRole to) {
        int at = roles.indexOf(from);
        if (at >= 0) {
            roles.set(at, to);
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
        if (!isGameReady || isGameOver) {
            return;
        }
        switch (phase) {
            case PLOTS -> {
                if (isBot(playerAt(leaderSeat))) {
                    if (plotDelay > 0) {
                        plotDelay--;
                    } else if (plotDelay == 0) {
                        plotDelay = -1;
                        botDistributePlots();
                    }
                }
            }
            case LADY -> tickLady();
            case VETO -> tickVeto();
            case SWING -> tickSwing();
            case VOTE -> tickVoters();
            case QUEST -> {
                // Plot cards first: a peek may read the cards that are already down, and a spotlight has
                // to be declared before the last card lands. The quest drivers run afterwards.
                tickPlotBots();
                tickQuesters();
            }
            case PROPOSE -> tickPlotBots();
            default -> {
            }
        }
    }

    /**
     * Lets one bot holder play a plot card once its beat has run out.
     *
     * <p>Only bots that still hold something are armed (see {@link #armPlotBots}); a seat gets exactly
     * one attempt per window, so a bot cannot spend a whole hand on the same proposal.
     */
    private void tickPlotBots() {
        if (!plotCards() || plotHands.isEmpty()) {
            return;
        }
        for (int seat = 0; seat < players.size() && seat < plotUseDelay.length; seat++) {
            if (plotUseTried[seat] || !isBot(playerAt(seat))
                    || seat >= plotHands.size() || plotHands.get(seat).isEmpty()) {
                continue;
            }
            if (plotUseDelay[seat] > 0) {
                plotUseDelay[seat]--;
                continue;
            }
            int action = AvalonBots.choosePlotUse(this, seat);
            if (action >= 0) {
                plotUseTried[seat] = true;
                int code = action - PLOT_USE_BASE;
                int target = code & 0xF;
                usePlot(seat, AvalonPlot.at(code >>> 4), target == NO_TARGET ? -1 : target);
                // The play may have changed the phase (a takeover) or ended the game; the next tick
                // picks the new phase up from scratch.
                return;
            }
            // Nothing legal yet (a peek before any card is down, a steal with nobody holding one):
            // the beat is over but the attempt is not, so the next tick looks again.
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
            // The optional modules are driven by their own click actions and timers, not by a turn.
            case OVER, PLOTS, LADY, VETO, SWORD, SWING -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Proposal
    // ---------------------------------------------------------------------------------------------

    private void beginProposal() {
        // A rejected team re-enters here, and it must not draw again: the plot cards belong to the quest
        // round, not to the proposal. `plotDrawPending` is armed once when a quest resolves.
        if (plotDrawPending && plotCards()) {
            beginPlotDistribution();
            return;
        }
        beginProposalNow();
    }

    private void beginProposalNow() {
        phase = Phase.PROPOSE;
        pendingMask = 0;
        teamMask = 0;
        strongLeaderUsed = false;
        // A fresh team means a fresh sword: the rejected proposal's bearer (if any) is gone with it.
        swordHolder = -1;
        swordUsed = false;
        swordAim = -1;
        openCardSeat = -1;
        armPlotBots();
        if (designatedQuests()) {
            // The previous quest is done and the next slot has not been named by this leader yet. A
            // rejected proposal starts here too, which is the rule: every attempt names its own quest.
            quest = -1;
        }
        setCurrentPlayer(leaderSeat);
        table(designatedQuests()
                ? net.minecraft.network.chat.Component.translatable("message.myavalon.proposal_started_designated",
                        playerAt(leaderSeat).getColoredName())
                : net.minecraft.network.chat.Component.translatable("message.myavalon.proposal_started", quest + 1,
                        playerAt(leaderSeat).getColoredName(), teamSize()));
        awaitAction();
    }

    /** Gives every bot holder a short, staggered beat before it considers its plot cards. */
    private void armPlotBots() {
        if (plotUseDelay.length != players.size()) {
            plotUseDelay = new int[players.size()];
            plotUseTried = new boolean[players.size()];
        }
        java.util.Arrays.fill(plotUseTried, false);
        for (int seat = 0; seat < players.size(); seat++) {
            plotUseDelay[seat] = isBot(playerAt(seat)) && !plotHands.isEmpty()
                    && seat < plotHands.size() && !plotHands.get(seat).isEmpty()
                    ? 20 + random.nextInt(40) : -1;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Plot cards
    // ---------------------------------------------------------------------------------------------

    /** Encodes a plot-card play in the one int the action payload carries. */
    public static int plotUseAction(AvalonPlot card, int target) {
        return PLOT_USE_BASE + card.ordinal() * 16 + (target < 0 ? NO_TARGET : target);
    }

    /** Encodes one hand-out in the same way. */
    public static int plotGiveAction(AvalonPlot card, int target) {
        return PLOT_GIVE_BASE + card.ordinal() * 16 + target;
    }

    /** Encodes a Lady inspection; {@code -1} is the skip. */
    public static int ladyAction(int target) {
        return target < 0 ? LADY_SKIP : LADY_BASE + target;
    }

    /** One round's draw and distribution. The leader must give every card to a different other seat. */
    private void beginPlotDistribution() {
        plotDrawPending = false;
        plotDrawn.clear();
        plotDrawnTo.clear();
        int draw = AvalonPlot.drawCount(players.size());
        for (int i = 0; i < draw && !plotDeck.isEmpty(); i++) {
            AvalonPlot card = plotDeck.remove(0);
            if (card == AvalonPlot.ESTABLISH_CONFIDENCE) {
                // The one card that names the leader: it is the leader who has to show a loyalty card, so
                // it stays in their hand instead of joining the hand-out row. It counts as one of the
                // round's drawn cards, which is why the loop does not draw a replacement.
                plotHands.get(leaderSeat).add(card);
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_kept",
                        playerAt(leaderSeat).getColoredName(),
                        net.minecraft.network.chat.Component.translatable(card.getTranslationKey())));
                continue;
            }
            plotDrawn.add(card);
            plotDrawnTo.add(-1);
        }
        if (plotDrawn.isEmpty()) {
            beginProposalNow();
            return;
        }
        phase = Phase.PLOTS;
        pendingMask = 0;
        teamMask = 0;
        setCurrentPlayer(leaderSeat);
        plotDelay = 30;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_dealt",
                playerAt(leaderSeat).getColoredName(), plotDrawn.size()));
    }

    /**
     * One hand-out from the leader.
     *
     * <p>Every recipient in a batch must be different, which is the printed rule and also what keeps a
     * three-card draw from turning into one player's private arsenal. A card still waiting in
     * {@link #plotDrawn} is matched by kind, not by index, because the payload has room for the kind and
     * the two copies of a kind are interchangeable anyway.
     */
    private void handlePlotGive(int from, int action) {
        if (phase != Phase.PLOTS || from != leaderSeat) {
            return;
        }
        int code = action - PLOT_GIVE_BASE;
        int ordinal = code >>> 4;
        int seat = code & 0xF;
        AvalonPlot card = AvalonPlot.at(ordinal);
        if (card == null || seat < 0 || seat >= players.size() || seat == leaderSeat) {
            return;
        }
        if (plotDrawnTo.contains(seat)) {
            return;
        }
        int waiting = -1;
        for (int i = 0; i < plotDrawn.size(); i++) {
            if (plotDrawnTo.get(i) < 0 && plotDrawn.get(i) == card) {
                waiting = i;
                break;
            }
        }
        if (waiting < 0) {
            return;
        }
        plotDrawnTo.set(waiting, seat);
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_given",
                playerAt(leaderSeat).getColoredName(),
                net.minecraft.network.chat.Component.translatable(card.getTranslationKey()),
                playerAt(seat).getColoredName()));
        if (!plotDrawnTo.contains(-1)) {
            for (int i = 0; i < plotDrawn.size(); i++) {
                plotHands.get(plotDrawnTo.get(i)).add(plotDrawn.get(i));
            }
            plotDrawn.clear();
            plotDrawnTo.clear();
            beginProposalNow();
        }
    }

    /** The leader's own hand-out pass, for a bot leader: every card to a different random other seat. */
    private void botDistributePlots() {
        List<Integer> recipients = AvalonBots.choosePlotRecipients(this, leaderSeat);
        for (int i = 0; i < plotDrawn.size() && i < recipients.size(); i++) {
            int seat = recipients.get(i);
            if (seat < 0 || seat >= players.size() || seat == leaderSeat) {
                continue;
            }
            plotDrawnTo.set(i, seat);
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_given",
                    playerAt(leaderSeat).getColoredName(),
                    net.minecraft.network.chat.Component.translatable(plotDrawn.get(i).getTranslationKey()),
                    playerAt(seat).getColoredName()));
        }
        for (int i = 0; i < plotDrawn.size(); i++) {
            if (plotDrawnTo.get(i) >= 0) {
                plotHands.get(plotDrawnTo.get(i)).add(plotDrawn.get(i));
            }
        }
        plotDrawn.clear();
        plotDrawnTo.clear();
        beginProposalNow();
    }

    /** What {@code seat} holds, as a bitmask over {@link AvalonPlot} ordinals. */
    public int plotHandMask(int seat) {
        int mask = 0;
        if (seat >= 0 && seat < plotHands.size()) {
            for (AvalonPlot card : plotHands.get(seat)) {
                mask |= 1 << card.ordinal();
            }
        }
        return mask;
    }

    /** The held cards whose window is open right now, whether or not a target has been picked. */
    public int plotUsableMask(int seat) {
        int mask = 0;
        if (seat >= 0 && seat < plotHands.size()) {
            for (AvalonPlot card : plotHands.get(seat)) {
                if (card.getUse() != AvalonPlot.Use.PASSIVE && hasLegalTarget(seat, card)) {
                    mask |= 1 << card.ordinal();
                }
            }
        }
        return mask;
    }

    /** The held cards of one seat, for the bot side. Do not mutate. */
    public List<AvalonPlot> plotHand(int seat) {
        return seat >= 0 && seat < plotHands.size() ? List.copyOf(plotHands.get(seat)) : List.of();
    }

    /** Whether {@code seat} could play this card at some target right now. */
    public boolean hasLegalTarget(int seat, AvalonPlot card) {
        if (!card.needsTarget()) {
            return canUsePlot(seat, card, -1);
        }
        for (int target = 0; target < players.size(); target++) {
            if (canUsePlot(seat, card, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code seat} may play {@code card} right now, optionally at {@code target}.
     *
     * <p>A {@code -1} target means "am I allowed to pick one at all", which is what the hand strip and
     * the bots use to light a card up before a seat has been chosen.
     */
    public boolean canUsePlot(int seat, AvalonPlot card, int target) {
        if (card == null || seat < 0 || seat >= players.size() || seat >= plotHands.size()
                || !plotHands.get(seat).contains(card)) {
            return false;
        }
        if (target < -1 || target >= players.size()) {
            return false;
        }
        boolean any = target < 0;
        return switch (card.getUse()) {
            case PASSIVE -> false;
            case TAKE_LEAD -> phase == Phase.PROPOSE && seat != leaderSeat && !strongLeaderUsed;
            case VETO -> phase == Phase.VETO;
            case PEEK -> phase == Phase.QUEST && !any && (teamMask & (1 << target)) != 0
                    && quested.contains(target);
            case SPOTLIGHT -> phase == Phase.QUEST && !any && spotlightSeat < 0 && quested.isEmpty()
                    && (teamMask & (1 << target)) != 0 && !quested.contains(target);
            case STEAL -> (phase == Phase.PROPOSE || phase == Phase.PLOTS) && !any
                    && target != seat && !plotHands.get(target).isEmpty();
            case SHOW_SELF -> (phase == Phase.PROPOSE || phase == Phase.PLOTS) && !any && target != seat;
            case NEIGHBOUR -> (phase == Phase.PROPOSE || phase == Phase.PLOTS) && !any
                    && target != seat && isNeighbour(seat, target);
        };
    }

    private boolean isNeighbour(int seat, int other) {
        return players.size() > 2 && (seatFrom(seat, 1) == other || seatFrom(seat, -1) == other);
    }

    /** Applies one plot-card play. The caller has already validated the action. */
    private void usePlot(int seat, AvalonPlot card, int target) {
        if (!canUsePlot(seat, card, target)) {
            return;
        }
        removePlot(seat, card);
        switch (card.getUse()) {
            case TAKE_LEAD -> {
                strongLeaderUsed = true;
                leaderSeat = seat;
                pendingMask = 0;
                teamMask = 0;
                if (designatedQuests()) {
                    quest = -1;
                }
                setCurrentPlayer(seat);
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_take_lead",
                        playerAt(seat).getColoredName()));
                // Retire the old leader's registration before arming the new one.
                turnToken++;
                awaiting = false;
                awaitAction();
            }
            case VETO -> {
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_veto",
                        playerAt(seat).getColoredName()));
                vetoTicks = 0;
                rejects++;
                if (rejects >= MAX_REJECTS) {
                    endedByRedAct = true;
                    finish(SIDE_EVIL, "message.myavalon.evil_by_rejects");
                    return;
                }
                leaderSeat = seatFrom(leaderSeat, 1);
                beginProposalNow();
            }
            case STEAL -> {
                List<AvalonPlot> stolen = plotHands.get(target);
                AvalonPlot taken = stolen.remove(random.nextInt(stolen.size()));
                plotHands.get(seat).add(taken);
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_steal",
                        playerAt(seat).getColoredName(), playerAt(target).getColoredName(),
                        net.minecraft.network.chat.Component.translatable(taken.getTranslationKey())));
            }
            case SHOW_SELF -> {
                revealFactionTo(target, seat);
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_show",
                        playerAt(seat).getColoredName(), playerAt(target).getColoredName()));
            }
            case NEIGHBOUR -> {
                revealFactionTo(seat, target);
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_overheard",
                        playerAt(seat).getColoredName(), playerAt(target).getColoredName()));
            }
            case PEEK -> {
                Card played = questCards.get(target);
                if (played != null) {
                    tell(seat, net.minecraft.network.chat.Component.translatable("message.myavalon.plot_peek",
                            playerAt(target).getColoredName(),
                            net.minecraft.network.chat.Component.translatable(AvalonCards.isSuccess(played)
                                    ? "card.myavalon.quest_success" : "card.myavalon.quest_fail")));
                }
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_peek_used",
                        playerAt(seat).getColoredName(), playerAt(target).getColoredName()));
            }
            case SPOTLIGHT -> {
                spotlightSeat = target;
                table(net.minecraft.network.chat.Component.translatable("message.myavalon.plot_spotlight",
                        playerAt(seat).getColoredName(), playerAt(target).getColoredName()));
            }
            default -> {
            }
        }
    }

    private void removePlot(int seat, AvalonPlot card) {
        if (seat >= 0 && seat < plotHands.size()) {
            plotHands.get(seat).remove(card);
        }
    }

    /** Whether any seat holds this kind -- used to decide whether a window is worth opening. */
    private boolean anyPlotHeld(AvalonPlot card) {
        for (List<AvalonPlot> hand : plotHands) {
            if (hand.contains(card)) {
                return true;
            }
        }
        return false;
    }

    /** Whether at least one holder of this kind is a real player, i.e. needs a window to answer in. */
    private boolean anyHumanPlots(AvalonPlot card) {
        for (int seat = 0; seat < plotHands.size(); seat++) {
            if (plotHands.get(seat).contains(card) && !isBot(playerAt(seat))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows {@code subject}'s faction to {@code viewer} and mirrors it for the client's toast.
     *
     * <p>This is the one place the plot cards and the Lady meet the loyalty cards: the message and the
     * card art are chosen by the loyalty rule, while the fact -- good or evil -- is the same either way.
     */
    private void revealFactionTo(int viewer, int subject) {
        AvalonRole role = roleAt(subject);
        if (viewer < 0 || viewer >= players.size() || role == null) {
            return;
        }
        // What is revealed is loyalty as it stands, not as it was dealt: an inspection of a turned wolf
        // must say what that seat is playing for now, or the Lady would hand out a lie.
        boolean evil = isEvilNow(subject);
        revealSeatFor[viewer] = subject;
        revealFactionFor[viewer] = evil ? SIDE_EVIL : SIDE_GOOD;
        revealStampFor[viewer] = ++revealSerial;
        tell(viewer, net.minecraft.network.chat.Component.translatable(loyaltyCards()
                        ? "message.myavalon.loyalty_seen" : "message.myavalon.plot_seen",
                playerAt(subject).getName(),
                net.minecraft.network.chat.Component.translatable(
                                evil ? "faction.myavalon.evil" : "faction.myavalon.good")
                        .withStyle(style -> style.withColor(
                                evil ? AvalonRole.EVIL_TEXT : AvalonRole.GOOD_TEXT))));
    }

    /** One private system line to one seat; does nothing for a bot or a vanished entity. */
    private void tell(int seat, net.minecraft.network.chat.Component message) {
        if (seat < 0 || seat >= players.size()) {
            return;
        }
        if (playerAt(seat).getEntity() instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message);
        }
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
        // Drop any play the seat's own driver completed while the table was in a phase that does not ask
        // for one. Charta's AutoPlayer answers every tick it is the current player, and during the plot
        // and Lady windows that answer is a bare null; without this reset the completed future would fire
        // the new registration at once and a bot would hand in an empty play.
        actor.resetPlay();
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
        if (isGameOver) {
            return;
        }
        int from = getSeat(player);
        if (from < 0) {
            return;
        }
        // The optional modules answer in their own action blocks and do not need the turn machinery:
        // a plot card can be played while somebody else is still building a team.
        if (action >= LADY_BASE) {
            handleLady(from, action);
            return;
        }
        if (action >= PLOT_USE_BASE) {
            int code = action - PLOT_USE_BASE;
            int target = code & 0xF;
            usePlot(from, AvalonPlot.at(code >>> 4), target == NO_TARGET ? -1 : target);
            return;
        }
        if (action >= PLOT_GIVE_BASE) {
            handlePlotGive(from, action);
            return;
        }
        if (action == SWORD_RETRIEVE) {
            retrieveSword(from);
            return;
        }
        if (action == SWORD_AIM_CLEAR) {
            clearSwordAim(from);
            return;
        }
        if (action >= SWORD_AIM_BASE && action < SWORD_AIM_BASE + MAX_SEATS) {
            aimSword(from, action - SWORD_AIM_BASE);
            return;
        }
        if (action >= SWORD_STRIKE_BASE) {
            if (action == SWORD_PASS) {
                if (phase == Phase.SWING && from == swordHolder) {
                    finishSwing();
                }
            } else {
                useSword(from, action - SWORD_STRIKE_BASE);
            }
            return;
        }
        if (action >= SWORD_ASSIGN_BASE && action < SWORD_ASSIGN_BASE + MAX_SEATS) {
            // The sword is given during the proposal now; the old hand-out window is kept for saves that
            // still run it, and the phase decides which reading applies. This used to route every
            // assignment to the hand-out window, which is what swallowed the proposal-time give.
            assignSword(from, action - SWORD_ASSIGN_BASE);
            return;
        }
        if (!awaiting || player != currentPlayer) {
            return;
        }
        if (phase == Phase.PROPOSE && from == leaderSeat) {
            if (designatedQuests() && action >= PICK_BASE && action < PICK_BASE + AvalonRole.QUESTS) {
                int picked = action - PICK_BASE;
                selectQuest(picked, picked != quest);
            } else if (action >= TOGGLE_BASE && action < TOGGLE_BASE + MAX_SEATS) {
                int target = action - TOGGLE_BASE;
                if (target < players.size()) {
                    pendingMask ^= 1 << target;
                    // Taking the bearer out of the team takes the sword back with them; leaving it on a
                    // seat that is no longer picked would show a badge the rule cannot honour.
                    if (swordHolder >= 0 && (pendingMask & (1 << swordHolder)) == 0) {
                        swordHolder = -1;
                    }
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
        table(designatedQuests()
                ? net.minecraft.network.chat.Component.translatable("message.myavalon.team_proposed_designated",
                        quest + 1, playerAt(leaderSeat).getColoredName(), namesOf(teamMask))
                : net.minecraft.network.chat.Component.translatable("message.myavalon.team_proposed",
                        namesOf(teamMask), playerAt(leaderSeat).getColoredName()));
        // The King's Sword is handed out during the proposal, not after it -- so by the time the team is
        // sealed the bearer is already known. A leader who confirmed without giving it is refused here
        // (the client greys the button, but the server is what decides); a bot leader picks on its own.
        if (excalibur() && (swordHolder < 0 || (teamMask & (1 << swordHolder)) == 0)) {
            if (!isBot(playerAt(leaderSeat))) {
                return;
            }
            int pick = AvalonBots.chooseSwordHolder(this, leaderSeat);
            if (pick < 0 || (teamMask & (1 << pick)) == 0) {
                return;
            }
            swordHolder = pick;
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_given",
                    playerAt(leaderSeat).getColoredName(), playerAt(pick).getColoredName()));
        }
        startVote();
    }

    /**
     * One sword assignment during a proposal: the leader names a picked seat that will carry the sword.
     *
     * <p>Same validation the hand-out window used, only against the live team instead of the sealed one,
     * so the leader can move the sword as often as the team changes before Confirm.
     */
    private void assignSword(int from, int seat) {
        if (!excalibur() || phase != Phase.PROPOSE || from != leaderSeat) {
            return;
        }
        if (seat < 0 || seat >= players.size() || seat == leaderSeat
                || (pendingMask & (1 << seat)) == 0) {
            return;
        }
        if (swordHolder == seat) {
            return;
        }
        swordHolder = seat;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_given",
                playerAt(leaderSeat).getColoredName(), playerAt(seat).getColoredName()));
    }

    /** The vote half of {@link #confirmTeam}, shared by the plain and the sword-bearing paths. */
    private void startVote() {
        phase = Phase.VOTE;
        voted.clear();
        yesVotes = 0;
        voteCards.clear();
        beginVoting();
    }

    // ---------------------------------------------------------------------------------------------
    // The Royal Sword
    // ---------------------------------------------------------------------------------------------

    /** Seats the leader may give the sword to: the team, minus the leader's own chair. */
    public int swordEligibleMask() {
        if (leaderSeat < 0 || leaderSeat >= players.size()) {
            return 0;
        }
        return teamMask & ~(1 << leaderSeat) & ((1 << players.size()) - 1);
    }

    /**
     * Opens the hand-out window.
     *
     * <p>The leader has to pick somebody on the team; the rule forbids keeping the sword for oneself.
     * It is a separate phase rather than a flag inside PROPOSE because the team is already sealed at this
     * point -- the leader is no longer picking players, only a bearer.
     */
    /**
     * The leader picks the placed sword back up. The rule wants the leader to hand it to somebody else
     * before Confirm, so a misplaced sword must be movable -- picking it up clears the bearer and the
     * client then carries the card again.
     */
    private void retrieveSword(int from) {
        if (!excalibur() || phase != Phase.PROPOSE || from != leaderSeat || swordHolder < 0) {
            return;
        }
        swordHolder = -1;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_taken",
                playerAt(leaderSeat).getColoredName()));
    }

    /** Seats the sword holder may flip: the team, minus the holder and any card played face up. */
    public int swordTargetMask() {
        int mask = teamMask;
        if (swordHolder >= 0 && swordHolder < players.size()) {
            mask &= ~(1 << swordHolder);
        }
        if (openCardSeat >= 0 && openCardSeat < players.size()) {
            mask &= ~(1 << openCardSeat);
        }
        // A seat that answered with no card at all has nothing to turn over.
        for (int seat = 0; seat < players.size(); seat++) {
            if (questCards.get(seat) == null) {
                mask &= ~(1 << seat);
            }
        }
        return mask & ((1 << players.size()) - 1);
    }

    /** Opens the holder's flip window after the last quest card has landed. */
    private void beginSwing() {
        if (swordHolder < 0 || swordHolder >= players.size() || swordTargetMask() == 0) {
            finishSwing();
            return;
        }
        phase = Phase.SWING;
        swordUsed = false;
        // A person may take as long as they like with the sword -- the choice is theirs and the table
        // waits; the skip button exists for passing, not a clock. The bot keeps a short beat so it does
        // not hold the table while "thinking".
        swingTicks = isBot(playerAt(swordHolder)) ? 20 : -1;
        swingBotDelay = 10;
        setCurrentPlayer(swordHolder);
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_ready",
                playerAt(swordHolder).getColoredName()));
    }

    /** Closes the window, announces a pass, and resolves the quest with whatever the pile now holds. */
    private void finishSwing() {
        swordAim = -1;
        if (!swordUsed && swordHolder >= 0 && swordHolder < players.size()) {
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_pass",
                    playerAt(swordHolder).getColoredName()));
        }
        phase = Phase.QUEST;
        resolveQuest();
    }

    private void tickSwing() {
        if (swingTicks > 0) {
            swingTicks--;
        }
        if (swingBotDelay > 0) {
            swingBotDelay--;
        } else if (swingBotDelay == 0) {
            swingBotDelay = -1;
            if (isBot(playerAt(swordHolder))) {
                int target = AvalonBots.chooseSwordTarget(this, swordHolder);
                if (target >= 0) {
                    useSword(swordHolder, target);
                    return;
                }
                finishSwing();
                return;
            }
        }
        if (phase == Phase.SWING && swingTicks == 0) {
            // Only a bot's beat ever reaches zero; a human's is -1 and waits for their click.
            finishSwing();
        }
    }

    /** The holder points the sword at a seat while looking at its card; public, and nothing is flipped. */
    private void aimSword(int from, int seat) {
        if (phase != Phase.SWING || from != swordHolder || swordHolder < 0) {
            return;
        }
        if (seat < 0 || seat >= players.size() || (swordTargetMask() & (1 << seat)) == 0) {
            return;
        }
        swordAim = seat;
    }

    /** The holder backs out of a look: the aim is withdrawn and the table reads the plain wait line. */
    private void clearSwordAim(int from) {
        if (phase != Phase.SWING || from != swordHolder) {
            return;
        }
        if (swordAim != -1) {
            swordAim = -1;
        }
    }

    /** One flip: a human holder's click or the bot's choice. */
    private void useSword(int actor, int target) {
        if (phase != Phase.SWING || actor != swordHolder || swordHolder < 0
                || (swordTargetMask() & (1 << target)) == 0) {
            return;
        }
        Card original = questCards.get(target);
        if (original == null) {
            return;
        }
        boolean wasSuccess = AvalonCards.isSuccess(original);
        Card flipped = AvalonCards.questCard(!wasSuccess);
        // The pile is what the reveal draws. questCards keeps the original, because that is the card the
        // target's hand is owed back after the quest; the flipped copy only lives on the pile.
        //
        // The card is flipped by PLAY ORDER, not by value: two members can play the same face, and an
        // equals() search would then flip whichever of the two came first in the pile -- the wrong
        // seat's card, and the wrong card for the holder who clicked it.
        List<Card> cards = pileCards();
        int at = questPlayOrder.indexOf(target);
        boolean replaced = false;
        if (at >= 0 && at < cards.size()) {
            cards.set(at, flipped);
            replaced = true;
        }
        if (replaced) {
            pile.setCards(cards);
        } else {
            pile.add(flipped);
        }
        if (wasSuccess) {
            questFails++;
        } else {
            questFails = Math.max(0, questFails - 1);
        }
        swordUsed = true;
        swordFlipTarget = target;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.sword_used",
                playerAt(swordHolder).getColoredName(), playerAt(target).getColoredName()));
        tell(swordHolder, net.minecraft.network.chat.Component.translatable("message.myavalon.sword_original",
                playerAt(target).getColoredName(),
                net.minecraft.network.chat.Component.translatable(wasSuccess
                        ? "card.myavalon.quest_success" : "card.myavalon.quest_fail")));
        // The holder's client has already played the card's turn-over before sending this: the strike
        // arrives at the end of its animation, so the reveal may start straight away.
        finishSwing();
    }

    /** The seat that played the {@code index}-th pile card, as seat + 1, or {@code 0}. */
    public int questOrderAt(int index) {
        return index >= 0 && index < questPlayOrder.size() ? questPlayOrder.get(index) + 1 : 0;
    }

    /** Credits the leader with a team nobody on it can sabotage -- see {@link #cleanTeams}. */
    private void countCleanTeam() {
        if (leaderSeat < 0 || leaderSeat >= cleanTeams.length) {
            return;
        }
        for (int seat = 0; seat < players.size(); seat++) {
            if ((teamMask & (1 << seat)) != 0 && isEvilNow(seat)) {
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
        // An approved team that survives the veto window ends the run. Cleared here rather than at the
        // vote so a veto can still count as the fifth rejection and hand evil the game.
        rejects = 0;
        quested.clear();
        questPlayOrder.clear();
        swordFlipTarget = -1;
        swordAim = -1;
        questFails = 0;
        questTeams[quest] = teamMask;
        spotlightSeat = -1;
        openCardSeat = -1;
        swordUsed = false;
        // A fresh window for the quest-only plot cards (a peek and a forced reveal).
        armPlotBots();
        // The spotlight has to be declared before any quest card is down, so a bot holding it gets the
        // first few ticks of the quest rather than the general twenty-to-sixty beat.
        for (int seat = 0; seat < players.size() && seat < plotUseDelay.length; seat++) {
            if (isBot(playerAt(seat)) && seat < plotHands.size()
                    && plotHands.get(seat).contains(AvalonPlot.IN_THE_SPOTLIGHT)) {
                plotUseDelay[seat] = random.nextInt(6);
            }
        }
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
            playerAt(member).resetPlay();
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
            // Same stale-completion guard as beginQuest: every seat is re-armed for this round, so a
            // null answer left over from a window that asked nothing must not count as a vote.
            playerAt(i).resetPlay();
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
            if ((teamMask & (1 << seat)) != 0 && isEvilNow(seat)) {
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
        // The Opinion Maker's whole effect: its holder's ballot is public from the moment it is cast,
        // rather than from the tally. Checked before the card leaves the hand's record so a holder who
        // was dealt the card still shows it this round.
        if (plotCards() && voter < plotHands.size()
                && plotHands.get(voter).contains(AvalonPlot.OPINION_MAKER)) {
            table(card == null
                    ? net.minecraft.network.chat.Component.translatable("message.myavalon.opinion_abstain",
                    playerAt(voter).getColoredName())
                    : net.minecraft.network.chat.Component.translatable("message.myavalon.opinion_vote",
                    playerAt(voter).getColoredName(),
                    net.minecraft.network.chat.Component.translatable(
                            yes ? "card.myavalon.vote_yes" : "card.myavalon.vote_no")));
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
            questPlayOrder.add(member);
        }
        // In the Spotlight: this member's card is public the moment it lands. Announced here rather than
        // at the reveal because the point of the card is the half-second before everybody else's.
        if (member == spotlightSeat && card != null) {
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.spotlight_played",
                    playerAt(member).getColoredName(),
                    net.minecraft.network.chat.Component.translatable(AvalonCards.isSuccess(card)
                            ? "card.myavalon.quest_success" : "card.myavalon.quest_fail")));
            // A card already played face up cannot be turned by the Royal Sword; remember which seat it was.
            openCardSeat = member;
            spotlightSeat = -1;
        }
        if (!AvalonCards.isSuccess(card)) {
            // Which includes answering with no card at all: an abstention is a fail, exactly as it was when
            // the card was handed around and a seat could time out.
            questFails++;
        }
        if (awaitingQuest.isEmpty()) {
            awaiting = false;
            // The sword's flip happens after every card is down and before the reveal; with no sword
            // (or no bearer) the quest resolves straight away as before.
            if (excalibur() && swordHolder >= 0) {
                beginSwing();
            } else {
                resolveQuest();
            }
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
                // The run of rejections is only over once the quest actually starts: a No Confidence
                // veto still counts as a failed vote, and a fifth one hands evil the game. So the
                // counter is cleared in beginQuest, not here.
                if (plotCards() && anyPlotHeld(AvalonPlot.NO_CONFIDENCE)) {
                    beginVeto();
                } else {
                    beginQuest();
                }
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

    // ---------------------------------------------------------------------------------------------
    // No Confidence: the beat between an approved vote and the quest
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the veto window.
     *
     * <p>Short when only bots hold the card, long enough for a person to read the approved team and
     * decide. A table where nobody acts simply starts the quest, which is the printed outcome of not
     * playing the card.
     */
    private void beginVeto() {
        phase = Phase.VETO;
        vetoTicks = anyHumanPlots(AvalonPlot.NO_CONFIDENCE) ? 200 : 20;
        vetoBotDelay = 15;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.veto_window"));
    }

    private void tickVeto() {
        if (vetoTicks > 0) {
            vetoTicks--;
        }
        if (vetoBotDelay > 0) {
            vetoBotDelay--;
        } else if (vetoBotDelay == 0) {
            vetoBotDelay = -1;
            for (int seat = 0; seat < players.size(); seat++) {
                if (isBot(playerAt(seat)) && plotHands.get(seat).contains(AvalonPlot.NO_CONFIDENCE)
                        && AvalonBots.chooseVeto(this, seat)) {
                    usePlot(seat, AvalonPlot.NO_CONFIDENCE, -1);
                    return;
                }
            }
        }
        if (phase == Phase.VETO && vetoTicks <= 0) {
            beginQuest();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The Lady of the Lake
    // ---------------------------------------------------------------------------------------------

    /**
     * Opens the Lady's window after the second, third or fourth resolved quest.
     *
     * <p>The holder is public and so is the act; only the loyalty answered is private. Skipped outright
     * when every other seat has already used the Lady, which can only happen on a short table.
     */
    private void beginLady() {
        if (ladyTargetMask() == 0) {
            leaderSeat = seatFrom(leaderSeat, 1);
            beginProposal();
            return;
        }
        // The Angel's window opens with a clean table: the previous proposal's team markers are put away
        // here, and the seats the Angel may examine are what the highlight is for from now on.
        pendingMask = 0;
        teamMask = 0;
        phase = Phase.LADY;
        ladyDelay = 30;
        setCurrentPlayer(ladyHolder);
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.lady_awakes",
                playerAt(ladyHolder).getColoredName()));
    }

    /** Seats the current Lady holder may examine. */
    public int ladyTargetMask() {
        if (ladyHolder < 0 || ladyHolder >= players.size()) {
            return 0;
        }
        int mask = 0;
        for (int seat = 0; seat < players.size(); seat++) {
            if (seat != ladyHolder && (ladySpentMask & (1 << seat)) == 0) {
                mask |= 1 << seat;
            }
        }
        return mask;
    }

    private void tickLady() {
        if (ladyRevealTicks > 0) {
            // The inspection is done and the reveal card is playing for everybody: the next proposal
            // waits for it, so the table reads the answer before it is asked to draft a team again.
            if (--ladyRevealTicks == 0) {
                leaderSeat = seatFrom(leaderSeat, 1);
                beginProposal();
            }
            return;
        }
        if (ladyHolder < 0 || !isBot(playerAt(ladyHolder)) || ladyDelay-- > 0) {
            return;
        }
        int target = AvalonBots.chooseLadyTarget(this, ladyHolder);
        if (target < 0) {
            // The inspection cannot be skipped, so a bot with no considered target takes the first legal
            // one rather than leaving the table waiting on a choice it will never make.
            int mask = ladyTargetMask();
            target = mask == 0 ? -1 : Integer.numberOfTrailingZeros(mask);
        }
        if (target >= 0) {
            handleLady(ladyHolder, ladyAction(target));
        }
    }

    /**
     * A Lady choice: {@code action} is {@link #ladyAction(int)}. There is no skip -- the rule's whole
     * point is that the holder must look, and the table knows it. A holder who never answers simply
     * holds the table (a human), which is the same pressure every turn on this table already carries.
     */
    private void handleLady(int from, int action) {
        if (phase != Phase.LADY || from != ladyHolder || ladyRevealTicks > 0) {
            return;
        }
        if (action == LADY_SKIP) {
            return; // No skip: the old action code is ignored rather than honoured.
        }
        int target = action - LADY_BASE;
        if (target < 0 || target >= players.size() || (ladyTargetMask() & (1 << target)) == 0) {
            return;
        }
        int holder = ladyHolder;
        revealFactionTo(holder, target);
        ladyUses++;
        ladyLastTarget = target;
        ladySpentMask |= 1 << holder;
        ladyHolder = target;
        table(net.minecraft.network.chat.Component.translatable("message.myavalon.lady_used",
                playerAt(holder).getColoredName(), playerAt(target).getColoredName()));
        // Not beginProposal() yet: the reveal card has to fly and turn over first. The countdown lives in
        // tickLady, and ACTIVE/ELIGIBLE are suppressed while it runs so the new holder cannot act early.
        ladyRevealTicks = LADY_REVEAL_TICKS;
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
            // The loyalty deck is turned after the second, third and fourth quests -- before the next
            // leader names anything, so the table knows which side the wolves are on for that round.
            drawLoyaltyIfDue();
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
            // A quest round begins here, and this is the only place its plot cards are drawn: a rejected
            // team re-enters the proposal without a fresh draw.
            plotDrawPending = plotCards();
            if (ladyOfTheLake() && ladyUses < 3 && resolvedQuests() >= 2 && resolvedQuests() <= 4) {
                beginLady();
                return;
            }
            leaderSeat = seatFrom(leaderSeat, 1);
            beginProposal();
        };
        lingerTicks = QUEST_LINGER;
    }

    /**
     * Turns the next loyalty card over once the board has the right number of quests in it.
     *
     * <p>Due after the second, third and fourth quests -- {@code loyaltyDrawn} trails {@code
     * resolvedQuests} by exactly two, which also makes a repeated call for the same board a no-op. The
     * card is public: the table is told whether the wolves switched, and only then told which side they
     * are now on. That is the whole information the module gives anybody who is not a wolf.
     */
    private void drawLoyaltyIfDue() {
        if (!lancelot() || loyaltyDrawn >= loyaltyDeck.size()) {
            return;
        }
        int resolved = resolvedQuests();
        if (resolved < 2 || resolved > 4 || loyaltyDrawn != resolved - 2) {
            return;
        }
        boolean swap = loyaltyDeck.get(loyaltyDrawn);
        loyaltyDrawn++;
        table(net.minecraft.network.chat.Component.translatable(
                swap ? "message.myavalon.lancelot_switch" : "message.myavalon.lancelot_steady", resolved));
        if (swap) {
            lancelotSwapped = !lancelotSwapped;
            table(net.minecraft.network.chat.Component.translatable("message.myavalon.lancelot_now",
                    net.minecraft.network.chat.Component.translatable(lancelotSwapped
                                    ? "faction.myavalon.evil" : "faction.myavalon.good")
                            .withStyle(style -> style.withColor(lancelotSwapped
                                    ? AvalonRole.EVIL_TEXT : AvalonRole.GOOD_TEXT))));
        }
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
        // The table stops talking about teams here: the flags and the leader's crown belong to the
        // proposal, and the one question left is the knife. Clearing the masks takes the flags and the
        // picked-seat highlight away for everyone at once.
        pendingMask = 0;
        teamMask = 0;
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
        // The legality test reads the seat's <em>current</em> side: a wolf that has been turned to good may
        // not sabotage any more, and one turned to evil no longer has to play a success.
        return currentSide(from) == SIDE_EVIL || AvalonCards.isSuccess(card);
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
                // A wolf is told which side it finished on, not the side it was dealt.
                boolean good = isGoodNow(i);
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
                net.minecraft.network.chat.Component roleName =
                        net.minecraft.network.chat.Component.translatable(roles.get(i).getTranslationKey());
                AvalonRole dealt = roles.get(i);
                net.minecraft.network.chat.Component subtitle = dealt == AvalonRole.LANCELOT_GOOD
                        || dealt == AvalonRole.LANCELOT_EVIL
                        ? net.minecraft.network.chat.Component.translatable("message.myavalon.you_were_now",
                        roleName, net.minecraft.network.chat.Component.translatable(good
                                ? "faction.myavalon.good" : "faction.myavalon.evil"))
                        : net.minecraft.network.chat.Component.translatable("message.myavalon.you_were", roleName);
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
                // The winner is a side; whether this seat won is the side it finished on compared with it.
                AvalonStats.record(serverPlayer.getServer(), serverPlayer, roles.get(i), goodWon == isGoodNow(i));
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
            lines.add(Component.translatable("message.myavalon.summary_rank",
                            padLeft(Integer.toString(place + 1), 2),
                            padRight(names.get(seat).getString(), 12),
                            padLeft(Integer.toString(counts[seat]), 2))
                    .withStyle(style -> style.withColor(evil ? SUMMARY_EVIL : SUMMARY_GOOD)));
        }
    }

    /**
     * Pads a string on the right so a name column lines up. Widths are counted in display columns: a
     * CJK glyph is two, anything else one, which is how the vanilla font lays them out.
     */
    private static String padRight(String text, int columns) {
        return text + " ".repeat(Math.max(0, columns - displayColumns(text)));
    }

    /** Pads a number on the left, so a right-aligned column of counts lines up too. */
    private static String padLeft(String text, int columns) {
        return " ".repeat(Math.max(0, columns - displayColumns(text))) + text;
    }

    private static int displayColumns(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width;
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

    /** How many evil seats are on the team currently on the table, by current side. */
    public int teamEvilCount() {
        int count = 0;
        for (int seat = 0; seat < players.size(); seat++) {
            if ((teamMask & (1 << seat)) != 0 && isEvilNow(seat)) {
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

    /** The card {@code seat} played in the running quest, or {@code null}. */
    @Nullable
    public Card questCardOf(int seat) {
        return questCards.get(seat);
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
            case ASSASSIN, MORGANA, MORDRED, MINION, LANCELOT_EVIL -> {
                for (int i = 0; i < roles.size(); i++) {
                    if (roles.get(i).isEvil() && roles.get(i) != AvalonRole.OBERON) {
                        known |= 1 << i;
                    }
                }
                // The pair knows each other, which is the one piece of information the module hands
                // out: the evil wolf sees the good one even though it was dealt on the other side.
                if (role == AvalonRole.LANCELOT_EVIL && lancelotGoodSeat >= 0) {
                    known |= 1 << lancelotGoodSeat;
                }
            }
            case LANCELOT_GOOD -> {
                // The good wolf knows only its counterpart -- it does not see the rest of evil.
                if (lancelotEvilSeat >= 0) {
                    known |= 1 << lancelotEvilSeat;
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
        int from = getSeat(viewer);
        if (index >= SYNC_PLOT_DRAW_BASE && index < SYNC_PLOT_DRAW_BASE + MAX_PLOT_DRAW) {
            int at = index - SYNC_PLOT_DRAW_BASE;
            if (phase != Phase.PLOTS || from < 0 || from != leaderSeat || at >= plotDrawn.size()) {
                return 0;
            }
            // Once a card has been handed to somebody it leaves the leader's strip, which is what tells
            // the screen how many are left to place.
            return plotDrawnTo.get(at) < 0 ? plotDrawn.get(at).ordinal() + 1 : 0;
        }
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
            case SYNC_CENSUS -> (int) (roleCensus & 0xFFFF);
            case SYNC_CENSUS_HIGH -> (int) ((roleCensus >>> 16) & 0xFFFF);
            case SYNC_CENSUS_TOP -> (int) ((roleCensus >>> 32) & 0xFFFF);
            case SYNC_DESIGNATED -> designatedQuests() ? 1 : 0;
            case SYNC_OPEN_QUESTS -> openQuestMask();
            case SYNC_PLOT_HAND -> from < 0 ? 0 : plotHandMask(from);
            case SYNC_PLOT_CAN_USE -> from < 0 ? 0 : plotUsableMask(from);
            case SYNC_VETO -> phase == Phase.VETO ? 1 : 0;
            case SYNC_SPOTLIGHT -> spotlightSeat;
            case SYNC_LADY_HOLDER -> ladyHolder;
            case SYNC_LADY_USES -> ladyUses;
            case SYNC_LADY_ACTIVE -> from >= 0 && from == ladyHolder && phase == Phase.LADY
                    && ladyRevealTicks <= 0 ? 1 : 0;
            case SYNC_LADY_ELIGIBLE -> from >= 0 && from == ladyHolder && ladyRevealTicks <= 0
                    ? ladyTargetMask() : 0;
            case SYNC_REVEAL_SEAT -> from < 0 ? -1 : revealSeatFor[from];
            case SYNC_REVEAL_FACTION -> from < 0 ? 0 : revealFactionFor[from];
            case SYNC_REVEAL_STAMP -> from < 0 ? 0 : revealStampFor[from];
            case SYNC_LOYALTY -> loyaltyCards() ? 1 : 0;
            case SYNC_SWORD -> swordHolder;
            case SYNC_SWORD_ELIGIBLE -> phase == Phase.SWING ? swordTargetMask() : 0;
            case SYNC_LANCELOT -> lancelot() ? 1 : 0;
            case SYNC_LANCELOT_SWAP -> lancelotSwapped ? 1 : 0;
            case SYNC_LADY_TARGET -> ladyLastTarget;
            case SYNC_EXCALIBUR -> excalibur() ? 1 : 0;
            case SYNC_QUEST_ORDER_BASE -> questOrderAt(0);
            case SYNC_QUEST_ORDER_BASE + 1 -> questOrderAt(1);
            case SYNC_QUEST_ORDER_BASE + 2 -> questOrderAt(2);
            case SYNC_QUEST_ORDER_BASE + 3 -> questOrderAt(3);
            case SYNC_QUEST_ORDER_BASE + 4 -> questOrderAt(4);
            case SYNC_TRACKER -> tracker() ? 1 : 0;
            case SYNC_SWORD_TARGET -> swordFlipTarget;
            case SYNC_SWORD_AIM -> swordAim;
            case SYNC_LADY_RULE -> ladyOfTheLake() ? 1 : 0;
            case SYNC_PLOT_RULE -> plotCards() ? 1 : 0;
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
