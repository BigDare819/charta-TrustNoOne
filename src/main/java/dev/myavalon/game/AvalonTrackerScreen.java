package dev.myavalon.game;

import dev.myavalon.client.CardTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The card tracker, opened from the table's top-right history button.
 *
 * <p>Charta's own history is a list of plays; this is the streamlined reading the table actually wants:
 * one block per quest round with a yellow round/quest header, then the Angel's check, each vote, the
 * King's Sword's flip and the quest's result. The model is {@link CardTracker}, which is written from
 * the table screen every frame — this screen keeps writing it too, because the game carries on behind
 * it and an event that happens while the log is open must not be lost.
 */
public class AvalonTrackerScreen extends Screen {

    private final Screen parent;
    private final AvalonMenu menu;

    /** Row offset, in GUI pixels; the wheel moves it. */
    private double scroll;

    /** Header lines are drawn larger than the events, as asked. */
    private static final float HEADER_SCALE = 1.6f;
    private static final float LINE_SCALE = 1.0f;
    private static final int PAD = 16;

    public AvalonTrackerScreen(Screen parent, AvalonMenu menu) {
        super(Component.translatable("tracker.myavalon.title"));
        this.parent = parent;
        this.menu = menu;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("\u25c0"), b -> onClose())
                .bounds(5, 5, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("message.charta.go_back")))
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20)
                .build());
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Keep the model current while the log is open: the game is still running behind it.
        CardTracker.update(menu);
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);

        List<CardTracker.Round> rounds = CardTracker.rounds();
        int headerHeight = Math.round((font.lineHeight + 3) * HEADER_SCALE);
        int lineHeight = Math.round((font.lineHeight + 2) * LINE_SCALE);
        int gap = 14;
        int contentHeight = 0;
        for (CardTracker.Round round : rounds) {
            contentHeight += headerHeight + round.lines.size() * lineHeight + gap;
        }
        int top = 30;
        int bottom = height - 34;
        double maxScroll = Math.max(0, contentHeight - (bottom - top));
        scroll = Mth.clamp(scroll, 0, maxScroll);

        if (rounds.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.translatable("tracker.myavalon.empty"),
                    width / 2, height / 2, CardTracker.DIM);
            return;
        }

        // Clip to the content area so a scrolled block cannot draw over the title or the buttons.
        guiGraphics.enableScissor(0, top, width, bottom);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0f, (float) (top - scroll), 0f);
        int y = 0;
        int left = PAD;
        int right = width - PAD;
        for (int i = 0; i < rounds.size(); i++) {
            CardTracker.Round round = rounds.get(i);
            Component header = round.quest >= 0
                    ? Component.translatable("tracker.myavalon.round", round.round, round.quest + 1)
                    : Component.translatable("tracker.myavalon.round_only", round.round)
                    .withStyle(style -> style.withColor(CardTracker.GOLD).withBold(true));
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(left, y, 0f);
            guiGraphics.pose().scale(HEADER_SCALE, HEADER_SCALE, 1f);
            guiGraphics.drawString(font, header, 0, 0, 0xFFFFFFFF, true);
            guiGraphics.pose().popPose();
            y += headerHeight;
            for (Component line : round.lines) {
                guiGraphics.pose().pushPose();
                int lineWidth = font.width(line);
                float fit = lineWidth > (right - left) ? (right - left) / (float) lineWidth : 1f;
                guiGraphics.pose().translate(left, y, 0f);
                guiGraphics.pose().scale(fit, fit, 1f);
                guiGraphics.drawString(font, line, 0, 0, 0xFFFFFFFF, true);
                guiGraphics.pose().popPose();
                y += lineHeight;
            }
            if (i < rounds.size() - 1) {
                guiGraphics.fill(left, y + gap / 2, right, y + gap / 2 + 1, 0x60FFFFFF);
            }
            y += gap;
        }
        guiGraphics.pose().popPose();
        guiGraphics.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= scrollY * 18;
        return true;
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
