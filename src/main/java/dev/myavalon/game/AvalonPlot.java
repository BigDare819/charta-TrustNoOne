package dev.myavalon.game;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The plot cards (谋略卡) of the optional rule: nine kinds, fifteen copies in the full pack.
 *
 * <h2>Where the deck comes from</h2>
 *
 * <p>Avalon's optional rules hand the plot cards of {@code The Resistance}'s "The Plot Thickens" to the
 * game. The printed pack is fifteen cards; a table of five or six plays the older seven-card set instead
 * (one copy of each of the seven base kinds), because a third of the pack would never be dealt at that
 * size. {@link #deckFor} and {@link #drawCount} are the two tables that encode exactly that.
 *
 * <h2>Who holds what</h2>
 *
 * <p>At the start of every quest round the leader draws one card per two seats past four
 * ({@link #drawCount}) and hands them to <em>other</em> players, one recipient per card. A rejected team
 * does not start a new round and therefore draws nothing. Distribution is public information: everybody
 * knows who holds which card, which is what makes {@code No Confidence} and {@code Strong Leader} work as
 * threats rather than surprises. The one exception is the {@code Establish Confidence} card, which forces
 * the leader to show a loyalty card; the draw is still public, only the loyalty shown is private.
 *
 * <h2>Loyalty, not roles</h2>
 *
 * <p>Every plot card that says "show your identity" shows a <b>loyalty</b> card (good / evil) rather than
 * the character card, so Merlin stays hidden even while his side is proven. {@link Use} is the timing a
 * card obeys; the effects themselves live in {@link AvalonGame} beside the phase that owns them.
 */
public enum AvalonPlot {

    /** Permanent: the holder's vote is taken in the open. */
    OPINION_MAKER("opinion_maker", 2, Use.PASSIVE),
    /** One-time: peek at a played quest card before the reveal. */
    CLOSE_EYE("close_eye", 2, Use.PEEK),
    /** One-time: take this round's leadership before the team is confirmed. */
    STRONG_LEADER("strong_leader", 2, Use.TAKE_LEAD),
    /** One-time: veto a team that was just approved. */
    NO_CONFIDENCE("no_confidence", 3, Use.VETO),
    /** One-time: take a plot card from another player. */
    TAKE_RESPONSIBILITY("take_responsibility", 1, Use.STEAL),
    /** One-time: show your own loyalty card to one player. */
    ESTABLISH_CONFIDENCE("establish_confidence", 1, Use.SHOW_SELF),
    /** One-time: look at the loyalty card of a neighbour. */
    OVERHEARD("overheard", 2, Use.NEIGHBOUR),
    /** One-time: force a team member to play their quest card face up. */
    IN_THE_SPOTLIGHT("in_the_spotlight", 1, Use.SPOTLIGHT),
    /** One-time: show your own loyalty card to one player (held, not forced). */
    OPEN_UP("open_up", 1, Use.SHOW_SELF);

    /**
     * When a card may be played, which is also what says whether it needs a target.
     *
     * <p>The names are the window, not the effect: two kinds share {@link #SHOW_SELF} because they are
     * played at the same moment and differ only in who initiates the reveal.
     */
    public enum Use {
        /** No click at all; the card works while it is held. */
        PASSIVE,
        /** During the quest, after a member's card is in. Target: a member who has played. */
        PEEK,
        /** During a proposal, before the team is confirmed. No target: the holder becomes leader. */
        TAKE_LEAD,
        /** During the veto window after an approved vote. No target. */
        VETO,
        /** During a proposal. Target: a player holding at least one plot card. */
        STEAL,
        /** During a proposal. Target: one other player, who is shown the holder's loyalty. */
        SHOW_SELF,
        /** During a proposal. Target: the holder's left or right neighbour. */
        NEIGHBOUR,
        /** During the quest, before a member has played. Target: that member. */
        SPOTLIGHT
    }

    private final String path;
    private final int copies;
    private final Use use;

    AvalonPlot(String path, int copies, Use use) {
        this.path = path;
        this.copies = copies;
        this.use = use;
    }

    public Use getUse() {
        return use;
    }

    /** Whether playing this card takes a second click on a seat. */
    public boolean needsTarget() {
        return switch (use) {
            case PASSIVE, TAKE_LEAD, VETO -> false;
            default -> true;
        };
    }

    /** {@code plot.myavalon.no_confidence}: the card's full name. */
    public String getTranslationKey() {
        return "plot.myavalon." + path;
    }

    /** {@code plot.myavalon.no_confidence.short}: the two- or three-character label for the hand strip. */
    public String getShortKey() {
        return "plot.myavalon." + path + ".short";
    }

    /** {@code plot.myavalon.no_confidence.hint}: the one-line rule, shown while it is hovered. */
    public String getHintKey() {
        return "plot.myavalon." + path + ".hint";
    }

    /** The card at {@code ordinal}, or {@code null} for a value outside the enum. */
    @Nullable
    public static AvalonPlot at(int ordinal) {
        AvalonPlot[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    // ---------------------------------------------------------------------------------------------
    // The pack
    // ---------------------------------------------------------------------------------------------

    /**
     * The shuffled deck a table of {@code players} plays with.
     *
     * <p>Five and six players get the seven-card base set — exactly one copy of each kind that is not
     * one of the two advanced cards. Everything from seven up gets the full fifteen. Returning a fresh
     * list rather than a shared one is deliberate: the game draws from it until it is empty.
     */
    public static List<AvalonPlot> deckFor(int players) {
        List<AvalonPlot> deck = new ArrayList<>();
        if (players < 7) {
            for (AvalonPlot card : values()) {
                if (card != IN_THE_SPOTLIGHT && card != OPEN_UP) {
                    deck.add(card);
                }
            }
            return deck;
        }
        for (AvalonPlot card : values()) {
            for (int copy = 0; copy < card.copies; copy++) {
                deck.add(card);
            }
        }
        return deck;
    }

    /** Cards the leader draws at the start of one quest round, by table size. */
    public static int drawCount(int players) {
        if (players <= 6) {
            return 1;
        }
        return players <= 8 ? 2 : 3;
    }
}
