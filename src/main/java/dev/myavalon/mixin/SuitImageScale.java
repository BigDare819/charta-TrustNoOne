package dev.myavalon.mixin;

import dev.lucaargolo.charta.common.utils.SuitImage;
import dev.lucaargolo.charta.common.utils.ZIPCompression;
import dev.myavalon.util.ScaledImages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.Arrays;

/**
 * The {@link CardImageScale} treatment for suit glyphs: read a {@code .mcsuit} blob at whatever integer scale
 * its length implies instead of forcing 13x13.
 *
 * <p>Suit reading is the only hook needed — {@code copy()} is inherited from CardImage and is already handled
 * there. Suit art is small enough that most packs will leave it at 13x13, but the same script emits both and a
 * uniform rule keeps them from drifting apart.
 */
@Mixin(SuitImage.class)
public abstract class SuitImageScale {

    @Invoker(value = "<init>", remap = false)
    public static SuitImage myavalon$create(byte[] pixels, int width, int height) {
        throw new AssertionError();
    }

    @Inject(method = "decompress([B)Ldev/lucaargolo/charta/common/utils/SuitImage;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void myavalon$decompressAtRealSize(byte[] data, CallbackInfoReturnable<SuitImage> cir) {
        int[] size = ScaledImages.readHeader(data);
        byte[] payload = size == null ? data : Arrays.copyOfRange(data, ScaledImages.HEADER_SIZE, data.length);
        byte[] pixels;
        try {
            pixels = ZIPCompression.decompress(payload);
        } catch (IOException exception) {
            return;
        }
        if (size == null) {
            size = ScaledImages.guess(pixels.length, SuitImage.WIDTH, SuitImage.HEIGHT);
        }
        cir.setReturnValue(myavalon$create(pixels, size[0], size[1]));
    }
}
