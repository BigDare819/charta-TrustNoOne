package dev.trustnoone.mixin;

import dev.lucaargolo.charta.client.ChartaModClient;
import dev.trustnoone.client.CardArt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Charta's palette-quantised card textures with the pack's full-colour PNGs.
 *
 * <p>All of the work — and the reasoning about the palette shader, the mip chain and the language
 * variants — lives in {@link CardArt}; this mixin is only the hook that makes the pack's art win
 * right after Charta registers the blob textures. {@code CardArt.rebind()} reuses the same code path
 * when the player switches language mid-session.
 */
@Mixin(ChartaModClient.class)
public abstract class FullColorCards {

    @Inject(method = "generateImages()V", at = @At("TAIL"), remap = false)
    private static void trustnoone$bindFullColorArt(CallbackInfo ci) {
        CardArt.bindAll();
    }
}
