package dev.trustnoone.mixin;

import dev.lucaargolo.charta.common.utils.CardImage;
import dev.lucaargolo.charta.common.utils.ZIPCompression;
import dev.trustnoone.util.ScaledImages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.util.Arrays;

/**
 * Lets a Charta card blob carry more than 25x35 pixels, so a pack can ship its art at full resolution.
 *
 * <p>Everything downstream is already size-agnostic — {@code CardImageUtils.convertImage} builds the texture
 * at {@code image.getWidth()/getHeight()}, and every blit uses normalised 0..1 UVs — while the only fixed
 * points are the two static readers and the copying helper. So rather than touching {@code WIDTH/HEIGHT}
 * (which are also the <em>layout</em> constants every slot, widget and half the mod multiplies), we let the
 * reader grow and keep the constants where they are.
 *
 * <p>Two readers matter:
 * <ul>
 *   <li>{@code decompress(byte[])} — the one the resource loader, the menu sync codec and the file loader all
 *       funnel through. We re-derive the size from the decompressed length and build the image ourselves.</li>
 *   <li>{@code copy()} — the lone size-sensitive spot: it allocates a fresh {@code new CardImage()} (25x35) and
 *       then copies {@code totalPixels} bytes into it, which overruns the buffer once the source is larger.
 *       {@code GameSlot} calls this every time a slot is snapshotted, so it has to be fixed.</li>
 * </ul>
 *
 * <p>Both injections are {@code @Inject(cancellable)} rather than {@code @Redirect}, so a sibling addon that
 * touches the same methods still gets to run and stays equivalent as long as it derives the size the same way.
 */
@Mixin(CardImage.class)
public abstract class CardImageScale {

    @Shadow(remap = false)
    private byte[] pixels;

    @Shadow(remap = false)
    private int totalPixels;

    @Shadow(remap = false)
    public abstract int getWidth();

    @Shadow(remap = false)
    public abstract int getHeight();

    /** The {@code protected CardImage(byte[], int, int)} constructor, which is out of reach from another package. */
    @Invoker(value = "<init>", remap = false)
    public static CardImage trustnoone$create(byte[] pixels, int width, int height) {
        throw new AssertionError();
    }

    @Inject(method = "decompress([B)Ldev/lucaargolo/charta/common/utils/CardImage;", at = @At("HEAD"), cancellable = true, remap = false)
    private static void trustnoone$decompressAtRealSize(byte[] data, CallbackInfoReturnable<CardImage> cir) {
        int[] size = ScaledImages.readHeader(data);
        byte[] payload = size == null ? data : Arrays.copyOfRange(data, ScaledImages.HEADER_SIZE, data.length);
        byte[] pixels;
        try {
            pixels = ZIPCompression.decompress(payload);
        } catch (IOException exception) {
            return; // Fall through to vanilla, which logs the failure.
        }
        if (size == null) {
            size = ScaledImages.guess(pixels.length, CardImage.WIDTH, CardImage.HEIGHT);
        }
        cir.setReturnValue(trustnoone$create(pixels, size[0], size[1]));
    }

    /**
     * Writes the size back into the header on the way out, so a blob that is decompressed, edited and
     * recompressed (Charta's pack tooling does this) keeps the exact size instead of degrading to the guess.
     */
    @Inject(method = "compress()[B", at = @At("HEAD"), cancellable = true, remap = false)
    private void trustnoone$compressWithHeader(CallbackInfoReturnable<byte[]> cir) {
        if (this.pixels == null) {
            return;
        }
        byte[] compressed;
        try {
            compressed = ZIPCompression.compress(this.pixels);
        } catch (IOException exception) {
            return; // Fall through to vanilla, which logs the failure.
        }
        byte[] header = ScaledImages.writeHeader(this.getWidth(), this.getHeight());
        byte[] out = new byte[header.length + compressed.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(compressed, 0, out, header.length, compressed.length);
        cir.setReturnValue(out);
    }

    @Inject(method = "copy()Ldev/lucaargolo/charta/common/utils/CardImage;", at = @At("HEAD"), cancellable = true, remap = false)
    private void trustnoone$copyAtRealSize(CallbackInfoReturnable<CardImage> cir) {
        if (this.pixels == null || this.totalPixels > this.pixels.length) {
            return;
        }
        byte[] copy = new byte[this.totalPixels];
        System.arraycopy(this.pixels, 0, copy, 0, this.totalPixels);
        // Rebuilding through the constructor also re-derives averageColor, so it needs no manual copy.
        cir.setReturnValue(trustnoone$create(copy, this.getWidth(), this.getHeight()));
    }
}
