package dev.trustnoone.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lucaargolo.charta.common.utils.CardImage;
import dev.lucaargolo.charta.common.utils.CardImageUtils;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives Charta's generated card/suit textures a mipmap chain — but only for the upscaled ones.
 *
 * <p>{@code CardImageUtils.convertImage} hands its pixels to a {@code DynamicTexture}, whose constructor only
 * ever calls {@code TextureUtil.prepareImage(id, width, height)} — the overload that pins the texture's max
 * level to 0. That is fine for stock 25x35 art, which is roughly drawn at 1:1 in the GUI, but the whole point
 * of {@link CardImageScale} is to carry 13x art that then gets minified hard (a 325x455 card lands on a
 * ~40-100px quad). Without a mip chain that minification aliases badly and shimmers while the hand fans out.
 *
 * <p>Which is only true on the {@code rgba} path, though, and the reason is subtle: without a shader mod
 * Charta stores each pixel as a <em>palette index</em> in a {@code LUMINANCE} texture, and {@code card.fsh}
 * decodes the red byte back into a 6-bit colour index plus a 2-bit alpha index. A mip level that averages raw
 * indices is therefore meaningless — indices are categorical, not linear, so averaging a green (39) against a
 * transparent neighbour (0) yields 19, i.e. orange, and every outline picks up a wrongly-coloured halo. On the
 * {@code rgba} path ({@link IrisCompatPresent} forces it) the bytes really are colours, averaging is correct,
 * and mipmapping does exactly what it should.
 *
 * <p>Stock-sized art is left completely untouched: Charta never calls {@code setFilter} itself, so its textures
 * keep the GL defaults (nearest magnification of the pixel art). Calling {@code setFilter} on those would swap
 * them over to linear filtering and soften every built-in deck — the "everything has a blur filter" look.
 *
 * <p>{@code setFilter} alone is not enough — it only pokes the GL min/mag filters and never builds the lower
 * levels, and the pinned max level would keep them unreachable anyway. So: set the max level, then let the
 * driver build the chain with {@code glGenerateMipmap}.
 *
 * <p>Client-only, because the GL calls only run from the client's texture-generation pass.
 */
@Mixin(CardImageUtils.class)
public abstract class CardImageFilters {

    /** 325x455 -> level 4 is 20x28, past the point a card is ever drawn that small. */
    private static final int TRUSTNOONE$MAX_MIP_LEVEL = 4;

    @Inject(method = "convertImage", at = @At("RETURN"))
    private static void trustnoone$buildMipmaps(CardImage image, boolean rgba, boolean glow,
                                              CallbackInfoReturnable<DynamicTexture> cir) {
        DynamicTexture texture = cir.getReturnValue();
        if (texture == null || !trustnoone$isUpscaled(image)) {
            return;
        }
        // generateImages also runs off the image sync payload, so this may be off the render thread; the GL
        // calls below have to be marshalled onto it.
        if (RenderSystem.isOnRenderThread()) {
            trustnoone$buildMipmapsNow(texture, rgba);
        } else {
            RenderSystem.recordRenderCall(() -> trustnoone$buildMipmapsNow(texture, rgba));
        }
    }

    /** Stock art is exactly {@code CardImage}'s base box; {@link CardImageScale} only ever grows it. */
    private static boolean trustnoone$isUpscaled(CardImage image) {
        return image.getWidth() > CardImage.WIDTH || image.getHeight() > CardImage.HEIGHT;
    }

    private static void trustnoone$buildMipmapsNow(DynamicTexture texture, boolean rgba) {
        GlStateManager._bindTexture(texture.getId());
        if (rgba) {
            // Real colours: a mip chain is both correct and wanted (big textures, small quads). blur=false
            // keeps magnification at GL_NEAREST so the authored pixels stay sharp; the min filter still
            // reaches the mip chain (GL_NEAREST_MIPMAP_LINEAR) so the hard downscale does not alias.
            texture.setFilter(false, true);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, TRUSTNOONE$MAX_MIP_LEVEL);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } else {
            // Palette indices: no mip chain. A mip chain would average the indices themselves and corrupt
            // both the colour and the alpha nibble at every edge, so it is better off than on. Nearest
            // sampling keeps the indexed art crisp.
            texture.setFilter(false, false);
        }
    }
}
