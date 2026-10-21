package dev.myavalon.game;

import dev.myavalon.client.AvalonFrame;
import dev.myavalon.client.AvalonLayout;
import dev.myavalon.client.GuiTextureFilters;
import dev.myavalon.client.TableDim;
import dev.myavalon.client.UiBlend;
import dev.myavalon.network.AvalonActionPayload;
import dev.lucaargolo.charta.common.sound.ModSounds;
import dev.lucaargolo.charta.client.ChartaModClient;
import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.card.Card;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.utils.CardPlayerHead;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

/**
 * The Avalon table.
 *
 * <h2>What is Charta's and what is ours</h2>
 *
 * <p>Extending {@link GameScreen} is what gives an addon the whole Charta feel: animated card widgets,
 * drag and drop with the hover lift, the carried-card follow, the audio, the history and options panels
 * and the blurred backdrop are all inherited. What this class writes is the table itself: a fan per
 * seat, a name plate that carries what that seat is known to be, the shared pile in the middle, and the
 * four status lines that narrate the phase.
 *
 * <h2>The hidden information is drawn, never shown</h2>
 *
 * <p>Nothing secret is ever fetched from {@code game.roleAt} here, because on a real client the game
 * object is a locally rebuilt stand-in with no roles in it: it is the <em>menu mirror</em> that carries
 * the viewer's own role, the seats whose faction they know, and the revealed roles at the end. So the
 * role reads go through {@link AvalonMenu#getLocalRole} and {@link AvalonMenu#getKnownMask}, which the
 * server fills per viewer — two players at the same table get two different pictures from one menu.
 *
 * <h2>How the phases are interacted with</h2>
 *
 * <p>A vote and a quest are <em>card</em> turns: the player picks a card out of their own fan and drops
 * it on the pile, which is Charta's normal click-to-carry, click-to-drop path, so no custom hit-testing
 * is involved. They differ in who may act: the whole table votes at the same moment, so every seat still
 * to answer may drop a card, while a quest card is played around the team in turn — see
 * {@link AvalonMenu#isLocalPending}, which is what every "may I click now?" test on this screen asks.
 * A proposal is an <em>action</em> turn where nothing leaves the hand, and it is played with the team
 * flags: a flag is a card too, so a click lifts one (off the pile, or out of a seat's socket), it rides
 * the pointer, and the click that puts it down on a seat is what picks that seat. A click on an empty
 * socket deliberately does nothing -- being on the team is something a flag has to be carried over for.
 * Once every flag is placed a confirm button appears in the middle; the assassination is a click on the
 * seat to stab, then the same button. Both go out as one {@link AvalonActionPayload} and the server
 * re-validates the lot.
 *
 * <h2>One design frame, scaled onto the window</h2>
 *
 * <p>Every coordinate in {@link AvalonLayout} is an absolute pixel count measured on a 640x360 screen,
 * which is exactly what a 1920x1080 window yields at GUI scale 3. Rather than sprinkle window-size
 * arithmetic through the drawing, this screen <em>is</em> 640x360: {@link #init} re-points
 * {@code width}/{@code height} at the design frame and {@link #render} wraps the entire vanilla pass in
 * a uniform scale and a centring translate from {@link AvalonFrame}. Shrinking the window, switching to
 * fullscreen or changing the GUI scale therefore only ever changes one number, and the table can no
 * longer walk off the edge of a short window.
 */
public class AvalonScreen extends GameScreen<AvalonGame, AvalonMenu> {

    // ---- palette -------------------------------------------------------------------------------

    private static final int FELT = 0x2E1B4A2E;
    private static final int LABEL = 0xFFFFFFFF;
    private static final int DIM = 0xFF9A9A9A;
    private static final int ACTIVE = 0xFFFFE97F;
    private static final int GOOD_COLOR = 0xFF7BD98C;
    private static final int EVIL_COLOR = 0xFFE2716F;
    private static final int OUTLINE = 0x60FFFFFF;
    private static final int CELL_BG = 0x50000000;
    private static final int CELL_HOVER = 0x60FFE97F;
    private static final int SHADOW = 0xB0000000;

    /** Drawn size of one card on the pile, and how far apart two of them sit at most. */
    private static final int PILE_CARD_W = 34;
    private static final int PILE_CARD_H = 47;
    private static final int PILE_MAX_STEP = 24;
    /**
     * Gap a quest row leaves between two cards, on top of the card's own width. A quest is the one row the
     * table has to be able to read card by card, so its cards are spread apart instead of overlapped; the
     * cap of five in a row (the largest team) fits inside the pile's box with room to spare.
     */
    private static final int PILE_QUEST_GAP = 8;

    private static final int STATUS_TEXT_H = 9;

    /**
     * The confirm button's box lives in {@code AvalonLayout.CONFIRM} so F9 can move it; its default is the
     * pile's own centre, where the last spare flag was.
     */

    // ---- props drawn over the table (crown, team flags, rejection nugget) -----------------------

    /** The leader's crown, the team's draggable flag and the rejection marker. */
    private static final ResourceLocation CROWN = gui("crown");
    private static final ResourceLocation TEAM_FLAG = gui("team_flag");
    private static final ResourceLocation NUGGET = gui("mission_marker");

    /** The pack's 325x325 team marker, drawn at a square 13x13 design footprint. */
    private static final int FLAG_W = 13;
    private static final int FLAG_H = 13;

    /**
     * Spare flags on the pile are drawn three times their seat size, so the thing the leader drags is the
     * thing the eye lands on. They shrink back to the slot's own size the moment they are placed.
     */
    private static final float FREE_FLAG_SCALE = 3f;

    /**
     * How far a seat's flag <em>slot</em> is grown on every side, in frame pixels, for the blink outline.
     * The slot itself is the 13x13 flag box; the outline is a touch larger so it reads as a socket.
     */
    private static final int FLAG_SLOT_INFLATE = 4;

    /**
     * How far the flag <em>drop</em> zone extends past the slot on every side, in design pixels. A fixed
     * margin rather than a fraction of the slot, so a layout that scales the flags up (the F9 editor can)
     * does not grow the drop zones into each other -- two seats' zones overlapping would make the drop
     * land on whichever seat came first in the ring rather than on the one it was aimed at.
     */
    private static final int FLAG_DROP_MARGIN = 13;
    /**
     * The rejection marker's art is a square 325x325 card as well, and 26 design pixels is the size of
     * one notch burned into the board -- so the marker card lands on a notch and covers its numeral.
     */
    private static final int NUGGET_W = 39;
    private static final int NUGGET_H = 39;

    /**
     * Vertical pad of a seat's selection zone past their plate. The flag is its own element now, so the
     * zone hugs the plate and no longer reaches down a whole flag row.
     */
    private static final int ZONE_PAD_BOTTOM = 4;

    /**
     * The pack draws the crown as a square 325x325 card now -- the same framed piece the team flag ships
     * in -- so the drawn box is square and the whole file is what is drawn, frame included. 22 is picked
     * so the crown <em>in</em> the frame (a little over two thirds of the art's width) still comes out
     * around the 15 design pixels the leader's marker has always been.
     */
    private static final int CROWN_SRC = 325;
    private static final float CROWN_W = 22f;
    private static final float CROWN_H = 22f;

    /**
     * How far a seat's selection zone pokes out past their name plate. See the props section for the
     * bottom margin, which is the flag's own row.
     */
    private static final int ZONE_PAD_X = 5;
    private static final int ZONE_PAD_TOP = 4;

    /** Milliseconds the crown spends flying to the next leader after a rejection. */
    private static final long CROWN_FLIGHT_MS = 900;
    /** Milliseconds a rejection nugget spends sliding from one counter notch to the next. */
    private static final long NUGGET_FLIGHT_MS = 620;

    private static ResourceLocation gui(String name) {
        return ResourceLocation.fromNamespaceAndPath("myavalon", "textures/gui/" + name + ".png");
    }

    /** The five rejection notches burned into the quest board's bottom-left, as art fractions. */
    private static final float[] REJECT_NOTCH_X = {0.09554f, 0.23688f, 0.37996f, 0.52426f, 0.66064f};
    private static final float REJECT_NOTCH_Y = 0.83686f;

    // ---- drag-to-pick state ---------------------------------------------------------------------

    /**
     * Whether a team flag is in hand. Mimics Charta's own cards: the first click lifts a flag (off the
     * pile, or out of a seat's socket) so it rides the pointer, and the next click sets it down -- on a
     * free seat to pick them, anywhere else to put it back.
     */
    private boolean carryingFlag;
    private double dragX;
    private double dragY;

    /**
     * The team the local leader is building, as the local seat last left it, or {@code -1} when the
     * server's own copy is the truth.
     *
     * <h2>The ghost flag this exists for</h2>
     *
     * <p>Every flag click is a packet: the click sends {@code TOGGLE_BASE + seat} and the mask it changed
     * comes back a round trip later. Drawing straight from the mirror therefore draws a flag row that is
     * one click behind — put a flag down on a seat and, for the 50-odd milliseconds before the server
     * answers, the row still counts the seat as unpicked and paints an extra flag on the pile; take one
     * back out and the row briefly shows a hole. That is the "a flag flashes on the pile" bug, and the
     * same lag is what makes the count in the hint line and the confirm button flicker with it.
     *
     * <p>So the click is applied here first and the mirror takes over the moment it agrees. The override
     * is dropped the frame it does, and also the moment the phase leaves {@code PROPOSE} — a click the
     * server refused (someone else's turn, a stale screen) must not be able to wedge the row forever.
     */
    private int localPending = -1;

    /**
     * The quest the local leader last clicked, for the same reason {@link #localPending} exists: the
     * server's answer is a round trip away, and a highlight that arrives a frame late reads as a missed
     * click. Cleared the moment the mirror agrees, or when the proposal ends.
     */
    private int localQuest = -1;
    private long localQuestAt;

    // ---- the flag under the pointer -------------------------------------------------------------

    /** Seat owning the hovered flag, or {@code -1} when it is a spare one on the pile or nothing at all. */
    private int hoverFlagSeat = -1;
    /** Ring index of the hovered placed flag, or {@code -1} for a spare. Drives where it is redrawn. */
    private int hoverFlagRing = -1;
    /** Index of the hovered spare flag in the pile's row, or {@code -1} for a placed one. */
    private int hoverFlagSpare = -1;

    /**
     * The hover animation, in the shape {@code AbstractCardWidget} keeps it: the widget writes the
     * <em>targets</em> on its 20 Hz tick and the renderer lerps last tick's value into this one, which is
     * what lets the tilt trail the pointer instead of snapping to it. Same three quantities Charta's own
     * cards use -- a negative inset (the card steps toward the camera) and up to 25 degrees of tilt
     * toward wherever on the card the pointer is.
     */
    private float flagInset;
    private float flagXRot;
    private float flagYRot;
    private float lastFlagInset;
    private float lastFlagXRot;
    private float lastFlagYRot;
    /** Wall clock of the last hover update, and the anchor the render lerp is measured from. */
    private long flagHoverTickMs;

    // ---- quest reveal animation -----------------------------------------------------------------

    /** When the shuffle/gather/flip reveal started, or {@code -1} while no quest is being revealed. */
    private long revealStartMs = -1L;
    /**
     * Cards trade places with each other, bunch up, hold, spread back out in their new order, then turn
     * over. Two of those beats are what make it read as a shuffle rather than a bounce: the cut at the
     * front, without which nothing is ever actually rearranged, and the hold, without which the cards
     * arrive in the middle and leave again in the same motion so the gathering is never the event.
     */
    private static final long REVEAL_SWAP_MS = 900;
    /** Cut rounds. Three, and each round moves <em>every</em> card, so every card changes place three times. */
    private static final int REVEAL_SWAPS = 3;
    private static final long REVEAL_GATHER_MS = 440;
    private static final long REVEAL_HOLD_MS = 620;
    private static final long REVEAL_SPREAD_MS = 340;
    private static final long REVEAL_FLIP_MS = 380;
    /** When each leg starts, in milliseconds from the reveal's start. */
    private static final long REVEAL_GATHER_AT = REVEAL_SWAP_MS;
    private static final long REVEAL_HOLD_AT = REVEAL_GATHER_AT + REVEAL_GATHER_MS;
    private static final long REVEAL_SPREAD_AT = REVEAL_HOLD_AT + REVEAL_HOLD_MS;
    private static final long REVEAL_FLIP_AT = REVEAL_SPREAD_AT + REVEAL_SPREAD_MS;
    private static final long REVEAL_MS = REVEAL_FLIP_AT + REVEAL_FLIP_MS;

    // ---- identity reveal (post-deal confirmation) -----------------------------------------------

    /**
     * When the "look at your role" overlay first became visible, or {@code -1} while it is not playing.
     *
     * <h2>The act</h2>
     *
     * <p>The server hands a seat its card the moment the deal settles, so the very first frame of
     * {@code PROPOSE} with a local role is the moment the player can look at it. The overlay plays
     * exactly once per game: a soft blur washes the table, an enlarged copy of the player's own role
     * card fades in (and lifts on hover, the same way a card in a hand does), fades out, then a line of
     * text in the faction colour fades in and out, and the wash lifts again. The whole thing is a
     * cosmetic curtain — the proposal underneath is already live, so the player can act the moment they
     * close the eye.
     */
    private long identityStartMs = -1L;

    /**
     * The match whose curtain has already been armed (and therefore must not be armed again).
     *
     * <p>Keyed on the server's deal id and static, in that order of importance. Static because the screen
     * itself is rebuilt every time the player opens the inventory and comes back, so an instance field
     * would replay the whole act on every E press; keyed on a deal id rather than on the menu because the
     * menu is rebuilt with the screen, and so is the client's copy of the game — there is no object that
     * survives the round trip to hang the flag on. See {@code AvalonGame.SYNC_MATCH}.
     *
     * <p>Written when the act <em>starts</em>, not when it ends. Writing it at the end was the bug where
     * closing the table during the nine-second curtain and reopening it played the whole curtain again:
     * the match had not been marked yet. A screen that reopens has {@code identityStartMs == -1}, which
     * is how this flag can mean "armed" without stopping the screen that is still playing it.
     */
    private static int identityShownMatch = -1;

    /** The deal this screen's act belongs to, so a fresh one re-arms instead of reading a stale clock. */
    private int identityMatch = -1;

    // ---- the assassination act -------------------------------------------------------------------

    /**
     * The beats of the closing act, in milliseconds: the wash and the two lines arrive, the lines are left
     * alone to be read, the lines and the card swap places, the card and the winner hold, and it all lifts.
     *
     * <p>Five numbers rather than the identity curtain's three because this act has two halves: what
     * happened, then what it means. The middle one is the hand-over between them and both ends are on it at
     * once — that overlap is the whole transition, so the two halves read as one movement.
     */
    private static final long AS_IN_MS = 1000;
    private static final long AS_LINES_MS = 2600;
    private static final long AS_SWAP_MS = 1100;
    private static final long AS_CARD_MS = 3600;
    private static final long AS_OUT_MS = 1100;
    private static final long AS_TOTAL_MS =
            AS_IN_MS + AS_LINES_MS + AS_SWAP_MS + AS_CARD_MS + AS_OUT_MS;

    /** How dark the wash goes at full strength: enough to read a line of text over, not quite opaque. */
    private static final int AS_WASH_ALPHA = 0xE8;

    /** When the act started, and the match it belongs to ({@code -1} until one ends by the knife). */
    private long assassinStartMs = -1L;
    private int assassinMatch = -1;
    /** The match whose assassination has already been armed, static for the deal-id reason above. */
    private static int assassinShownMatch = -1;

    // ---- evil's sabotage ending ------------------------------------------------------------------

    /**
     * The beats of the red announcement, in milliseconds: the screen takes on the colour, holds the winner's
     * name, and gives it back — and the table closes itself on the last frame.
     */
    private static final long EV_IN_MS = 1300;
    private static final long EV_HOLD_MS = 3400;
    private static final long EV_OUT_MS = 900;
    private static final long EV_TOTAL_MS = EV_IN_MS + EV_HOLD_MS + EV_OUT_MS;

    /** How red the wash goes at full strength: a visible table behind it, and darkness enough for the name. */
    private static final int EV_WASH_ALPHA = 0xE0;

    private long evilStartMs = -1L;
    private int evilMatch = -1;
    /** The match whose red ending has already been armed, static for the deal-id reason above. */
    private static int evilShownMatch = -1;

    /**
     * The act's beats, in milliseconds: fade up, hold, fade away. One curve drives all of it — the
     * darkening, the frost, the card and the line arrive together and leave together — so there are three
     * numbers and not seven.
     */
    private static final long ID_IN_MS = 3000;
    private static final long ID_HOLD_MS = 3000;
    private static final long ID_OUT_MS = 3000;
    private static final long ID_TOTAL_MS = ID_IN_MS + ID_HOLD_MS + ID_OUT_MS;
    /** The enlarged role card's footprint, in frame pixels; the card's own 1.5:1 ratio gives the height. */
    private static final int ID_CARD_W = 110;
    private static final int ID_CARD_H = 165;
    /** How far above the middle the card sits, in frame pixels, so the line below it has room. */
    private static final int ID_CARD_LIFT = 14;

    // ---- the frost the act lays over the table --------------------------------------------------

    /** The vanilla box-blur post chain, driven by hand so its radius can be animated. */
    @Nullable
    private PostChain curtainBlur;
    /** Set when the chain could not be built, so the attempt is not repeated every frame. */
    private boolean curtainBlurBroken;

    private static final ResourceLocation BLUR_CHAIN =
            ResourceLocation.withDefaultNamespace("shaders/post/blur.json");

    /**
     * Blur radius at full frost, in framebuffer pixels. Matched to the game's own "menu background
     * blurriness" setting with a floor, so the effect respects a player's taste but can never be set so low
     * that the table behind the card still reads as sharp.
     */
    private static final float ID_BLUR_RADIUS_FLOOR = 3f;

    // ---- animation state ------------------------------------------------------------------------

    /** Seat the crown is flying from / to, and when that flight started ({@code -1} when idle). */
    private int crownFrom = -1;
    private int crownTo = -1;
    private long crownFlightStart;
    /** Leader as of the previous frame, so a change is a rising edge to animate. */
    private int lastLeader = -1;

    /** Rejection count as of the previous frame; a change drives the nugget. */
    private int lastRejects;
    /** The match the crown and nugget baselines belong to; see {@link #updateAnimations}. */
    private int animationsMatch = -1;
    /** Notch the nugget is travelling from / to ({@code -1} means "off the board"). */
    private int nuggetFrom = -1;
    private int nuggetTo = -1;
    private long nuggetFlightStart;
    private boolean nuggetFlying;

    /** The whole screen layout, shared by every screen instance and editable with F9. */
    private static final AvalonLayout LAYOUT = new AvalonLayout();

    private boolean editing;
    @Nullable
    private AvalonLayout.Element selected;
    @Nullable
    private AvalonLayout.Element hovered;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    public AvalonScreen(AvalonMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        // The panel *is* the frame, so leftPos/topPos collapse to zero and a slot's stored coordinate
        // is already a screen coordinate. See AvalonMenu#panelLeft.
        this.imageWidth = AvalonFrame.WIDTH;
        this.imageHeight = AvalonFrame.HEIGHT;
        Arrays.fill(sealStartMs, -1L);
    }

