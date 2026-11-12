package dev.trustnoone.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * The two pieces {@code Screen} keeps private/protected that the tracker button needs: the renderable
 * list (to find Charta's own history button) and {@code addRenderableWidget} (to put ours in its place,
 * which also wires it into {@code children()} so it receives clicks).
 */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Accessor("renderables")
    List<Renderable> trustnoone$renderables();

    @Accessor("children")
    List<GuiEventListener> trustnoone$children();

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T trustnoone$addWidget(T widget);
}
