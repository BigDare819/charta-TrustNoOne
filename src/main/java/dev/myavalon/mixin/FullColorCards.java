package dev.myavalon.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.lucaargolo.charta.client.ChartaModClient;
import dev.lucaargolo.charta.client.compat.IrisCompat;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.utils.CardImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces Charta's palette-quantised card textures with the pack's full-colour PNGs.
 *
 * <p>Even with {@link CardImageScale} keeping the resolution, every {@code .mccard} pixel is one byte: six bits
 * of palette index and two bits of alpha, so each pixel can only be one of 64 colours at one of 4 alpha levels.
 * On flat fills that is invisible, but it hard-bands the anti-aliased outlines against the transparent
 * background — which is exactly the "still compressed" look.
 *
 * <p>So the art ships twice: the blob stays the wire/sync format, and an ordinary RGBA PNG next to it is what
 * the client binds. This runs at the tail of {@code generateImages} — after Charta (and {@code IrisCompat}) have
 * registered the textured-up blobs — and re-registers the same texture ids from the PNGs. Anything without a
 * PNG falls through to the blob, so the mod still works with only half the resources present.
 *
 * <p><b>The bound texture has to match the shader Charta will draw it with</b>, and both hinge on
 * {@link IrisCompat#isPresent()} — the palette shader reads the red channel as an index, so a full-colour
 * texture only decodes correctly on the direct-sampling path. {@link IrisCompatPresent} forces that path on, but
 * this class does not take it for granted: it checks the very same predicate at bind time and, if the flip ever
 * failed, falls back to handing the PNG back in Charta's own palette byte format so the palette shader still
 * decodes it correctly instead of turning the art into noise.
 */
@Mixin(ChartaModClient.class)
public abstract class FullColorCards {

    private static final Logger MYAVALON$LOGGER = LogUtils.getLogger();

    /** Past this level a 325x455 card is never drawn small enough to matter. */
    private static final int MYAVALON$MAX_MIP_LEVEL = 4;

    @Inject(method = "generateImages()V", at = @At("TAIL"), remap = false)
    private static void myavalon$bindFullColorArt(CallbackInfo ci) {
        boolean argb = IrisCompat.isPresent();
        Minecraft client = Minecraft.getInstance();
        TextureManager manager = client.getTextureManager();
        int[] counts = new int[2]; // [scheduled, seen]
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        ChartaMod.SUIT_IMAGES.getImages().keySet()
                .forEach(id -> myavalon$replace(client, manager, ChartaModClient.getSuitTexture(id), id, argb, counts, pending));
        ChartaMod.CARD_IMAGES.getImages().keySet()
                .forEach(id -> myavalon$replace(client, manager, ChartaModClient.getCardTexture(id), id, argb, counts, pending));
        ChartaMod.DECK_IMAGES.getImages().keySet()
                .forEach(id -> myavalon$replace(client, manager, ChartaModClient.getDeckTexture(id), id, argb, counts, pending));
        // Every PNG is read and decoded on the common pool, so the ~21 files (two of them 2 MB board scans)
        // no longer stall the render thread one after another on the payload path that opens a match. The
        // install itself still happens on the render thread (see {@link #myavalon$install}), so this only
        // waits on the decode half.
        //
        // On the natural resource-reload path generateImages runs on the render thread, and waiting here is
        // safe: the workers do no GL work. On the image-sync payload path it may already be off the render
        // thread (Charta runs it from a client executor), so joining would pin that executor for no reason --
        // the installs are recorded render calls and run on their own.
        if (RenderSystem.isOnRenderThread()) {
            pending.forEach(CompletableFuture::join);
        }
        MYAVALON$LOGGER.info("[myavalon] full-colour card art: argb={}, scheduled {}/{} textures",
                argb, counts[0], counts[1]);
    }

    private static void myavalon$replace(Minecraft client, TextureManager manager, ResourceLocation textureId,
                                         ResourceLocation artId, boolean argb, int[] counts,
                                         List<CompletableFuture<Void>> pending) {
        counts[1]++;
        Optional<Resource> resource = myavalon$findArt(client, artId);
        if (resource.isEmpty()) {
            return;
        }
        counts[0]++;
        Resource art = resource.get();
        pending.add(CompletableFuture.runAsync(() -> myavalon$decode(manager, textureId, artId, art, argb, counts)));
    }

    /** Reads the PNG and turns it into the bytes the shader expects; pure CPU, so it runs on a worker. */
    private static void myavalon$decode(TextureManager manager, ResourceLocation textureId, ResourceLocation artId,
                                        Resource art, boolean argb, int[] counts) {
        NativeImage pixels;
        try (InputStream stream = art.open()) {
            pixels = NativeImage.read(stream);
        } catch (Exception exception) {
            MYAVALON$LOGGER.warn("[myavalon] could not load full-colour art {}", artId, exception);
            return;
        }
        // The palette re-encode is a second byte loop, so it belongs on the worker too. It closes the source.
        NativeImage ready = argb ? pixels : myavalon$packAsPalette(pixels);
        myavalon$install(manager, textureId, ready, argb);
    }

    /**
     * Builds the {@link DynamicTexture} for a decoded image and registers it. The upload binds a GL id, so
     * when the decode ran on the common pool this is marshalled over to the render thread with a
     * {@code recordRenderCall}; the texture itself is created off-thread (which is safe -- the GL work is
     * deferred) so the id and the mip chain are the only pieces that wait for the render thread.
     */
    private static void myavalon$install(TextureManager manager, ResourceLocation textureId, NativeImage ready,
                                         boolean argb) {
        DynamicTexture texture = new DynamicTexture(ready);
        Runnable install = () -> {
            manager.register(textureId, texture);
            myavalon$buildMipmaps(texture, argb);
        };
        // register() calls DynamicTexture.load (a no-op) and the mip build binds the id, so both need the
        // render thread; from the payload path (already off-thread) they have to be marshalled.
        if (RenderSystem.isOnRenderThread()) {
            install.run();
        } else {
            RenderSystem.recordRenderCall(install::run);
        }
    }

    /**
     * Resolves the full-colour PNG for a texture id. Charta's ids carry no extension and the resource manager
     * does not invent one, so the ".png" is spelled out. A non-chinese client also looks under its own language
     * folder first (the pack ships english character art), then under {@code en_us}, and finally falls back to
     * the neutral top-level file — so a missing translation degrades to the chinese art instead of vanishing.
     */
    private static Optional<Resource> myavalon$findArt(Minecraft client, ResourceLocation artId) {
        String language = client.getLanguageManager().getSelected();
        List<ResourceLocation> candidates = new ArrayList<>();
        if (!language.startsWith("zh")) {
            candidates.add(artId.withPrefix("fullcard/" + language + "/").withSuffix(".png"));
            if (!language.startsWith("en")) {
                candidates.add(artId.withPrefix("fullcard/en_us/").withSuffix(".png"));
            }
        }
        candidates.add(artId.withPrefix("fullcard/").withSuffix(".png"));
        for (ResourceLocation candidate : candidates) {
            Optional<Resource> resource = client.getResourceManager().getResource(candidate);
            if (resource.isPresent()) {
                return resource;
            }
        }
        return Optional.empty();
    }

    /**
     * Re-encodes an RGBA image into Charta's one-byte-per-pixel palette format, for the (unexpected) case where
     * the palette shader is the one that will read it. Mirrors {@code CardImage}: low six bits are a palette
     * index, top two bits are an alpha index; index 0 doubles as "empty pixel".
     */
    private static NativeImage myavalon$packAsPalette(NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        NativeImage target = new NativeImage(NativeImage.Format.LUMINANCE, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = source.getPixelRGBA(x, y); // packed ABGR
                int alpha = (pixel >>> 24) & 0xFF;
                int red = pixel & 0xFF;
                int green = (pixel >> 8) & 0xFF;
                int blue = (pixel >> 16) & 0xFF;
                if (alpha < 8) {
                    target.setPixelLuminance(x, y, (byte) 0);
                } else {
                    int alphaIndex = alpha < 128 ? 1 : (alpha < 214 ? 2 : 3);
                    target.setPixelLuminance(x, y, (byte) ((alphaIndex << 6) | myavalon$nearestColour(red, green, blue)));
                }
            }
        }
        source.close();
        return target;
    }

    /** Index 0 is skipped because it doubles as the "empty pixel" marker. */
    private static int myavalon$nearestColour(int red, int green, int blue) {
        int best = 1;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 1; index < CardImage.COLOR_PALETTE.length; index++) {
            int rgb = CardImage.COLOR_PALETTE[index];
            int dr = ((rgb >> 16) & 0xFF) - red;
            int dg = ((rgb >> 8) & 0xFF) - green;
            int db = (rgb & 0xFF) - blue;
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static void myavalon$buildMipmaps(DynamicTexture texture, boolean argb) {
        // blur=false keeps magnification at GL_NEAREST so the authored pixels stay crisp; the min filter
        // still reaches the mip chain (rgba path) for the hard downscale in the hand.
        texture.setFilter(false, argb);
        if (!argb) {
            return; // palette indices are categorical, a mip chain would average them.
        }
        GlStateManager._bindTexture(texture.getId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MYAVALON$MAX_MIP_LEVEL);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }
}
