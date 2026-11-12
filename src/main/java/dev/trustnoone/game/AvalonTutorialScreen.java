package dev.trustnoone.game;

import dev.lucaargolo.charta.client.render.screen.MarkdownScreen;
import dev.trustnoone.client.GuiTextureFilters;
import dev.trustnoone.client.UiBlend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The illustrated tutorial: the pages of the deck, one per screen, with a way through to the text rules.
 *
 * <h2>Why a screen of pictures instead of more markdown</h2>
 *
 * <p>Charta's {@code MarkdownScreen} understands paragraphs, headings, lists, bold and italic -- and no
 * images at all. The deck is almost entirely pictures of the table with callouts drawn on them, so its
 * pages are shipped as textures and paged here rather than flattened into text that would lose every
 * pointer in it. The rules button in the table strip now opens this screen, which is what "in the
 * how-to-play" means in practice: the first thing the book shows is the illustrated tour, and its
 * {@code 文字规则} button still opens the original markdown page for the parts that read better as prose.
 *
 * <p>The pages are 1920x1080 and are always fitted, never stretched: the draw box is derived from the
 * screen's aspect so the deck keeps its shape at any GUI scale. Pages advance by the arrow buttons, the
 * left/right keys, or a click on the half of the picture it leads to.
 */
public class AvalonTutorialScreen extends Screen {

    private static final int PAGES = 9;
    private static final int SOURCE_W = 1920;
    private static final int SOURCE_H = 1080;

    private static final List<ResourceLocation> TEXTURES = new ArrayList<>(PAGES);

    static {
        for (int i = 1; i <= PAGES; i++) {
            TEXTURES.add(ResourceLocation.fromNamespaceAndPath("trustnoone",
                    "textures/gui/tutorial/cn_" + i + ".png"));
        }
    }

    private final Screen parent;
    private int page;
    /**
     * Whether the page is shown at its native 100% size instead of fitted into the frame. Toggled with F;
     * the frame's title and buttons are hidden under the page while it is on, which is the point.
     */
    private boolean nativeView;

    @Nullable
    private Button previous;
    @Nullable
    private Button next;

