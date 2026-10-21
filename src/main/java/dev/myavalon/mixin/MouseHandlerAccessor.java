package dev.myavalon.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writable cursor position, so the dev harness can drive a hover without a mouse.
 *
 * <p>{@code MouseHandler.onMove} is private and only runs when GLFW reports a real cursor move, which a
 * headless {@code runClient} on an unfocused window never gets. Poking the two fields straight is the
 * shortest path to "the pointer is over that card" and it is what every reader of {@code xpos()} -- the
 * game renderer's tooltip mouse, {@code GameScreen.containerTick}, the screen's own input -- all end up
 * consuming, so nothing has to be taught about it.
 *
 * <p>The values are in raw cursor space: {@code xpos * guiScaledWidth / screenWidth} is the GUI
 * coordinate.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("xpos")
    void myavalon$setXpos(double x);

    @Accessor("ypos")
    void myavalon$setYpos(double y);
}
