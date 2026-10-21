package dev.myavalon.mixin;

import dev.lucaargolo.charta.client.compat.IrisCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Charta always believe a shader mod is installed, so it takes its "full colour" path everywhere.
 *
 * <p>Charta has two rendering paths and picks between them on {@code IrisCompat.isPresent()}: with Iris it
 * builds {@code RGBA} textures and samples them directly ({@code card_argb} shader, {@code entityTranslucent}
 * render type); without it, it packs pixels into a luminance texture and has the {@code card} shader look
 * each byte up in a hard-coded 64-colour palette. Since {@code isPresent()} is nothing but a couple of
 * {@code isModLoaded} checks — it never touches an Iris class — we can flip it to true without Iris installed
 * and get the direct-sampling path.
 *
 * <p>That matters because the palette path interprets a texture's red channel as a palette index, so it simply
 * cannot represent the full-colour PNGs {@link FullColorCards} loads. With this forced on, every card texture
 * (ours and Charta's built-ins alike) is RGBA and every consumer samples it directly.
 *
 * <p>The descriptor is spelled out rather than left to inference: this is a zero-argument {@code static}
 * method, and a silent miss would be hard to notice given the config's {@code defaultRequire} of 0.
 */
@Mixin(IrisCompat.class)
public abstract class IrisCompatPresent {

    @Inject(method = "isPresent()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void myavalon$alwaysPresent(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
