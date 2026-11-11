package dev.myavalon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lucaargolo.charta.common.utils.CardImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Gives the screen's plain GUI sprites a mip chain and crisp (nearest-magnification) sampling.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>Every sprite the table draws over the board is a <em>large</em> PNG drawn into a <em>small</em> quad:
 * the rejection marker is 325x325 into 26x26, the flag is 325x325 into 13x13, the quest board is
 * 2020x1180 into 360x203. Vanilla GUI textures are uploaded with the GL defaults -- {@code GL_NEAREST}
 * and max mip level 0 -- so an 11x minification picks a single texel per output pixel and throws the rest
 * away. On art whose edges are antialiased over twenty texels, that collapses the whole alpha ramp into
 * one hard step: the soft, semi-transparent fringe turns into a hard opaque edge, and a faint glow either
 * lands on a sampled texel or vanishes. It also shimmers, because which texel is picked depends on the
 * sub-pixel offset.
 *
 * <p>Stock-size art used to hide this by being resized offline; shipping the pack's files verbatim (which
 * is the point -- nothing is allowed to resample them) is what exposed it.
 *
 * <h2>Why mipmaps and not just {@code setFilter}</h2>
 *
 * <p>{@code setFilter(linear, mipmap)} only pokes the GL min/mag filters; it never builds the lower
 * levels, and vanilla's loader {@code prepareImage(id, 0, w, h)} pinned {@code GL_TEXTURE_MAX_LEVEL} to 0,
 * so the chain has to be both allowed and filled. Same shape as {@code CardImageFilters}, which does this
 * for Charta's card textures. The one trap: {@code NativeImage.upload} is a {@code _texSubImage2D}, so it
 * writes into storage that must already be allocated -- the max level has to be raised <em>and</em> the
 * levels allocated via {@code TextureUtil.prepareImage} before a single level is uploaded, or the texture
 * stays incomplete and samples black under a mipmap min filter.
 *
 * <h2>Why the chain is built by hand and not {@code glGenerateMipmap}</h2>
 *
 * <p>{@code glGenerateMipmap} averages straight RGBA, and this pack's sprites keep their transparent
 * margin as fully-transparent <em>black</em>. Averaging that in drags every fringe texel toward black
 * while its alpha stays where it was, so an 11x minification shows the soft edge as a dark halo -- the
 * whole fringe loses its colour. The game's own {@link MipmapGenerator} instead averages the colour over
 * the <em>non-transparent</em> texels of each 2x2 block (its {@code alphaBlend} skips alpha 0), so the
 * fringe keeps the sprite's own colour at every level while its alpha is still averaged normally. That is
 * the same downsampler the GUI atlas uses, so the result matches vanilla's own scaling exactly.
 *
 * <h2>Which filter the minification gets: pixel art picks one level, the board blends two</h2>
 *
 * <p>Magnification is {@code GL_NEAREST} for everything here, so an authored pixel stays a pixel when the
 * art is grown. Minification is where the two kinds of art in this pack part ways:
 *
 * <ul>
 *   <li>The props and seals are pixel art drawn 13 times over (a 325x325 flag into 13 or 39 design
 *       pixels). {@code GL_NEAREST_MIPMAP_LINEAR} -- what {@code setFilter(false, true)} gives -- blends
 *       the two mip levels either side of the draw ratio, and on hard-edged art that blend reads as the
 *       picture having been resampled: the frame's one-unit border smears across a level's worth of
 *       texels. These ids get {@code GL_NEAREST_MIPMAP_NEAREST} instead, so one level is picked whole
 *       and every output pixel is a real texel of the art, which is what "crisp" means for pixel art.</li>
 *   <li>The quest board is painted art with anti-aliased detail and no single right level, so it keeps
 *       the blending filter; there is nothing for a nearest level to preserve.</li>
 * </ul>
 *
 * <h2>When it re-applies</h2>
 *
 * <p>A resource reload replaces the {@link AbstractTexture} object behind an id, taking the filters with
 * it, so what is remembered per id is the <em>object</em>: as long as the manager keeps handing back the
 * same instance there is nothing to do, and the frame a reload swaps it the new one is filtered on the
 * spot. Cheap enough to call every frame -- a {@code HashMap} get per id.
 *
 * <h2>Why the build is off the render thread</h2>
 *
 * <p>The first frame that draws the table used to decode every sprite and run its whole mip chain on the
 * render thread, and the two 2 MB board scans (1920x1080) alone are a visible hitch. Both halves are pure
 * CPU, so they run on the common pool and only the GL upload -- id, storage, {@code texSubImage2D} -- is
 * marshalled back onto the render thread. Until the chain lands the sprite simply draws textured with the
 * default nearest filter, which is exactly what it looked like before this class existed, so there is no
 * half-built state to see.
 */
public final class GuiTextureFilters {

    /** 1920x1080 -> level 5 is 60x34, well past the ~360px the board is ever drawn at. */
    private static final int MAX_MIP_LEVEL = 5;

    /** Texture object last filtered per id; a different instance means a resource reload happened. */
    private static final Map<ResourceLocation, AbstractTexture> FILTERED = new ConcurrentHashMap<>();