    // ---------------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        // width/height arrive here as the real GUI-scaled window size; from this point on this screen
        // paints inside the 640x360 design frame, so they are re-pointed at it -- and done *before*
        // super.init(), because AbstractContainerScreen derives leftPos/topPos from them, GameScreen
        // places its corner buttons off them, and the chat screen is initialised with them.
        this.width = AvalonFrame.WIDTH;
        this.height = AvalonFrame.HEIGHT;
        // GameScreen rebuilds its slot widgets in init(), and a widget caches the slot position it was
        // built with, so publish the layout first.
        applyLayout();
        super.init();
        replaceHelpButton();
        moveCornerButtons();
    }

    /**
     * Points the rules button at the illustrated tutorial instead of the text page.
     *
     * <p>{@code GameScreen.init} builds that button itself, keeps no reference to it and exposes no
     * setter for its press action, so it is found by the one bound Charta gives it and swapped for a
     * button that opens {@link AvalonTutorialScreen}. The original text page is still one press away,
     * from the tutorial's {@code 文字规则} button.
     */
    private void replaceHelpButton() {
        for (GuiEventListener child : new ArrayList<>(children())) {
            if (child instanceof Button button && button.getX() == 5 && button.getY() == 35
                    && button.getWidth() == 20) {
                removeWidget(button);
                break;
            }
        }
        Component icon = Component.literal("\ue90e").withStyle(ChartaMod.SYMBOLS);
        addRenderableWidget(new Button.Builder(icon, b -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new AvalonTutorialScreen(this));
                    }
                })
                .bounds(5, 35, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("message.charta.how_to_play")))
                .build());
    }

    /**
     * Slides Charta's four corner buttons up into the strip the top bar would have used.
     *
     * <p>They are placed at y=35 by {@code GameScreen}, which is exactly where the first row of other
     * seats now sits -- this screen drops the top bar (see {@link #renderTopBar}) and hands the top 28
     * pixels to the buttons instead. The buttons are identified by their left edge rather than by
     * identity because {@code GameScreen} keeps only one of them in a field.
     */
    private void moveCornerButtons() {
        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) {
                buttons.add(button);
            }
        }
        buttons.sort(Comparator.comparingInt(Button::getX));
        for (int i = 0; i < buttons.size(); i++) {
            int x = switch (i) {
                case 0 -> 5;
                case 1 -> 27;
                default -> width - 25 - (buttons.size() - 1 - i) * 22;
            };
            buttons.get(i).setPosition(x, 4);
        }
    }

    /** Pushes the layout into the menu's card slots. Cheap, so it runs on every resize and edit. */
    private void applyLayout() {
        LAYOUT.apply(menu, width, height);
    }

    /**
     * Frames drawn since the screen opened.
     *
     * <p>What a pass several callers want uses to tell "already done this frame" from "do it again" —
     * currently only the plate measurement, which the top bar needs before {@code renderBg} does. Cheaper
     * and exact next to a wall-clock compare, which can call two frames one millisecond.
     */
    private long frameStamp;

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * The table, drawn over the world.
     *
     * <p>The world behind is frosted by {@link #renderBlurredBackground} and then washed by the scrim on
     * the frames where the table is busy. The scrim is painted <em>outside</em> the frame pose so it covers
     * the letterbox margins too, and the mouse is converted on the way in, so from {@code super.render} down
     * everything is in frame space. The chat is painted after the pose is popped, in window coordinates,
     * because vanilla draws its own copy there and the two must agree; see {@code GameScreenChatFrame}.
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        AvalonFrame frame = AvalonFrame.current();
        frameStamp++;

        updateAnimations();

        // Timestamp the start of the deal so the flying-card animation can be timed off it. The match is
        // remembered statically because the screen is thrown away whenever the table is closed: without
        // that, reopening mid-deal started the whole flight again. A reopened screen is put past the end
        // of the animation instead, which draws no thrown cards and leaves the already-dealt hands alone.
        if (dealing()) {
            // Match id 0 is the unsynced mirror. Arming on it would remember the wrong id and restart
            // the deal once the real one arrived.
            int match = menu.getMatchId();
            if (match > 0 && dealStartMs < 0) {
                if (dealShownMatch == match) {
                    dealStartMs = Util.getMillis() - 1_000_000L;
                } else {
                    dealShownMatch = match;
                    dealStartMs = Util.getMillis();
                }
            }
        } else {
            dealStartMs = -1L;
        }

        int frameMouseX = (int) Math.floor(frame.toFrameX(mouseX));
        int frameMouseY = (int) Math.floor(frame.toFrameY(mouseY));

        // Dimming the finished table, by multiplication rather than by a wash over the top.
        //
        // A wash cannot be relied on here. Charta's cards do not go through {@code GuiGraphics}' batch --
        // they build their quad on the shared {@code Tesselator} and upload it on the spot -- so a
        // translucent rectangle written after the hands in code could still land under them, and "the table
        // dims and the hand rows stay bright" survived two attempts at moving that call. Multiplying has no
        // order to get wrong: the pictures take it through {@link TableDim} (see {@code CardPictureDim}) and
        // the batched half -- felt, board, plates, chips, every line of text -- through {@code setColor},
        // which is what a batch actually reads.
        float dim = isBusy() ? TableDim.BUSY : 1f;
        TableDim.value = dim;
        guiGraphics.setColor(dim, dim, dim, 1f);

        frame.push(guiGraphics);
        super.render(guiGraphics, frameMouseX, frameMouseY, partialTick);
        // The revealed cards are laid down under the dim as well: the finished board dims as one piece.
        renderRevealedRoles(guiGraphics);
        frame.pop(guiGraphics);

        // Full brightness for everything from here on: the closing acts draw their own washes and are the
        // one bright thing this screen still puts on a finished table, and the chat never dims.
        guiGraphics.setColor(1f, 1f, 1f, 1f);
        TableDim.value = 1f;

        // The post-deal identity curtain sits over the whole frame, including the letterbox margins. It
        // frosts the finished table first, so the hands and the pile behind it stop reading as sharp.
        // The frost is a post chain and therefore reads the framebuffer, so this frame's batched work has
        // to be landed before it runs -- see {@link #wash}.
        guiGraphics.flush();
        renderIdentityBlur(partialTick);
        renderIdentityReveal(guiGraphics, partialTick);
        // The closing act, on the same terms: over the whole frame, in window coordinates. It can never
        // coincide with the deal's curtain, since one is armed by the deal and the other by the end.
        renderAssassination(guiGraphics, partialTick);
        renderEvilEnding(guiGraphics, partialTick);

        renderSummary(guiGraphics);

        renderChat(guiGraphics, mouseX, mouseY);
    }

    /**
     * A translucent rectangle over everything drawn so far, drawn the way the cards are.
     *
     * <h2>Why not {@code guiGraphics.fill}</h2>
     *
     * <p>Because a card does not go through {@code GuiGraphics}' batch: {@code ChartaGuiGraphics.innerBlit}
     * builds its quad on the shared {@code Tesselator} and uploads it there and then. A {@code fill} only
     * <em>queues</em> its quad, so the two arrive on the framebuffer in the batch's order rather than in
     * call order — and the batch belongs to whoever flushes it next. That is what "the table dims and the
     * hand rows stay bright" was: write a wash before a card and it can land under it, write it after and
     * the batch may still be sitting behind the cards it was meant to cover. Moving the call around could
     * not fix that, which is why doing it twice did nothing.
     *
     * <p>So: land the queue first ({@link GuiGraphics#flush}), then draw the wash on the same road the
     * cards take. Everything already drawn — table, hands, revealed cards — is under it, and everything
     * drawn afterwards (the acts' own cards and lines, the chat) is over it. Deterministic either way, and
     * the alpha composites exactly as a {@code fill}'s would.
     */
    private static void wash(GuiGraphics guiGraphics, int x1, int y1, int x2, int y2, int argb) {
        guiGraphics.flush();
        float alpha = (argb >>> 24) / 255f;
        float red = ((argb >> 16) & 0xFF) / 255f;
        float green = ((argb >> 8) & 0xFF) / 255f;
        float blue = (argb & 0xFF) / 255f;
        UiBlend.on();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f pose = guiGraphics.pose().last().pose();
        BufferBuilder buffer =
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.addVertex(pose, x1, y1, 0f).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x1, y2, 0f).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x2, y2, 0f).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x2, y1, 0f).setColor(red, green, blue, alpha);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /**
     * Frosts the world behind the table.
     *
     * <p>Vanilla never reaches this on its own for a container screen: {@code GameScreen.render} calls
     * {@code renderBlurredBackground}, but Charta overrides {@code renderBackground} down to a plain
     * {@code renderTransparentBackground} and the blur call goes with it. Overriding this method is the
     * cleanest way back in, and it is the one place in the pass where the framebuffer still holds nothing
     * but the world -- {@code GameScreen.render} runs it before any scrim, felt or card is drawn.
     *
     * <p>The felt behind the table is deliberately translucent ({@code FELT}'s alpha is 0x2E), so the
     * blurred world reads through it exactly like it does behind the vanilla inventory; that, and the
     * letterbox margins on a non-16:9 window, is the whole reason to pay for the blur.
     *
     * <p>Cost, for the record: {@code minecraft:shaders/post/blur.json} is three ping-pong box passes, so
     * six full-window quads per frame. It is the single most expensive thing on this screen; the radius
     * is whatever the player set under {@code Video Settings -> Menu Background Blurriness}.
     */
    @Override
    public void renderBlurredBackground(float partialTick) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        // While the identity curtain is at strength, its own chain frosts the whole finished frame a moment
        // later -- world included -- so doing the world here as well is six full-window quads for a picture
        // that is about to be blurred again. Skipped only past half strength: below that the curtain's
        // radius is too small to stand in for this one, and dropping this would show the world snapping
        // sharp for the first frames of the act.
        if (identityCurtainUp() && identityFade() >= ID_BLUR_HANDS_OVER_AT) {
            return;
        }
        minecraft.gameRenderer.processBlurEffect(partialTick);
        minecraft.getMainRenderTarget().bindWrite(false);
    }

    /** Frost strength past which the curtain's own blur stands in for the world's. */
    private static final float ID_BLUR_HANDS_OVER_AT = 0.5f;

    // ------------------------------------------------------------------ harness readouts ---
    // Two plain getters for the dev harness's log line (see MyAvalonDebug). They are read once per
    // client tick and exist only so the harness does not have to reach into Charta's types.

    /** Whether Charta currently has a card under the cursor. */
    public boolean getHoverablePeek() {
        return this.hoverable != null;
    }

    /** Where the hovered card is, in frame coordinates. */
    public String getHoveredProbe() {
        if (this.hoverable == null) {
            return "none";
        }
        if (this.hoverable instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
            return widget.getClass().getSimpleName() + "@" + widget.getX() + "," + widget.getY()
                    + "+" + widget.getWidth() + "x" + widget.getHeight();
        }
        return this.hoverable.getClass().getSimpleName();
    }

    /**
     * Turns last frame's mirrored state into this frame's animation: a leader change launches the crown
     * across the table and a rejection change launches the nugget along the board's counter.
     *
     * <p>Both are rising edges off values the server already mirrors ({@code SYNC_LEADER} moves clockwise
     * the instant a proposal is voted down, {@code SYNC_REJECTS} ticks up with it), so the client needs no
     * packet of its own — it only has to notice the number moved.
     */
    private void updateAnimations() {
        if (menu.getPhase() != AvalonGame.Phase.PROPOSE) {
            carryingFlag = false;
            localQuest = -1;
        }
        // Every read of the team goes through pendingMask(), so asking it here is what retires the local
        // override the frame the server agrees -- see localPending.
        pendingMask();
        // First sight of a match (or a fresh screen on an old match) takes the current crown and rejection
        // counter as the baseline. Without that, a screen reopened with two rejections already on the
        // board animated the nugget in from off the board, and a rematch replayed the previous game's
        // counters before settling.
        int match = menu.getMatchId();
        if (match != animationsMatch) {
            animationsMatch = match;
            lastLeader = menu.getLeaderSeat();
            lastRejects = menu.getRejects();
            nuggetFlying = false;
        }
        int leader = menu.getLeaderSeat();
        if (lastLeader < 0) {
            lastLeader = leader;
        } else if (leader != lastLeader) {
            crownFrom = lastLeader;
            crownTo = leader;
            crownFlightStart = Util.getMillis();
            lastLeader = leader;
        }

        int rejects = menu.getRejects();
        if (rejects != lastRejects) {
            if (rejects > lastRejects) {
                // First rejection flies in off the board; each later one slides on from the notch before.
                nuggetFrom = lastRejects == 0 ? -1 : lastRejects - 1;
                nuggetTo = rejects - 1;
            } else {
                // The counter was cleared by an approved team: the nugget flies back off the board.
                nuggetFrom = lastRejects - 1;
                nuggetTo = -1;
            }
            nuggetFlightStart = Util.getMillis();
            nuggetFlying = true;
            lastRejects = rejects;
        }
    }

    /**
     * {@code true} while the assassination or a finished game wants the table dimmed.
     *
     * <p>The proposal no longer does. Its dim was there to make the flags the only lit thing on the screen,
     * but the flags already carry their own sockets and the team's plates carry the hairline, and what the
     * dim actually cost was the ability to read the board — the quest history and the rejection counter are
     * exactly what a leader is deciding against, and they were the things being greyed out.
     *
     * <p>What it drives is a multiplier over the table, not a rectangle over the top of it: see
     * {@link TableDim} and the note in {@code render} for why that distinction turned out to matter.
     */
    private boolean isBusy() {
        AvalonGame.Phase phase = menu.getPhase();
        return phase == AvalonGame.Phase.ASSASSIN
                || phase == AvalonGame.Phase.OVER;
    }

    /** The chat, in window coordinates, where {@code GameScreen.render} used to put it. */
    private void renderChat(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.gui.getChat().isChatFocused()) {
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0f, -25f, 0f);
        double chatWidth = minecraft.options.chatWidth().get();
        double newChatWidth = Math.min(chatWidth * 280.0,
                (AvalonFrame.windowWidth() / 2.0) - (imageWidth / 2.0) - 50.0) / 280.0;
        if (newChatWidth > 0.0 && newChatWidth < 1) {
            minecraft.options.chatWidth().set(newChatWidth);
        }
        minecraft.gui.getChat().render(guiGraphics, minecraft.gui.getGuiTicks(), mouseX, mouseY + 25, false);
        minecraft.options.chatWidth().set(chatWidth);
        guiGraphics.pose().popPose();
    }

    /**
     * The act's one curve, {@code 0} to {@code 1}: up over the opening beat, held, and away over the last.
     * Everything the act shows is this number — the wash, the frost, the card's alpha and the line's alpha
     * — which is what makes them one arrival rather than four staggered ones.
     *
     * <p>The wash and the blur are the screen-wide half of it: the wash is what the eye reads as
     * "something is happening", the blur is what stops the hands and the pile behind it from still looking
     * sharp and clickable.
     */
    private float identityFade() {
        long elapsed = Util.getMillis() - identityStartMs;
        if (elapsed < ID_IN_MS) {
            return smooth(elapsed / (float) ID_IN_MS);
        }
        if (elapsed < ID_IN_MS + ID_HOLD_MS) {
            return 1f;
        }
        return 1f - smooth((elapsed - ID_IN_MS - ID_HOLD_MS) / (float) ID_OUT_MS);
    }

    /**
     * Blurs the finished table in place, under the curtain.
     *
     * <h2>Why this has to run here, and not in the screen's backdrop</h2>
     *
     * <p>{@code renderBlurredBackground} frosts the <em>world</em>, and it runs before a single card is
     * drawn — so everything this screen paints, most loudly the two hands and the pile, lands on top of it
     * razor sharp. Under a wash alone a hand still reads as bright, legible and clickable, which is exactly
     * what the act is trying to say it is not. So the whole frame is blurred again <em>after</em> the table
     * has been painted, and the card and the line are then drawn back over it unblurred.
     *
     * <h2>Why the chain is owned here rather than borrowed from the game</h2>
     *
     * <p>Vanilla's {@code GameRenderer.processBlurEffect} exists and does the same thing, but its radius is
     * read straight off the video setting with no way in, and it silently skips itself below a radius of 1
     * — so it can only be all-on or all-off and the frost would arrive as a step. Building the same
     * {@code minecraft:shaders/post/blur.json} chain here buys the one uniform that matters: the radius is
     * animated on the same curve as the wash, and the frost comes and goes smoothly.
     *
     * <p>The chain ping-pongs the main framebuffer through {@code swap} and finishes back on the main
     * target, so this is an in-place blur: nothing has to be copied out and no second screen pass is
     * involved. Six full-window quads a frame for the length of the act, and nothing at all either side of
     * it.
     */
    private void renderIdentityBlur(float partialTick) {
        if (identityStartMs < 0L) {
            return;
        }
        frostTable(partialTick, identityFade());
    }

    /**
     * Frosts the frame in place, at {@code strength} of whatever blur the player has configured.
     *
     * <h2>Why a closing act needs this, and not just a wash</h2>
     *
     * <p>Because a post pass <em>rewrites the framebuffer from itself</em>, and that is the one thing that
     * makes a wash over the top land on a card. A card is uploaded straight to the framebuffer rather than
     * queued through {@code GuiGraphics} — see {@code CardPictureDim} — and while that is true there is no
     * flushing order that reliably puts a later quad over it: the deal's curtain had this exact bug, and
     * frosting the frame first is what fixed it. Afterwards the picture is just framebuffer, the wash goes
     * over all of it, and the hands dim with the felt. Copy the curtain.
     *
     * <p>Callers land their own batch first ({@link GuiGraphics#flush}); the frost reads the framebuffer, so
     * anything still queued would be missing from it.
     */
    private void frostTable(float partialTick, float strength) {
        if (strength <= 0.01f || minecraft == null || minecraft.level == null) {
            return;
        }
        PostChain chain = curtainBlurChain();
        if (chain == null) {
            return;
        }
        // Every call, not only when the window changes size: this is what installs each pass's ortho matrix,
        // and a pass without one nulls inside process(). Cheap -- the targets guard their own reallocation
        // and the rest is one matrix per pass -- and it cannot go stale under a resize, a reload or a chain
        // that was built on a frame where a size cache had already matched.
        chain.resize(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        chain.setUniform("Radius", Math.max(ID_BLUR_RADIUS_FLOOR,
                minecraft.options.getMenuBackgroundBlurriness()) * strength);
        // A post pass installs its own orthographic projection for its full-screen quad and never puts the
        // caller's back -- PostChain has no save/restore of its own -- so the GUI projection has to be
        // saved and re-installed by hand, or every draw after this one lands in the wrong space.
        RenderSystem.backupProjectionMatrix();
        chain.process(partialTick);
        RenderSystem.restoreProjectionMatrix();
        // The chain's passes bind and unbind their own targets and finish on the default framebuffer with
        // the viewport their last target left. Stating the main target here is what re-points the viewport
        // at the window, which anything but a window-sized post target would otherwise leave wrong.
        minecraft.getMainRenderTarget().bindWrite(true);
    }

    /**
     * The blur chain, built on first use.
     *
     * <p>Built and nothing else: the resizing moved out to {@link #frostTable}, which states the window size
     * on every call. That is what the crash was -- {@code resize} is the only thing that hands a pass the
     * ortho matrix it uploads as {@code ProjMat}, and a chain that was created without one blew up inside
     * {@code process} with a null matrix. Resizing only when the size changed meant a chain could sit there
     * created-but-unresized, and the knife's act was the frame that found out.
     */
    @Nullable
    private PostChain curtainBlurChain() {
        if (curtainBlurBroken) {
            return null;
        }
        if (curtainBlur == null) {
            try {
                curtainBlur = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(),
                        minecraft.getMainRenderTarget(), BLUR_CHAIN);
            } catch (IOException | JsonSyntaxException exception) {
                curtainBlurBroken = true;
                return null;
            }
        }
        return curtainBlur;
    }

    /** The blur is the screen's own GL object, so it is released with the screen. */
    @Override
    public void removed() {
        super.removed();
        if (curtainBlur != null) {
            curtainBlur.close();
            curtainBlur = null;
        }
        // Left at full brightness for whoever draws next: the multiplier is this screen's business, and the
        // mixin that reads it has no way of knowing the screen is gone.
        TableDim.value = 1f;
    }

    /**
     * {@code true} while the identity curtain is on screen at all — the act is still running, so the table
     * is not open for business and a click does nothing.
     *
     * <h2>Why the table waits the curtain out</h2>
     *
     * <p>The curtain is not decoration over a live table — it <em>is</em> the stage between the deal and
     * the first proposal, and the player is meant to read their card before the table asks them for
     * anything. Left live, the proposal underneath is a trap: the first stray click on the big card in the
     * middle (which sits exactly where the pile is) lifts a spare team marker and starts "picking" a team
     * the player has not looked at yet.
     */
    private boolean identityCurtainUp() {
        return identityStartMs >= 0L && Util.getMillis() - identityStartMs < ID_TOTAL_MS;
    }

    /**
     * {@code true} while everything that belongs to "your turn to pick" is held off the table: the spare
     * team markers and the pile's caption, the seat sockets and any placed markers, the turn and tally
     * lines, and the confirm button. What is left is the board, the seats and their plates.
     *
     * <h2>Why this window ends exactly one beat before the act does</h2>
     *
     * <p>So that the furniture is already in place, under a wash that is still fully opaque, when the last
     * three seconds start. That final fade then <em>reveals</em> a complete table. Letting the furniture
     * arrive on the same frame the curtain lifts instead would slam it into place in full view; and
     * letting it be clickable the moment it appears would open the selection stage while the table is
     * still visibly behind glass — hence the deliberate gap against {@link #identityCurtainUp}, which
     * holds the input until the last beat is over.
     */
    private boolean identityFurnitureHidden() {
        return identityStartMs >= 0L && Util.getMillis() - identityStartMs < ID_IN_MS + ID_HOLD_MS;
    }

    /**
     * Whether the pointer is over one of the screen's vanilla buttons — the rules, options, deck and
     * history chips in the top strip. They are the only interactive furniture the curtain does not own, so
     * they are the only thing a swallowed click has to be tested against.
     *
     * <p>Identified by being a {@link Button} in {@code children()} rather than by identity, because
     * {@code GameScreen} keeps only one of the four in a field; the bounds are compared in frame
     * coordinates, which is the space {@code moveCornerButtons} placed them in and the space a click
     * arrives in.
     */
    private boolean overCornerButton(double mouseX, double mouseY) {
        for (GuiEventListener child : children()) {
            if (child instanceof Button button && button.visible
                    && mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
                    && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The post-deal identity curtain: a soft wash, an enlarged role card that fades in, lifts on hover
     * and fades out, then a faction-coloured line of text that fades in and out.
     *
     * <h2>When it starts, and what may stop it</h2>
     *
     * <p>Armed on the first frame that is past the deal and has a local role — which is the first frame of
     * {@code PROPOSE} in practice, since that is when {@code AvalonGame} stops dealing. From then on it
     * runs on the wall clock off {@link #identityStartMs} for {@link #ID_TOTAL_MS} <em>regardless of the
     * phase</em>.
     *
     * <p>That last part is the whole point, and it is a bug fix rather than a preference: the act used to
     * be gated on {@code PROPOSE} for its whole length, so anything that moved the table on cut it off
     * mid-word. And something usually does — a bot leader picks a team and confirms a couple of seconds
     * after the deal, so on a table with any bot at all (never mind a bot in the leader's chair) the wash
     * snapped off almost before it had faded in, and the player saw a flicker instead of their card. A
     * curtain that can be cut off by other players is not a curtain.
     *
     * <p>The one thing that may still stop it is a fresh deal, which is a new game: {@code DEALING} clears
     * both the timestamp and the done-flag.
     *
     * <h2>Why this paints in window coordinates</h2>
     *
     * <p>It is called after {@code AvalonScreen.render} has popped the design-frame pose, because the
     * curtain has to cover the letterbox margins and sit over the fans, the slots and the carried card
     * alike. So every size here is a <em>frame</em> measure multiplied by {@link AvalonFrame#scale()},
     * which keeps the composition identical from a 320-pixel window to an 8K one.
     */
    private void renderIdentityReveal(GuiGraphics guiGraphics, float partialTick) {
        // A fresh deal re-arms, and also throws away the previous act's clock: the screen outlives a
        // rematch, so without this the second game would be judged against the first game's timestamp.
        int match = menu.getMatchId();
        if (match <= 0) {
            // The mirror has not synced the deal id yet; arming on the placeholder would mark the wrong
            // match and replay the curtain when the real id landed.
            return;
        }
        if (match != identityMatch) {
            identityMatch = match;
            identityStartMs = -1L;
        }
        AvalonRole role = menu.getLocalRole();
        if (role == null || (identityShownMatch == match && identityStartMs < 0L)) {
            // The second half of that test is what tells "this screen is playing the act" apart from
            // "this match already armed it": a reopened screen has no start time, and must not restart
            // the curtain it was closed on.
            return;
        }
        if (identityStartMs < 0L) {
            // Let the deal land first: while DEALING the fans are still filling and the stack of
            // face-down cards in the middle is what the eye should be on.
            if (menu.getPhase() == AvalonGame.Phase.DEALING) {
                return;
            }
            // Armed, not finished: marking the match here is what stops a close-and-reopen during the
            // nine-second act from playing the whole thing again.
            identityShownMatch = match;
            identityStartMs = Util.getMillis();
        }
        long elapsed = Util.getMillis() - identityStartMs;
        if (elapsed >= ID_TOTAL_MS) {
            identityShownMatch = match;
            return;
        }

        // The wash: the visual half of the frost, and on the same curve so the two arrive and leave as one
        // effect. Full strength through the card and the line, tapering over the last beat.
        int washAlpha = Math.round(0xD8 * identityFade());
        wash(guiGraphics, 0, 0, AvalonFrame.windowWidth(), AvalonFrame.windowHeight(),
                (washAlpha << 24) | 0x0C0C12);

        // The card and the line ride the act's one curve, alongside the wash and the frost.
        float content = identityFade();

        int windowW = AvalonFrame.windowWidth();
        int windowH = AvalonFrame.windowHeight();
        float s = AvalonFrame.current().scale();

        // The card's box is worked out once and used by both halves: the line hangs off its bottom edge,
        // and the two no longer take turns on screen.
        float grow = 1f + 0.16f * (1f - content);
        int cardW = Math.round(ID_CARD_W * s * grow);
        int cardH = Math.round(ID_CARD_H * s * grow);
        float cx = windowW / 2f;
        float cy = windowH / 2f - ID_CARD_LIFT * s;

        if (content > 0f) {
            // The pointer over the card does not move it. Charta's cards lift and tilt on hover, and this
            // one used to copy that, but the card sits dead centre -- which is where the pointer already
            // is when the act opens -- so it spent the whole act pushed toward the camera and tilted, and
            // the lift read as the card squirming rather than as the pointer finding it.
            boolean underPointer = mouseOver(cx, cy, cardW, cardH);
            Card card = AvalonCards.roleCard(role);
            Deck deck = getDeck();
            blitIdentityCard(guiGraphics, partialTick, deck.getCardTexture(card, false),
                    deck.getCardTexture(card, true), cx, cy, cardW, cardH, 0f, 0f, 0f, content);
            // A card says what it is when you point at it; this one is the whole point of the act.
            if (underPointer) {
                setTooltipForNextRenderPass(role.tooltip());
            }
        }

        if (content > 0f) {
            float textAlpha = content;
            // The role's own name and its one-line reminder, through one template key the player can
            // rewrite. Good is blue and evil is red, so the colour alone answers "which side am I on".
            Component line = Component.translatable("reveal.myavalon.line",
                    Component.translatable(role.getTranslationKey()),
                    Component.translatable(role.getHintKey()));
            // ...and under it, the names that role was told about, which the chips cannot show yet: they
            // are behind the wash and blurred. Without this a Merlin reads "you know who the evil are" and
            // then has to wait out the act to find out.
            Component known = knownLine(role);
            int color = role.isEvil() ? ID_EVIL_TEXT : ID_GOOD_TEXT;
            float textScale = s * 1.6f;
            int widest = Math.max(font.width(line), known == null ? 0 : font.width(known));
            int lineWidth = Math.round(widest * textScale);
            // A long line on a narrow window would run off the edges; step the headline down until it
            // fits rather than letting it clip.
            float maxWidth = windowW - 40f;
            if (lineWidth > maxWidth && lineWidth > 0) {
                textScale *= maxWidth / lineWidth;
            }
            // The line hangs under the card's bottom edge, since the two are on screen together now; the
            // names go under that again.
            int lineY = Math.round(cy + cardH / 2f + 10f * s);
            drawRevealLine(guiGraphics, line, lineY, textScale, textAlpha, color);
            if (known != null) {
                drawRevealLine(guiGraphics, known, lineY + Math.round(13f * textScale), textScale, textAlpha,
                        ID_KNOWN_LABEL);
            }
            // The public setup, under the private card: which identities are on this table at all. One
            // line, every role in its faction colour, scaled to fit rather than clipped on a short window.
            Component census = censusLine();
            if (census != null) {
                int censusY = lineY + Math.round((known != null ? 27f : 14f) * textScale);
                drawRevealLine(guiGraphics, census, censusY, fittedScale(census, s * 1.1f, windowW),
                        textAlpha, ID_KNOWN_LABEL);
            }
        }
    }

    /**
     * The roles this deal put on the table, in the order the role enum lists them (good first), each
     * name in its faction's colour with its count.
     *
     * <p>Read off {@code SYNC_CENSUS}, which the server packs four bits per role. That is public setup
     * information -- the printed game names every identity in play -- and it stays true for a custom
     * setup or a table padded with loyalists, where composing it on the client from the option mask
     * would not be.
     */
    @Nullable
    private Component censusLine() {
        int census = menu.getRoleCensus();
        if (census == 0) {
            return null;
        }
        MutableComponent list = Component.empty();
        boolean first = true;
        for (AvalonRole role : AvalonRole.values()) {
            int count = (census >>> (role.ordinal() * 4)) & 0xF;
            if (count <= 0) {
                continue;
            }
            if (!first) {
                list.append(Component.translatable("reveal.myavalon.census_sep"));
            }
            first = false;
            list.append(Component.translatable("reveal.myavalon.role_count", role.coloredName(),
                    Integer.toString(count)));
        }
        return first ? null : Component.translatable("reveal.myavalon.roles_in_play", list);
    }

    /**
     * The names this seat was told about, coloured by what it was told, or {@code null} when it was told
     * about nobody.
     *
     * <p>Read straight off the viewer's known-seat mask — the same mirrored value the plates' faction tags
     * come from — so the act and the table can never disagree, and it inherits that mask's exceptions for
     * free: Merlin's list has no Mordred in it, and Percival's is the ambiguous Merlin-or-Morgana pair
     * rather than a side.
     */
    @Nullable
    private Component knownLine(AvalonRole role) {
        int known = menu.getKnownMask();
        if (known == 0) {
            return null;
        }
        boolean pair = role == AvalonRole.PERCIVAL;
        MutableComponent names = Component.empty();
        boolean first = true;
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            if ((known & (1 << seat)) == 0) {
                continue;
            }
            CardPlayer player = menu.getGame().playerAt(seat);
            if (player == null) {
                continue;
            }
            if (!first) {
                names.append(Component.literal("  "));
            }
            first = false;
            names.append(player.getName().copy()
                    .withStyle(style -> style.withColor(pair ? ACTIVE : ID_EVIL_TEXT)));
        }
        // The label names what this seat can actually see, not "you know". Merlin is shown the evil side, so
        // his line says as much; Percival is shown a role, so his names it. A pair of keys rather than one
        // template, because the two are saying genuinely different things.
        return names.getString().isEmpty()
                ? null
                : Component.translatable(pair ? "reveal.myavalon.known_pair" : "reveal.myavalon.known_evil",
                        names);
    }

    /** One centred line of the act: the component's own per-name styles win over this default. */
    private void drawRevealLine(GuiGraphics guiGraphics, Component text, int y, float scale, float alpha,
                                int color) {
        int x = (AvalonFrame.windowWidth() - Math.round(font.width(text) * scale)) / 2;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0f);
        guiGraphics.pose().scale(scale, scale, 1f);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        guiGraphics.drawString(font, text, 0, 0, color, true);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().popPose();
    }

    /** The identity curtain's two text colours: good blue, evil red, both legible on the dark wash. */
    private static final int ID_GOOD_TEXT = AvalonRole.GOOD_TEXT;
    private static final int ID_EVIL_TEXT = AvalonRole.EVIL_TEXT;
    /** The "you are told about" line's own label, so the names' colours are what the eye lands on. */
    private static final int ID_KNOWN_LABEL = 0xFFCFCFCF;

    /** Whether the pointer sits inside a box given by its centre and size, in window coordinates. */
    private boolean mouseOver(float centreX, float centreY, int width, int height) {
        double mouseX = guiMouseX();
        double mouseY = guiMouseY();
        return mouseX >= centreX - width / 2.0 && mouseX <= centreX + width / 2.0
                && mouseY >= centreY - height / 2.0 && mouseY <= centreY + height / 2.0;
    }

    /** {@link #mouseOver} inside the frame pose, which is where the table and its cards live. */
    private boolean mouseOverFrame(float centreX, float centreY, int width, int height) {
        AvalonFrame frame = AvalonFrame.current();
        return mouseOver((float) frame.toFrameX(guiMouseX()), (float) frame.toFrameY(guiMouseY()),
                width, height);
    }

    /** Window mouse position in GUI-scaled coordinates, for the curtain's hover test. */
    private double guiMouseX() {        if (minecraft == null || minecraft.getWindow().getScreenWidth() == 0) {
            return -1;
        }
        return minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth();
    }

    private double guiMouseY() {
        if (minecraft == null || minecraft.getWindow().getScreenHeight() == 0) {
            return -1;
        }
        return minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight();
    }

    /**
     * One card drawn the way {@link #blitChartaCard} draws one — the inflated quad through Charta's
     * perspective card shader, its silhouette into the glow buffer, and the blurred halo composited back
     * — but tinted by a single alpha so the curtain can fade it in and out.
     *
     * <h2>Why the halo is not queued</h2>
     *
     * <p>Everything else on this screen defers its halo to {@link #flushCardGlow} so a fan of cards
     * costs one buffer bind. The curtain cannot: it paints <em>after</em> {@code GameScreen.render} has
     * already run {@code CardScreen.renderGlowBlur} and cleared the buffer, so a queued entry would only
     * be composited on the following frame — the halo would sit one frame behind the card, and worse, it
     * would stay at full strength for the whole fade-out and then flash once after the card was gone.
     * Drawing and compositing it here keeps the halo glued to the card, and the alpha carries into the
     * halo (the glow pass is additive, so the tint is what fades it).
     */
    private void blitIdentityCard(GuiGraphics guiGraphics, float partialTick,
                                  ResourceLocation texture, ResourceLocation glow,
                                  float centreX, float centreY, int cardW, int cardH,
                                  float inset, float xRot, float yRot, float alpha) {
        float xOffset = (cardW * 1.333333f - cardW) / 2f;
        float yOffset = (cardH * 1.333333f - cardH) / 2f;
        float x = centreX - cardW / 2f - xOffset;
        float y = centreY - cardH / 2f - yOffset;
        float width = cardW + xOffset * 2f;
        float height = cardH + yOffset * 2f;

        // Pass one, onto the main buffer: the card itself.
        cardUniforms(inset, 30f, xRot, yRot);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        ChartaGuiGraphics.blitCard(guiGraphics, texture, x, y, width, height);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // Pass two, into the glow buffer: the silhouette in black, then the card's own glow art. The
        // buffer was left clean and empty by this frame's renderGlowBlur, so what is in it afterwards is
        // this card and nothing else.
        RenderTarget glowTarget = ChartaModClient.getShaderManager().getGlowRenderTarget();
        glowTarget.bindWrite(false);
        RenderSystem.viewport(0, 0, glowTarget.width, glowTarget.height);
        cardUniforms(inset, 30f, xRot, yRot);
        RenderSystem.setShaderColor(0f, 0f, 0f, alpha);
        ChartaGuiGraphics.blitCard(guiGraphics, texture, x, y, width, height);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        ChartaGuiGraphics.blitCardGlow(guiGraphics, glow, x, y, width, height);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // Pass three, back on the main buffer: blur the glow buffer and lay it down additively. This
        // mirrors CardScreen.renderGlowBlur, which has already run for this frame and cannot be called
        // again (it would composite the whole buffer a second time) -- except that the quad is measured
        // in window pixels here rather than in frame pixels, because the frame pose is already popped.
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        ChartaModClient.getShaderManager().processBlurEffect(partialTick);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, glowTarget.getColorTextureId());
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
        ChartaGuiGraphics.innerBlit(guiGraphics, 0, AvalonFrame.windowWidth(), 0,
                AvalonFrame.windowHeight(), 0f, 1f, 1f, 0f);
        RenderSystem.defaultBlendFunc();
        glowTarget.clear(Minecraft.ON_OSX);
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
    }

    // ---------------------------------------------------------------------------------------------
    // The assassination act
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the closing act is holding the screen this frame.
     *
     * <p>What {@code renderRevealedRoles} asks before it turns the whole table over: the act is the one
     * moment the reveal must not race, and its first second is a wash coming up, so without this the table
     * flashed every seat's card for a beat and then hid them again behind the act meant to be showing them.
     * Either closing act counts -- the knife's, and evil's sabotage ending's red fade, which covers the same
     * reveal on its way out of the table.
     */
    private boolean closingActUp() {
        return assassinationCurtainUp() || evilEndingUp();
    }

    private boolean assassinationCurtainUp() {
        return assassinStartMs >= 0L
                && Util.getMillis() - assassinStartMs < AS_TOTAL_MS;
    }

    private boolean evilEndingUp() {
        return evilStartMs >= 0L
                && Util.getMillis() - evilStartMs < EV_TOTAL_MS;
    }

    /**
     * The report: the same lines the chat gets, faded in over the finished table.
     *
     * <h2>When</h2>
     *
     * <p>On every ending, and only once the closing acts are done -- they own the screen until then, and
     * the report is the last thing to say. A game that ended without one of them (a good win, a table packed
     * up mid-game) starts it as soon as the phase turns {@code OVER}.
     *
     * <h2>Why the screen stays open</h2>
     *
     * <p>Because the report is worth reading and there is nothing behind it to hurry back to: the table is
     * swept by the server either way and a rematch is one right-click away, so the player leaves with Esc
     * when they are done rather than being thrown out mid-sentence.
     *
     * <h2>What is drawn</h2>
     *
     * <p>{@code AvalonGame.summaryLines} — the builder the chat uses, called with this side's copy of the
     * same facts: the seats, the revealed roles, the missions, the winner and the two counters that exist on
     * the client only for this ({@code SYNC_STATS_BASE}). One list, two places to read it.
     */
    private void renderSummary(GuiGraphics guiGraphics) {
        int match = menu.getMatchId();
        if (match <= 0) {
            return;
        }
        if (match != summaryMatch) {
            summaryMatch = match;
            summaryStartMs = -1L;
            summaryLines = null;
        }
        if (menu.getPhase() != AvalonGame.Phase.OVER || closingActUp()) {
            return;
        }
        if (summaryStartMs < 0L) {
            // A report already faded in once for this match is drawn at full strength rather than
            // fading in again when the table is reopened.
            summaryStartMs = summaryShownMatch == match
                    ? Util.getMillis() - SUMMARY_IN_MS
                    : Util.getMillis();
            summaryShownMatch = match;
        }
        float alpha = smooth((Util.getMillis() - summaryStartMs) / (float) SUMMARY_IN_MS);
        if (alpha <= 0.01f) {
            return;
        }
        if (summaryLines == null) {
            summaryLines = buildSummary();
        }

        // Frame coordinates, inside its own pose: this is part of the table's composition rather than
        // something laid over it, so it scales with the frame like the board does.
        AvalonFrame frame = AvalonFrame.current();
        frame.push(guiGraphics);
        float scale = SUMMARY_SCALE;
        int lineHeight = Math.round((font.lineHeight + 1) * scale);
        int widest = 0;
        for (Component line : summaryLines) {
            widest = Math.max(widest, font.width(line));
        }
        int blockHeight = summaryLines.size() * lineHeight;
        int pad = 6;
        int boxW = Math.round(widest * scale) + pad * 2;
        int boxH = blockHeight + pad * 2;
        int x = (AvalonFrame.WIDTH - boxW) / 2;
        int y = Math.max(4, (AvalonFrame.HEIGHT - boxH) / 2);
        guiGraphics.fill(x, y, x + boxW, y + boxH, withAlpha(SUMMARY_BG, alpha));
        frame(guiGraphics, x, y, boxW, boxH, withAlpha(SUMMARY_BORDER, alpha));

        int textY = y + pad;
        for (Component line : summaryLines) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x + (boxW - font.width(line) * scale) / 2f, textY, 0f);
            guiGraphics.pose().scale(scale, scale, 1f);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            guiGraphics.drawString(font, line, 0, 0, 0xFFFFFFFF, true);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            guiGraphics.pose().popPose();
            textY += lineHeight;
        }
        frame.pop(guiGraphics);
    }

    /**
     * Card widgets ask the screen for a tooltip on every frame they are hovered; this is the one gate
     * that can say no for a whole phase.
     *
     * <p>When the final report is up, the cards behind it are decoration: a tooltip popping over the
     * report is exactly the bug this suppresses. The opening curtain gets the same treatment for the
     * table underneath, while the curtain's own role card still schedules its tooltip directly -- that
     * one is the act, not a card behind it.
     */
    @Override
    public void scheduleTooltip(Component component) {
        if (summaryOwnsScreen() || identityCurtainUp()) {
            return;
        }
        super.scheduleTooltip(component);
    }

    /** {@code true} while the end-of-game report owns the screen. */
    private boolean summaryOwnsScreen() {
        return menu != null && menu.getPhase() == AvalonGame.Phase.OVER;
    }

    /** The report for this table, built off the mirror — see {@link #renderSummary}. */
    private List<Component> buildSummary() {
        List<Component> names = new ArrayList<>();
        List<AvalonRole> revealed = new ArrayList<>();
        for (int seat = 0; seat < ring.length; seat++) {
            AvalonRole role = menu.getRevealedRole(seat);
            CardPlayer player = menu.getGame().playerAt(seat);
            if (role == null || player == null) {
                continue;
            }
            names.add(player.getName());
            revealed.add(role);
        }
        int[] missions = new int[AvalonRole.QUESTS];
        int[] sharp = new int[names.size()];
        int[] clean = new int[names.size()];
        for (int i = 0; i < missions.length; i++) {
            missions[i] = menu.getMission(i);
        }
        for (int seat = 0; seat < names.size(); seat++) {
            sharp[seat] = menu.getSharpNo(seat);
            clean[seat] = menu.getCleanTeams(seat);
        }
        return AvalonGame.summaryLines(names, revealed, missions, sharp, clean, menu.getWinner());
    }

    /** The report's state: the lines, the fade clock and the match they belong to. */
    @Nullable
    private List<Component> summaryLines;
    @Nullable
    private long summaryStartMs = -1L;
    private int summaryMatch = -1;
    /** The match whose report has already faded in once; static so a reopen does not fade it again. */
    private static int summaryShownMatch = -1;

    /** How long the report takes to fade in. Nothing closes it afterwards; Esc does. */
    private static final long SUMMARY_IN_MS = 1500;

    /** Small enough that a ten-seat report — winner, roles, quests and two rankings — fits one screen. */
    private static final float SUMMARY_SCALE = 0.8f;

    private static final int SUMMARY_BG = 0xC0101014;
    private static final int SUMMARY_BORDER = 0x80FFFFFF;

    /** {@code argb} with its alpha scaled by {@code alpha}, for the fade. */
    private static int withAlpha(int argb, float alpha) {
        return (Math.round(((argb >>> 24) & 0xFF) * alpha) << 24) | (argb & 0xFFFFFF);
    }

    /**
     * The closing act: the assassin's pick, and what it cost.
     *
     * <h2>What it shows</h2>
     *
     * <p>Two halves on one clock. First the screen darkens while two lines come up in the middle — the
     * assassin's own role, the name of the seat it picked, and what that seat turns out to have been. Then
     * the lines and the card swap: the text goes out while the picked seat's identity card fades in, with
     * the side that won under it. After that the wash lifts and the table is there again, turned over, for
     * good.
     *
     * <h2>When it starts</h2>
     *
     * <p>Armed on the first frame of a finished game that has a picked target and a winner — the two facts
     * the server mirrors the moment the knife lands ({@code SYNC_TARGET} and {@code SYNC_WINNER}) — and
     * shown once per deal, like the deal's own curtain. A game that ended any other way has no target and
     * never arms it; a seat that rejoins the table after it has played misses it, which is the same bargain
     * the opening curtain makes.
     *
     * <p>Like the deal's curtain this paints in window coordinates, after the frame pose has been popped, so
     * it covers the letterbox margins too and one set of sizes works at any window size.
     */
    private void renderAssassination(GuiGraphics guiGraphics, float partialTick) {
        int match = menu.getMatchId();
        if (match <= 0) {
            return;
        }
        if (match != assassinMatch) {
            assassinMatch = match;
            assassinStartMs = -1L;
        }
        int target = menu.getPendingTarget();
        if ((assassinShownMatch == match && assassinStartMs < 0L) || target < 0
                || menu.getWinner() == 0 || menu.getPhase() != AvalonGame.Phase.OVER) {
            return;
        }
        if (assassinStartMs < 0L) {
            // Armed, not finished -- see the identity curtain's flag: a reopen must not replay the act.
            assassinShownMatch = match;
            assassinStartMs = Util.getMillis();
        }
        long elapsed = Util.getMillis() - assassinStartMs;
        if (elapsed >= AS_TOTAL_MS) {
            assassinShownMatch = match;
            return;
        }

        // The three envelopes. The wash is up for everything but the two fades, the lines own the first
        // half, and the card owns the second -- and the hand-over in the middle is the one stretch where
        // both are on their way, which is what makes it read as one movement rather than two beats.
        float wash = elapsed < AS_IN_MS ? smooth(elapsed / (float) AS_IN_MS)
                : elapsed < AS_TOTAL_MS - AS_OUT_MS ? 1f
                : 1f - smooth((elapsed - (AS_TOTAL_MS - AS_OUT_MS)) / (float) AS_OUT_MS);
        float lines = elapsed < AS_IN_MS ? smooth(elapsed / (float) AS_IN_MS)
                : elapsed < AS_IN_MS + AS_LINES_MS ? 1f
                : 1f - smooth((elapsed - AS_IN_MS - AS_LINES_MS) / (float) AS_SWAP_MS);
        float card = elapsed < AS_IN_MS + AS_LINES_MS ? 0f
                : elapsed < AS_IN_MS + AS_LINES_MS + AS_SWAP_MS
                        ? smooth((elapsed - AS_IN_MS - AS_LINES_MS) / (float) AS_SWAP_MS)
                : elapsed < AS_TOTAL_MS - AS_OUT_MS ? 1f
                : 1f - smooth((elapsed - (AS_TOTAL_MS - AS_OUT_MS)) / (float) AS_OUT_MS);

        int washAlpha = Math.round(AS_WASH_ALPHA * wash);
        // Land the batch, frost the frame, and only then lay the wash: this is the deal's curtain recipe,
        // and the frost is the part that makes the hands take the wash with everything else.
        guiGraphics.flush();
        frostTable(partialTick, wash);
        wash(guiGraphics, 0, 0, AvalonFrame.windowWidth(), AvalonFrame.windowHeight(),
                (washAlpha << 24) | 0x0C0C12);

        int windowW = AvalonFrame.windowWidth();
        int windowH = AvalonFrame.windowHeight();
        float s = AvalonFrame.current().scale();
        AvalonRole struck = menu.getRevealedRole(target);

        // What happened, and then what it cost: line one is the pick on its own, and line two belongs to
        // the card below rather than to line one -- the two arrive together, which is the whole reveal.
        Component who = Component.translatable("reveal.myavalon.assassin_chose",
                Component.translatable(AvalonRole.ASSASSIN.getTranslationKey()), nameOf(target));
        Component what = Component.translatable("reveal.myavalon.assassin_was",
                struck == null ? Component.empty() : Component.translatable(struck.getTranslationKey()));

        if (lines > 0f) {
            drawRevealLine(guiGraphics, who, Math.round(windowH / 2f - 10f * s),
                    fittedScale(who, s * 1.6f, windowW), lines, ID_KNOWN_LABEL);
        }

        if (card > 0f) {
            int cardW = Math.round(ID_CARD_W * s);
            int cardH = Math.round(ID_CARD_H * s);
            float cx = windowW / 2f;
            float cy = windowH / 2f - ID_CARD_LIFT * s;
            if (struck != null) {
                Card cardFace = AvalonCards.roleCard(struck);
                Deck deck = getDeck();
                blitIdentityCard(guiGraphics, partialTick, deck.getCardTexture(cardFace, false),
                        deck.getCardTexture(cardFace, true), cx, cy, cardW, cardH, 0f, 0f, 0f, card);
            }
            // Under the card and on the same envelope, so the seat's face and what it was are read in one
            // look. The side that won comes later, on the report.
            drawRevealLine(guiGraphics, what, Math.round(cy + cardH / 2f + 12f * s),
                    fittedScale(what, s * 1.6f, windowW), card, ID_KNOWN_LABEL);
        }
    }

    /** {@code scale} stepped down until {@code text} fits inside {@code window} less its margins. */
    private float fittedScale(Component text, float scale, int windowWidth) {
        float maxWidth = windowWidth - 40f;
        float width = font.width(text) * scale;
        return width > maxWidth && width > 0f ? scale * (maxWidth / width) : scale;
    }

    /**
     * The other ending that says so itself: evil's third sabotaged quest.
     *
     * <h2>Why this one is different</h2>
     *
     * <p>Every other ending either keeps the table for the reveal or has already lost it (a table packed up
     * mid-game). This one is played out: three quests are face-down on the pile, the side that won won by
     * sabotage, and there is nothing left to look at but the fact. So the screen takes on the colour and the
     * winner is named in the middle of it, and then the table closes itself like every other ending — with
     * the server's hold timed so that the close lands just after the fade (see
     * {@code AvalonGame.EVIL_CLOSE_LINGER}). The report is already in everyone's chat by then.
     *
     * <h2>How this side knows</h2>
     *
     * <p>From the mirror alone: the winner is evil, no target was ever picked, and three missions are marked
     * failed. That is exactly this ending — evil can also win on the fifth rejection, which has no failures
     * to count, and by the knife, which has a target.
     */
    private void renderEvilEnding(GuiGraphics guiGraphics, float partialTick) {
        int match = menu.getMatchId();
        if (match <= 0) {
            return;
        }
        if (match != evilMatch) {
            evilMatch = match;
            evilStartMs = -1L;
        }
        if ((evilShownMatch == match && evilStartMs < 0L) || !evilEndingMine()) {
            return;
        }
        if (evilStartMs < 0L) {
            // Armed, not finished, for the same reason as the other two one-shot acts.
            evilShownMatch = match;
            evilStartMs = Util.getMillis();
        }
        long elapsed = Util.getMillis() - evilStartMs;
        if (elapsed >= EV_TOTAL_MS) {
            // The close is not done here: every ending is closed by {@link #closeWhenFinished} when the
            // server's own hold expires, which for this one is timed to land right after the red fade
            // (see {@code AvalonGame.EVIL_CLOSE_LINGER}). One mechanism, and this act only has to say so.
            evilShownMatch = match;
            return;
        }

        float fade = elapsed < EV_IN_MS ? smooth(elapsed / (float) EV_IN_MS)
                : elapsed < EV_IN_MS + EV_HOLD_MS ? 1f
                : 1f - smooth((elapsed - EV_IN_MS - EV_HOLD_MS) / (float) EV_OUT_MS);
        int washAlpha = Math.round(EV_WASH_ALPHA * fade);
        // Same recipe as the deal's curtain and the knife's act: land the batch, frost the frame, then wash.
        guiGraphics.flush();
        frostTable(partialTick, fade);
        wash(guiGraphics, 0, 0, AvalonFrame.windowWidth(), AvalonFrame.windowHeight(),
                (washAlpha << 24) | 0x300A0A);

        float s = AvalonFrame.current().scale();
        Component name = Component.translatable("status.myavalon.evil_wins");
        float textScale = s * 2.2f;
        float maxWidth = AvalonFrame.windowWidth() - 40f;
        if (font.width(name) * textScale > maxWidth) {
            textScale *= maxWidth / (font.width(name) * textScale);
        }
        drawRevealLine(guiGraphics, name,
                Math.round(AvalonFrame.windowHeight() / 2f - 10f * s), textScale, fade, ID_EVIL_TEXT);
    }

    /**
     * Whether this finished game is evil's to announce in red: it won, and not by the knife.
     *
     * <p>That covers both of evil's material wins -- three sabotaged quests and five rejected teams -- and
     * the knife's is excluded on purpose, because it has an act of its own. Read off the mirror: the winner,
     * and the absence of a picked target.
     */
    private boolean evilEndingMine() {
        return menu.getPhase() == AvalonGame.Phase.OVER
                && menu.getWinner() == AvalonGame.SIDE_EVIL
                && menu.getPendingTarget() < 0;
    }

    /**
     * The top bar: Charta's own, over the seats across from the local player.
     *
     * <h2>What this is</h2>
     *
     * <p>A copy of {@code GameScreen.renderTopBar} — the band, the seat-coloured chip, the wool frame
     * around it, the half-scale name, the head — with exactly two changes, both noted below. Every
     * constant, every {@code Mth.floor}/{@code Mth.ceil}, the {@code 0x88000000 + colour} tint that lets
     * its carry drop the alpha to {@code 0x87}, and the head's own overdraw of its frame are Charta's, not
     * a look-alike: this is the same bar Charta draws over its Crazy Eights table.
     *
     * <p>The chip geometry comes from {@link AvalonLayout}, so the chips land exactly where the seats'
     * slots are and an F9 drag moves the bar with them.
     *
     * <h2>Change one: the local seat is left out</h2>
     *
     * <p>Charta's bar walks every player including the viewer, and in its games every chip carries that
     * player's hand. Here the viewer's hand is the full-size fan along the bottom of the frame, so a chip
     * for them would be a second copy of the same five cards — and one that could not be filled anyway,
     * since there is one slot per seat and the viewer's is already spent on the fan.
     *
     * <h2>Change two: the seat's faction tag rides the name</h2>
     *
     * <p>The plates that used to hang under each hand are gone with this bar, and a plate was carrying
     * more than a name: what the viewer knows about that seat — the faction, or the Merlin/Morgana pair —
     * which is the one thing a player actually reads a seat for. It is appended to the chip's name in the
     * chip's own half scale, coloured as it always was.
     */
    @Override
    public void renderTopBar(@NotNull GuiGraphics guiGraphics) {
        // The bar runs before renderBg, so the frame's memo of plate texts and of the seat ring has to be
        // filled here -- everything below reads the memo rather than asking the menu again, and the menu
        // builds a fresh ring array on every ask.
        measurePlates();
        int[] ring = this.ring;
        int others = Math.max(0, ring.length - 1);
        if (others == 0) {
            return;
        }
        // Every box comes off the layout, so the bar and the slots it decorates cannot drift apart and an
        // F9 drag on a chip moves its hand, its marker and its bar chip together.
        int[][] chips = new int[others][];
        int rowLeft = Integer.MAX_VALUE;
        int rowRight = Integer.MIN_VALUE;
        for (int i = 0; i < others; i++) {
            chips[i] = LAYOUT.bounds(AvalonLayout.Element.otherPlate(i), width, height);
            // Only the chips on the bar's own line set its ends; a parked one is a row lower and sits
            // flush against the frame edge, so counting it would leave the band with nowhere to go.
            if (LAYOUT.onBar(i)) {
                rowLeft = Math.min(rowLeft, chips[i][0]);
                rowRight = Math.max(rowRight, chips[i][0] + chips[i][2]);
            }
        }
        float chipWidth = chips[0][2];

        // The band and the gaps are painted FIRST, and the chips over them.
        //
        // That order is the fix for a chip that has been dragged out of row order -- an F9 drag can put a
        // chip to the left of the first one, and then a gap fill written as "from the right edge of one
        // chip to the left edge of the next" runs backwards. GuiGraphics.fill does not care about the sign,
        // so it painted a dark rectangle from the far left to the dragged chip's old place: a slab of
        // shadow lying across every name plate in between. Painting the gaps first means an over-wide or
        // back-to-front one is simply covered by the chips that follow, and the uncovered part reads as the
        // gap it is.
        if (rowLeft > 0) {
            guiGraphics.fill(0, 0, rowLeft, TOP_BAR_H, TOP_BAR_BG);
        }
        if (rowRight < width) {
            guiGraphics.fill(rowRight, 0, width, TOP_BAR_H, TOP_BAR_BG);
        }
        for (int i = 0; i < others - 1; i++) {
            if (!LAYOUT.onBar(i) || !LAYOUT.onBar(i + 1)) {
                continue;
            }
            int from = Math.min(Mth.ceil(chips[i][0] + chipWidth), chips[i + 1][0]);
            int to = Math.max(Mth.ceil(chips[i][0] + chipWidth), chips[i + 1][0]);
            if (to > from) {
                guiGraphics.fill(from, 0, to, TOP_BAR_H, TOP_BAR_BG);
            }
        }

        for (int i = 0; i < others; i++) {
            int seat = ring[i + 1];
            CardPlayer player = menu.getGame().playerAt(seat);
            DyeColor color = player.getColor();
            int x = chips[i][0];
            int chipTop = chips[i][1];

            // A seat the table has just done something with -- on turn, picked for the team, or the
            // assassin's mark -- wears its colour as a breathing wash rather than as the flat dark chip.
            // The hairline underneath it used to be the whole signal, and one pixel of colour under a name
            // is easy to miss on a table this busy; a chip that swells is not.
            int fill = marked(seat)
                    ? (markedPulseAlpha() << 24) | (color.getTextureDiffuseColor() & 0xFFFFFF)
                    : TOP_BAR_BG + color.getTextureDiffuseColor();
            guiGraphics.fill(x, chipTop, Mth.ceil(x + chipWidth), chipTop + TOP_BAR_H, fill);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x + 26f, chipTop + 2f, 0f);
            guiGraphics.pose().scale(0.5f, 0.5f, 0.5f);
            guiGraphics.drawString(font, plateTexts[i + 1], 0, 0, 0xFFFFFFFF, true);
            guiGraphics.pose().popPose();

            TextureAtlasSprite wool = woolSprite(color);
            guiGraphics.blit(x + 4, chipTop + 2, 0, 16, 1, wool);
            guiGraphics.blit(x + 2, chipTop + 2 + 1, 0, 20, 22, wool);
            guiGraphics.blit(x + 4, chipTop + 2 + 23, 0, 16, 1, wool);

            CardPlayerHead.renderHead(guiGraphics, x, chipTop + 2, player);
        }
    }

    /** {@code true} when the table has just done something with {@code seat}: on turn, team, or stabbed. */
    private boolean marked(int seat) {
        return seat == menu.getCurrentSeat() && menu.getGame().isGameReady()
                || seat == menu.getLeaderSeat()
                || (pendingMask() & (1 << seat)) != 0
                || menu.getPendingTarget() == seat && menu.getPhase() == AvalonGame.Phase.ASSASSIN;
    }

    /**
     * The chip highlight's breath, {@code 0x66} to {@code 0xFA}.
     *
     * <p>Louder than {@link #pulseAlpha}, which drives the marker sockets: a socket is under the cursor and
     * being aimed at, while a chip is read across a table already carrying nine of them, so it has to carry
     * further. Sharing the phase with the crown's breath ({@link #breath}) keeps the whole table's
     * attention in one rhythm instead of three.
     */
    private static int markedPulseAlpha() {
        return (int) (0x66 + 0x94 * breath());
    }

    /** One shared 0..1 breath, so everything that pulses on this screen pulses together. */
    private static double breath() {
        return breath(127.0);
    }

    /**
     * The same breath at its own pace.
     *
     * <p>The crown takes three times as long over one: a chip flashing for attention wants to be quick, while
     * a marker that is lit all game reads better as a slow swell — a fast pulse on something permanent starts
     * to look like an alarm.
     */
    private static double breath(double periodMs) {
        return 0.5 + 0.5 * Math.sin(Util.getMillis() / periodMs);
    }

    /** The crown's own breath period: three times the chips', because it is lit for a whole game, not a turn. */
    private static final double CROWN_BREATH_MS = 381.0;

    /** The bar is 28 tall, which is also exactly the height of one seat's chip. */
    private static final int TOP_BAR_H = 28;
    /** Charta's own band tint; see the {@code +} that follows it at every use. */
    private static final int TOP_BAR_BG = 0x88000000;

    /**
     * The block atlas, and the one wool sprite per dye colour off it.
     *
     * <p>Charta writes this inline, and inline it costs a {@link ResourceLocation} built by two string
     * concatenations plus a map lookup in the atlas, per chip, per frame — nine of each for a full table,
     * for a sprite that never changes. Cached here, keyed on the atlas object so a resource reload (which
     * hands back a new one) drops the lot.
     */
    @Nullable
    private Function<ResourceLocation, TextureAtlasSprite> blockAtlas;
    private final TextureAtlasSprite[] woolSprites = new TextureAtlasSprite[DyeColor.values().length];

    private TextureAtlasSprite woolSprite(DyeColor color) {
        Function<ResourceLocation, TextureAtlasSprite> atlas = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        if (atlas != blockAtlas) {
            blockAtlas = atlas;
            Arrays.fill(woolSprites, null);
        }
        int at = color.ordinal();
        TextureAtlasSprite sprite = woolSprites[at];
        if (sprite == null) {
            sprite = atlas.apply(ResourceLocation.withDefaultNamespace("block/" + color.getName() + "_wool"));
            woolSprites[at] = sprite;
        }
        return sprite;
    }

    /** Nothing behind the local fan; see {@code GameScreen.renderBottomBar} for the band it would draw. */
    @Override
    public void renderBottomBar(@NotNull GuiGraphics guiGraphics) {
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        GuiTextureFilters.ensure(GuiTextureFilters.pixelSprites(), GuiTextureFilters.paintedSprites());
        measurePlates();
        updateReveal();
        updateFlagHover();
        renderBackdrop(guiGraphics);
        renderTaskCard(guiGraphics);
        renderQuestSeals(guiGraphics);
        renderQuestPick(guiGraphics);
        if (dealing()) {
            // Opening: only the table, the task card and the seats -- the fans are still empty and the
            // stack of face-down role cards in the middle is what the eye should be on.
            renderDeal(guiGraphics);
            flushCardGlow(guiGraphics);
            renderSeats(guiGraphics);
            renderRejectNugget(guiGraphics);
            if (editing) {
                renderEditor(guiGraphics);
            }
            return;
        }
        renderPile(guiGraphics);
        flushCardGlow(guiGraphics);
        renderSeats(guiGraphics);
        renderFlags(guiGraphics);
        // The flags are cards too, so their halos queue behind them; the pile's batch already went out.
        flushCardGlow(guiGraphics);
        renderCrown(guiGraphics);
        // On top of the tracker, which is itself painted over the board's own notches.
        renderRejectNugget(guiGraphics);
        renderStatus(guiGraphics);
        if (editing) {
            renderEditor(guiGraphics);
        }
    }

    /**
     * The end-of-game reveal: every other seat's identity card, turned face-up where that seat's hand lies.
     *
     * <h2>Where this is called from, and why not from {@code renderBg}</h2>
     *
     * <p>A hand is not drawn by this screen at all: it is a card widget, and {@code GameScreen} renders the
     * whole widget list <em>after</em> {@code renderBg}. So a card laid down inside the table's own pass
     * ends up behind the backs it is meant to replace. It is drawn instead from {@code render} right after
     * {@code super.render}, which is the first moment everything below — table and widgets alike — is on the
     * buffer; it is still inside the frame pose, and still ahead of the glow composite at the end of
     * {@code GameScreen.render}, so the card's halo is batched and blurred like any other.
     *
     * <h2>Why it is drawn rather than flipped</h2>
     *
     * <p>An opponent's hand on this client is a row of <em>blank</em> cards: Charta's censored hand keeps the
     * count of what that seat holds and throws the faces away, and only the local fan is the real thing. So
     * there is nothing in those cards to turn over. What the client does know, the moment the game ends, is
     * every seat's role — it comes down the sync channel for the plates' tags — so the card is built from
     * that and laid over the leftmost back, which is where the role card always sits: it is dealt first,
     * never played, and the hand is kept sorted around it.
     *
     * <p>The local seat needs nothing: their own fan is the real hand, and its leftmost card is their role
     * card, already face-up.
     */
    private void renderRevealedRoles(GuiGraphics guiGraphics) {
        if (menu.getPhase() != AvalonGame.Phase.OVER || ring.length < 2 || closingActUp()) {
            return;
        }
        Deck deck = getDeck();
        boolean any = false;
        for (int r = 1; r < ring.length; r++) {
            AvalonRole role = menu.getRevealedRole(ring[r]);
            if (role == null) {
                continue;
            }
            int[] hand = LAYOUT.bounds(AvalonLayout.Element.otherHand(r - 1), width, height);
            Card card = AvalonCards.roleCard(role);
            blitChartaCard(guiGraphics, deck.getCardTexture(card, false), deck.getCardTexture(card, true),
                    hand[0] + HAND_CARD_W / 2f, hand[1] + HAND_CARD_H / 2f,
                    HAND_CARD_W, HAND_CARD_H, 0f, 0f);
            // No hover tooltip here: the final report owns the screen from the end of the game, and a
            // card peeking out from behind it must not pop a window over the report. The report already
            // names every seat's role.
            any = true;
        }
        if (any) {
            flushCardGlow(guiGraphics);
        }
    }

    /** {@code true} while the opening deal is still running. */
    private boolean dealing() {
        return menu.getPhase() == AvalonGame.Phase.DEALING;
    }

    /** A soft felt wash so the fan areas read as the table rather than as blank screen. */
    private void renderBackdrop(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, 0, width, height, FELT);
    }

    /**
     * The quest card, centred: nine sixteenths of the frame wide, with the height that width implies on the
     * source 16:9 art, so the original proportions are kept exactly.
     *
     * <p>It is blitted as one quad at frame resolution, so the 1920x1080 file is scaled by the GPU once at
     * draw time and nothing is resampled offline.
     */
    private void renderTaskCard(GuiGraphics guiGraphics) {
        int[] box = taskCardBox();
        // An immediate blit does not touch blend state; without this the board's soft edges stamp down
        // opaque. See UiBlend.
        UiBlend.on();
        guiGraphics.blit(taskCardTexture(), box[0], box[1], box[2], box[3], 0f, 0f,
                box[2], box[3], box[2], box[3]);
    }

    /** Where the quest board is blitted: its own editable layout box. */
    private int[] taskCardBox() {
        return LAYOUT.bounds(AvalonLayout.Element.TASK_CARD, width, height);
    }

    /**
     * Presses each resolved mission's outcome seal onto its slot on the board: a finished seal on a
     * success, a torn one on a sabotage. The result arrives already synced ({@code 1} success, {@code 2}
     * sabotage from the menu), so a rising edge from {@code 0} is what arms the stamp.
     *
     * <p>The stamp itself is a short drop: it comes in about twice its size, overshoot-free, easing down
     * to rest while fading in. Timing is off the wall clock for the same reason the deal is -- see
     * {@link #dealStartMs}.
     */
    private void renderQuestSeals(GuiGraphics guiGraphics) {
        // Prime the seals from the mirror the first time this screen sees a match. Without this, every
        // seal already on the board re-stamped itself when the table was closed and reopened, because
        // the instance fields started at zero and read the synced missions as fresh news.
        int match = menu.getMatchId();
        if (match != sealsMatch) {
            sealsMatch = match;
            for (int i = 0; i < AvalonRole.QUESTS; i++) {
                int mission = menu.getMission(i);
                sealed[i] = mission;
                sealStartMs[i] = mission == 0 ? -1L : 0L;
            }
        }
        int[] card = taskCardBox();
        float side = card[2] * SEAL_SIZE;
        long now = Util.getMillis();
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            int mission = menu.getMission(i);
            if (mission != sealed[i]) {
                sealed[i] = mission;
                long start = mission == 0 ? -1L : now;
                // The just-resolved quest is the one the reveal is playing under; hold its stamp until the
                // cards have gathered, shuffled and turned over, so the seal lands on the finished spread
                // rather than on top of a face-down row.
                if (mission != 0 && revealStartMs >= 0L && i == menu.getQuestNumber()) {
                    start = revealStartMs + REVEAL_MS;
                }
                sealStartMs[i] = start;
            }
            if (mission == 0 || sealStartMs[i] < 0L || now < sealStartMs[i]) {
                continue;
            }
            float t = Mth.clamp((now - sealStartMs[i]) / (float) SEAL_STAMP_MS, 0f, 1f);
            float settle = 1f - (1f - t) * (1f - t) * (1f - t);
            float scale = Mth.lerp(settle, 2.2f, 1f);
            float alpha = Mth.clamp(t * 3f, 0f, 1f);
            float cx = card[0] + card[2] * AvalonLayout.questSlotX(i);
            float cy = card[1] + card[3] * SEAL_SLOT_Y;
            blitSeal(guiGraphics, mission == 1 ? SEAL_FINISH : SEAL_FAIL,
                    cx - side * scale / 2f, cy - side * scale / 2f, side * scale, alpha);
        }
    }

    /** Draws a seal centred on its slot, scaled to {@code size} and tinted to {@code alpha}. */
    private void blitSeal(GuiGraphics guiGraphics, ResourceLocation texture, float x, float y,
                          float size, float alpha) {
        int px = Mth.floor(x);
        int py = Mth.floor(y);
        int s = Math.max(1, Math.round(size));
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x - px, y - py, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        // The seal is an immediate blit: it carries its own alpha ramp (the stamp fades in) and its art
        // has soft edges, so it needs blending turned on explicitly. See UiBlend.
        UiBlend.on();
        guiGraphics.blit(texture, px, py, s, s, 0f, 0f, SEAL_SRC, SEAL_SRC, SEAL_SRC, SEAL_SRC);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        guiGraphics.pose().popPose();
    }

    /**
     * The designated-quest picker: the five slots on the board lit as the choices they are.
     *
     * <p>During a proposal the local leader sees every open slot breathe; the choice itself is solid
     * amber and stays lit through the vote and the quest so the whole table can see which slot is being
     * attempted. A slot that is unplayed but not open -- the fifth quest before two successes -- is
     * darkened rather than hidden, because "you cannot pick this yet" is information the board should
     * carry. Completed slots are left to their seals.
     *
     * <p>Drawn with {@link #washMany} rather than {@code fill}: the board is an immediate blit, and a
     * queued fill can be flushed after the hands and land on top of them. The whole point of the picker is
     * that it sits on the board.
     */
    private void renderQuestPick(GuiGraphics guiGraphics) {
        if (!menu.isDesignatedQuests()) {
            return;
        }
        AvalonGame.Phase phase = menu.getPhase();
        if (phase != AvalonGame.Phase.PROPOSE && phase != AvalonGame.Phase.VOTE
                && phase != AvalonGame.Phase.QUEST) {
            return;
        }
        boolean picking = phase == AvalonGame.Phase.PROPOSE && menu.isLocalTurn();
        int open = openQuests();
        int chosen = chosenQuest();

        List<int[]> chosenFill = new ArrayList<>();
        List<int[]> chosenEdge = new ArrayList<>();
        List<int[]> openFill = new ArrayList<>();
        List<int[]> openEdge = new ArrayList<>();
        List<int[]> lockedFill = new ArrayList<>();
        List<int[]> lockedEdge = new ArrayList<>();

        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (menu.getMission(i) != 0) {
                continue;
            }
            int[] box = questSlotBox(i);
            boolean isChosen = i == chosen;
            boolean isOpen = (open & (1 << i)) != 0;
            if (isChosen) {
                chosenFill.add(box);
                addBorder(chosenEdge, box, 2);
            } else if (picking && isOpen) {
                openFill.add(box);
                addBorder(openEdge, box, 1);
            } else if (picking) {
                lockedFill.add(box);
                addBorder(lockedEdge, box, 1);
            }
        }

        if (!openFill.isEmpty()) {
            int alpha = 0x18 + ((pulseAlpha() - 0x30) >> 1);
            washMany(guiGraphics, openFill, (alpha << 24) | 0xFFE97F);
            washMany(guiGraphics, openEdge, (pulseAlpha() << 24) | 0xFFE97F);
        }
        if (!chosenFill.isEmpty()) {
            washMany(guiGraphics, chosenFill, 0x38FFE97F);
            washMany(guiGraphics, chosenEdge, 0xFFFFE97F);
        }
        if (!lockedFill.isEmpty()) {
            washMany(guiGraphics, lockedFill, 0x50000000);
            washMany(guiGraphics, lockedEdge, 0x60FFFFFF);
        }
    }

    /** The four thin rectangles that outline {@code box}; appended to {@code out}. */
    private static void addBorder(List<int[]> out, int[] box, int thickness) {
        int x = box[0];
        int y = box[1];
        int w = box[2];
        int h = box[3];
        int t = Math.max(1, thickness);
        out.add(new int[]{x, y, w, t});
        out.add(new int[]{x, y + h - t, w, t});
        out.add(new int[]{x, y + t, t, h - t * 2});
        out.add(new int[]{x + w - t, y + t, t, h - t * 2});
    }

    /**
     * {@link #wash} for several boxes of one colour, landed as one immediate batch.
     *
     * <p>Five slots' worth of fill and outline would otherwise be ten flushes a frame, and each flush is
     * a draw call. One buffer keeps the picker cheap while preserving the ordering that made
     * {@link #wash} necessary in the first place.
     */
    private static void washMany(GuiGraphics guiGraphics, List<int[]> boxes, int argb) {
        if (boxes.isEmpty()) {
            return;
        }
        guiGraphics.flush();
        float alpha = (argb >>> 24) / 255f;
        float red = ((argb >> 16) & 0xFF) / 255f;
        float green = ((argb >> 8) & 0xFF) / 255f;
        float blue = (argb & 0xFF) / 255f;
        UiBlend.on();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f pose = guiGraphics.pose().last().pose();
        BufferBuilder buffer =
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (int[] box : boxes) {
            float x1 = box[0];
            float y1 = box[1];
            float x2 = box[0] + box[2];
            float y2 = box[1] + box[3];
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            any = true;
            buffer.addVertex(pose, x1, y1, 0f).setColor(red, green, blue, alpha);
            buffer.addVertex(pose, x1, y2, 0f).setColor(red, green, blue, alpha);
            buffer.addVertex(pose, x2, y2, 0f).setColor(red, green, blue, alpha);
            buffer.addVertex(pose, x2, y1, 0f).setColor(red, green, blue, alpha);
        }
        if (!any) {
            return;
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /**
     * The rejection counter's marker: an iron nugget resting on the notch that matches the run of
     * consecutive rejections, sliding in from off the board on the first one, from notch to notch on
     * each later one, and flying back off the board when an approved team clears the counter.
     *
     * <p>The notch positions are read off the board art as fractions, so the nugget lands on the drawn
     * numerals instead of on a second set of our own.
     *
     * <p>The travel is a plain horizontal slide with a smoothstep on it -- accelerating out of the old
     * notch and easing into the new one. It deliberately does <em>not</em> arc: the notches are all on
     * one line, so a hop reads as the nugget jumping over the counter rather than moving along it.
     */
    private void renderRejectNugget(GuiGraphics guiGraphics) {
        int rejects = menu.getRejects();
        long elapsed = Util.getMillis() - nuggetFlightStart;
        if (nuggetFlying && elapsed >= NUGGET_FLIGHT_MS) {
            nuggetFlying = false;
        }
        if (!nuggetFlying && rejects <= 0) {
            return;
        }

        float t = nuggetFlying ? ease(elapsed, 0f, NUGGET_FLIGHT_MS) : 1f;
        int fromIndex = nuggetFlying ? nuggetFrom : rejects - 1;
        int toIndex = nuggetFlying ? nuggetTo : rejects - 1;

        float x = Mth.lerp(t, notchCentre(fromIndex)[0], notchCentre(toIndex)[0]);
        float y = notchCentre(toIndex)[1];

        blitExact(guiGraphics, NUGGET, x - NUGGET_W / 2f, y - NUGGET_H / 2f, NUGGET_W, NUGGET_H, 1f);
    }

    /** Screen centre of rejection notch {@code index}, or a spot off the board's left for {@code -1}. */
    private float[] notchCentre(int index) {
        int[] card = taskCardBox();
        if (index < 0) {
            return new float[]{card[0] - NUGGET_W * 1.5f, card[1] + card[3] * REJECT_NOTCH_Y};
        }
        int notch = Mth.clamp(index, 0, REJECT_NOTCH_X.length - 1);
        return new float[]{card[0] + card[2] * REJECT_NOTCH_X[notch], card[1] + card[3] * REJECT_NOTCH_Y};
    }

    /** Smooth 0..1 ramp for one leg of an animation, {@code delayMs} after it started. */
    private static float ease(long elapsed, float delayMs, float durationMs) {
        float t = Mth.clamp((elapsed - delayMs) / durationMs, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Alpha of the lit zone's amber tint, breathing between {@code 0x30} and {@code 0x88}. */
    private static int pulseAlpha() {
        return (int) (0x30 + 0x58 * (0.5 + 0.5 * Math.sin(Util.getMillis() / 420.0)));
    }

    /**
     * The board art to draw: the one for this table's size if the pack has drawn it, else the general one.
     *
     * <p>The printed board carries that table's five quest sizes, so a size whose board has been drawn gets
     * its own file ({@code task_card_6.png}, the size it belongs to) and the numbers on the table's board
     * are then the numbers the table is playing. A size with no art of its own keeps the general board —
     * which is also what every size used before the pack started drawing them.
     *
     * <p>Resolved once per (size, language) and kept: this is asked on every frame of the table, and the
     * resolution is a resource-manager lookup, i.e. a file probe in the render loop. The language is the
     * second axis because the pack ships two boards, and it can change under a running screen.
     */
    private ResourceLocation taskCardTexture() {
        String language = minecraft == null ? "zh_cn" : minecraft.getLanguageManager().getSelected();
        boolean chinese = language.startsWith("zh");
        int seats = menu.getGame().getPlayers().size();
        if (taskCardResolved != null && seats == taskCardSeats && chinese == taskCardChinese) {
            return taskCardResolved;
        }
        taskCardSeats = seats;
        taskCardChinese = chinese;
        taskCardResolved = resolveTaskCard(chinese, seats);
        return taskCardResolved;
    }

    /** The most specific board for {@code seats} that the pack actually ships, or the general one. */
    private static ResourceLocation resolveTaskCard(boolean chinese, int seats) {
        ResourceLocation sized = ResourceLocation.fromNamespaceAndPath("myavalon",
                "textures/gui/task_card_" + seats + (chinese ? "" : "_en_us") + ".png");
        if (hasTexture(sized)) {
            return sized;
        }
        return chinese ? TASK_CARD : TASK_CARD_EN;
    }

    /** Whether a texture file is in the loaded packs. Probes the file, never binds it. */
    private static boolean hasTexture(ResourceLocation id) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        Optional<Resource> resource = client.getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            return false;
        }
        try (InputStream in = resource.get().open()) {
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /** What {@link #taskCardTexture} settled on, and the size/language it settled it for. */
    @Nullable
    private ResourceLocation taskCardResolved;
    private int taskCardSeats = -1;
    private boolean taskCardChinese;

    /** The quest card's two language variants; its size lives in {@code AvalonLayout.TASK_CARD}. */
    private static final ResourceLocation TASK_CARD =
            ResourceLocation.fromNamespaceAndPath("myavalon", "textures/gui/task_card.png");
    private static final ResourceLocation TASK_CARD_EN =
            ResourceLocation.fromNamespaceAndPath("myavalon", "textures/gui/task_card_en_us.png");

    /** The seal row's height on the board; the slot centres come from {@link AvalonLayout#questSlotX}. */
    private static final float SEAL_SLOT_Y = 0.38305f;
    /**
     * Seal side, as a fraction of the card's width. Sized off the slot (81x113, i.e. 0.0559 of the card
     * width tall) times 1.6, because the seal has to <em>cover</em> the printed "quest N" numeral under
     * it, and the seal art's own transparent corners mean its opaque disc is only about 0.85 of the box.
     * The corners reach about 0.85 * 113/2 = 48px from the slot centre on the diagonal; 0.0895 * 2020 *
     * 0.425 = 77px of opaque radius clears that, so no corner of the numeral pokes out.
     */
    private static final float SEAL_SIZE = 113f / 2020f * 1.6f;
    /** The seals are shipped 320x320; the blit samples that square and scales it to the slot. */
    private static final int SEAL_SRC = 320;
    /** How long a seal spends being pressed on: it drops in oversized and settles. */
    private static final long SEAL_STAMP_MS = 220;

    private static final ResourceLocation SEAL_FINISH = gui("task_finish");
    private static final ResourceLocation SEAL_FAIL = gui("task_unfinish");

    /** Per slot: the outcome already sealed, and when its stamp-in began ({@code -1} while unsealed). */
    private final int[] sealed = new int[AvalonRole.QUESTS];
    private final long[] sealStartMs = new long[AvalonRole.QUESTS];
    /** The match whose existing seals this screen has already primed; see {@link #renderQuestSeals}. */
    private int sealsMatch = -1;

    /** Face-down role cards, drawn at the same size the pile uses. */
    /**
     * Size a dealt card leaves the middle stack at: the pile card's own size, which is also what the
     * face-down stack in the middle is drawn at, so a card does not change size the moment it launches.
     */
    private static final int DEAL_CARD_W = PILE_CARD_W;
    private static final int DEAL_CARD_H = PILE_CARD_H;

    /**
     * Size of one card resting in another seat's hand: a third of a full card, which is what Charta's
     * {@code PREVIEW} widget draws its children at, and what makes a five-card row fit inside a chip's
     * forty-one pixels. The point of aiming a dealt card at this size is that the last frame of its flight
     * is already the size it will keep -- a full-size card blinking out and a third-size one blinking in
     * was the visible seam.
     */
    private static final int HAND_CARD_W = 13;
    private static final int HAND_CARD_H = 18;
    /** Size of one card resting in the local hand, whose fan is still drawn at full card size. */
    private static final int SELF_CARD_W = 38;
    private static final int SELF_CARD_H = 53;

    /**
     * Tick the deal started on, in wall-clock milliseconds, or -1 while the table is not dealing.
     *
     * <p>Milliseconds rather than {@code gui.getGuiTicks()} because of what {@code partialTick} actually
     * is: {@code GameRenderer} hands a screen {@code DeltaTracker.getRealtimeDeltaTicks()}, which is
     * <em>this frame's</em> length in ticks ({@code frameMs / 50} -- about 0.33 at 60 fps, 0.14 at 144),
     * <em>not</em> the 0..1 fraction of the way through the current tick. Adding it to an integer tick
     * count therefore gives a staircase: within one tick the value is constant, then it steps by one, so
     * a card in flight would hold the same pixel for three frames and then jump -- a 20 Hz animation
     * whatever the frame rate. Timing off the wall clock is continuous, and at 20 tps one tick is exactly
     * fifty milliseconds, so the tick-denominated constants below are simply divided by 50.
     */
    private long dealStartMs = -1L;

    /**
     * The deal whose animation has already started on this client.
     *
     * <p>Static for the same reason the identity curtain's flag is: closing and reopening the table
     * rebuilds the screen, and a per-screen value would make the deal fly again from an empty middle
     * stack. A new match has a new id, so a rematch still animates.
     */
    private static int dealShownMatch = -1;

    /**
     * Cards whose body is already on the main buffer but whose halo has not been painted yet.
     *
     * <p>A halo costs two render-target switches (bind the glow buffer, bind the main one back), and a
     * deal throws two dozen cards through the air at once — so switching per card means fifty buffer
     * binds a frame. Queuing them lets {@link #flushCardGlow} bind each target once and run the whole
     * batch, which is the difference between a stutter and a smooth sixty.
     */
    private final List<GlowCard> glowQueue = new ArrayList<>();

    /** One queued halo: everything {@link #flushCardGlow} needs to replay a card's glow pass later. */
    private record GlowCard(ResourceLocation texture, ResourceLocation glow, float x, float y,
                            float width, float height, float spin, float tilt,
                            float inset, float xRot, float yRot) {
    }

    /**
     * The opening deal, animated: a stack of face-down role cards in the middle, each one flying out to a
     * seat's fan in turn until the stack is spent.
     *
     * <p>The server hands a seat its hand exactly when that seat's card is due to land — see
     * {@code AvalonGame.DEAL_SETTLE}/{@code DEAL_STEP} — so the arriving card and the fan filling up line up
     * without either side telling the other where it is. The only thing drawn here is the copy still in the
     * air; once a card has landed it is skipped and the real hand shows through.
     */
    private void renderDeal(GuiGraphics guiGraphics) {
        float elapsed = elapsedDeal();
        if (elapsed < 0f) {
            return;
        }
        // Wall-clock driven, in ticks: the logic runs at 20 Hz, the drawing does not. See dealStartMs.
        Deck deck = getDeck();
        ResourceLocation back = deck.getTexture(false);
        ResourceLocation backGlow = deck.getTexture(true);
        int count = menu.getGame().getPlayers().size();
        // Every landing spot for every seat, worked out once for the whole deal rather than once per frame:
        // the geometry cannot move while the cards are in the air, and this walks the ring and allocates a
        // row per seat. Keyed on the deal's own timestamp and the seat count, so the next deal and a table
        // that changes size both start clean.
        if (dealTargetsFor != dealStartMs || dealTargets == null || dealTargets.length != count) {
            dealTargetsFor = dealStartMs;
            dealTargets = handTargets(count);
        }
        float[][] targets = dealTargets;
        int identityEnd = AvalonGame.DEAL_SETTLE + count * AvalonGame.DEAL_STEP;

        // Act one: an identity card per seat, thrown out of the middle stack to the leftmost slot of that
        // seat's hand -- the position the card will keep, since the role is the hand's first card.
        for (int seat = count - 1; seat >= 0; seat--) {
            int land = AvalonGame.DEAL_SETTLE + seat * AvalonGame.DEAL_STEP;
            drawThrownCard(guiGraphics, back, backGlow, seat, land, targets[seat], 0, elapsed,
                    AvalonGame.DEAL_STEP - 1);
        }

        // Act two: the ability stack is on the table the moment the identities are out, and its cards leave
        // one by one after the pause. Nothing is drawn before then, so the two stacks never overlap.
        if (elapsed >= identityEnd) {
            int featureStart = identityEnd + AvalonGame.DEAL_PAUSE;
            int total = count * 4;
            for (int index = total - 1; index >= 0; index--) {
                int land = featureStart + (index + 1) * AvalonGame.DEAL_FEATURE_STEP;
                drawThrownCard(guiGraphics, back, backGlow, index + 64, land, targets[index / 4],
                        index % 4 + 1, elapsed, AvalonGame.DEAL_FEATURE_STEP - 1);
            }
        }
    }

    /** Where each seat's dealt cards aim, and the deal they were measured for. */
    @Nullable
    private float[][] dealTargets;
    private long dealTargetsFor = Long.MIN_VALUE;

    /**
     * Draws a dealt card that has not landed yet: parked in the middle stack, or in flight to its seat.
     *
     * <p>The card shrinks the whole way. It leaves the stack at a full pile card's size and arrives at the
     * size one card actually has in a hand — a third, now that a hand is a winnowed row inside a top-bar
     * chip — so the thing that lands and the thing that was always going to be there are the same size.
     * Without that the last frame of every flight is a full card blinking out and a small one blinking in.
     */
    private void drawThrownCard(GuiGraphics guiGraphics, ResourceLocation back, ResourceLocation backGlow,
                                int seed, int land, float[] target, int slot, float elapsed, int fly) {
        if (elapsed >= land) {
            return; // landed -- the seat's real hand is on screen now
        }
        // Airborne for one tick longer than the gap between two cards, so a card is in the air the whole
        // time and the table is never briefly still: the next one launches as the last one lands.
        int air = fly + 1;
        float fromX = width / 2f + stackJitter(seed, 37, 11);
        float fromY = height / 2f + stackJitter(seed, 53, 9);
        float x = fromX;
        float y = fromY;
        float cardW = DEAL_CARD_W;
        float cardH = DEAL_CARD_H;
        float spin = 0f;
        float tilt = 0f;
        int launch = land - air;
        if (elapsed > launch) {
            float progress = (elapsed - launch) / air;
            float toX = target[0] + slot * target[4];
            x = Mth.lerp(progress, fromX, toX);
            y = Mth.lerp(progress, fromY, target[1]);
            cardW = Mth.lerp(progress, (float) DEAL_CARD_W, target[2]);
            cardH = Mth.lerp(progress, (float) DEAL_CARD_H, target[3]);
            // Thrown across the table: it turns about its vertical axis and tips as it settles.
            spin = (float) Math.sin(Math.PI * progress) * 34f;
            tilt = (float) Math.sin(Math.PI * progress) * 14f;
        }
        // The very same draw a card in a fan gets, halo included, so a card in the air is indistinguishable
        // from one resting on the table rather than a flat pasted rectangle.
        blitChartaCard(guiGraphics, back, backGlow, x, y, cardW, cardH, spin, tilt);
    }

    private float elapsedDeal() {
        return dealStartMs < 0L ? -1f : (Util.getMillis() - dealStartMs) / 50f;
    }

    /** A small, stable offset per card so the middle reads as a stack and not a single flat pile. */
    private static float stackJitter(int seat, int multiplier, int modulus) {
        return (seat * multiplier % modulus - (modulus - 1) / 2f) * 2f;
    }

    /**
     * Where a card has to land for each seat, indexed by seat:
     * {@code x, y, width, height, step} — the centre of that hand's first card, that card's size, and the
     * pitch to the next one.
     *
     * <p>Both kinds of hand are read back out of Charta's own layout arithmetic rather than measured off
     * the widgets, because a landing card needs its aim on the frame it lands, before the widget has laid
     * anything out. The two are genuinely different: another seat's hand is a winnowed {@code PREVIEW} row
     * of third-size cards inside a chip, while the local one is a {@code HORIZONTAL} fan of full-size
     * cards, bottom-aligned in its box.
     */
    private float[][] handTargets(int count) {
        float[][] targets = new float[count][];
        int[] ring = this.ring;
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            if (seat < 0 || seat >= count) {
                continue;
            }
            boolean local = r == 0;
            AvalonLayout.Element element = local
                    ? AvalonLayout.Element.SELF_HAND : AvalonLayout.Element.otherHand(r - 1);
            int[] box = LAYOUT.bounds(element, width, height);
            if (local) {
                // A HORIZONTAL hand hangs its cards off the bottom of its box, and spreads them across it
                // at the widget's own maximum pitch, centred.
                float step = SELF_CARD_W * 1.1f;
                float row = SELF_CARD_W + step * 4;
                targets[seat] = new float[]{box[0] + (box[2] - row) / 2 + SELF_CARD_W / 2f,
                        box[1] + box[3] - SELF_CARD_H / 2f, SELF_CARD_W, SELF_CARD_H, step};
            } else {
                // A PREVIEW hand packs its row into the box, so the pitch is whatever is left over
                // divided by the gaps; with five cards in forty-one pixels that is seven.
                float step = (box[2] - HAND_CARD_W) / 4f;
                targets[seat] = new float[]{box[0] + HAND_CARD_W / 2f, box[1] + box[3] / 2f,
                        HAND_CARD_W, HAND_CARD_H, step};
            }
        }
        for (int seat = 0; seat < count; seat++) {
            if (targets[seat] == null) {
                targets[seat] = new float[]{width / 2f, height / 2f, HAND_CARD_W, HAND_CARD_H, 0f};
            }
        }
        return targets;
    }

    // ------------------------------------------------------------------- pile ---

    /** Reused by {@link #renderPile} so a draw does not allocate a list to read the pile into. */
    private final List<Card> pileScratch = new ArrayList<>();

    /**
     * The pile: every vote and quest card the table has played, fanned out in the middle.
     *
     * <p>The pile's own slot reports itself empty (see {@code AvalonGame.PileSlot}) because a slot draws
     * its contents at a single point and would stack ten cards on the same pixel; the slot keeps working
     * as the drop target because the hit-test is against its declared box, which is this whole rectangle.
     * So the ring of cards is a picture and the box is the button.
     */
    private void renderPile(GuiGraphics guiGraphics) {
        int[] box = LAYOUT.bounds(AvalonLayout.Element.PILE, width, height);
        AvalonGame.Phase phase = menu.getPhase();
        boolean proposing = phase == AvalonGame.Phase.PROPOSE && menu.isGameReadyAndRunning();
        boolean myDrop = menu.isLocalPending() && menu.getGame().isCardPhase() && menu.isGameReadyAndRunning();

        guiGraphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], CELL_BG);
        // While the spare flags are up the pile is no longer a drop target -- the flags are -- so its own
        // amber "now" outline steps aside and leaves the frame neutral.
        frame(guiGraphics, box[0], box[1], box[2], box[3], myDrop ? ACTIVE : OUTLINE);
        if (myDrop) {
            guiGraphics.fill(box[0] + 1, box[1] + 1, box[0] + box[2] - 1, box[1] + 2, ACTIVE);
        }
        if (identityFurnitureHidden()) {
            // The bare frame, and neither the spare markers nor the "pile" caption: both of those are the
            // selection stage announcing itself, and it has not opened yet. See identityFurnitureHidden.
            return;
        }

        if (proposing) {
            // The spare team flags wait here; the leader clicks one to lift it and clicks a seat to place
            // it. No pile cards exist yet, so this replaces the row entirely.
            renderFreeFlags(guiGraphics, box);
            return;
        }

        // A copy of the pile, because the loop below reads it by index over a count it fixes first; the list
        // is a field rather than a fresh one so a full table does not allocate one per frame.
        List<Card> cards = pileScratch;
        cards.clear();
        for (Card card : menu.getPileCards().getCards()) {
            cards.add(card);
        }
        if (cards.isEmpty()) {
            // The pile's own name used to sit here, over the card back in the middle of the frame. The back
            // is the label -- it is the one empty rectangle on the table, and its caption said nothing a
            // player had not already understood by looking at it. The drop hint stays: that one is an
            // instruction and only appears while a card is actually waiting to be placed.
            if (!myDrop) {
                return;
            }
            Component hint = Component.translatable("status.myavalon.drop_here");
            guiGraphics.drawString(font, hint,
                    box[0] + (box[2] - font.width(hint)) / 2, box[1] + (box[3] - 8) / 2, ACTIVE, true);
            return;
        }

        int count = cards.size();
        // A quest row is spread wider than a vote row. Both are capped by the pile's width, but a quest has
        // at most five cards in it and those five are what the whole table is about to read: at the vote's
        // pitch they overlap by a third and the leftmost numeral of each is all that shows. The reveal ends
        // by returning every card to this row, so this is the pitch they are read at.
        int maxStep = phase == AvalonGame.Phase.QUEST ? PILE_CARD_W + PILE_QUEST_GAP : PILE_MAX_STEP;
        int step = count <= 1 ? 0 : Math.min(maxStep, (box[2] - PILE_CARD_W) / (count - 1));
        int rowWidth = PILE_CARD_W + step * (count - 1);
        int left = box[0] + (box[2] - rowWidth) / 2;
        int top = box[1] + (box[3] - PILE_CARD_H) / 2;

        Deck deck = getDeck();
        long reveal = revealStartMs < 0L ? -1L : Util.getMillis() - revealStartMs;
        for (int i = 0; i < count; i++) {
            int[] slot = new int[]{left + i * step, top, PILE_CARD_W, PILE_CARD_H};
            if (phase == AvalonGame.Phase.QUEST) {
                // A quest card is played face-down and only turns over in the reveal, so nobody reads the
                // table left-to-right as the cards land.
                drawQuestCard(guiGraphics, cards.get(i), deck, slot, i, count, box, step, reveal);
            } else {
                drawCard(guiGraphics, cards.get(i), deck, slot);
            }
        }
    }

    /**
     * The spare team flags, in a row on the pile, while a proposal is being built — three times their
     * placed size, so the thing the leader drags is the thing the eye lands on.
     *
     * <p>One fewer than the team size for every seat already picked, and one fewer again for the flag
     * riding the pointer — so what is on the pile is exactly what is still unplaced. The flag under the
     * pointer is painted a second time, after the row, so the lifted card is never behind its neighbours.
     */
    private void renderFreeFlags(GuiGraphics guiGraphics, int[] box) {
        int free = freeFlagCount();
        if (free <= 0) {
            return;
        }
        int[] row = freeFlagRow(free, box);
        for (int i = 0; i < free; i++) {
            blitFlag(guiGraphics, row[0] + i * row[3], row[1], FREE_FLAG_SCALE);
        }
        if (hoverFlagSpare >= 0 && hoverFlagSpare < free) {
            blitFlag(guiGraphics, row[0] + hoverFlagSpare * row[3], row[1], FREE_FLAG_SCALE, flagHoverTilt());
        }
        Component hint = Component.translatable("status.myavalon.drag_flags");
        guiGraphics.drawString(font, hint, box[0] + (box[2] - font.width(hint)) / 2,
                box[1] - 12, DIM, true);
    }

    /**
     * The spare flags' row, as {@code left, top, size, step}: the box one flag occupies and the pitch to
     * the next. Shared by the painting and the hit-test so a click lands on the flag it was aimed at.
     */
    private int[] freeFlagRow(int free, int[] box) {
        int size = Math.round(FLAG_W * FREE_FLAG_SCALE);
        int step = size + 2;
        int rowWidth = size + step * (free - 1);
        return new int[]{box[0] + (box[2] - rowWidth) / 2, box[1] + (box[3] - size) / 2, size, step};
    }

    /** Unplaced flags: team size, minus the seats already picked, minus the one in hand. */
    private int freeFlagCount() {
        int placed = Integer.bitCount(pendingMask());
        return Math.max(0, localTeamSize() - placed - (carryingFlag ? 1 : 0));
    }

    /**
     * Paints one quest card through the reveal: dealt face-down into the row, cut — cards trading places
     * with each other — then gathered, held, spread back out into their <em>new</em> order, and turned over.
     *
     * <h2>Why it opens with a cut</h2>
     *
     * <p>A shuffle that only converges and separates never actually rearranges anything: the cards come
     * back out in the order they went in, and the middle of the act reads as a bounce rather than as a
     * shuffle. So the first thing the act does is swap cards with each other, a few times, and their
     * resting places for the rest of the act are where those swaps put them — the row comes back in a
     * different order than it left, which is the whole point of having shown a shuffle at all.
     *
     * <p>The schedule is drawn once from a {@code Random} seeded on {@link #revealStartMs}, so it is fixed
     * for the length of one reveal (no card changing its mind mid-flight) and different for the next.
     *
     * <p>The whole act is driven off {@link #revealStartMs}, armed on the client on the wall clock, so
     * nothing about it has to be synced. The card turns through {@code spin} degrees about the vertical
     * axis and swaps to its face texture past ninety, which is the frame its back has rotated edge-on.
     */
    private void drawQuestCard(GuiGraphics guiGraphics, Card card, Deck deck, int[] slot,
                               int index, int count, int[] pile, int step, long reveal) {
        float rowY = slot[1] + slot[3] / 2f;
        float cx = slot[0] + slot[2] / 2f;
        float cy = rowY;
        float spin = 0f;
        boolean faceUp = false;

        // Where this card sits once the cut is over: where it rests, where it re-emerges from the gather,
        // and -- once the act is over but the played cards are still being read -- where it keeps sitting.
        //
        // Not before the act, though. A row that is still filling up is in the order it was dealt, and the
        // cut starts from that order; drawing the landed cards pre-permuted would make them jump back the
        // moment the last one landed and the act began.
        boolean cut = reveal >= 0L || revealFinished;
        float restX = cut
                ? slot[0] + (revealStages(count)[REVEAL_SWAPS][index] - index) * step + slot[2] / 2f
                : slot[0] + slot[2] / 2f;
        cx = restX;

        if (reveal >= 0L) {
            int[][] stages = revealStages(count);
            float centreX = pile[0] + pile[2] / 2f;
            // Left of centre jitters to the right and right of centre to the left, so the gather reads as
            // the two halves grinding toward each other; the same run carries them all the way in.
            float side = Math.signum(index - (count - 1) / 2f);

            if (reveal < REVEAL_GATHER_AT) {
                // The cut: this card trades places with another, a few times over. Only the x moves -- a
                // swap is a slide along the row, and letting them also change rows would read as a scatter.
                float perSwap = REVEAL_SWAP_MS / (float) REVEAL_SWAPS;
                int k = Mth.clamp((int) (reveal / perSwap), 0, REVEAL_SWAPS - 1);
                float t = smooth(Mth.clamp(reveal / perSwap - k, 0f, 1f));
                float fromX = slot[0] + (stages[k][index] - index) * step + slot[2] / 2f;
                float toX = slot[0] + (stages[k + 1][index] - index) * step + slot[2] / 2f;
                cx = Mth.lerp(t, fromX, toX);
            } else if (reveal < REVEAL_HOLD_AT) {
                // No stagger on the way in: the cards are meant to end up exactly on top of each other, as
                // one square of card, not fanned a few pixels apart. The wave is the only thing moving here.
                float t = smooth((reveal - REVEAL_GATHER_AT) / (float) REVEAL_GATHER_MS);
                float wave = (float) Math.sin(t * Math.PI * 5f) * 6f;
                cx = Mth.lerp(t, restX, centreX) - side * wave;
            } else if (reveal < REVEAL_SPREAD_AT) {
                // Gathered and holding: the bunch sits as one card in the middle, cut spent, for the pause.
                cx = centreX;
            } else if (reveal < REVEAL_FLIP_AT) {
                float t = (reveal - REVEAL_SPREAD_AT) / (float) REVEAL_SPREAD_MS;
                cx = Mth.lerp(smooth(t), centreX, restX);
            } else {
                float t = Mth.clamp((reveal - REVEAL_FLIP_AT) / (float) REVEAL_FLIP_MS, 0f, 1f);
                spin = 180f * smooth(t);
                faceUp = spin > 90f;
            }
        }

        ResourceLocation texture = faceUp ? deck.getCardTexture(card, false) : deck.getTexture(false);
        ResourceLocation glow = faceUp ? deck.getCardTexture(card, true) : deck.getTexture(true);
        blitChartaCard(guiGraphics, texture, glow, cx, cy, slot[2], slot[3], spin, 0f);
    }

    /**
     * Where each card sits after {@code k} of the reveal's swaps, indexed {@code [k][card]}.
     *
     * <p>Precomputed for the whole act rather than reasoned out per frame, so a card's flight is one look-up
     * and cannot change course: the drawing only ever interpolates between two consecutive stages.
     */
    private int[][] revealStages(int count) {
        if (revealStages != null && revealStagesFor == revealSerial && revealStages[0].length == count) {
            return revealStages;
        }
        revealStagesFor = revealSerial;
        Random random = new Random(revealSerial * 2654435761L);
        int[][] stages = new int[REVEAL_SWAPS + 1][count];
        for (int i = 0; i < count; i++) {
            stages[0][i] = i;
        }
        for (int k = 1; k <= REVEAL_SWAPS; k++) {
            System.arraycopy(stages[k - 1], 0, stages[k], 0, count);
            if (count < 2) {
                continue;
            }
            // A round pairs the whole row up at random and swaps each pair, rather than swapping one random
            // pair per round. One pair a round moves two cards and leaves the rest of the row still, which
            // reads as a nudge; pairing everything moves every card, which reads as a cut. The shuffle of
            // who pairs with whom is what makes each round land differently.
            int[] order = shuffled(count, random);
            for (int i = 0; i + 1 < count; i += 2) {
                int a = order[i];
                int b = order[i + 1];
                int held = stages[k][a];
                stages[k][a] = stages[k][b];
                stages[k][b] = held;
            }
            if ((count & 1) != 0) {
                // An odd row leaves one card without a partner, and a round that moves every card except one
                // is a round that card sat out -- which, over three rounds, is visible. It trades with a
                // random card instead, so nothing stands still and the pair it borrows simply moves twice.
                int spare = order[count - 1];
                int with = order[random.nextInt(count - 1)];
                int held = stages[k][spare];
                stages[k][spare] = stages[k][with];
                stages[k][with] = held;
            }
        }
        revealStages = stages;
        return stages;
    }

    /** {@code 0..count-1} in a random order, into a fresh array. */
    private static int[] shuffled(int count, Random random) {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        for (int i = count - 1; i > 0; i--) {
            int at = random.nextInt(i + 1);
            int held = order[i];
            order[i] = order[at];
            order[at] = held;
        }
        return order;
    }

    /** The cut's schedule for the reveal in progress; rebuilt when a new one starts. */
    @Nullable
    private int[][] revealStages;
    private long revealStagesFor = Long.MIN_VALUE;
    /** Counts reveals, so the cut outlives the timestamp the way it has to; see {@link #updateReveal}. */
    private long revealSerial;

    /**
     * The quest whose reveal has already been armed on this client: match id in the high half, quest
     * slot in the low half.
     *
     * <p>Static because the screen is rebuilt on every close/reopen; without it, reopening during a quest
     * reveal shuffled the same row again from the start. A new quest, or the same slot in a new match,
     * produces a different key and gets its own act.
     */
    private static long revealShownKey = Long.MIN_VALUE;

    private long revealKey() {
        return ((long) menu.getMatchId() << 32) ^ (menu.getQuestNumber() & 0xFFFFFFFFL);
    }

    /**
     * Arms the reveal the frame the whole team has played, and clears it the moment the phase leaves
     * {@code QUEST}. Read before the seals so a divided draw starts the stamp window instead of stamping
     * over the animation.
     *
     * <p>Arming also bumps {@link #revealSerial}, and that — not the timestamp — is what the cut is keyed
     * on. The timestamp is cleared the moment the reveal ends, and the cards are still on the table
     * face-up for a while after that: keying the cut on it would snap the row back into its dealt order on
     * the frame the phase moved on, undoing the shuffle in front of everybody.
     */
    private void updateReveal() {
        boolean revealing = menu.getPhase() == AvalonGame.Phase.QUEST
                && menu.isGameReadyAndRunning()
                && menu.getTeamSize() > 0
                && Integer.bitCount(menu.getQuestMask()) >= menu.getTeamSize();
        if (revealing) {
            if (revealStartMs < 0L) {
                if (menu.getMatchId() <= 0) {
                    return; // the deal id has not reached the mirror yet
                }
                long key = revealKey();
                if (revealShownKey == key) {
                    // This quest's reveal already played on a screen that has since been closed. Put the
                    // clock past the act instead of running it again: every card draws at its rest
                    // position, face-up, which is exactly the frame the act ends on.
                    revealStartMs = Util.getMillis() - REVEAL_MS;
                    revealFinished = true;
                } else {
                    revealShownKey = key;
                    revealStartMs = Util.getMillis();
                    revealSerial++;
                    revealFinished = false;
                }
            }
            revealFinished = revealFinished || Util.getMillis() - revealStartMs >= REVEAL_MS;
        } else if (revealStartMs >= 0L && Util.getMillis() - revealStartMs < REVEAL_MS) {
            // The server moved the table on while the act was still playing. Whatever is left -- the flip,
            // usually, since it is the last leg -- runs to its end anyway: the cards are still on the pile
            // (the sweep only happens when the linger expires), so there is nothing to gain by cutting it
            // and a face-down row that was rearranged but never turned over to lose.
            revealFinished = false;
        } else {
            revealStartMs = -1L;
        }
        // The pile is swept between quests, and the row that fills up next is in the order it is dealt: the
        // cut belongs to the row it shuffled and should not still be rearranging the next one's landing.
        if (menu.getPileCards().isEmpty()) {
            revealFinished = false;
        }
    }

    /** Whether the act has run its course for the row currently on the table; see {@link #updateReveal}. */
    private boolean revealFinished;

    /** Smoothstep 0..1. */
    private static float smooth(float t) {
        float c = Mth.clamp(t, 0f, 1f);
        return c * c * (3f - 2f * c);
    }

    /**
     * Paints one card into a box the way an unhovered {@code AbstractCardWidget} would.
     *
     * <p>Charta's cards are not plain blits: a vertex shader fakes a camera perspective on the quad, so
     * the widget hands it a box a third larger than the card and drives it with four shader uniforms.
     * Reproducing those here rather than blitting the texture straight in is what makes a played card
     * the same size and shape as one still in a fan — and the uniforms have to be reset first, because
     * the last thing to touch them was a card being hovered, which lifts and tilts.
     */
    private void drawCard(GuiGraphics guiGraphics, Card card, Deck deck, int[] box) {
        ResourceLocation texture = card.flipped()
                ? deck.getTexture(false)
                : deck.getCardTexture(card, false);
        ResourceLocation glow = card.flipped()
                ? deck.getTexture(true)
                : deck.getCardTexture(card, true);
        blitChartaCard(guiGraphics, texture, glow,
                box[0] + box[2] / 2f, box[1] + box[3] / 2f, box[2], box[3], 0f, 0f);
    }

    /**
     * Paints one card exactly the way a real {@code AbstractCardWidget} does: the card through Charta's
     * perspective shader, its silhouette into the glow buffer so {@code CardScreen.renderGlowBlur} can
     * blur a soft halo around it, and then the halo itself.
     *
     * <p>That three-pass dance is what makes a card read as a physical object lifted off the felt instead
     * of a flat sticker, and it is why a hand-drawn card has to run it too -- skip the glow buffer and the
     * card pops against the fan cards it is supposed to match. The quad is inflated by a third and the
     * shader maps it back down; {@code spin}/{@code tilt} are extra rotation about the vertical/horizontal
     * axes, used to make a thrown card turn through the air.
     *
     * <p>{@code inset}/{@code hoverXRot}/{@code hoverYRot} are the hover half of the same uniforms, which
     * is how a flag gets a card's hover lift without being a widget itself; they add to {@code spin} and
     * {@code tilt} so a card can be thrown <em>and</em> hovered at once.
     */
    private void blitChartaCard(GuiGraphics guiGraphics, ResourceLocation texture, ResourceLocation glow,
                                float centreX, float centreY, float cardWidth, float cardHeight,
                                float spin, float tilt) {
        blitChartaCard(guiGraphics, texture, glow, centreX, centreY, cardWidth, cardHeight,
                spin, tilt, 0f, 0f, 0f);
    }

    private void blitChartaCard(GuiGraphics guiGraphics, ResourceLocation texture, ResourceLocation glow,
                                float centreX, float centreY, float cardWidth, float cardHeight,
                                float spin, float tilt, float inset, float hoverXRot, float hoverYRot) {
        float xOffset = (cardWidth * 1.333333f - cardWidth) / 2f;
        float yOffset = (cardHeight * 1.333333f - cardHeight) / 2f;
        float x = centreX - cardWidth / 2f - xOffset;
        float y = centreY - cardHeight / 2f - yOffset;
        float width = cardWidth + xOffset * 2f;
        float height = cardHeight + yOffset * 2f;

        cardUniforms(inset, 30f, tilt + hoverXRot, spin + hoverYRot);
        // Pass one, onto the main buffer: the card itself. Passes two and three go to the glow buffer and
        // are deferred, so a fan of cards costs one buffer bind instead of one apiece.
        ChartaGuiGraphics.blitCard(guiGraphics, texture, x, y, width, height);
        glowQueue.add(new GlowCard(texture, glow, x, y, width, height, spin, tilt, inset, hoverXRot, hoverYRot));
    }

    /**
     * Runs the deferred half of {@link #blitChartaCard} for everything queued this frame — the silhouette
     * and halo of every card drawn since the last flush.
     *
     * <p>This is a straight batching of what a real {@code AbstractCardWidget} does one card at a time:
     * paint the card's shape into the glow buffer in black, then its glow texture over the top. Doing a
     * batch of them under a single buffer bind leaves the glow buffer byte-for-byte what the per-card
     * version would have left, since nothing else touches it between the cards. {@code
     * CardScreen.renderGlowBlur} blurs and composites it later in the same frame.
     */
    private void flushCardGlow(GuiGraphics guiGraphics) {
        if (glowQueue.isEmpty()) {
            return;
        }
        RenderTarget glow = ChartaModClient.getShaderManager().getGlowRenderTarget();
        glow.bindWrite(false);
        // Stated rather than left to {@code RenderTargetViewport}: this is the hot path, and the halo of
        // every card on the table depends on the glow buffer's own (smaller) viewport being in force.
        RenderSystem.viewport(0, 0, glow.width, glow.height);
        for (GlowCard card : glowQueue) {
            cardUniforms(card.inset(), 30f, card.tilt() + card.xRot(), card.spin() + card.yRot());
            RenderSystem.setShaderColor(0f, 0f, 0f, 1f);
            ChartaGuiGraphics.blitCard(guiGraphics, card.texture(), card.x(), card.y(),
                    card.width(), card.height());
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            ChartaGuiGraphics.blitCardGlow(guiGraphics, card.glow(), card.x(), card.y(),
                    card.width(), card.height());
        }
        // bindWrite(true) so the window viewport is restored along with the target; see the note above.
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        glowQueue.clear();
    }

    /** Drives the card shader's four uniforms; the halo pass reads them too, so it is repeated per card. */
    private void cardUniforms(float inset, float fov, float xRot, float yRot) {
        ChartaModClient.getShaderManager().getCardInset().accept(inset);
        ChartaModClient.getShaderManager().getCardFov().accept(fov);
        ChartaModClient.getShaderManager().getCardXRot().accept(xRot);
        ChartaModClient.getShaderManager().getCardYRot().accept(yRot);
    }

    // ------------------------------------------------------------------- seats ---

    /**
     * Seat ring plus every seat's plate text and plate box, measured once per frame.
     *
     * <h2>Why this is memoised, twice over</h2>
     *
     * <p>A plate's box is sized from its text ({@code font.width} of a name plus faction tag), and several
     * passes want it -- the plates themselves, the pick-a-team zones, the team flags and the crown anchor.
     * Each of those used to call {@code plateBox} again, and {@code plateBox} rebuilt the plate's
     * {@link Component} from scratch, so a full board measured the same names a dozen-plus times per frame
     * and threw away every intermediate.
     *
     * <p>Then the frame guard, because two passes now want the result: the top bar, which draws the names
     * and runs before {@code renderBg}, and everything inside {@code renderBg}. Nothing here changes inside
     * a frame -- the ring, the mirrored state and the frame size are all fixed until the next one.
     *
     * <p>The names themselves are rebuilt only when what they say changes, which is almost never: the
     * viewer's knowledge, the phase, and which seat is the viewer's. A {@link Component} resolves its own
     * translation when it is drawn, so caching one across a language change is safe.
     */
    private int[] ring = new int[0];
    private Component[] plateTexts = new Component[0];
    private int[][] plateBoxes = new int[0][];
    private long platesMeasuredAt = -1L;
    private int plateTextsKey = Integer.MIN_VALUE;

    private void measurePlates() {
        if (platesMeasuredAt == frameStamp) {
            return;
        }
        platesMeasuredAt = frameStamp;
        int[] seats = menu.seats();
        if (ring.length != seats.length) {
            ring = seats;
            plateTexts = new Component[seats.length];
            plateBoxes = new int[seats.length][];
            plateTextsKey = Integer.MIN_VALUE;
        } else {
            System.arraycopy(seats, 0, ring, 0, seats.length);
        }
        int key = menu.getKnownMask() * 31 + menu.getPhase().ordinal() * 7919 + menu.getLocalSeat();
        boolean textsStale = key != plateTextsKey;
        plateTextsKey = key;
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            Component text = plateTexts[r];
            if (textsStale || text == null) {
                text = plateText(menu.getGame().playerAt(seat), seat);
                plateTexts[r] = text;
            }
            plateBoxes[r] = plateBox(r, text);
        }
    }

    /** A name plate per seat, pinned under that seat's fan. */
    private void renderSeats(GuiGraphics guiGraphics) {
        for (int r = 0; r < ring.length; r++) {
            drawPlate(guiGraphics, r, ring[r]);
        }
    }

    /**
     * A seat's plate box, drawn.
     *
     * <p>The local seat's plate is not painted at all — it is a name chip over a fan, and once every other
     * seat became a top-bar chip with a head, a wool frame and a colour, a bare name floating over the local
     * hand was the odd one out: the viewer is the one person whose name they do not need telling. Its box
     * stays, and stays in the F9 editor, because it is still what the local marker and the crown are
     * anchored to.
     *
     * <p>Another seat's "plate" is its whole bar chip, and the bar has already drawn the part of it that
     * says the table is doing something with that seat: the chip's background breathes at the seat's colour
     * for a seat on turn, on the team or under the assassin's finger. What is left for this box is the one
     * marking the pulse cannot carry — the assassin's pick, in red, so that "about to be stabbed" does not
     * look like every other kind of attention.
     */
    private void drawPlate(GuiGraphics guiGraphics, int ringIndex, int seat) {
        if (ringIndex == 0) {
            return;
        }
        boolean chosen = menu.getPendingTarget() == seat && menu.getPhase() == AvalonGame.Phase.ASSASSIN;
        if (!chosen) {
            return;
        }
        int[] plate = plateBoxes[ringIndex];
        guiGraphics.fill(plate[0], plate[1], plate[0] + plate[2], plate[1] + 1, EVIL_COLOR);
    }

    /** The name plus whatever {@code seat} is publicly known to be — the text a plate is sized from. */
    private Component plateText(CardPlayer player, int seat) {
        boolean local = seat == menu.getLocalSeat();
        Component text = player.getName().copy();
        if (local) {
            text = text.copy().append(Component.translatable("status.myavalon.you_tag"));
        }
        AvalonRole revealed = menu.getRevealedRole(seat);
        // The viewer's own role, for every seat and not only their own: it is what decides how a seat they
        // were dealt knowledge of reads. Handing this only the local seat is why a Percival used to see the
        // Merlin he was told about tagged as evil -- the pair wording could never be reached.
        AvalonRole viewer = menu.getLocalRole();
        Component tag = factionTag(seat, local, revealed, viewer);
        if (tag != null) {
            text = text.copy().append(" ").append(tag);
        }
        return text;
    }

    /** Drawn rectangle of a seat's plate, {@code x, y, width, height} in the design frame, sized from {@code text}. */
    /**
     * Drawn rectangle of a seat's plate, {@code x, y, width, height} in the design frame.
     *
     * <p>Two different things now. The local seat still gets a name chip sized from its own text, hung
     * under the local hand and centred on the seat. Every other seat's "plate" is the whole top-bar chip,
     * so its box is simply the chip's box — Charta sizes and draws that one, and sizing it from a name
     * here would fight the bar.
     */
    private int[] plateBox(int ringIndex, Component text) {
        if (ringIndex > 0) {
            return LAYOUT.bounds(AvalonLayout.Element.otherPlate(ringIndex - 1), width, height);
        }
        int[] box = LAYOUT.bounds(AvalonLayout.Element.SELF_PLATE, width, height);
        int plateWidth = font.width(text) + 12;
        int plateHeight = 12;
        int centreX = box[0] + box[2] / 2;
        int left = Mth.clamp(centreX - plateWidth / 2, 2, width - plateWidth - 2);
        return new int[]{left, box[1], plateWidth, plateHeight};
    }

    // --------------------------------------------------------- team zones and flags ---

    /**
     * The team the local leader is putting together: the local click wins until the server's own copy
     * catches up, so a flag never appears on the pile a round trip after it was put down.
     */
    private int pendingMask() {
        if (localPending >= 0) {
            if (menu.getPhase() != AvalonGame.Phase.PROPOSE || menu.getPendingMask() == localPending) {
                localPending = -1;
            } else {
                return localPending;
            }
        }
        return menu.getPendingMask();
    }

    /**
     * The quest the proposal is for, with the local click applied first on the same terms as
     * {@link #pendingMask()}.
     *
     * <p>Classic play reads the server cursor and has no override; the designated rule is the only place
     * a quest can be clicked, and it is also the only place the choice can be a frame behind.
     */
    private int chosenQuest() {
        if (!menu.isDesignatedQuests()) {
            return menu.getQuestNumber();
        }
        if (menu.getPhase() != AvalonGame.Phase.PROPOSE) {
            localQuest = -1;
            return menu.getQuestNumber();
        }
        if (localQuest >= 0) {
            if (menu.getQuestNumber() == localQuest) {
                localQuest = -1;
            } else if (Util.getMillis() - localQuestAt < 1200L) {
                return localQuest;
            } else {
                // A refused click must not keep the board frozen on a quest the server never took.
                localQuest = -1;
            }
        }
        return menu.getQuestNumber();
    }

    /** The size of the team the screen is asking for, off the same local-first quest as the highlight. */
    private int localTeamSize() {
        if (!menu.isDesignatedQuests()) {
            return menu.getTeamSize();
        }
        int quest = chosenQuest();
        return quest < 0 ? 0 : menu.getGame().teamSizeFor(quest);
    }

    /** How many fail cards the chosen quest needs, read off the local choice rather than the mirror. */
    private int localRequiredFails() {
        if (!menu.isDesignatedQuests()) {
            return menu.getGame().requiredFails();
        }
        int quest = chosenQuest();
        return quest >= 0 && AvalonRole.needsTwoFails(menu.getGame().getPlayers().size(), quest) ? 2 : 1;
    }

    /**
     * The board slot {@code index}'s box: the F9-movable element that ships fitted to the printed
     * indicator frame.
     *
     * <p>Its default is a screen square on the frame -- the part of the board that says "point here" --
     * with the bottom edge reaching the top of the pile. A board click is consumed before the flag
     * branch, so a click in the overlap picks the quest rather than putting a carried flag back; the
     * spare markers themselves still sit mostly below it and are grabbed in their lower half.
     *
     * <p>Both the highlight and the hit-test read this one box, so an F9 drag moves the mark and the
     * clickable area together.
     */
    private int[] questSlotBox(int index) {
        return LAYOUT.bounds(AvalonLayout.Element.questSlot(index), width, height);
    }

    /**
     * The mirrored open mask, repaired locally if it arrives empty during a proposal.
     *
     * <p>An empty mask with slots still unplayed is never a legal message from the server (it would
     * leave the leader unable to act), so the screen rebuilds it from the board itself for both the
     * highlight and the click. The server's own masking stays authoritative -- a click is still refused
     * if it names a slot the server does not consider open.
     */
    private int openQuests() {
        int open = menu.getOpenQuestMask();
        if (open != 0 || menu.getPhase() != AvalonGame.Phase.PROPOSE) {
            return open;
        }
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (menu.getMission(i) == 0) {
                open |= 1 << i;
            }
        }
        return open;
    }

    /** The quest slot under the pointer, or {@code -1}. */
    private int questSlotAt(double mouseX, double mouseY) {
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (inside(questSlotBox(i), mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Picks or unpicks {@code seat} on the local screen immediately, then asks the server for the same
     * change. The one place a flag is ever toggled, so the optimistic copy cannot drift from the request.
     */
    private void toggleFlag(int seat) {
        localPending = pendingMask() ^ (1 << seat);
        send(AvalonGame.TOGGLE_BASE + seat);
    }

    /**
     * A seat's zone: their plate grown by the {@code ZONE_PAD_*} margins, {@code x, y, width, height}.
     *
     * <p>This is both the box the highlight is painted in and the box a click has to land in, so what is
     * lit and what is clickable are the same rectangle by construction. The flag is a separate element
     * and no longer part of this box; the zone is the plate alone.
     */
    private int[] plateZone(int ringIndex) {
        int[] plate = plateBoxes[ringIndex];
        return new int[]{plate[0] - ZONE_PAD_X, plate[1] - ZONE_PAD_TOP,
                plate[2] + ZONE_PAD_X * 2, plate[3] + ZONE_PAD_TOP + ZONE_PAD_BOTTOM};
    }

    /**
     * A seat's flag slot: the flag's own {@link AvalonLayout} box ({@code SELF_FLAG}/{@code FLAG_n}), the
     * shape that both the placed flag and the blink socket are drawn from. Independent of the plate so the
     * F9 editor can slide a flag anywhere.
     */
    private int[] flagSlotBox(int ringIndex) {
        return LAYOUT.bounds(flagElement(ringIndex), width, height);
    }

    /**
     * The area a dropped flag is accepted in: the slot grown by {@link #FLAG_DROP_MARGIN} on every side.
     * Letting go anywhere in here snaps the flag onto the seat's slot, so the leader does not have to hit
     * a 13-pixel square.
     */
    private int[] flagDropZone(int ringIndex) {
        int[] slot = flagSlotBox(ringIndex);
        return new int[]{slot[0] - FLAG_DROP_MARGIN, slot[1] - FLAG_DROP_MARGIN,
                slot[2] + FLAG_DROP_MARGIN * 2, slot[3] + FLAG_DROP_MARGIN * 2};
    }

    /**
     * The seat whose flag drop zone is under the pointer, or {@code -1}. When two zones overlap -- which
     * only happens on a layout that has dragged two flags close together -- the nearer slot wins, so the
     * flag lands where it was let go rather than on whichever seat the ring happens to list first.
     */
    private int seatAtFlagZone(double mouseX, double mouseY) {
        int found = -1;
        double best = Double.MAX_VALUE;
        for (int r = 0; r < ring.length; r++) {
            int[] zone = flagDropZone(r);
            if (!inside(zone, mouseX, mouseY)) {
                continue;
            }
            int[] slot = flagSlotBox(r);
            double dx = slot[0] + slot[2] / 2.0 - mouseX;
            double dy = slot[1] + slot[3] / 2.0 - mouseY;
            double distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                found = ring[r];
            }
        }
        return found;
    }

    /**
     * The team flag meant for each picked seat, and the one in hand — for as long as the table is talking
     * about that team.
     *
     * <h2>How long a flag lives</h2>
     *
     * <p>From the first click that puts it on a seat until the next leader opens a proposal. The mask the
     * flags are read off is <em>not</em> reset when the team is confirmed, so the row survives the vote and
     * the quest that follows and keeps saying who is out there; the server clears it in
     * {@code AvalonGame.beginProposal}, so the flags disappear on the same frame the new leader's empty
     * sockets appear and the two never mix. That is why there is no phase gate here — the mask's own
     * lifetime is the feature.
     *
     * <p>In a proposal an empty slot pulses at its seat's colour with a doubled frame, the bridge addon's
     * lit trick-cell treatment: the alpha breathes so a socket says "drop here" rather than reading as a
     * static box. That ring is the whole invitation -- there is no second, outer box around it, so what
     * breathes is exactly the hole a flag goes in. Every later phase drops the sockets: nothing can be
     * dropped there, so a breathing ring would be claiming an invitation that has already been accepted,
     * while the flag itself is still the thing worth seeing.
     *
     * <p>Every flag is painted the way a card is painted — the perspective shader and, for the one under
     * the pointer, the same hover lift and tilt the widget gives a card in a hand — but in the pack's own
     * colours and without a halo; see {@link #blitCardTrueColor}. So a flag is a card in the same sense a
     * vote is: hovering it reads like hovering a card, and picking a seat is a flag being carried into the
     * socket. The flag under the pointer is painted a second time, over its neighbours, because a lifted
     * card is never behind the row it was lifted out of.
     */
    private void renderFlags(GuiGraphics guiGraphics) {
        if (identityFurnitureHidden()) {
            return;
        }
        boolean proposing = menu.getPhase() == AvalonGame.Phase.PROPOSE;
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            int[] slot = flagSlotBox(r);
            int colour = 0xFF000000 | rgb(menu.getGame().playerAt(seat).getColor());
            int x = slot[0] - FLAG_SLOT_INFLATE;
            int y = slot[1] - FLAG_SLOT_INFLATE;
            int w = slot[2] + FLAG_SLOT_INFLATE * 2;
            int h = slot[3] + FLAG_SLOT_INFLATE * 2;

            if (proposing) {
                guiGraphics.fill(x, y, x + w, y + h, CELL_BG);
                guiGraphics.fill(x, y, x + w, y + h, (pulseAlpha() << 24) | (colour & 0xFFFFFF));
                frame(guiGraphics, x, y, w, h, colour);
            }

            if ((pendingMask() & (1 << seat)) != 0) {
                blitFlag(guiGraphics, slot[0], slot[1], LAYOUT.scale(flagElement(r)));
            }
        }
        if (hoverFlagSeat >= 0 && (pendingMask() & (1 << hoverFlagSeat)) != 0) {
            // The lift and tilt measured for this flag's own box, lerped from the last 20 Hz update.
            int[] slot = flagSlotBox(hoverFlagRing);
            blitFlag(guiGraphics, slot[0], slot[1], LAYOUT.scale(flagElement(hoverFlagRing)),
                    flagHoverTilt());
        }
        if (carryingFlag) {
            float size = FLAG_W * FREE_FLAG_SCALE;
            blitFlag(guiGraphics, (float) dragX - size / 2f, (float) dragY - size / 2f, FREE_FLAG_SCALE);
        }
        scheduleFlagTooltip();
    }

    /** The flag element for the {@code ringIndex}-th seat: the local one, or another seat's. */
    private static AvalonLayout.Element flagElement(int ringIndex) {
        return ringIndex == 0 ? AvalonLayout.Element.SELF_FLAG : AvalonLayout.Element.otherFlag(ringIndex - 1);
    }

    // ------------------------------------------------------------------- flag hover ---

    /**
     * Re-targets the flag hover, at most once per game tick.
     *
     * <p>The 20 Hz split is Charta's own: {@code AbstractCardWidget.tick} writes the hover targets and
     * its renderer lerps the previous tick's value into the current one by the fraction of a tick that
     * has passed, so a card that is hovered mid-tick eases into the tilt instead of jumping. The game
     * tick is a wall-clock 50 ms here because the sequence has to keep running on a server that lags --
     * the same reason the deal and the reveal are timed off {@code Util.getMillis}.
     */
    private void updateFlagHover() {
        long now = Util.getMillis();
        if (now - flagHoverTickMs < 50L) {
            return;
        }
        flagHoverTickMs = now;
        lastFlagInset = flagInset;
        lastFlagXRot = flagXRot;
        lastFlagYRot = flagYRot;
        hoverFlagSeat = -1;
        hoverFlagRing = -1;
        hoverFlagSpare = -1;
        flagInset = 0f;
        flagXRot = 0f;
        flagYRot = 0f;

        if (menu.getPhase() != AvalonGame.Phase.PROPOSE || !menu.isGameReadyAndRunning() || carryingFlag) {
            return;
        }
        double mouseX = scaledMouseX();
        double mouseY = scaledMouseY();
        int[] box = flagAt(mouseX, mouseY);
        if (box == null) {
            return;
        }
        // The pointer's position on the flag, as Charta measures it: -1 at one edge, +1 at the other.
        // Measured on the box the flag is drawn in, so the tilt follows the art, not the socket.
        float half = Math.max(1f, box[2] / 2f);
        float xDif = (float) ((box[0] + half - mouseX) / half);
        float yDif = (float) ((box[1] + half - mouseY) / half);
        flagInset = -30f;
        flagXRot = yDif * 25f;
        flagYRot = xDif * -25f;
    }

    /**
     * The flag under the pointer as {@code x, y, w, h}, filling in which flag it was, or {@code null}
     * when there is none. Only flags that exist are targets -- an empty socket is a hole, not a card --
     * and a spare one counts only while the local seat could actually take it.
     */
    @Nullable
    private int[] flagAt(double mouseX, double mouseY) {
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            if ((pendingMask() & (1 << seat)) == 0) {
                continue;
            }
            int[] box = flagSlotBox(r);
            if (inside(box, mouseX, mouseY)) {
                hoverFlagSeat = seat;
                hoverFlagRing = r;
                return box;
            }
        }
        int free = freeFlagCount();
        if (free <= 0 || !menu.isLocalTurn()) {
            return null;
        }
        int[] row = freeFlagRow(free, pileBox());
        for (int i = 0; i < free; i++) {
            int[] box = {row[0] + i * row[3], row[1], row[2], row[2]};
            if (inside(box, mouseX, mouseY)) {
                hoverFlagSpare = i;
                return box;
            }
        }
        return null;
    }

    /**
     * The hover lift and tilt, lerped to where this frame stands between the last two 20 Hz updates:
     * {@code inset, xRot, yRot}, the three values {@code AbstractCardWidget} feeds its shader.
     */
    private float[] flagHoverTilt() {
        float t = Mth.clamp((Util.getMillis() - flagHoverTickMs) / 50f, 0f, 1f);
        return new float[]{Mth.lerp(t, lastFlagInset, flagInset),
                Mth.lerp(t, lastFlagXRot, flagXRot),
                Mth.lerp(t, lastFlagYRot, flagYRot)};
    }

    /** The flag's own tooltip, while one is under the pointer: a card tells you what it is. */
    private void scheduleFlagTooltip() {
        if (summaryOwnsScreen() || identityCurtainUp()) {
            return;
        }
        if (hoverFlagSeat < 0 && hoverFlagSpare < 0) {
            return;
        }
        boolean mine = menu.isLocalTurn() && menu.isGameReadyAndRunning();
        Component tip = Component.translatable("status.myavalon.flag_name")
                .withStyle(style -> style.withColor(ACTIVE))
                .copy()
                .append(Component.literal("\n").withStyle(style -> style.withColor(DIM)))
                .append(Component.translatable(mine
                                ? "status.myavalon.flag_hint" : "status.myavalon.flag_hint_wait")
                        .withStyle(style -> style.withColor(DIM)));
        setTooltipForNextRenderPass(tip);
    }

    /**
     * One team flag, drawn as a card: the perspective quad and the hover lift, in the artist's own
     * colours and with no halo. See {@link #blitCardTrueColor} for why both halves of that matter.
     */
    private void blitFlag(GuiGraphics guiGraphics, float x, float y, float scale) {
        blitFlag(guiGraphics, x, y, scale, NO_FLAG_TILT);
    }

    private void blitFlag(GuiGraphics guiGraphics, float x, float y, float scale, float[] hover) {
        float size = FLAG_W * scale;
        blitCardTrueColor(guiGraphics, TEAM_FLAG, x + size / 2f, y + size / 2f, size, size,
                hover[0], hover[1], hover[2]);
    }

    /**
     * Draws a whole texture the way {@link #blitChartaCard} draws a card — the same inflated quad, the
     * same perspective uniforms, the same hover lift — but through the true-colour card shader and with
     * no glow pass at all.
     *
     * <h2>Why the shader is named explicitly</h2>
     *
     * <p>Charta has two card shaders and picks on {@code IrisCompat.isPresent()}: {@code charta:card}
     * reads the red channel as an index into a 64-colour palette ({@code charta:palette.glsl}), which is
     * the format card art is stored in, and {@code charta:card_argb} samples the texture as it is. The
     * mod's own {@code IrisCompatPresent} mixin already forces the direct-sampling path on, so this is
     * the shader {@code blitCard} would have picked anyway — but a sprite authored here as an ordinary
     * RGBA PNG has no business depending on that flip, so the right one is named outright.
     *
     * <h2>Why the flag takes no halo — this is the brightness bug</h2>
     *
     * <p>A card's halo is a second pass with a dedicated glow texture ({@code card_glow/<id>}, generated
     * by Charta from the card image). The flag was passing <em>its own art</em> as that glow texture,
     * which is a blurred copy of the sprite composited over the whole screen with additive blending:
     * the flag got a second copy of itself laid on top of itself — brighter than the file, and washed
     * out wherever the art is even slightly transparent — and, because the glow buffer covers the entire
     * screen, every flag that moved dragged that wash across the name plates and the status lines with
     * it. <b>That single mistake is both the "why is the flag so bright" and the text flash.</b>
     *
     * <p>There is no glow art for the flag in the pack, and there shouldn't be: it is a flat marker, not
     * a card lifted off the felt. So the pass is simply not taken — the flag keeps the perspective quad
     * and the hover lift, and is otherwise the PNG.
     */
    private void blitCardTrueColor(GuiGraphics guiGraphics, ResourceLocation texture,
                                   float centreX, float centreY, float cardWidth, float cardHeight,
                                   float inset, float hoverXRot, float hoverYRot) {
        float xOffset = (cardWidth * 1.333333f - cardWidth) / 2f;
        float yOffset = (cardHeight * 1.333333f - cardHeight) / 2f;
        // The same four uniforms, at the same values, as the card path drives them with.
        cardUniforms(inset, 30f, hoverXRot, hoverYRot);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(ChartaModClient.getShaderManager()::getCardArgbShader);
        ChartaGuiGraphics.innerBlit(guiGraphics,
                centreX - cardWidth / 2f - xOffset, centreX + cardWidth / 2f + xOffset,
                centreY - cardHeight / 2f - yOffset, centreY + cardHeight / 2f + yOffset,
                0f, 1f, 0f, 1f);
    }

    /** A flag at rest: no inset, no tilt -- what the shader is driven with for the placed flags. */
    private static final float[] NO_FLAG_TILT = {0f, 0f, 0f};

    /**
     * Blits a whole texture at a <em>fractional</em> position instead of a snapped one.
     *
     * <p>{@code GuiGraphics.blit} only takes integer coordinates, so an animated position handed to it
     * straight is silently rounded — and since the design frame is one 640x360 screen scaled up to the
     * window, one frame pixel is three window pixels at 1080p. Rounding therefore throws away up to
     * three pixels of travel <em>per frame</em>.
     *
     * <p>What that looks like depends on speed, which is why it went unnoticed on the fast parts: on a
     * card thrown across the table the rounding is smaller than one frame's movement, so it reads as
     * smooth. On anything slow — the crown's bob, either end of an eased arc, the last pixels of a slide
     * — several frames in a row round to the <em>same</em> integer and the object simply sits still, then
     * jumps. That does not read as "slow animation", it reads as an animation running at a handful of
     * frames a second, which is exactly what it is: the bob's whole amplitude is under two pixels.
     *
     * <p>Done by keeping the vanilla integer blit and shifting the pose by the fractional part, rather
     * than reaching for a float-capable inner blit, so the texture, shader, blend and filter are the very
     * same ones every other sprite on the screen goes through.
     */
    private void blitExact(GuiGraphics guiGraphics, ResourceLocation texture, float x, float y,
                           int sourceW, int sourceH, float scale) {
        int px = Mth.floor(x);
        int py = Mth.floor(y);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x - px, y - py, 0f);
        // The crown and the rejection marker both come through here, and both are art with soft edges.
        // An immediate blit leaves blend state exactly as the last batch left it, so ask for it here.
        UiBlend.on();
        guiGraphics.blit(texture, px, py, Math.round(sourceW * scale), Math.round(sourceH * scale),
                0f, 0f, sourceW, sourceH, sourceW, sourceH);
        guiGraphics.pose().popPose();
    }

    // ------------------------------------------------------------------- crown ---

    /**
     * Whether the crown belongs on the table this frame.
     *
     * <p>Every phase the game is still being played. It used to stop at the end of the vote, which took
     * the crown off the table for the whole quest -- and the quest is exactly when a seat's chip is still
     * lit as the leader ({@link #marked}) while the crown that explains the light is missing. Two phases
     * are left out and both are the ends of a game rather than rounds in it: the deal, which has no leader
     * yet, and the summary, which has no proposal left to lead.
     */
    private boolean crownOnTable() {
        AvalonGame.Phase phase = menu.getPhase();
        return phase == AvalonGame.Phase.PROPOSE || phase == AvalonGame.Phase.VOTE
                || phase == AvalonGame.Phase.QUEST || phase == AvalonGame.Phase.ASSASSIN;
    }

    /**
     * The leader's crown, resting still beside their plate — or arcing clockwise to the next leader while
     * a rejected proposal hands the lead over.
     *
     * <p>It no longer drifts while it rests. The bob was two incommensurate sine waves at two or three
     * pixels' amplitude, and next to a name plate that never moves it read as the marker sliding around
     * on its own rather than as a badge sitting still; the place the eye wants is "on the plate's line,
     * just off its left edge", and a still crown says exactly that. The hand-over arc stays, because that
     * one is saying something: it is the lead moving to the next seat, and it happens once.
     */
    private void renderCrown(GuiGraphics guiGraphics) {
        int leader = menu.getLeaderSeat();
        if (leader < 0) {
            return;
        }
        long elapsed = Util.getMillis() - crownFlightStart;
        boolean flying = crownFrom >= 0 && crownTo >= 0 && elapsed < CROWN_FLIGHT_MS;
        if (!flying && !crownOnTable()) {
            if (crownFrom >= 0 && elapsed >= CROWN_FLIGHT_MS) {
                crownFrom = -1;
                crownTo = -1;
            }
            return;
        }

        float x;
        float y;
        if (flying) {
            float[] a = crownAnchor(crownFrom);
            float[] b = crownAnchor(crownTo);
            // A plain eased translation, no arc. The old version lifted the crown thirty pixels mid-flight
            // to say "this one is being handed over", but the flight is short and the two rests are close,
            // and the lift read as the crown hopping rather than as it moving. The hand-over is already
            // said by the fact that it is moving at all, and by where it lands.
            float t = ease(elapsed, 0f, CROWN_FLIGHT_MS);
            x = Mth.lerp(t, a[0], b[0]);
            y = Mth.lerp(t, a[1], b[1]);
        } else {
            float[] anchor = crownAnchor(leader);
            x = anchor[0];
            y = anchor[1];
        }
        float sc = crownScale(flying ? crownTo : leader);
        float w = CROWN_W * sc;
        float h = CROWN_H * sc;
        blitExact(guiGraphics, CROWN, x - w / 2f, y - h / 2f, CROWN_SRC, CROWN_SRC, w / CROWN_SRC);
        // The glow: a second, larger, additively-blended copy breathing under the crown. The crown used to
        // fade its own alpha, which reads as the marker going translucent rather than as it lighting up --
        // and a leader's crown drawn at 70% opacity over a dark bar mostly reads as "dimmed". Two passes
        // keep the crown solid and put the pulse into brightness, where a glow belongs.
        blitGlowAdditive(guiGraphics, CROWN, x - w * 1.18f / 2f, y - h * 1.18f / 2f,
                w * 1.18f, h * 1.18f, (float) (0.12 + 0.75 * breath(CROWN_BREATH_MS)));
    }

    /**
     * A second copy of a sprite, scaled up and blended additively: the glow that makes a marker read as lit.
     *
     * <p>Additive is the whole point: normal alpha blending over a dark bar would let the bar through and dim
     * what is under it, while {@code GL_ONE, GL_ONE} only ever adds light.
     *
     * <h2>Why the strength goes into the colour and not the alpha</h2>
     *
     * <p>Two traps, and the first one is why an earlier attempt at this did not breathe at all.
     * {@code GL_ONE, GL_ONE} adds the source <em>rgb</em> and ignores the source alpha entirely, so a
     * strength handed over as {@code setShaderColor(1, 1, 1, strength)} scales nothing — the glow is simply
     * added at full brightness whatever the breath is doing. Scaling the rgb instead is what makes the
     * amount of added light follow the breath. The second trap is that blend function: it has to be set
     * <em>after</em> {@link UiBlend#on}, because that is what resets it.
     */
    private void blitGlowAdditive(GuiGraphics guiGraphics, ResourceLocation texture, float x, float y,
                                  float width, float height, float strength) {
        int px = Mth.floor(x);
        int py = Mth.floor(y);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x - px, y - py, 0f);
        UiBlend.on();
        RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE);
        RenderSystem.setShaderColor(strength, strength, strength, 1f);
        guiGraphics.blit(texture, px, py, Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                0f, 0f, CROWN_SRC, CROWN_SRC, CROWN_SRC, CROWN_SRC);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        guiGraphics.pose().popPose();
    }

    /** The scale of {@code seat}'s own crown; a flight is drawn at the size of the chair it is heading for. */
    private float crownScale(int seat) {
        for (int r = 0; r < ring.length; r++) {
            if (ring[r] == seat) {
                return LAYOUT.scale(AvalonLayout.Element.crown(r));
            }
        }
        return 1f;
    }

    /**
     * Centre of the crown's resting spot for {@code seat}: the centre of that seat's own crown element.
     *
     * <p>One element per seat, not one shared handle. A shared offset was fine while every seat wore a
     * plate at its own fixed place, but the seats are spread along a bar now, so the same offset from each
     * seat walks the outer crowns further and further out — by the last chair, off the frame. What the
     * element's default anchor is differs by seat and is the layout's business: just off the local plate's
     * left, and under the left corner of another seat's chip, on that seat's marker line.
     */
    private float[] crownAnchor(int seat) {
        for (int r = 0; r < ring.length; r++) {
            if (ring[r] != seat) {
                continue;
            }
            int[] box = LAYOUT.bounds(AvalonLayout.Element.crown(r), width, height);
            return new float[]{box[0] + box[2] / 2f, box[1] + box[3] / 2f};
        }
        return new float[]{width / 2f, height / 2f};
    }

    /**
     * What {@code seat} is known to be, or {@code null} when nothing about them is knowable.
     *
     * <p>Three separate things can make a seat readable, and they are checked in falling order of
     * authority: the end-of-game reveal names everybody outright; otherwise a seat the viewer was dealt
     * knowledge of is marked with what that knowledge actually means — for Merlin and evil that is the
     * faction, for Percival it is the ambiguous Merlin-or-Morgana pair, which is why the wording differs.
     *
     * <p>{@code viewer} is the <em>looking</em> seat's own role and the only thing that picks between those
     * two wordings: Percival's knowledge is the one that does not name a side, so his two seats get the
     * pair and every other viewer gets the faction.
     */
    @Nullable
    private Component factionTag(int seat, boolean local, @Nullable AvalonRole revealed,
                                 @Nullable AvalonRole viewer) {
        if (local) {
            return null;
        }
        if (revealed != null) {
            Component name = Component.translatable(revealed.getTranslationKey());
            return name.copy().withStyle(style -> style.withColor(revealed.isEvil() ? EVIL_COLOR : GOOD_COLOR));
        }
        if (viewer == null || (menu.getKnownMask() & (1 << seat)) == 0) {
            return null;
        }
        boolean pair = viewer == AvalonRole.PERCIVAL;
        return Component.translatable(pair ? "status.myavalon.known_pair" : "status.myavalon.known_evil")
                .copy().withStyle(style -> style.withColor(pair ? ACTIVE : EVIL_COLOR));
    }

    // ------------------------------------------------------------------- status ---

    private void renderStatus(GuiGraphics guiGraphics) {
        if (identityFurnitureHidden()) {
            // The turn and tally lines and the confirm button are all "act now" furniture; the winner
            // banner can never coincide with the curtain, so hiding the lot costs nothing.
            return;
        }
        AvalonGame.Phase phase = menu.getPhase();
        // "May I act?" rather than "is it my turn": a vote is taken all at once, so during that phase the
        // local seat is an actor until its own answer is on the pile -- see AvalonMenu#isLocalPending.
        boolean myTurn = menu.isLocalPending();
        boolean voting = phase == AvalonGame.Phase.VOTE;

        // The stack is down to two rows. The phase line that used to head it went first (the tally line
        // already says how far into the phase the table is, so it was the same news twice), and the hint
        // line followed it out -- it restated what the sockets, the pile's own caption and the confirm
        // button were already showing. The one thing the title row alone ever carried is who won, so it
        // stays for that and nothing else.
        drawStatusLine(guiGraphics, phase == AvalonGame.Phase.OVER ? winnerLine() : Component.empty(),
                AvalonLayout.Element.STATUS_TITLE, 0xFFFFFFFF);

        // Whose turn it is: their name, in their own colour, or "you".
        if (phase != AvalonGame.Phase.DEALING && phase != AvalonGame.Phase.OVER) {
            Component turn;
            if (phase == AvalonGame.Phase.ASSASSIN) {
                // Not a turn at all: it is the one question the whole table is watching, so the line states
                // the task and names both roles rather than naming a seat. Brighter for the assassin, who is
                // the only person who can act on it.
                turn = Component.translatable("status.myavalon.turn_assassin",
                        Component.translatable(AvalonRole.ASSASSIN.getTranslationKey()),
                        Component.translatable(AvalonRole.MERLIN.getTranslationKey()));
            } else if (myTurn) {
                turn = Component.translatable("status.myavalon.your_turn");
            } else if (voting) {
                // Nobody is being waited on in particular: the whole table answered at the same moment,
                // so what this line can honestly say is that this seat's own vote is already in.
                turn = Component.translatable("status.myavalon.vote_in");
            } else if (phase == AvalonGame.Phase.QUEST) {
                // Same shape, and the same reason: the team plays at once, so once this seat's card is down
                // there is no one seat to name. Without this the line would name the leader, who is only
                // still the current player because the proposal left them there.
                turn = Component.translatable("status.myavalon.hint_quest_wait");
            } else {
                turn = Component.translatable("status.myavalon.waiting_for", nameOf(menu.getCurrentSeat()));
            }
            drawStatusLine(guiGraphics, turn.copy().withStyle(style -> style.withColor(
                    myTurn ? ACTIVE : DIM)), AvalonLayout.Element.STATUS_TURN, 0xFFFFFFFF);
        } else {
            drawStatusLine(guiGraphics, Component.empty(), AvalonLayout.Element.STATUS_TURN, 0xFFFFFFFF);
        }

        drawStatusLine(guiGraphics, tallyLine(), AvalonLayout.Element.STATUS_TALLY, 0xFFFFFFFF);

        if (phase == AvalonGame.Phase.PROPOSE || phase == AvalonGame.Phase.ASSASSIN) {
            renderConfirmButton(guiGraphics);
        }
    }

    /**
     * Who has voted against the team so far, in red — the names the tally line ends with.
     *
     * <p>The two counters beside them say how many; this is the part a table actually argues about, and it
     * is live: a name appears the moment that seat answers and stays for the rest of the round, including
     * the beat the result is read during.
     *
     * <p>Red as a whole rather than per seat: the seats are already talking with their votes, and a plain
     * line of red names is read at a glance where one coloured per seat is not.
     */
    private Component noVoterNames() {
        MutableComponent names = Component.empty();
        int mask = menu.getNoVoterMask();
        boolean first = true;
        for (int seat = 0; seat < ring.length; seat++) {
            if ((mask & (1 << seat)) == 0) {
                continue;
            }
            if (!first) {
                names.append("、");
            }
            first = false;
            names.append(nameOf(seat));
        }
        return names.withStyle(style -> style.withColor(0xFF6B6B));
    }

    /** The team size against the votes already in, so "are we still deciding" is readable. */
    private Component tallyLine() {
        AvalonGame.Phase phase = menu.getPhase();
        if (phase == AvalonGame.Phase.VOTE) {
            int voted = Integer.bitCount(menu.getVotedMask());
            return Component.translatable("status.myavalon.tally", voted, menu.getGame().getPlayers().size(),
                    menu.getYesVotes(), noVoterNames());
        }
        if (phase == AvalonGame.Phase.QUEST) {
            // How many cards are in, and nothing else. The fail count is known to the whole table the moment
            // a card is played -- but the cards come down one at a time, so printing "fails: 2" while three of
            // four seats have played tells everyone which of the four still owed a card was the evil one.
            int done = Integer.bitCount(menu.getQuestMask());
            return Component.translatable("status.myavalon.quest_tally", done, menu.getTeamSize());
        }
        if (phase == AvalonGame.Phase.PROPOSE) {
            if (menu.isDesignatedQuests()) {
                int quest = chosenQuest();
                if (quest < 0) {
                    return Component.translatable(menu.isLocalTurn()
                            ? "status.myavalon.pick_quest" : "status.myavalon.pick_quest_wait");
                }
                return Component.translatable("status.myavalon.team_line_quest", quest + 1,
                        Integer.bitCount(pendingMask()), localTeamSize(), localRequiredFails());
            }
            return Component.translatable("status.myavalon.team_line",
                    Integer.bitCount(pendingMask()), menu.getTeamSize(),
                    menu.getGame().requiredFails());
        }
        return Component.empty();
    }

    private Component winnerLine() {
        return switch (menu.getWinner()) {
            case AvalonGame.SIDE_GOOD -> Component.translatable("status.myavalon.good_wins")
                    .withStyle(ChatFormatting.GREEN);
            case AvalonGame.SIDE_EVIL -> Component.translatable("status.myavalon.evil_wins")
                    .withStyle(ChatFormatting.RED);
            default -> Component.translatable("status.myavalon.aborted").withStyle(ChatFormatting.YELLOW);
        };
    }

    private void renderConfirmButton(GuiGraphics guiGraphics) {
        boolean propose = menu.getPhase() == AvalonGame.Phase.PROPOSE;
        // In a proposal the button only surfaces once every flag is placed, so it reads as the reward for
        // finishing the team; in the assassination it is there from the start but stays disabled until a
        // target is picked. Under the designated rule it also waits for a quest: a team has no size until
        // the leader names one.
        if (propose && (localTeamSize() <= 0 || Integer.bitCount(pendingMask()) != localTeamSize())) {
            return;
        }
        boolean enabled = menu.isLocalTurn() && menu.isGameReadyAndRunning()
                && (propose || menu.getPendingTarget() >= 0);
        int[] box = confirmBox();
        double mouseX = scaledMouseX();
        double mouseY = scaledMouseY();
        boolean hoveredNow = enabled && inside(box, mouseX, mouseY);

        guiGraphics.fill(box[0], box[1], box[0] + box[2], box[1] + box[3],
                enabled ? (hoveredNow ? CELL_HOVER : 0x88202010) : 0x30000000);
        frame(guiGraphics, box[0], box[1], box[2], box[3], enabled ? ACTIVE : OUTLINE);
        Component label = Component.translatable("status.myavalon.confirm");
        guiGraphics.drawString(font, label, box[0] + (box[2] - font.width(label)) / 2, box[1] + 4,
                enabled ? ACTIVE : DIM, true);
    }

    private int[] confirmBox() {
        return LAYOUT.bounds(AvalonLayout.Element.CONFIRM, width, height);
    }

    private static boolean inside(int[] box, double x, double y) {
        return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
    }

    // ------------------------------------------------------------------- editor ---

    /**
     * F9 overlay: a box per movable element, a drag handle on each and a scroll-to-scale hint.
     *
     * <p>Boxes are drawn from {@link AvalonLayout#bounds}, i.e. the same values the slots are written
     * from, so what is highlighted is exactly what a click will hit.
     */
    private void renderEditor(GuiGraphics guiGraphics) {
        for (AvalonLayout.Element element : elements()) {
            int[] box = LAYOUT.bounds(element, width, height);
            boolean isSelected = element == selected;
            int color = isSelected ? ACTIVE : element == hovered ? 0xFFFFFFFF : 0x8000E0FF;
            frame(guiGraphics, box[0] - 1, box[1] - 1, box[2] + 2, box[3] + 2, color);
            String label = element.label() + "  " + Math.round(LAYOUT.scale(element) * 100) + "%";
            guiGraphics.drawString(font, label, box[0], box[1] + box[3] + 2, color, true);
        }

        Component hint = Component.translatable("editor.myavalon.hint");
        int y = height - 14;
        guiGraphics.fill(0, y - 3, width, height, SHADOW);
        guiGraphics.drawString(font, hint, 4, y, LABEL, true);
        if (selected != null) {
            guiGraphics.drawString(font, selected.label() + "  (" + selected.key + ")", 4, y - 16, ACTIVE, true);
        }
    }

    /**
     * Every element currently on screen, in click-priority order (later wins the hit-test).
     *
     * <p>The big quest board is first so the pile, the plates and the flags laid over it stay pickable;
     * the flag and crown handles are listed right after the plates they sit near, so a click on the small
     * handle reaches it rather than the plate behind it.
     */
    private List<AvalonLayout.Element> elements() {
        List<AvalonLayout.Element> list = new ArrayList<>();
        list.add(AvalonLayout.Element.TASK_CARD);
        // Before the pile, so the board's own handle loses to the boxes drawn on it but the pile still
        // owns the strip where the spare markers sit.
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            list.add(AvalonLayout.Element.questSlot(i));
        }
        list.add(AvalonLayout.Element.PILE);
        // After the pile so its handle wins the hit-test in the strip they share.
        list.add(AvalonLayout.Element.CONFIRM);
        list.add(AvalonLayout.Element.SELF_HAND);
        list.add(AvalonLayout.Element.SELF_PLATE);
        list.add(AvalonLayout.Element.SELF_FLAG);
        list.add(AvalonLayout.Element.SELF_CROWN);
        int others = Math.max(0, ring.length - 1);
        for (int i = 0; i < others; i++) {
            // The chip first and the hand after it, because the hand sits inside the chip: with the hand
            // last the chip would win every hit-test over it and the hand could never be picked up.
            list.add(AvalonLayout.Element.otherPlate(i));
            list.add(AvalonLayout.Element.otherHand(i));
            list.add(AvalonLayout.Element.otherFlag(i));
            list.add(AvalonLayout.Element.otherCrown(i));
        }
        // STATUS_TITLE is still here because the end-of-game banner is drawn in it; the rejects row is
        // gone from the table, so it is gone from the editor too.
        list.add(AvalonLayout.Element.STATUS_TITLE);
        list.add(AvalonLayout.Element.STATUS_TURN);
        list.add(AvalonLayout.Element.STATUS_TALLY);
        return list;
    }

    @Nullable
    private AvalonLayout.Element elementAt(double mouseX, double mouseY) {
        AvalonLayout.Element found = null;
        for (AvalonLayout.Element element : elements()) {
            if (inside(LAYOUT.bounds(element, width, height), mouseX, mouseY)) {
                found = element;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------- input ---

    private double scaledMouseX() {
        if (minecraft == null || minecraft.getWindow().getScreenWidth() == 0) {
            return lastMouseX;
        }
        return frameX(minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth());
    }

    private double scaledMouseY() {
        if (minecraft == null || minecraft.getWindow().getScreenHeight() == 0) {
            return lastMouseY;
        }
        return frameY(minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight());
    }

    /** Window coordinate to design-frame coordinate. */
    private static double frameX(double guiX) {
        return AvalonFrame.current().toFrameX(guiX);
    }

    private static double frameY(double guiY) {
        return AvalonFrame.current().toFrameY(guiY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        lastMouseX = frameMouseX;
        lastMouseY = frameMouseY;
        if (carryingFlag) {
            dragX = frameMouseX;
            dragY = frameMouseY;
        }
        if (editing) {
            hovered = elementAt(frameMouseX, frameMouseY);
        }
        super.mouseMoved(frameMouseX, frameMouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        lastMouseX = frameMouseX;
        lastMouseY = frameMouseY;

        if (editing && button == 0) {
            selected = elementAt(frameMouseX, frameMouseY);
            if (selected != null) {
                dragging = true;
                return true;
            }
            return false;
        }
        if (identityCurtainUp() && !overCornerButton(frameMouseX, frameMouseY)) {
            // The table is not open for business yet: swallowed whole rather than passed down, so the
            // click cannot lift a marker off the pile or drop a vote card the player cannot see under the
            // wash. The F9 editor above and the four corner buttons beside this are deliberately exempt --
            // reading the rules mid-curtain is a reasonable thing to want.
            return true;
        }
        if (button == 0 && handleActionClick(frameMouseX, frameMouseY)) {
            return true;
        }
        return super.mouseClicked(frameMouseX, frameMouseY, button);
    }

    /**
     * Turns a click into a flag lift/place, an assassination pick or a confirm.
     *
     * <p>Only the two action phases are handled here: a vote and a quest go through the inherited
     * click-to-carry, click-to-drop on the card slots, which is why this returns {@code false} in those
     * phases and lets {@code super} run.
     *
     * <p>Flags are played the way Charta's cards are, because that is what they are: a click lifts one
     * (off the pile, or out of a seat's socket) onto the pointer, and the next click sets it down on a
     * free seat to pick them -- <em>that</em> is what marking a seat takes, since a flag has to be carried
     * over to it. A click on an empty socket does nothing at all: the seat is not a checkbox, and being
     * on the team is a flag being put there. Clicking a seat that already holds a flag lifts that flag
     * back into the hand instead of silently returning it to the pile.
     *
     * @return {@code true} when the click was consumed
     */
    private boolean handleActionClick(double mouseX, double mouseY) {
        AvalonGame.Phase phase = menu.getPhase();
        if (phase != AvalonGame.Phase.PROPOSE && phase != AvalonGame.Phase.ASSASSIN) {
            return false;
        }
        if (!menu.isLocalTurn() || !menu.isGameReadyAndRunning()) {
            return false;
        }
        if (phase == AvalonGame.Phase.ASSASSIN) {
            if (inside(confirmBox(), mouseX, mouseY)) {
                send(AvalonGame.CONFIRM);
                return true;
            }
            int seat = seatAtPlate(mouseX, mouseY);
            if (seat >= 0 && seat != menu.getLocalSeat()) {
                send(AvalonGame.PICK_BASE + seat);
                return true;
            }
            return false;
        }

        // The designated rule turns the board itself into the quest picker. Any click on a slot is
        // consumed, so a miss on a completed or locked slot cannot fall through to the pile underneath.
        // This runs before the flag branch: a quest is a bigger decision than a flag, and a leader who
        // clicks the board while carrying one means the quest, not "put the flag back".
        if (menu.isDesignatedQuests()) {
            int slot = questSlotAt(mouseX, mouseY);
            if (slot >= 0) {
                if ((openQuests() & (1 << slot)) != 0) {
                    localQuest = slot;
                    localQuestAt = Util.getMillis();
                    // A different quest can have a different size, so the optimistic team has to give
                    // way to the server's trimmed one rather than staying a flag over the limit.
                    localPending = -1;
                    carryingFlag = false;
                    send(AvalonGame.PICK_BASE + slot);
                    cardSound(ModSounds.CARD_PLAY);
                }
                return true;
            }
        }

        if (carryingFlag) {
            // Second click: put it down. A free seat takes it; the pile, or anywhere else, returns it.
            // Either way something is being put down, so either way it sounds like a card being played --
            // Charta's own sound for a slot gaining a card.
            int seat = seatAtFlagZone(mouseX, mouseY);
            if (seat >= 0 && (pendingMask() & (1 << seat)) == 0) {
                toggleFlag(seat);
                carryingFlag = false;
                cardSound(ModSounds.CARD_PLAY);
            } else if (inside(pileBox(), mouseX, mouseY)) {
                carryingFlag = false;
                cardSound(ModSounds.CARD_PLAY);
            }
            return true;
        }

        boolean questReady = !menu.isDesignatedQuests() || chosenQuest() >= 0;
        boolean full = Integer.bitCount(pendingMask()) == localTeamSize();
        if (questReady && full && inside(confirmBox(), mouseX, mouseY)) {
            send(AvalonGame.CONFIRM);
            return true;
        }
        // A flag in a socket: the click pulls it back out into the hand, so a misdrop is undone by
        // taking the flag rather than by toggling the seat out from under it.
        for (int r = 0; r < ring.length; r++) {
            int seat = ring[r];
            if ((pendingMask() & (1 << seat)) != 0 && inside(flagSlotBox(r), mouseX, mouseY)) {
                toggleFlag(seat);
                carryingFlag = true;
                dragX = mouseX;
                dragY = mouseY;
                cardSound(ModSounds.CARD_DRAW);
                return true;
            }
        }
        if (freeFlagCount() > 0 && inside(pileBox(), mouseX, mouseY)) {
            carryingFlag = true;
            dragX = mouseX;
            dragY = mouseY;
            cardSound(ModSounds.CARD_DRAW);
            return true;
        }
        // An empty socket, and the generous pad around it: consumed so the click does not fall through
        // to whatever card happens to lie under the zone -- but it picks nobody.
        if (seatAtFlagZone(mouseX, mouseY) >= 0) {
            return true;
        }
        return false;
    }

    /**
     * A marker going down or coming up, in Charta's own card sounds.
     *
     * <p>Played here rather than server-side, because a marker never enters a {@code GameSlot} — it is a
     * client-side prop the server only hears about as a bit flip — so the sounds {@code GameSlot} would
     * have made for it are made by hand, with the same two events and at the same two moments: a lift is a
     * card leaving a slot, a put-down is a card entering one.
     *
     * <h2>Why {@code playLocalSound} and not the obvious call</h2>
     *
     * <p>{@code CardPlayer.playSound} — the method Charta's own games use — goes through
     * {@code level.playSound(player, x, y, z, ...)}, and on a <em>client</em> level that is a dead end: it
     * forwards to the {@code BlockPos} overload, which {@code Level} leaves empty and {@code ClientLevel}
     * never overrides. So a client-side card sound through the obvious path is silently dropped, which is
     * exactly what these were. {@code playLocalSound} is the one {@code ClientLevel} actually implements.
     */
    private void cardSound(java.util.function.Supplier<net.minecraft.sounds.SoundEvent> sound) {
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        CardPlayer local = menu.getCardPlayer();
        var entity = local == null ? null : local.getEntity();
        if (entity == null) {
            return;
        }
        minecraft.level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), sound.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1f,
                0.7f + entity.getRandom().nextFloat() * 0.5f, false);
    }

    /** The pile's own box, where the spare proposal flags sit. */
    private int[] pileBox() {
        return LAYOUT.bounds(AvalonLayout.Element.PILE, width, height);
    }

    /** The seat whose plate (or flag under it) is under the pointer, or {@code -1}. */
    private int seatAtPlate(double mouseX, double mouseY) {
        for (int r = 0; r < ring.length; r++) {
            if (inside(plateZone(r), mouseX, mouseY)) {
                return ring[r];
            }
        }
        return -1;
    }

    private void send(int action) {
        ChartaMod.getPacketManager().sendToServer(new AvalonActionPayload(menu.containerId, action));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing && dragging && selected != null) {
            // The deltas are window pixels like everything else the event system hands over, so they
            // shrink with the frame; without the division a drag would outrun the pointer.
            AvalonFrame frame = AvalonFrame.current();
            LAYOUT.move(selected, (float) frame.toFrameLength(dragX), (float) frame.toFrameLength(dragY));
            applyLayout();
            return true;
        }
        if (carryingFlag) {
            this.dragX = frameMouseX;
            this.dragY = frameMouseY;
        }
        return super.mouseDragged(frameMouseX, frameMouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing && dragging) {
            dragging = false;
            LAYOUT.save();
            return true;
        }
        if (identityCurtainUp() && !overCornerButton(frameMouseX, frameMouseY)) {
            // Paired with the swallowed press, so the slot layer below sees neither half of the click;
            // the corner buttons are exempt on both halves for the same reason.
            return true;
        }
        if (carryingFlag) {
            // A click lifts and drops, so the release decides nothing; it only keeps the flag alive while
            // the finger drags. Swallow it so the slot layer below does not also see the click.
            return true;
        }
        return super.mouseReleased(frameMouseX, frameMouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double frameMouseX = frameX(mouseX);
        double frameMouseY = frameY(mouseY);
        if (editing) {
            AvalonLayout.Element element = selected != null ? selected : elementAt(frameMouseX, frameMouseY);
            if (element != null) {
                LAYOUT.scaleBy(element, (float) scrollY * AvalonLayout.SCALE_STEP);
                applyLayout();
                LAYOUT.save();
                return true;
            }
        }
        return super.mouseScrolled(frameMouseX, frameMouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) {
            editing = !editing;
            if (editing) {
                selected = null;
            } else {
                LAYOUT.save();
            }
            return true;
        }
        if (editing && keyCode == GLFW.GLFW_KEY_R) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                LAYOUT.resetAll();
                selected = null;
            } else if (selected != null) {
                LAYOUT.reset(selected);
            }
            applyLayout();
            LAYOUT.save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------- helpers ---

    private void drawStatusLine(GuiGraphics guiGraphics, Component text, AvalonLayout.Element element, int color) {
        int[] box = LAYOUT.bounds(element, width, height);
        float scale = LAYOUT.scale(element);
        int x = box[0] + (box[2] - Math.round(font.width(text) * scale)) / 2;
        int y = box[1] + (box[3] - Math.round(STATUS_TEXT_H * scale)) / 2;
        if (scale == 1f) {
            guiGraphics.drawString(font, text, x, y, color, true);
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0d);
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.drawString(font, text, 0, 0, color, true);
        guiGraphics.pose().popPose();
    }

    private void frame(GuiGraphics guiGraphics, int x, int y, int w, int h, int color) {
        guiGraphics.fill(x, y, x + w, y + 1, color);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, color);
        guiGraphics.fill(x, y, x + 1, y + h, color);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private Component nameOf(int seat) {
        CardPlayer player = menu.getGame().playerAt(seat);
        if (seat == menu.getLocalSeat()) {
            return Component.translatable("status.myavalon.you");
        }
        return player == null ? Component.empty() : player.getName();
    }

    private static int rgb(DyeColor color) {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** Unused twin of {@link #rgb}, kept for the seat colour that the wool frames used to show. */
    private static int rgbOf(CardPlayer player) {
        return rgb(player.getColor());
    }

    /** Microsecond-free "how long has this been up" clock, used by the editor readout only. */
    private static long now() {
        return Util.getMillis();
    }

    /** The pile's slot, as a card container; handy for tests that want the raw slot. */
    private GameSlot pileSlot() {
        return menu.getPileCards();
    }
}
