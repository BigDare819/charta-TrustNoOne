package dev.myavalon.game;

import java.util.Locale;

/**
 * How hard the bots play.
 *
 * <p>Held on {@link AvalonBot} rather than read back off the game's option, because the game asks the
 * <em>bot</em> to decide and the client rebuilds its whole roster from the option bytes: a bot built
 * from a strength and keeping it for the table is the only version of this that survives both sides
 * agreeing.
 *
 * <p>The three levels differ in what a bot is allowed to reason about, and the differences are all in
 * {@link AvalonBots}:
 * <ul>
 *   <li>{@link #EASY} votes yes to almost anything and plays whatever quest card it holds.</li>
 *   <li>{@link #NORMAL} tracks who has failed which quest and keeps evil off teams that already have
 *       evil on them, and an evil bot fails when it is safe to.</li>
 *   <li>{@link #HARD} additionally reasons about Merlin (a good bot avoids the teams Merlin would
 *       avoid) and an evil bot holds its fail back until the quest is at risk.</li>
 * </ul>
 */
public enum AvalonStrength {

    EASY,
    NORMAL,
    HARD;

    /** The strength a {@code GameOption.Number} value stands for, clamped to the three levels. */
    public static AvalonStrength of(int ordinal) {
        AvalonStrength[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    public String getTranslationKey() {
        return "strength.myavalon." + name().toLowerCase(Locale.ROOT);
    }

    public String getHintKey() {
        return getTranslationKey() + ".description";
    }

}