    /**
     * Ids whose (possibly still running) build has already been scheduled for the current texture object, so
     * a second frame does not kick off a duplicate decode. The value is the texture instance it was scheduled
     * for; a reload that swaps the object makes the entry stale and the new one is rebuilt.
     */
    private static final Map<ResourceLocation, AbstractTexture> PENDING = new ConcurrentHashMap<>();

    private GuiTextureFilters() {
    }

    /**
     * Filters each id if it is not already filtered, loading it first if needed. Meant to be called from
     * the screen's draw pass; a no-op for every id after the first frame.
     *
     * <p>Two lists rather than one because the minification filter differs per kind of art -- see the
     * class docs. {@code pixelArt} ids get a nearest-picking mip min filter, {@code painted} ids keep the
     * blending one.
     */
    public static void ensure(ResourceLocation[] pixelArt, ResourceLocation[] painted) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        for (ResourceLocation id : pixelArt) {
            Schedule(id, minecraft, PIXEL);
        }
        for (ResourceLocation id : painted) {
            Schedule(id, minecraft, PAINTED);
        }
    }

    /**
     * Filters large screenshots meant to be read: no mip chain at all, linear minification, nearest
     * magnification.
     *
     * <p>The mipmapped filter the painted art gets is the right answer for a board drawn 5x down, but it
     * is the wrong answer for a 1920x1080 page shown at almost its own size: trilinear sampling at a
     * ratio near 1 still mixes in a quarter-resolution level, and that reads as the whole page being
     * soft -- the "why is this blurry when the viewer is sharp" report. With no chain, a page at 1:1 is
     * byte-for-byte its file and a mild downscale is plain bilinear, which is what desktop viewers do
     * before they reach for Lanczos.
     */
    public static void ensureSharp(ResourceLocation[] ids) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }
        for (ResourceLocation id : ids) {
            Schedule(id, minecraft, SHARP);
        }
    }

    private static final int PAINTED = 0;
    private static final int PIXEL = 1;
    private static final int SHARP = 2;

    private static void Schedule(ResourceLocation id, Minecraft minecraft, int mode) {
        AbstractTexture texture = minecraft.getTextureManager().getTexture(id);
        if (texture == null || FILTERED.get(id) == texture) {
            return;
        }
        if (PENDING.get(id) == texture) {
            return; // a build for this exact object is already in flight
        }
        PENDING.put(id, texture);
        // The byte loops (PNG decode + the whole mip chain) are pure CPU and run on the common pool, so
        // the frame that first draws the table does not stall on the two 2 MB board scans. Only the GL
        // upload waits for the render thread, and it is a handful of texSubImage2D calls.
        CompletableFuture.runAsync(() -> build(id, texture, mode));
    }

    private static void build(ResourceLocation id, AbstractTexture texture, int mode) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<Resource> resource = minecraft.getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            PENDING.remove(id);
            return;
        }
        NativeImage base;
        try (InputStream in = resource.get().open()) {
            base = NativeImage.read(in);
        } catch (IOException exception) {
            PENDING.remove(id);
            return;
        }
        int baseWidth = Math.max(1, base.getWidth());
        int baseHeight = Math.max(1, base.getHeight());
        int maxLevel = Math.min(MAX_MIP_LEVEL,
                Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.min(baseWidth, baseHeight))));
        // generateMipLevels only fills the array when it is shorter than maxLevel+1, so hand it a
        // one-element array and take back the full chain it allocates. All CPU, all on this worker.
        // The sharp mode never builds a chain: see ensureSharp.
        NativeImage[] levels = mode == SHARP
                ? new NativeImage[]{base}
                : MipmapGenerator.generateMipLevels(new NativeImage[]{base}, maxLevel);
        // levels[0] *is* base, so it is not closed here -- uploadLevels closes the whole chain after upload.
        // The upload binds the GL id, so it is marshalled onto the render thread. From there the id is
        // allocated, storage prepared and every level uploaded; the object is only marked filtered once the
        // whole chain is in place.
        Runnable upload = () -> {
            uploadLevels(texture, levels, mode);
            PENDING.remove(id);
            FILTERED.put(id, texture);
        };
        if (RenderSystem.isOnRenderThread()) {
            upload.run();
        } else {
            RenderSystem.recordRenderCall(upload::run);
        }
    }

    private static void uploadLevels(AbstractTexture texture, NativeImage[] levels, int mode) {
        GlStateManager._bindTexture(texture.getId());
        // Allocates storage for level 0..MAX_MIP_LEVEL (TextureUtil.prepareImage loops _texImage2D with a
        // null buffer) and pins GL_TEXTURE_MAX_LEVEL/BASE_LEVEL/MAX_LOD there. This has to happen *before*
        // the chain is uploaded: NativeImage.upload only does _texSubImage2D, which writes into storage
        // that must already exist. Vanilla's SimpleTexture.load only prepareImage(id, 0, w, h), i.e. level
        // 0 alone -- so without this call levels 1..5 have no storage, the texture reports as incomplete,
        // and GL sampling with the LINEAR_MIPMAP_LINEAR filter below returns plain black.
        NativeImage level0 = levels[0];
        int maxLevel = levels.length - 1;
        TextureUtil.prepareImage(texture.getId(), maxLevel, level0.getWidth(), level0.getHeight());
        for (int level = 0; level < levels.length; level++) {
            NativeImage image = levels[level];
            image.upload(level, 0, 0, 0, 0, image.getWidth(), image.getHeight(), true, true, false, false);
        }
        for (NativeImage image : levels) {
            image.close();
        }
        // NativeImage.upload calls setFilter with its own (mipmap=false) flags, so the filter has to be
        // re-asserted here, after the chain exists, for sampling to actually reach it.
        // blur=false keeps the magnification filter at GL_NEAREST so the sprites' authored pixels stay
        // crisp; the min filter still reaches the mip chain for the heavy downscale. Linear magnification
        // here is what made the pixel art look soft.
        if (mode == SHARP) {
            // No chain was uploaded; the base level is the whole texture. Linear minification, nearest
            // magnification, and MAX_LEVEL 0 so nothing can sample a level that does not exist.
            GlStateManager._bindTexture(texture.getId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            return;
        }
        texture.setFilter(false, true);
        if (mode == PIXEL) {
            // ...and the min filter is then narrowed to a single level for pixel art: NEAREST_MIPMAP_LINEAR
            // (what setFilter(false, true) leaves behind) averages the two levels either side of the draw
            // ratio, which on hard-edged 13x-scaled art reads as a resampled picture. Asserted by hand
            // because setFilter only ever offers the two mip pairs; the id is bound from the loop above.
            GlStateManager._bindTexture(texture.getId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST_MIPMAP_NEAREST);
        }
    }

    /**
     * The sprite ids that are pixel art shipped at its authored size: the props the pack draws on the
     * table (crown, flag, rejection marker) and the two outcome seals. Drawn up to 25x down, so they are
     * the ids that must not have two mip levels blended -- see the class docs.
     */
    public static ResourceLocation[] pixelSprites() {
        return PIXEL_SPRITES;
    }

    /** The painted sprite ids: the quest board and its english twin, which want the smooth minification. */
    public static ResourceLocation[] paintedSprites() {
        return PAINTED_SPRITES;
    }

    /**
     * The tutorial pages. Kept out of {@link #paintedSprites()} so the nine 1920x1080 scans are only
     * loaded and mip-chained when the tutorial screen actually opens, not on every table.
     */
    public static ResourceLocation[] tutorialSprites() {
        return TUTORIAL_SPRITES;
    }

    /**
     * Built once, not per call: these are handed to {@link #ensure} from the screen's draw pass, and a
     * fresh array of fresh {@link ResourceLocation}s every frame would be seven allocations for nothing.
     */
    private static final ResourceLocation[] PIXEL_SPRITES = {
            gui("myavalon", "crown"),
            gui("myavalon", "team_flag"),
            gui("myavalon", "mission_marker"),
            gui("myavalon", "task_finish"),
            gui("myavalon", "task_unfinish"),
    };

    private static final ResourceLocation[] PAINTED_SPRITES = {
            gui("myavalon", "task_card"),
            gui("myavalon", "task_card_en_us"),
            // The boards drawn per table size. Listed by hand alongside the other ids so a size that has no
            // art is not asked for: a missing file here would be loaded as a missing texture and logged.
            gui("myavalon", "task_card_6"),
            gui("myavalon", "task_card_6_en_us"),
            gui("myavalon", "task_card_7"),
            gui("myavalon", "task_card_7_en_us"),
            gui("myavalon", "task_card_8"),
            gui("myavalon", "task_card_8_en_us"),
            gui("myavalon", "task_card_9"),
            gui("myavalon", "task_card_9_en_us"),
            gui("myavalon", "task_card_10"),
            gui("myavalon", "task_card_10_en_us"),
    };

    /**
     * Full-resolution tutorial pages. They are drawn several times smaller than they are authored, so
     * without a mip chain the same aliasing the class exists for would eat the text and callouts; the
     * files themselves stay 1920x1080.
     */
    private static final ResourceLocation[] TUTORIAL_SPRITES = {
            gui("myavalon", "tutorial/cn_1"),
            gui("myavalon", "tutorial/cn_2"),
            gui("myavalon", "tutorial/cn_3"),
            gui("myavalon", "tutorial/cn_4"),
            gui("myavalon", "tutorial/cn_5"),
            gui("myavalon", "tutorial/cn_6"),
            gui("myavalon", "tutorial/cn_7"),
            gui("myavalon", "tutorial/cn_8"),
            gui("myavalon", "tutorial/cn_9"),
    };

    private static ResourceLocation gui(String namespace, String name) {
        return ResourceLocation.fromNamespaceAndPath(namespace, "textures/gui/" + name + ".png");
    }

    /** Unused import guard: {@code CardImage} documents the size the card textures are authored at. */
    static int stockCardArea() {
        return CardImage.WIDTH * CardImage.HEIGHT;
    }
}
