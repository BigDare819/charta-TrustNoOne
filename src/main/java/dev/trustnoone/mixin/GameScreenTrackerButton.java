package dev.trustnoone.mixin;

import dev.lucaargolo.charta.client.render.screen.GameScreen;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.trustnoone.game.AvalonMenu;
import dev.trustnoone.game.AvalonTrackerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the table's top-right history button into the card tracker, and makes its light follow the
 * tracker option.
 *
 * <p>Charta adds a history button at {@code (width - 47, 35)} that opens its own play log. The log is
 * not what this table wants to read: <em>this</em> tracker is the per-round brief (see
 * {@code CardTracker}), so the button is kept exactly where it is, with the same glyph and tooltip,
 * but pressed it opens the tracker.
 *
 * <h2>Why everything is cleared first</h2>
 *
 * <p>Closing a screen returns to the same {@code GameScreen} object, and Minecraft re-runs its
 * {@code init} <em>without</em> clearing the widget lists (only {@code rebuildWidgets} clears). Charta
 * therefore re-adds its history button on every return, and a naive hook added a second tracker button
 * on top of ours. So every widget wearing the history glyph -- Charta's copies and ours -- is removed
 * from both lists first, and exactly one tracker button is put back. Other Charta buttons may still be
 * duplicated by that re-init, but they are identical and harmless; this one decides what the top-right
 * corner does.
 *
 * <p>"Off" is a dark button: {@code active} is read straight from the mirrored option, which is the
 * same slot the server fills, so what the player sees lit is what the game will show.
 */
@Mixin(GameScreen.class)
public abstract class GameScreenTrackerButton {

    private static final String HISTORY_GLYPH = "\uE889";

    @Inject(method = "init", at = @At("TAIL"))
    private void trustnoone$trackerButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (!(self instanceof GameScreen<?, ?> gameScreen)
                || !(gameScreen.getMenu() instanceof AvalonMenu menu)) {
            return;
        }
        ScreenAccessor access = (ScreenAccessor) (Object) this;

        int x = self.width - 47;
        int y = 35;
        int buttonW = 20;
        int buttonH = 20;
        boolean found = false;
        List<Renderable> renderables = new ArrayList<>(access.trustnoone$renderables());
        List<GuiEventListener> children = new ArrayList<>(access.trustnoone$children());
        for (Renderable renderable : renderables) {
            if (!(renderable instanceof Button button)
                    || !HISTORY_GLYPH.equals(button.getMessage().getString())) {
                continue;
            }
            // Position and size come off the freshest button; every copy is at the same place.
            if (!found) {
                x = button.getX();
                y = button.getY();
                buttonW = button.getWidth();
                buttonH = button.getHeight();
                found = true;
            }
            access.trustnoone$renderables().remove(renderable);
            children.remove(renderable);
        }
        List<GuiEventListener> liveChildren = access.trustnoone$children();
        liveChildren.clear();
        liveChildren.addAll(children);
        if (!found) {
            return;
        }

        Button tracker = Button.builder(Component.literal(HISTORY_GLYPH).withStyle(ChartaMod.SYMBOLS),
                        b -> Minecraft.getInstance().setScreen(new AvalonTrackerScreen(self, menu)))
                .bounds(x, y, buttonW, buttonH)
                .tooltip(Tooltip.create(Component.translatable("message.charta.game_history")))
                .build();
        tracker.active = menu.isTracker();
        access.trustnoone$addWidget(tracker);
    }
}
