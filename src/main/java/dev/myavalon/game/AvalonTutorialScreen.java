package dev.myavalon.game;

import dev.lucaargolo.charta.client.render.screen.MarkdownScreen;
import dev.myavalon.client.GuiTextureFilters;
import dev.myavalon.client.UiBlend;
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

    /** Screen margin the picture keeps clear of the title and the button row. */
    private static final int TOP = 26;
    private static final int BOTTOM = 58;

    private static final List<ResourceLocation> TEXTURES = new ArrayList<>(PAGES);

    static {
        for (int i = 1; i <= PAGES; i++) {
            TEXTURES.add(ResourceLocation.fromNamespaceAndPath("myavalon",
                    "textures/gui/tutorial/cn_" + i + ".png"));
        }
    }

    private final Screen parent;
    private int page;

    @Nullable
    private Button previous;
    @Nullable
    private Button next;

    public AvalonTutorialScreen(Screen parent) {
        super(Component.translatable("tutorial.myavalon.title"));
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
        addRenderableWidget(Button.builder(Component.translatable("tutorial.myavalon.text"), b -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new MarkdownScreen(title, this, "myavalon.how_to_play_avalon"));
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
        // Full-resolution pages, mip-chained for the heavy downscale; see GuiTextureFilters.
        GuiTextureFilters.ensure(new ResourceLocation[0], GuiTextureFilters.tutorialSprites());
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int areaX = 16;
        int areaY = TOP;
        int areaW = width - areaX * 2;
        int areaH = height - TOP - BOTTOM;
        float scale = Math.min(areaW / (float) SOURCE_W, areaH / (float) SOURCE_H);
        int drawW = Math.round(SOURCE_W * scale);
        int drawH = Math.round(SOURCE_H * scale);
        int drawX = (width - drawW) / 2;
        int drawY = areaY + (areaH - drawH) / 2;

        UiBlend.on();
        guiGraphics.blit(TEXTURES.get(page), drawX, drawY, drawW, drawH,
                0f, 0f, SOURCE_W, SOURCE_H, SOURCE_W, SOURCE_H);

        guiGraphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(font,
                Component.translatable("tutorial.myavalon.page", page + 1, PAGES),
                width / 2, height - 44, 0xFFCFCFCF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && super.mouseClicked(mouseX, mouseY, button)) {
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
        return mouseY >= TOP && mouseY < height - BOTTOM && mouseX >= 16 && mouseX < width - 16;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
