package dev.trustnoone.client;

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
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Binds the pack's full-colour PNGs over the palette textures Charta generated, and keeps them
 * current when the client language changes.
 *
 * <p>Even with {@code CardImageScale} keeping the resolution, every {@code .mccard} pixel is one byte:
 * six bits of palette index and two bits of alpha, so each pixel can only be one of 64 colours at one
 * of 4 alpha levels. On flat fills that is invisible, but it hard-bands the anti-aliased outlines
 * against the transparent background — which is exactly the "still compressed" look.
 *
 * <p>So the art ships twice: the blob stays the wire/sync format, and an ordinary RGBA PNG next to it
 * is what the client binds. {@link #bindAll()} runs at the tail of {@code generateImages} — after
 * Charta (and {@code IrisCompat}) have registered the textured-up blobs — and re-registers the same
 * texture ids from the PNGs. Anything without a PNG falls through to the blob, so the mod still works
 * with only half the resources present.
 *
 * <h2>The language variants</h2>
 *
 * <p>The character cards are drawn twice — chinese art at the neutral {@code fullcard/} path and
 * english art under {@code fullcard/en_us/} — and {@link #findArt} picks per the selected language.
 * That choice is only consulted when the textures are <em>registered</em>, so switching language
 * mid-session needs a re-registration: {@link #rebind()} does exactly that and is called from the
 * {@code LanguageManager.setSelected} mixin. It is safe to run there, before the resource reload that
 * follows the selection, for two reasons verified against the client jar:
 *
 * <ul>
 *   <li>the PNGs live in the mod jar, whose resources the reload never adds or removes — only the
 *       selected-language string matters, and {@code setSelected} has already written it;</li>
 *   <li>{@code TextureManager.reload} calls {@code AbstractTexture.reset}, which merely re-registers
 *       the same texture instances — a {@code DynamicTexture} without a backing {@code textures/}
 *       file is carried across the reload untouched, not rebuilt or dropped.</li>
 * </ul>
 *
 * <p><b>The bound texture has to match the shader Charta will draw it with</b>, and both hinge on
 * {@link IrisCompat#isPresent()} — the palette shader reads the red channel as an index, so a
 * full-colour texture only decodes correctly on the direct-sampling path. {@code IrisCompatPresent}
 * forces that path on, but this class does not take it for granted: it checks the very same predicate
 * at bind time and, if the flip ever failed, falls back to handing the PNG back in Charta's own
 * palette byte format so the palette shader still decodes it correctly instead of turning the art
 * into noise.
 */
public final class CardArt {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Past this level a 325x455 card is never drawn small enough to matter. */
    private static final int MAX_MIP_LEVEL = 4;

    /**
     * Bumped on every bind request; an install decoded for an older request is dropped, so two
     * language switches in quick succession can never land their textures out of order.
     */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    /** The language the currently registered art was resolved for; {@code null} before the first bind. */
    private static volatile String lastBoundCode;

    private CardArt() {
    }

    /**
     * Whether {@code code} is a language the pack shows the chinese art and text for.
     *
     * <p>Every chinese variant vanilla ships — simplified, traditional, Hong Kong — plus Classical
     * Chinese ({@code lzh}, which vanilla does list) and any Tibetan pack ({@code bo}, which vanilla
     * does not). The two extras are deliberate: a Classical Chinese client reading english cards was
     * the gap, and the Tibetan pack this table is played with carries no card art of its own, so both
     * fall on the chinese side of the curtain on purpose.
     */
    public static boolean isChineseLike(@Nullable String code) {
        if (code == null) {
            return true; // No language manager yet: the pack's own art is the neutral answer.
        }
        return code.startsWith("zh") || code.startsWith("bo") || code.equals("lzh");
    }

    /**
     * Re-binds the art after a language change. Skipped while no images have been synced yet (the
     * startup {@code setSelected} from the options runs long before that) and when the requested code
     * is the one the registered art was already resolved for — the language screen fires the hook even
     * when the player confirms the language they were on.
     */
    public static void rebind() {
        if (ChartaMod.CARD_IMAGES.getImages().isEmpty()
                && ChartaMod.SUIT_IMAGES.getImages().isEmpty()
                && ChartaMod.DECK_IMAGES.getImages().isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getTextureManager() == null) {
            return;
        }
        String code = client.getLanguageManager().getSelected();
        if (code == null || code.equals(lastBoundCode)) {
            return;
        }
        lastBoundCode = code;
        bindAll();
    }

    /** Resolves and re-registers every texture that has a full-colour PNG for the current language. */
    public static void bindAll() {
        int generation = GENERATION.incrementAndGet();
        lastBoundCode = Minecraft.getInstance().getLanguageManager().getSelected();
        boolean argb = IrisCompat.isPresent();
        Minecraft client = Minecraft.getInstance();
        TextureManager manager = client.getTextureManager();
        int[] counts = new int[2]; // [scheduled, seen]
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        ChartaMod.SUIT_IMAGES.getImages().keySet()
                .forEach(id -> replace(client, manager, ChartaModClient.getSuitTexture(id), id, argb, generation, counts, pending));
        ChartaMod.CARD_IMAGES.getImages().keySet()
                .forEach(id -> replace(client, manager, ChartaModClient.getCardTexture(id), id, argb, generation, counts, pending));
        ChartaMod.DECK_IMAGES.getImages().keySet()
                .forEach(id -> replace(client, manager, ChartaModClient.getDeckTexture(id), id, argb, generation, counts, pending));
        // Every PNG is read and decoded on the common pool, so the ~21 files (two of them 2 MB board scans)
        // no longer stall the render thread one after another on the payload path that opens a match. The
        // install itself still happens on the render thread (see {@link #install}), so this only
        // waits on the decode half.
        //
        // On the natural resource-reload path generateImages runs on the render thread, and waiting here is
        // safe: the workers do no GL work. On the image-sync payload path it may already be off the render
        // thread (Charta runs it from a client executor), so joining would pin that executor for no reason --
        // the installs are recorded render calls and run on their own.
        if (RenderSystem.isOnRenderThread()) {
            pending.forEach(CompletableFuture::join);
        }
        LOGGER.info("[trustnoone] full-colour card art: argb={}, scheduled {}/{} textures",
                argb, counts[0], counts[1]);
    }

    private static void replace(Minecraft client, TextureManager manager, ResourceLocation textureId,
                                ResourceLocation artId, boolean argb, int generation, int[] counts,
                                List<CompletableFuture<Void>> pending) {
        counts[1]++;
        Optional<Resource> resource = findArt(client, artId);
        if (resource.isEmpty()) {
            return;
        }
        counts[0]++;
        Resource art = resource.get();
        pending.add(CompletableFuture.runAsync(() -> decode(manager, textureId, artId, art, argb, generation, counts)));
    }

    /** Reads the PNG and turns it into the bytes the shader expects; pure CPU, so it runs on a worker. */
    private static void decode(TextureManager manager, ResourceLocation textureId, ResourceLocation artId,
                               Resource art, boolean argb, int generation, int[] counts) {
        NativeImage pixels;
        try (InputStream stream = art.open()) {
            pixels = NativeImage.read(stream);
        } catch (Exception exception) {
            LOGGER.warn("[trustnoone] could not load full-colour art {}", artId, exception);
            return;
        }
        if (GENERATION.get() != generation) {
            return; // A newer bind started while this decode ran; its own pass will register the texture.
        }
        // The palette re-encode is a second byte loop, so it belongs on the worker too. It closes the source.
        NativeImage ready = argb ? pixels : packAsPalette(pixels);
        install(manager, textureId, ready, argb);
    }

    /**
     * Builds the {@link DynamicTexture} for a decoded image and registers it. The upload binds a GL id, so
     * when the decode ran on the common pool this is marshalled over to the render thread with a
     * {@code recordRenderCall}; the texture itself is created off-thread (which is safe -- the GL work is
     * deferred) so the id and the mip chain are the only pieces that wait for the render thread.
     */
    private static void install(TextureManager manager, ResourceLocation textureId, NativeImage ready,
                                boolean argb) {
        DynamicTexture texture = new DynamicTexture(ready);
        Runnable register = () -> {
            manager.register(textureId, texture);
            buildMipmaps(texture, argb);
        };
        // register() calls DynamicTexture.load (a no-op) and the mip build binds the id, so both need the
        // render thread; from the payload path (already off-thread) they have to be marshalled.
        if (RenderSystem.isOnRenderThread()) {
            register.run();
        } else {
            RenderSystem.recordRenderCall(register::run);
        }
    }

    /**
     * Resolves the full-colour PNG for a texture id. Charta's ids carry no extension and the resource manager
     * does not invent one, so the ".png" is spelled out. A non-chinese client also looks under its own language
     * folder first (the pack ships english character art), then under {@code en_us}, and finally falls back to
     * the neutral top-level file — so a missing translation degrades to the chinese art instead of vanishing.
     * Every chinese variant, Classical Chinese and Tibetan count as chinese here; see {@link #isChineseLike}.
     */
    private static Optional<Resource> findArt(Minecraft client, ResourceLocation artId) {
        String language = client.getLanguageManager().getSelected();
        List<ResourceLocation> candidates = new ArrayList<>();
        if (!isChineseLike(language)) {
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
    private static NativeImage packAsPalette(NativeImage source) {
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
                    target.setPixelLuminance(x, y, (byte) ((alphaIndex << 6) | nearestColour(red, green, blue)));
                }
            }
        }
        source.close();
        return target;
    }

    /** Index 0 is skipped because it doubles as the "empty pixel" marker. */
    private static int nearestColour(int red, int green, int blue) {
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

    private static void buildMipmaps(DynamicTexture texture, boolean argb) {
        // blur=false keeps magnification at GL_NEAREST so the authored pixels stay crisp; the min filter
        // still reaches the mip chain (rgba path) for the hard downscale in the hand.
        texture.setFilter(false, argb);
        if (!argb) {
            return; // palette indices are categorical, a mip chain would average them.
        }
        GlStateManager._bindTexture(texture.getId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MAX_MIP_LEVEL);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }
}
