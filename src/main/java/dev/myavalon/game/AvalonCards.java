package dev.myavalon.game;

import dev.myavalon.MyAvalonMod;
import dev.lucaargolo.charta.common.game.Ranks;
import dev.lucaargolo.charta.common.game.Suits;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Rank;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import net.minecraft.core.Registry;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The four suits and twelve ranks the Avalon deck is built from, and the lookup both ways between a
 * {@link Card} and the {@link AvalonRole} it stands for.
 *
 * <h2>Why these are registered by hand instead of through {@code Suits.MOD_REGISTRY}</h2>
 *
 * <p>{@code ModRegistry.registerDirectly} parks an entry in a map that only gets pushed into the real
 * registry when {@code ModRegistry.init()} runs — and that call belongs to {@code ChartaMod.init()},
 * i.e. to Charta's own entrypoint. Fabric does not order a dependant's entrypoint after its
 * dependency's, so an addon that registers a suit this way has a coin-flip chance of adding it to a
 * map nobody will ever drain. {@code Registry.register} writes straight into the registry object that
 * already exists and has no such ordering requirement; that is also how the game type in
 * {@link MyAvalonMod} is registered.
 *
 * <p>Two side effects worth knowing: the ids land in the {@code myavalon} namespace (the {@code
 * ModRegistry} path would have filed them under {@code charta}), and neither registry is ever frozen,
 * so a reload cannot pull them back out from under a live deck.
 *
 * <h2>Ranks carry the identity, suits carry the side</h2>
 *
 * <p>A role card is a suit (which side it plays for) plus a rank (which role it is). Keeping the two
 * apart is what lets the screen colour a card by {@code deck.getSuitColor} and still name it by rank,
 * and it means the plain seats — of which there are several copies — are one rank each rather than one
 * rank per copy.
 *
 * <p>That last point is also why {@code data/myavalon/decks/avalon.json} lists each rank exactly once:
 * the deck's card list is what the deck preview page draws, so a second entry for a rank is a second
 * identical row on it. Nothing else reads the list — the hands come from {@link #roleCard} and its two
 * siblings, which mint a fresh card whenever they are asked — so the entry count is a catalogue, not a
 * box count.
 */
public final class AvalonCards {

    public static Suit GOOD;
    public static Suit EVIL;
    public static Suit VOTE;
    public static Suit QUEST;
    /**
     * The table's own furniture as cards: the leader's crown and the rejection marker. They are never
     * dealt -- {@link #isAvalonSuit} leaves them out of the game's card list and the hands are built by
     * hand anyway -- they exist so the pack's prop art is part of the deck, which is where it is looked
     * at (the deck preview) and where the pack's own pieces belong.
     */
    public static Suit TOKEN;

    public static Rank MERLIN;
    public static Rank PERCIVAL;
    public static Rank LOYALIST;
    public static Rank ASSASSIN;
    public static Rank MORGANA;
    public static Rank MORDRED;
    public static Rank OBERON;
    public static Rank MINION;
    public static Rank LANCELOT_GOOD;
    public static Rank LANCELOT_EVIL;

    public static Rank YES;
    public static Rank NO;
    public static Rank SUCCESS;
    public static Rank FAIL;

    public static Rank CROWN;
    public static Rank IRON_NUGGET;

    private static final Map<AvalonRole, Rank> ROLE_RANKS = new EnumMap<>(AvalonRole.class);
    private static final Map<Rank, AvalonRole> RANK_ROLES = new java.util.HashMap<>();

    private AvalonCards() {
    }

    /** Registers everything. Called once from the mod entrypoint, after Charta is known to be up. */
    public static void init() {
        GOOD = suit("good");
        EVIL = suit("evil");
        VOTE = suit("vote");
        QUEST = suit("quest");
        TOKEN = suit("token");

        // Ordinals only ever drive sorting; the role ranks start at 100 so they can never collide with
        // the standard ace-to-king run if a future Charta adds a shared comparator.
        MERLIN = rank("merlin", 100);
        PERCIVAL = rank("percival", 101);
        LOYALIST = rank("loyalist", 102);
        ASSASSIN = rank("assassin", 103);
        MORGANA = rank("morgana", 104);
        MORDRED = rank("mordred", 105);
        OBERON = rank("oberon", 106);
        MINION = rank("minion", 107);
        LANCELOT_GOOD = rank("lancelot_good", 108);
        LANCELOT_EVIL = rank("lancelot_evil", 109);

        YES = rank("yes", 200);
        NO = rank("no", 201);
        SUCCESS = rank("success", 202);
        FAIL = rank("fail", 203);

        // The two props get the next block up; nothing sorts them against roles or votes.
        CROWN = rank("crown", 300);
        IRON_NUGGET = rank("iron_nugget", 301);

        bind(AvalonRole.MERLIN, MERLIN);
        bind(AvalonRole.PERCIVAL, PERCIVAL);
        bind(AvalonRole.LOYALIST, LOYALIST);
        bind(AvalonRole.ASSASSIN, ASSASSIN);
        bind(AvalonRole.MORGANA, MORGANA);
        bind(AvalonRole.MORDRED, MORDRED);
        bind(AvalonRole.OBERON, OBERON);
        bind(AvalonRole.MINION, MINION);
        bind(AvalonRole.LANCELOT_GOOD, LANCELOT_GOOD);
        bind(AvalonRole.LANCELOT_EVIL, LANCELOT_EVIL);
    }

    private static Suit suit(String path) {
        return Registry.register(Suits.getRegistry(), MyAvalonMod.id(path), new Suit());
    }

    private static Rank rank(String path, int ordinal) {
        return Registry.register(Ranks.getRegistry(), MyAvalonMod.id(path), new Rank(ordinal));
    }

    private static void bind(AvalonRole role, Rank rank) {
        ROLE_RANKS.put(role, rank);
        RANK_ROLES.put(rank, role);
    }

    public static Rank rankOf(AvalonRole role) {
        return ROLE_RANKS.get(role);
    }

    /** The role a card stands for, or {@code null} when it is a vote or quest card instead. */
    @Nullable
    public static AvalonRole roleOf(@Nullable Card card) {
        return card == null ? null : RANK_ROLES.get(card.rank());
    }

    public static boolean isRole(@Nullable Card card) {
        return card != null && (card.suit() == GOOD || card.suit() == EVIL);
    }

    public static boolean isVote(@Nullable Card card) {
        return card != null && card.suit() == VOTE;
    }

    public static boolean isQuest(@Nullable Card card) {
        return card != null && card.suit() == QUEST;
    }

    public static Card roleCard(AvalonRole role) {
        return Card.create(role.getFaction() == AvalonRole.Faction.GOOD ? GOOD : EVIL, rankOf(role));
    }

    public static Card voteCard(boolean yes) {
        return Card.create(VOTE, yes ? YES : NO);
    }

    public static Card questCard(boolean success) {
        return Card.create(QUEST, success ? SUCCESS : FAIL);
    }

    /** {@code true} when {@code card} is a "yes" vote. */
    public static boolean isYes(@Nullable Card card) {
        return card != null && card.rank() == YES;
    }

    /** {@code true} when {@code card} is a "no" vote. A seat that abstained played neither. */
    public static boolean isNo(@Nullable Card card) {
        return card != null && card.rank() == NO;
    }

    /** {@code true} when {@code card} is a "success" quest card. */
    public static boolean isSuccess(@Nullable Card card) {
        return card != null && card.rank() == SUCCESS;
    }

    /**
     * Whether {@code card} belongs to the game -- the four suits a hand can hold.
     *
     * <p>{@link #TOKEN} is deliberately left out: the crown and the nugget are card-shaped pieces of the
     * table, not cards anybody is dealt, so {@code AvalonGame.getCardPredicate} keeps them out of the
     * game's own deck even though they do sit in the pack's.
     */
    public static boolean isAvalonSuit(@Nullable Card card) {
        return card != null && (card.suit() == GOOD || card.suit() == EVIL
                || card.suit() == VOTE || card.suit() == QUEST);
    }
}
