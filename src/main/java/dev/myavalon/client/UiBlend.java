package dev.myavalon.client;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Straight-alpha compositing, on demand, for the one kind of drawing that does not ask for it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code GuiGraphics} has two ways to put a picture down and only one of them manages blending:
 *
 * <ul>
 *   <li>the <b>batched</b> paths -- {@code fill}, {@code drawString} and every {@code RenderType} --
 *       set their own state up and tear it down again, turning blending on while the batch draws and
 *       calling {@code disableBlend} when it is flushed;</li>
 *   <li>an <b>immediate</b> picture blit -- {@code blit(ResourceLocation, ...)} and {@code blitSprite}
 *       -- goes straight to {@code GuiGraphics.innerBlit}, which sets the texture and the shader and
 *       <b>nothing else</b>. So a picture gets stamped down with whatever the last batch left behind,
 *       which is blending <em>off</em>.</li>
 * </ul>
 *
 * <p>With blending off the file's pixels are painted as they are, alpha included, and the pack keeps
 * all sorts of things under its transparent pixels (the rejection nugget keeps transparent black). A
 * sprite whose soft edge is a twenty-texel alpha ramp therefore arrives as a hard, fully opaque
 * rectangle instead of a soft object: <em>"the transparency is gone"</em> is not the engine refusing
 * alpha, it is this one call never asking for it. This is the nugget's "comes out solid" bug, and the
 * same one the {@code boardgame}/{@code werewolf} table solves with an identical helper.
 *
 * <p>Vanilla knows: {@code Button.renderWidget} calls {@code RenderSystem.enableBlend()} immediately
 * before its own {@code blitSprite}. Everything this screen draws from the artist's files does the same,
 * through here, so the result no longer depends on what was drawn before it.
 *
 * <p>{@code defaultBlendFunc()} rather than a hand-written {@code blendFuncSeparate}: it is the same
 * {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} vanilla's batches use, so a picture and a filled plate over
 * each other composite identically.
 */
public final class UiBlend {

    private UiBlend() {
    }

    /** Blend the next immediate picture with what is already on the screen. */
    public static void on() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }
}
