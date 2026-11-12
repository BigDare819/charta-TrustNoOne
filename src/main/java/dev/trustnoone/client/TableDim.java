package dev.trustnoone.client;

/**
 * How much of full brightness the table is drawn at this frame, as a plain multiplier.
 *
 * <h2>Why a static and not a parameter</h2>
 *
 * <p>The number is written by {@code AvalonScreen} once a frame and read from inside a mixin on Charta's
 * picture blit, i.e. from code that has no way to be handed a parameter. One field, one writer, one reader.
 *
 * <h2>Why the cards need it at all</h2>
 *
 * <p>Dimming the finished table with a translucent rectangle over the top is the obvious way to do it, and
 * it is the way that kept failing: Charta's cards do not go through {@code GuiGraphics}' batch — they build
 * a quad on the shared {@code Tesselator} and upload it there and then — so a wash written after the hands
 * in code could still land under them, and "the table dims and the hand rows stay bright" survived two
 * attempts at moving that call around. Multiplying the pictures at the source has no order to get wrong:
 * every card that is drawn while this is below {@code 1} comes out dimmed, wherever in the frame it lands.
 *
 * <p>The rest of the table — the felt, the board, the plates and every line of text — is batched and does go
 * through {@code GuiGraphics}, so it is dimmed with {@code GuiGraphics.setColor} instead. The two meet at
 * the same brightness: {@link #BUSY} is {@code 1 - } the scrim's darkness.
 */
public final class TableDim {

    private TableDim() {
    }

    /** What the table is multiplied by while the screen is busy: the finished board, dimmed but readable. */
    public static final float BUSY = 0.62f;

    /** Live value. {@code 1} is full brightness. */
    public static float value = 1f;
}
