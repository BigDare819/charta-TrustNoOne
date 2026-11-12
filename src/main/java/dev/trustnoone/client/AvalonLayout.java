package dev.trustnoone.client;

import dev.trustnoone.game.AvalonMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Where everything on the Avalon table sits, in the 640x360 design frame, and the handles the F9 editor
 * moves.
 *
 * <h2>Two families of element, two ways of anchoring</h2>
 *
 * <p>The pile, the local hand and the four status lines are always on screen, so they are plain
 * {@link Element} constants with a fixed anchor — a fan is centred on the vertical axis, the status
 * stack is centred and hung a fixed distance above the bottom edge, so nothing has to be re-derived
 * when the window changes height.
 *
 * <p>The other seats are not: a table holds five to ten players and how many chairs are taken is only
 * known once the menu exists, so those are laid out arithmetically in {@link #chipBounds}, in one row
 * across the top bar, and they are reached through the {@code HAND_1..HAND_9} and
 * {@code PLATE_1..PLATE_9} constants purely so the F9 editor has something stable to address.
 *
 * <h2>One coordinate space: the frame</h2>
 *
 * <p>{@link #bounds} returns the <em>frame</em> rectangle of an element. {@code CardSlot.x} and
 * {@code CardSlot.y} mean something different per {@link CardSlot.Type}, and the translation back into
 * that private convention happens in exactly one place, {@link #setSlot}, so the F9 outline, the painted
 * decorations and the click hit-tests all read the same numbers.
 *
 * <p>The screen reports {@code leftPos}/{@code topPos} of zero (it points its own {@code width}/
 * {@code height} at the frame), so a stored coordinate <em>is</em> a screen coordinate and
 * {@link AvalonMenu#panelLeft}/{@link AvalonMenu#panelTop} are zero by construction.
 */
public final class AvalonLayout {

    /**
     * The top-bar chip anther seat sits in, and where Charta puts the hand inside it.
     *
     * <p>All four numbers are Charta's own and none of them are a taste of ours: the chip is
     * {@code CardSlot.getWidth(PREVIEW) + 28} wide — the hand plus the room the head and its wool frame
     * take — and the hand goes at {@code (26, 7)} inside it, which is where
     * {@code AbstractCardMenu.addTopPreview} and {@code GameScreen.renderTopBar} respectively put them in
     * Charta's own Crazy Eights. The {@code 28} of the chip is also exactly the height of the bar's band.
     */
    private static final int CHIP_W = 41 + 28;
    private static final int CHIP_H = 28;
    private static final int CHIP_HAND_X = 26;
    private static final int CHIP_HAND_Y = 7;

    /** Size of the hand inside a chip: Charta's declared {@code PREVIEW} box, rounded to whole pixels. */
    private static final int OTHER_HAND_W = 41;
    private static final int OTHER_HAND_H = 18;

    /** Composed size of the team flag and the leader's crown, as the F9 editor's boxes. */
    private static final int FLAG_SIZE = 13;
    /** The crown ships as a square card now, so its box matches the square the screen draws. */
    private static final int CROWN_BOX_W = 22;
    private static final int CROWN_BOX_H = 22;

    /** The angel badge's square, for the seats across the table: about the size of the chip's avatar. */
    private static final int ANGEL_BOX_W = 16;
    private static final int ANGEL_BOX_H = 22;
    /** The local angel is a full card beside the hand, at the size a card in that fan is drawn. */
    private static final int SELF_ANGEL_W = 38;
    private static final int SELF_ANGEL_H = 53;
    /** The drafting sword's square on the proposal bar. */
    private static final int SWORD_BOX = 26;

    /** The quest board's proportions on its 2020x1180 source art; it spans 9/16 of the frame wide. */
    private static final float TASK_CARD_WIDTH_RATIO = 9f / 16f;
    private static final float TASK_CARD_ASPECT = 1180f / 2020f;

    /**
     * The centres of the five printed indicator frames on the board, as fractions of its width (the
     * source PSD's x=253/624/1012/1383/1754 on the 2020-wide canvas).
     */
    private static final float[] QUEST_SLOT_X = {0.12525f, 0.30891f, 0.50099f, 0.68465f, 0.86832f};

    /** Where the indicator frames start, and how tall they are, as fractions of the board's height. */
    private static final float QUEST_SLOT_TOP = 0.2415f;
    private static final float QUEST_SLOT_SIZE = 0.2288f;

    /** Centre of the {@code index}-th indicator frame, as a fraction of the board's width. */
    public static float questSlotX(int index) {
        return QUEST_SLOT_X[Math.min(4, Math.max(0, index))];
    }

    /** A movable piece of the screen. Sizes are the <em>drawn</em> extent, not a bounding hint. */
    public enum Element {
        /** The pile vote and quest cards land on, in the middle of the table. */
        PILE("pile", 200, 46),
        /** The local hand: a fan along the bottom edge, in front of whoever is looking. */
        SELF_HAND("self_hand", 320, 53),
        HAND_1("hand_1", OTHER_HAND_W, OTHER_HAND_H),
        HAND_2("hand_2", OTHER_HAND_W, OTHER_HAND_H),
        HAND_3("hand_3", OTHER_HAND_W, OTHER_HAND_H),
        HAND_4("hand_4", OTHER_HAND_W, OTHER_HAND_H),
        HAND_5("hand_5", OTHER_HAND_W, OTHER_HAND_H),
        HAND_6("hand_6", OTHER_HAND_W, OTHER_HAND_H),
        HAND_7("hand_7", OTHER_HAND_W, OTHER_HAND_H),
        HAND_8("hand_8", OTHER_HAND_W, OTHER_HAND_H),
        HAND_9("hand_9", OTHER_HAND_W, OTHER_HAND_H),
        SELF_PLATE("self_plate", 90, 12),
        /**
         * The team flag meant for the local seat. Independent of the plate it starts under: it is drawn
         * from its own box so the F9 editor can slide it anywhere, and the plate is not lifted to make
         * room for it.
         */
        SELF_FLAG("self_flag", FLAG_SIZE, FLAG_SIZE),
        PLATE_1("plate_1", CHIP_W, CHIP_H),
        PLATE_2("plate_2", CHIP_W, CHIP_H),
        PLATE_3("plate_3", CHIP_W, CHIP_H),
        PLATE_4("plate_4", CHIP_W, CHIP_H),
        PLATE_5("plate_5", CHIP_W, CHIP_H),
        PLATE_6("plate_6", CHIP_W, CHIP_H),
        PLATE_7("plate_7", CHIP_W, CHIP_H),
        PLATE_8("plate_8", CHIP_W, CHIP_H),
        PLATE_9("plate_9", CHIP_W, CHIP_H),
        /** One flag per other seat, same decoupling as {@link #SELF_FLAG}. */
        FLAG_1("flag_1", FLAG_SIZE, FLAG_SIZE),
        FLAG_2("flag_2", FLAG_SIZE, FLAG_SIZE),
        FLAG_3("flag_3", FLAG_SIZE, FLAG_SIZE),
        FLAG_4("flag_4", FLAG_SIZE, FLAG_SIZE),
        FLAG_5("flag_5", FLAG_SIZE, FLAG_SIZE),
        FLAG_6("flag_6", FLAG_SIZE, FLAG_SIZE),
        FLAG_7("flag_7", FLAG_SIZE, FLAG_SIZE),
        FLAG_8("flag_8", FLAG_SIZE, FLAG_SIZE),
        FLAG_9("flag_9", FLAG_SIZE, FLAG_SIZE),
        /**
         * The quest board. Its box is derived from the frame width (nine sixteenths, 16:9 tall), so the
         * editor handle rides at the board's own centre and the seals and the rejection nugget, which are
         * read off this same box, follow it.
         */
        TASK_CARD("task_card", 360, 203),
        /** The leader's crown. One per seat, so the F9 editor can place each one where that seat is. */
        SELF_CROWN("self_crown", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_1("crown_1", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_2("crown_2", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_3("crown_3", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_4("crown_4", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_5("crown_5", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_6("crown_6", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_7("crown_7", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_8("crown_8", CROWN_BOX_W, CROWN_BOX_H),
        CROWN_9("crown_9", CROWN_BOX_W, CROWN_BOX_H),
        /**
         * The confirm button. Its default spot is the pile's own centre, so the spare team flags and the
         * button it turns into sit in the same place -- the flags are gone by the time it appears.
         */
        CONFIRM("confirm", 96, 16),
        STATUS_TITLE("status_title", 340, 10),
        STATUS_TURN("status_turn", 340, 10),
        STATUS_HINT("status_hint", 380, 10),
        STATUS_TALLY("status_tally", 300, 10),
        /**
         * The rejection counter's row. No longer drawn and no longer in the editor's hit-test -- the
         * marker card on the quest board is the counter -- but kept so a saved layout file written
         * against it still parses.
         */
        STATUS_REJECTS("status_rejects", 200, 10),
        /**
         * The five designated-quest selection boxes. Anchored to the printed indicator frames on the
         * board, one per slot, and individually movable and scalable from the F9 editor; the screen's
         * highlight and its click hit-test both read these boxes, so what is dragged is what the leader
         * clicks. Appended last on purpose: the ordinal ranges of the seat families must not shift.
         */
        QUEST_SLOT_1("quest_slot_1", 48, 48),
        QUEST_SLOT_2("quest_slot_2", 48, 48),
        QUEST_SLOT_3("quest_slot_3", 48, 48),
        QUEST_SLOT_4("quest_slot_4", 48, 48),
        QUEST_SLOT_5("quest_slot_5", 48, 48),
        /**
         * The angel badge that marks who holds the Lady of the Lake, one per seat and one for the local
         * player — whose plate is not painted, so their badge hangs beside the hand instead. Appended
         * last like the quest slots: the ordinal ranges of the seat families must not shift, and a saved
         * layout file that predates these simply leaves them at their defaults.
         */
        SELF_ANGEL("self_angel", SELF_ANGEL_W, SELF_ANGEL_H),
        ANGEL_1("angel_1", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_2("angel_2", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_3("angel_3", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_4("angel_4", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_5("angel_5", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_6("angel_6", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_7("angel_7", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_8("angel_8", ANGEL_BOX_W, ANGEL_BOX_H),
        ANGEL_9("angel_9", ANGEL_BOX_W, ANGEL_BOX_H),
        /**
         * The King's Sword marker for the local seat: the mirror of the local angel card, on the other
         * side of the hand, at a team flag's size (the flags ship at {@code FLAG_DEFAULT_SCALE}).
         */
        SELF_SWORD("self_sword", FLAG_SIZE, FLAG_SIZE),
        /**
         * The drafting sword the proposal bar gains: the leader lifts it and drops it on a player's
         * avatar to pick them. One element for the whole table — it is a tool, not a per-seat marker.
         */
        SWORD_SELECT("sword_select", SWORD_BOX, SWORD_BOX);

        /** Config key, and the suffix of this element's {@code editor.trustnoone.*} label. */
        public final String key;
        public final int baseWidth;
        public final int baseHeight;

        Element(String key, int baseWidth, int baseHeight) {
            this.key = key;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
        }

        boolean isOtherHand() {
            return ordinal() >= HAND_1.ordinal() && ordinal() <= HAND_9.ordinal();
        }

        boolean isOtherPlate() {
            return ordinal() >= PLATE_1.ordinal() && ordinal() <= PLATE_9.ordinal();
        }

        boolean isOtherFlag() {
            return ordinal() >= FLAG_1.ordinal() && ordinal() <= FLAG_9.ordinal();
        }

        /**
         * Whether this element belongs to another seat's row on the top bar — its chip, its hand, or the
         * marker under it. The three families that version 2 re-anchored; see {@code VERSION}.
         */
        boolean isOtherSeat() {
            return isOtherHand() || isOtherPlate() || isOtherFlag();
        }

        boolean isOtherCrown() {
            return ordinal() >= CROWN_1.ordinal() && ordinal() <= CROWN_9.ordinal();
        }

        boolean isQuestSlot() {
            return ordinal() >= QUEST_SLOT_1.ordinal() && ordinal() <= QUEST_SLOT_5.ordinal();
        }

        int questSlotIndex() {
            return ordinal() - QUEST_SLOT_1.ordinal();
        }

        boolean isOtherAngel() {
            return ordinal() >= ANGEL_1.ordinal() && ordinal() <= ANGEL_9.ordinal();
        }

        int otherAngelIndex() {
            return ordinal() - ANGEL_1.ordinal();
        }

        public static Element otherAngel(int index) {
            return values()[ANGEL_1.ordinal() + Math.min(8, Math.max(0, index))];
        }

        /** The angel badge of the seat at {@code ringIndex}: the local one, or another seat's. */
        public static Element angel(int ringIndex) {
            return ringIndex == 0 ? SELF_ANGEL : otherAngel(ringIndex - 1);
        }

        public static Element questSlot(int index) {
            return values()[QUEST_SLOT_1.ordinal() + Math.min(4, Math.max(0, index))];
        }

        int otherCrownIndex() {
            return ordinal() - CROWN_1.ordinal();
        }

        public static Element otherCrown(int index) {
            return values()[CROWN_1.ordinal() + Math.min(8, Math.max(0, index))];
        }

        /** The crown of the seat at {@code ringIndex}: the local one, or another seat's. */
        public static Element crown(int ringIndex) {
            return ringIndex == 0 ? SELF_CROWN : otherCrown(ringIndex - 1);
        }

        int otherIndex() {
            if (isOtherHand()) {
                return ordinal() - HAND_1.ordinal();
            }
            return isOtherFlag() ? ordinal() - FLAG_1.ordinal() : ordinal() - PLATE_1.ordinal();
        }

        public static Element otherHand(int index) {
            return values()[HAND_1.ordinal() + Math.min(8, Math.max(0, index))];
        }

        public static Element otherPlate(int index) {
            return values()[PLATE_1.ordinal() + Math.min(8, Math.max(0, index))];
        }

        public static Element otherFlag(int index) {
            return values()[FLAG_1.ordinal() + Math.min(8, Math.max(0, index))];
        }

        /** Translated name, for the F9 overlay. */
        public String label() {
            return Component.translatable("editor.trustnoone." + key).getString();
        }
    }

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("trustnoone-layout.properties");

    /**
     * Layout schema.
     *
     * <p>Version 2 moved every other seat onto the top bar: their plates became chips, their hands became
     * rows of third-size cards inside those chips, and their team markers moved from under the plate to
     * under the hand. Those three families are therefore no longer anchored where they used to be, and a
     * delta tuned against the old anchor is not merely stale but actively wrong — a plate that had been
     * dragged up 77 pixels to sit under a hand at {@code y = 88} lands at {@code y = -77}, clean off the
     * top of the screen, once the anchor itself is at {@code y = 0}.
     *
     * <p>Version 3 then capped the bar at seven chips and parked the rest under the corner buttons, which
     * moves the eighth seat's anchor from the far right of the row to the far left of the frame — so the
     * same argument applies again, and to exactly the chips a player had to hand-place to make room before
     * this existed.
     *
     * <p>Version 4 split the one shared crown into one per seat, so that a crown can be placed against the
     * seat it marks instead of every crown taking the same offset from its own — which, with the seats now
     * spread across a bar, walked the outer crowns off the screen.
     *
     * <p>So an older file is not discarded whole: only the families whose anchor moved are dropped, and the
     * pile, the confirm button, the task card, the four status lines and the local seat all still anchor
     * where they always did. A player's tuning of those is real work worth keeping.
     */
    private static final String VERSION = "4";
    /** Older schemas still understood, and only for the elements that have not moved since. */
    private static final List<String> LEGACY_VERSIONS = List.of("1", "2", "3");

    /** Schemas from before the top bar existed, whose other-seat anchors moved twice over. */
    private static final List<String> LEGACY_BAR_VERSIONS = List.of("1", "2");

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    public static final float SCALE_STEP = 0.05f;

    /** Absolute anchors of the fixed furniture, measured in the design frame. */
    private static final int PILE_Y = 152;
    private static final int STATUS_TITLE_Y = 204;
    private static final int STATUS_TURN_Y = 216;
    private static final int STATUS_HINT_Y = 228;
    private static final int STATUS_TALLY_Y = 240;
    private static final int STATUS_REJECTS_Y = 252;
    /** Bottom edge an element keeps clear of: the self hand hangs here and the plate above it. */
    private static final int SELF_BOTTOM = 58;
    private static final int PLATE_GAP = 4;
    /** Gap between a plate's bottom edge and the flag's default spot under it. */
    private static final int FLAG_GAP = 1;

    /**
     * Gap between a top-bar chip's bottom edge and that seat's team marker. Four pixels wider than
     * {@link #FLAG_GAP}, which is what it takes for the blinking socket the screen draws around an empty
     * marker — the marker grown by four on every side — to clear the bar rather than sit on it.
     */
    private static final int CHIP_FLAG_GAP = FLAG_GAP + 4;

    /**
     * Room a plate keeps under itself for the team marker's <em>default</em> spot when the editor has to
     * be told a number. The anchor derives it from the marker's own height rather than using this; it is
     * the floor, for a marker scaled right down.
     */
    private static final int PLATE_FLAG_ROW = 15;

    /** {@code {dx, dy, scale}} per {@link Element}, indexed by {@link Element#ordinal()}. */
    private final float[][] values = new float[Element.values().length][3];

    /** How many other seats the last {@link #apply} saw; drives the animated rows. */
    private int otherCount;

    public AvalonLayout() {
        resetAll();
        load();
    }

    // ------------------------------------------------------------------ values ---

    /**
     * The arrangement the mod ships with, per element.
     *
     * <p>Not a neutral grid: this is a real table, tuned in the F9 editor and then written down here, so a
     * fresh install — and every player whose saved file is dropped by a {@code VERSION} bump — lands on the
     * layout the table was composed for rather than on the anchors' bare positions. The pile, the board and
     * the status rows are the parts a player would otherwise have to nudge back into place by hand on their
     * first launch.
     */
    private static float[] defaults(Element element) {
        float[] value = {0f, 0f, defaultScale(element)};
        switch (element) {
            // The shipped arrangement is the user's own tuned table, written back from their F9 save.
            case PILE -> {
                value[0] = 2.4102569f;
                value[1] = 32.65806f;
            }
            case CONFIRM -> {
                value[0] = -0.66666627f;
                value[1] = -1.666666f;
            }
            case TASK_CARD -> value[2] = 1.2999998f;
            case STATUS_TITLE -> {
                value[0] = -0.6666107f;
                value[1] = 84.33329f;
            }
            case STATUS_TURN -> {
                value[0] = 5.0001435f;
                value[1] = 32.000004f;
            }
            case STATUS_TALLY -> {
                value[0] = 1.6668044f;
                value[1] = -5.333328f;
            }
            case STATUS_HINT -> {
                value[0] = -456.38416f;
                value[1] = -43.000008f;
            }
            case STATUS_REJECTS -> {
                value[0] = -3.333335f;
                value[1] = -15.333335f;
            }
            case SELF_PLATE -> {
                value[0] = -0.6666652f;
                value[1] = 3.9999979f;
            }
            case SELF_FLAG -> {
                value[0] = 125.83745f;
                value[1] = -17.792894f;
            }
            case SELF_CROWN -> {
                value[0] = -34.333366f;
                value[1] = 11.666672f;
            }
            case PLATE_8 -> {
                value[0] = 1f;
                value[1] = 3.6666687f;
            }
            case CROWN_1 -> {
                value[0] = 1.0000002f;
                value[1] = -4.666668f;
            }
            case CROWN_2 -> {
                value[0] = -12.666665f;
                value[1] = -4.9999995f;
            }
            case CROWN_3 -> {
                value[0] = -12.666666f;
                value[1] = -4.3333335f;
            }
            case CROWN_4 -> {
                value[0] = -12.666661f;
                value[1] = -4.0000014f;
            }
            case CROWN_5 -> {
                value[0] = -12.999997f;
                value[1] = -4.333333f;
            }
            case CROWN_6 -> {
                value[0] = -12.333331f;
                value[1] = -5.0f;
            }
            case CROWN_7 -> {
                value[0] = -13.666663f;
                value[1] = -4.3333325f;
            }
            case CROWN_8 -> {
                value[0] = -13.333334f;
                value[1] = -4.000001f;
            }
            case CROWN_9 -> {
                value[0] = -12.66667f;
                value[1] = -4.0000005f;
            }
            case FLAG_1 -> {
                value[0] = 8.000001f;
                value[1] = -3.6666663f;
            }
            case FLAG_2 -> {
                value[0] = -0.6666667f;
                value[1] = -3.6666665f;
            }
            case FLAG_3 -> {
                value[0] = 4.3333335f;
                value[1] = -3.6666665f;
            }
            case FLAG_4 -> {
                value[0] = 5.0000005f;
                value[1] = -3.6666665f;
            }
            case FLAG_5 -> {
                value[0] = 4.333333f;
                value[1] = -3.3333333f;
            }
            case FLAG_6 -> {
                value[0] = 5.333334f;
                value[1] = -2.6666665f;
            }
            case FLAG_7 -> {
                value[0] = -6.000001f;
                value[1] = -3.6666665f;
            }
            case FLAG_8 -> {
                value[0] = 4.3333335f;
                value[1] = -2.9999998f;
            }
            case FLAG_9 -> {
                value[0] = 3.9999998f;
                value[1] = -3.0f;
            }
            default -> {
            }
        }
        return value;
    }

    /**
     * The scale an element starts at, before the F9 editor has touched it.
     *
     * <p>Markers are drawn at {@link #FLAG_DEFAULT_SCALE} rather than at their authored
     * {@link #FLAG_SIZE} because the pack ships them as a full framed card: at 13 pixels the art reads as a
     * speck next to a top-bar chip, and at 240% it reads as the badge it is meant to be.
     */
    private static float defaultScale(Element element) {
        return element == Element.SELF_FLAG || element == Element.SELF_SWORD || element.isOtherFlag()
                ? FLAG_DEFAULT_SCALE : 1f;
    }

    /** The scale a marker is authored at, as a multiple of its 13-pixel art. */
    private static final float FLAG_DEFAULT_SCALE = 2.4f;

    private float[] entry(Element element) {
        return values[element.ordinal()];
    }

    public float dx(Element element) {
        return entry(element)[0];
    }

    public float dy(Element element) {
        return entry(element)[1];
    }

    public float scale(Element element) {
        float stored = entry(element)[2];
        // Zero means "never touched", which is what a saved file writes for an element the player has only
        // moved: fall back to the element's own starting scale rather than to 1.
        return stored <= 0f ? defaultScale(element) : stored;
    }

    public void move(Element element, float ddx, float ddy) {
        float[] value = entry(element);
        value[0] += ddx;
        value[1] += ddy;
        boxesStale = true;
    }

    public void scaleBy(Element element, float amount) {
        float[] value = entry(element);
        value[2] = Mth.clamp(scale(element) + amount, MIN_SCALE, MAX_SCALE);
        boxesStale = true;
    }

    public void reset(Element element) {
        values[element.ordinal()] = defaults(element);
        boxesStale = true;
    }

    public void resetAll() {
        for (Element element : Element.values()) {
            reset(element);
        }
    }

    public int otherCount() {
        return otherCount;
    }

    // ------------------------------------------------------------------ geometry ---

    /** On-screen rectangle of an element: {@code x, y, width, height}, in the design frame. */
    /**
     * On-screen rectangle of an element: {@code x, y, width, height}, in the design frame.
     *
     * <h2>Why this is a lookup and not a calculation</h2>
     *
     * <p>A single frame asks for these boxes thirty-odd times — the bar wants one per seat, the markers
     * want theirs, the seals, the crown, the confirm button, the status rows and the editor each want
     * theirs — and several of them ask in a loop, every frame, for a table whose geometry has not moved. So
     * the whole set is worked out once per frame into {@link #boxes} and handed out by reference.
     *
     * <p>Two things make that safe. The arrays are read-only to every caller (nothing writes into the
     * result), and the set is rebuilt whenever any of its three inputs moves: the frame size, the seat
     * count, and any {@code dx}/{@code dy}/{@code scale} an F9 edit has landed on.
     *
     * <p>The dependencies inside {@link #compute} resolve through {@link #box(Element, int, int)} rather
     * than by computing afresh, which is also what makes a marker follow the plate or chip it belongs to:
     * the box it leans on is that element's <em>placed</em> box, offsets included.
     */
    public int[] bounds(Element element, int screenWidth, int screenHeight) {
        if (boxesStale || screenWidth != boxesWidth || screenHeight != boxesHeight) {
            rebuildBoxes(screenWidth, screenHeight);
        }
        return boxes[element.ordinal()];
    }

    /** Where every element sits, rebuilt lazily on the next {@link #bounds} after something moved. */
    private final int[][] boxes = new int[Element.values().length][];
    private final boolean[] boxesBuilt = new boolean[Element.values().length];
    private boolean boxesStale = true;
    private int boxesWidth = -1;
    private int boxesHeight = -1;

    private void rebuildBoxes(int screenWidth, int screenHeight) {
        boxesWidth = screenWidth;
        boxesHeight = screenHeight;
        boxesStale = false;
        Arrays.fill(boxesBuilt, false);
        for (Element element : Element.values()) {
            box(element, screenWidth, screenHeight);
        }
    }

    /** One element's placed box, computed at most once per rebuild. */
    private int[] box(Element element, int screenWidth, int screenHeight) {
        int at = element.ordinal();
        if (boxesBuilt[at]) {
            return boxes[at];
        }
        int[] box = compute(element, screenWidth, screenHeight);
        box[0] += (int) dx(element);
        box[1] += (int) dy(element);
        boxes[at] = box;
        boxesBuilt[at] = true;
        return box;
    }

    private int[] compute(Element element, int screenWidth, int screenHeight) {
        int width = Math.round(element.baseWidth * scale(element));
        int height = Math.round(element.baseHeight * scale(element));
        if (element == Element.PILE) {
            return new int[]{(screenWidth - width) / 2, PILE_Y, width, height};
        }
        if (element == Element.SELF_HAND) {
            return new int[]{(screenWidth - width) / 2, screenHeight - SELF_BOTTOM, width, height};
        }
        if (element == Element.SELF_PLATE) {
            // Lifted clear of the marker that hangs under it, which is lifted clear of the hand: the local
            // seat is the one place the marker cannot go below the hand (that is the screen edge), so the
            // plate has to make room for it instead. Measured off the marker's live height rather than a
            // constant, because a constant tuned for a 13-pixel marker overlaps the hand the moment the
            // marker is scaled -- and it ships scaled.
            int markerHeight = Math.max(PLATE_FLAG_ROW, Math.round(FLAG_SIZE * scale(Element.SELF_FLAG)));
            return new int[]{(screenWidth - width) / 2,
                    screenHeight - SELF_BOTTOM - PLATE_GAP - markerHeight - FLAG_GAP - height, width, height};
        }
        if (element == Element.SELF_FLAG) {
            // The local seat's default spot stays under the local plate: below the local hand is the
            // bottom edge of the screen, so a marker there would hang off it. The plate is already lifted
            // to leave this room, and the hand sits below the marker.
            int[] p = box(Element.SELF_PLATE, screenWidth, screenHeight);
            return new int[]{p[0] + p[2] / 2 - width / 2, p[1] + p[3] + FLAG_GAP, width, height};
        }
        if (element.isOtherFlag() || element.isOtherHand()) {
            // Both hang off the seat's chip on the top bar: the hand sits inside it at Charta's own
            // offset, and the marker hangs directly under the hand. Anchoring through the chip is what
            // makes the chip's F9 handle drag a whole seat -- hand and marker together -- rather than
            // leaving them behind.
            int index = element.otherIndex();
            Element chip = Element.otherPlate(index);
            Element hand = Element.otherHand(index);
            float chipScale = scale(chip);
            int[] chipBox = box(chip, screenWidth, screenHeight);
            int handX = chipBox[0] + Math.round(CHIP_HAND_X * chipScale);
            int handY = chipBox[1] + Math.round(CHIP_HAND_Y * chipScale);
            if (element.isOtherHand()) {
                return new int[]{handX, handY, width, height};
            }
            int handW = Math.round(hand.baseWidth * scale(hand));
            int handH = Math.round(hand.baseHeight * scale(hand));
            // Measured off the chip's bottom edge rather than the hand's, so the marker and the blinking
            // socket the screen draws around it clear the bar instead of sitting on its last rows.
            int belowChip = chipBox[1] + chipBox[3] + CHIP_FLAG_GAP;
            return new int[]{handX + (handW - width) / 2,
                    Math.max(handY + handH + FLAG_GAP, belowChip), width, height};
        }
        if (element == Element.TASK_CARD) {
            int cardWidth = Math.round(screenWidth * TASK_CARD_WIDTH_RATIO * scale(element));
            int cardHeight = Math.round(cardWidth * TASK_CARD_ASPECT);
            return new int[]{(screenWidth - cardWidth) / 2, (screenHeight - cardHeight) / 2, cardWidth, cardHeight};
        }
        if (element.isQuestSlot()) {
            // The box is a screen square fitted to the printed indicator frame: the board supplies the
            // anchor, and the element's own scale grows the square about that anchor. Level with
            // TASK_CARD, so a board move carries its five boxes along.
            int index = element.questSlotIndex();
            int[] card = box(Element.TASK_CARD, screenWidth, screenHeight);
            int side = Math.max(12, Math.round(card[3] * QUEST_SLOT_SIZE * scale(element)));
            int centreX = Math.round(card[0] + card[2] * QUEST_SLOT_X[index]);
            int top = card[1] + Math.round(card[3] * QUEST_SLOT_TOP);
            return new int[]{centreX - side / 2, top, side, side};
        }
        if (element == Element.SELF_CROWN) {
            // Just off the local plate's left, on its line.
            int[] p = box(Element.SELF_PLATE, screenWidth, screenHeight);
            return new int[]{p[0] - width + 1, p[1] + p[3] / 2 - height / 2, width, height};
        }
        if (element.isOtherCrown()) {
            // Under the seat's chip's left corner, on the same line as that seat's team marker: there is no
            // room beside a chip, because the bar is spaced to fit nine and the neighbour's hand is only a
            // few pixels away. Below is the one spot a neighbour cannot reach into.
            int index = element.otherCrownIndex();
            int[] chip = box(Element.otherPlate(index), screenWidth, screenHeight);
            int[] marker = box(Element.otherFlag(index), screenWidth, screenHeight);
            return new int[]{chip[0] + width / 2 + 2,
                    marker[1] + marker[3] / 2 - height / 2, width, height};
        }
        if (element == Element.SELF_ANGEL) {
            // Beside the local hand, bottom-aligned with it: the angel is a card at the size the fan's
            // own cards are drawn, so it reads as one more card laid down next to the hand. The local
            // seat has no painted plate to ride, and below the hand is the screen edge.
            int[] hand = box(Element.SELF_HAND, screenWidth, screenHeight);
            return new int[]{hand[0] + hand[2] + 6, hand[1] + hand[3] - height, width, height};
        }
        if (element.isOtherAngel()) {
            // On the avatar itself: the angel rides the head in the chip's left half, overlapping it so
            // it reads as pinned to the player rather than to the chip's furniture, and at about the
            // head's own size. Anchored through the chip, so an F9 drag of the chip carries it along.
            int index = element.otherAngelIndex();
            Element chip = Element.otherPlate(index);
            float chipScale = scale(chip);
            int[] chipBox = box(chip, screenWidth, screenHeight);
            return new int[]{chipBox[0] + Math.round(10 * chipScale),
                    chipBox[1] + Math.round(14 * chipScale), width, height};
        }
        if (element == Element.SELF_SWORD) {
            // The mirror of the local angel card: the other side of the hand, bottom-aligned with it. A
            // flag-sized marker (the element ships at FLAG_DEFAULT_SCALE), because it is a marker in the
            // same family as the team flags, not a card.
            int[] hand = box(Element.SELF_HAND, screenWidth, screenHeight);
            return new int[]{hand[0] - width - 6, hand[1] + hand[3] - height, width, height};
        }
        if (element == Element.SWORD_SELECT) {
            // Just left of the pile, on its centre line: the spare flags own the pile's strip, and the
            // sword is the tool that works that row rather than one of its markers.
            int[] pile = box(Element.PILE, screenWidth, screenHeight);
            return new int[]{pile[0] - width - 6, pile[1] + pile[3] / 2 - height / 2, width, height};
        }
        if (element == Element.CONFIRM) {
            // Sits on the pile's centre: the spare flags are drawn there and this replaces them.
            int[] p = box(Element.PILE, screenWidth, screenHeight);
            return new int[]{p[0] + p[2] / 2 - width / 2, p[1] + p[3] / 2 - height / 2, width, height};
        }
        if (element.isOtherPlate()) {
            return chipBounds(element.otherIndex(), otherCount, screenWidth);
        }
        int y = switch (element) {
            case STATUS_TITLE -> STATUS_TITLE_Y;
            case STATUS_TURN -> STATUS_TURN_Y;
            case STATUS_HINT -> STATUS_HINT_Y;
            case STATUS_REJECTS -> STATUS_REJECTS_Y;
            default -> STATUS_TALLY_Y;
        };
        return new int[]{(screenWidth - width) / 2, y, width, height};
    }

    /**
     * How many seats the top bar itself holds, and where the ones past that go.
     *
     * <p>Seven is what an eight-player table has across from its viewer, and that row already runs from
     * x=58 to x=582 — so the ninth and tenth seats cannot join it without either being squeezed into each
     * other or being pushed under the four corner buttons that live in the same strip. They are parked
     * under those buttons instead: flush against each edge, one row below the bar, which is where the two
     * buttons that share their corners are.
     */
    private static final int BAR_CHIPS = 7;

    /**
     * How many other seats the top bar itself holds, for the screen.
     *
     * <p>The screen draws the bar's furniture — the band, the gaps between chips — off this, and it has to
     * agree with {@link #chipBounds} about which chips are on the row: a chip parked under a corner button
     * is one row down, so it neither contributes an end to the band nor has a gap on the bar's line. Reading
     * the ends off <em>every</em> chip instead puts them at the frame's own edges (the two parked chips are
     * flush against them) and the band disappears, while the gaps — which are between neighbours and
     * therefore still written against the parked chip's x — keep darkening the left.
     */
    public static final int BAR_SEATS = BAR_CHIPS;

    /** Whether the {@code index}-th other seat sits on the top bar rather than parked under a button. */
    public boolean onBar(int index) {
        return index < BAR_CHIPS;
    }

    /**
     * Box of the {@code index}-th other seat's chip: {@code x, y, width, height}.
     *
     * <p>The first {@link #BAR_CHIPS} are laid out across the frame. The spacing is Charta's — a chip is
     * {@link #CHIP_W} wide and the gap to the next is a tenth of that, exactly the {@code totalWidth}/
     * {@code totalWidth / 10f} pair {@code GameScreen.renderTopBar} and
     * {@code AbstractCardMenu.addTopPreview} both step by — and the row is centred, which is what the
     * audience of Charta's own games sees.
     *
     * <h2>The one adaptation</h2>
     *
     * <p>Nine chips at that spacing are 683 pixels and this frame is 640, so the step is clamped to fit
     * when it has to be. Charta's own games seat two to four, so its arithmetic never had to; a table of
     * ten does. The clamp reads the <em>bar's</em> seven, not the table's total, which is what keeps the
     * first seven in exactly the same places whether the table seats eight, nine or ten.
     */
    private int[] chipBounds(int index, int count, int screenWidth) {
        Element chip = Element.otherPlate(index);
        float chipScale = scale(chip);
        int width = Math.round(CHIP_W * chipScale);
        int height = Math.round(CHIP_H * chipScale);
        if (index >= BAR_CHIPS) {
            // Past the bar: flush left under the options button, or flush right under the deck one.
            int x = index == BAR_CHIPS ? 0 : screenWidth - width;
            return new int[]{x, height, width, height};
        }
        int chips = Math.min(Math.max(1, count), BAR_CHIPS);
        float step = CHIP_W * 1.1f;
        if (chips > 1) {
            step = Math.min(step, (screenWidth - CHIP_W) / (chips - 1f));
        }
        step *= chipScale;
        float total = width + step * (chips - 1);
        int x = Math.round(screenWidth / 2f - total / 2f + index * step);
        return new int[]{x, 0, width, height};
    }

    // ------------------------------------------------------------------ applying ---

    /**
     * Writes the current layout into the menu's card slots.
     *
     * <p>Slot order is positional and owned by {@link AvalonMenu}: pile, then the seats in the viewer's
     * own ring order with their own chair first. The pile and the local hand are the two fixed
     * elements; every later seat is routed to one of the {@code HAND_n} anchors.
     */
    public void apply(AvalonMenu menu, int screenWidth, int screenHeight) {
        int[] rings = menu.seats();
        if (menu.cardSlots.size() < 1 + rings.length) {
            return;
        }
        otherCount = Math.max(0, rings.length - 1);
        boxesStale = true;
        setSlot(menu, 0, Element.PILE, screenWidth, screenHeight);
        for (int i = 0; i < rings.length; i++) {
            Element element = i == 0 ? Element.SELF_HAND : Element.otherHand(i - 1);
            setSlot(menu, menu.handSlotIndex(i), element, screenWidth, screenHeight);
        }
    }

    /**
     * The one place that knows how {@code GameScreen} stores a slot per type, undone so the slot's
     * <em>drawn</em> top-left lands on {@code box[0], box[1]}:
     * <ul>
     *   <li>{@code PREVIEW} is read as an absolute screen y and drawn at {@code y}.</li>
     *   <li>{@code HORIZONTAL} is a bottom offset, drawn at {@code y + screenHeight - declaredHeight};
     *       both draw and hover use the declared height, so subtracting the same expression keeps the
     *       two coincident — a click lands on the card that was painted.</li>
     * </ul>
     * {@code x} is drawn at {@code leftPos + x} and {@code leftPos} is zero, so it is stored as-is.
     */
    private void setSlot(AvalonMenu menu, int index, Element element, int screenWidth, int screenHeight) {
        CardSlot<?, ?> slot = menu.cardSlots.get(index);
        CardSlotAccess access = (CardSlotAccess) slot;
        int[] box = bounds(element, screenWidth, screenHeight);

        access.avalon$setX(box[0] - menu.panelLeft(screenWidth));
        if (slot.getType() == CardSlot.Type.HORIZONTAL) {
            access.avalon$setY(box[1] - screenHeight + box[3]);
        } else {
            access.avalon$setY(box[1] - menu.panelTop(screenHeight));
        }
        access.avalon$setSize(box[2], box[3]);
    }

    // ------------------------------------------------------------------ persistence ---

    private void load() {
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException ignored) {
            return;
        }
        String version = properties.getProperty("version");
        if (!VERSION.equals(version) && !LEGACY_VERSIONS.contains(version)) {
            return;
        }
        boolean legacy = !VERSION.equals(version);
        boolean barMoved = LEGACY_BAR_VERSIONS.contains(version);
        for (Element element : Element.values()) {
            // An older file's deltas for these were measured against anchors that a later version moved;
            // see VERSION. Left at their defaults rather than loaded, which is what the skip does. The
            // crowns moved once for every old file, so every old file's crown offsets are meaningless; the
            // other seats' families moved twice, and only files from before the bar did.
            if (legacy && (element.isOtherCrown() || element == Element.SELF_CROWN
                    || (barMoved && element.isOtherSeat()))) {
                continue;
            }
            float[] value = entry(element);
            value[0] = parse(properties, element.key + ".dx", 0f);
            value[1] = parse(properties, element.key + ".dy", 0f);
            value[2] = parse(properties, element.key + ".scale", 0f);
        }
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        for (Element element : Element.values()) {
            float[] value = entry(element);
            properties.setProperty(element.key + ".dx", Float.toString(value[0]));
            properties.setProperty(element.key + ".dy", Float.toString(value[1]));
            properties.setProperty(element.key + ".scale", Float.toString(value[2]));
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                properties.store(out, "TrustNoOne UI layout - edited in game with F9");
            }
        } catch (IOException ignored) {
            // A read-only instance still plays fine, it just forgets the layout.
        }
    }

    private static float parse(Properties properties, String key, float fallback) {
        try {
            return Float.parseFloat(properties.getProperty(key, Float.toString(fallback)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

}