    public AvalonTutorialScreen(Screen parent) {
        super(Component.translatable("tutorial.trustnoone.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int rowY = height - 30;
        int centre = width / 2;
        this.previous = addRenderableWidget(Button.builder(Component.literal("\u25c0"), b -> turn(-1))
                .bounds(centre - 96, rowY, 28, 20)
                .build());
        this.next = addRenderableWidget(Button.builder(Component.literal("\u25b6"), b -> turn(1))
                .bounds(centre + 68, rowY, 28, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(centre - 28, rowY, 56, 20)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("tutorial.trustnoone.text"), b -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new MarkdownScreen(title, this, "trustnoone.how_to_play_avalon"));
                    }
                })
                // Above the row on the right, so it cannot collide with the page buttons on a narrow GUI.
                .bounds(width - 96, rowY - 24, 88, 20)
                .build());
        turn(0);
    }

    private void turn(int delta) {
        this.page = Mth.clamp(page + delta, 0, PAGES - 1);
        if (previous != null) {
            previous.active = page > 0;
        }
        if (next != null) {
            next.active = page < PAGES - 1;
        }
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        // Full-resolution pages, filtered sharp: no mip chain, so the page is not blended with a
        // quarter-resolution level; see GuiTextureFilters.
        GuiTextureFilters.ensureSharp(GuiTextureFilters.tutorialSprites());
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // The page has to be drawn AFTER super.render, not before: super.render runs
        // renderBackground again, whose blurred-background pass is a full-screen post effect over
        // whatever is already in the framebuffer. A page painted before it would come out blurred; this
        // is the "there is a blur filter on the pictures" report.
        int[] box = pageBox();
        UiBlend.on();
        guiGraphics.blit(TEXTURES.get(page), box[0], box[1], box[2], box[3],
                0f, 0f, SOURCE_W, SOURCE_H, SOURCE_W, SOURCE_H);

        if (nativeView) {
            // Over the page, not under it: the page is drawn last and would cover anything the widget
            // pass laid down.
            Component hint = Component.translatable("tutorial.trustnoone.native_hint");
            guiGraphics.fill(width / 2 - font.width(hint) / 2 - 4, 2,
                    width / 2 + font.width(hint) / 2 + 4, 2 + font.lineHeight + 2, 0x99000000);
            guiGraphics.drawCenteredString(font, hint, width / 2, 4, 0xFFE0E0E0);
            return;
        }
        guiGraphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font,
                Component.translatable("tutorial.trustnoone.page", page + 1, PAGES),
                width / 2, height - 44, 0xFFCFCFCF);
    }

    /**
     * Where the page is drawn: fitted inside the framed area under the title and above the button row.
     *
     * <p>It is <em>not</em> blown up to the window. A page at the window's size is, on a 1080p display,
     * physically the same 1920x1080 the file is, but it also swallows the title and the navigation
     * buttons, and the screen reads as one big picture rather than a page you turn. The picture therefore
     * keeps its frame; the filtering (no mip chain -- see {@link GuiTextureFilters#ensureSharp}) is what
     * keeps it from going soft on the way down.
     */
    private int[] pageBox() {
        if (nativeView) {
            return nativeBox();
        }
        int areaX = 16;
        int areaY = 26;
        int areaW = width - areaX * 2;
        int areaH = height - 26 - 58;
        // Never larger than the source's own pixels: on a window wider than 1920 the framed area would
        // otherwise magnify the page past 100%, which is exactly the softness a picture viewer never
        // shows. Capped, a large window sees the page 1:1, only a smaller one has to shrink it.
        // One texel per physical pixel, expressed in GUI pixels.
        float perTexel = 1f / guiScale();
        float scale = Math.min(perTexel, Math.min(areaW / (float) SOURCE_W, areaH / (float) SOURCE_H));
        int drawW = Math.round(SOURCE_W * scale);
        int drawH = Math.round(SOURCE_H * scale);
        return new int[]{(width - drawW) / 2, areaY + (areaH - drawH) / 2, drawW, drawH};
    }

    /**
     * The page at exactly one texel per physical pixel, centred — what "F" switches to.
     *
     * <p>This is the size at which the file is shown un-resampled, i.e. what the desktop viewer shows at
     * 100%. It cannot share the screen with the title and the button row (on a 1080p window it is the
     * whole window), which is why it is a view you ask for rather than the default.
     */
    private int[] nativeBox() {
        float guiScale = guiScale();
        int drawW = Math.min(width, Math.round(SOURCE_W / guiScale));
        int drawH = Math.round(drawW * (SOURCE_H / (float) SOURCE_W));
        if (drawH > height) {
            drawH = height;
            drawW = Math.round(drawH * (SOURCE_W / (float) SOURCE_H));
        }
        return new int[]{(width - drawW) / 2, (height - drawH) / 2, drawW, drawH};
    }

    /** The window's GUI scale, guarded against a screen used before the window exists. */
    private float guiScale() {
        double scale = minecraft == null || minecraft.getWindow() == null
                ? 1.0 : minecraft.getWindow().getGuiScale();
        return (float) Math.max(1.0, scale);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // In the 100% view the widget row is hidden underneath the page, so its clicks must not fire.
        if (!nativeView && button == 0 && super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        // The picture itself is the largest next/previous target on a mouse; the buttons stay for
        // discoverability and for keyboard users.
        if (button == 0 && insideImage(mouseX, mouseY)) {
            turn(mouseX < width / 2.0 ? -1 : 1);
            return true;
        }
        return false;
    }

    private boolean insideImage(double mouseX, double mouseY) {
        int[] box = pageBox();
        return mouseX >= box[0] && mouseX < box[0] + box[2]
                && mouseY >= box[1] && mouseY < box[1] + box[3];
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F) {
            nativeView = !nativeView;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            turn(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            turn(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
