package dev.trustnoone.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes every {@code bindWrite} also point the GL viewport at the target it just bound.
 *
 * <h2>The hole this plugs</h2>
 *
 * <p>{@code RenderTarget.bindWrite(false)} binds the framebuffer and <em>deliberately leaves the viewport
 * alone</em>; only {@code bindWrite(true)} re-points it. Vanilla gets away with that because every target it
 * binds this way happens to match the viewport that is already set — the main target is the window, and a
 * post pass sets its own viewport by hand before binding (see {@code PostPass.process}). So the two agree,
 * and the flag is dead weight.
 *
 * <p>Charta's glow buffer is the case in point. It <em>was</em> shrunk to half by {@code AvalonScreen},
 * which is what this was written for, and it is window-sized again now that the shrink was reverted; the
 * same argument covers any post-pass target that happens not to be window-sized. A card's glow pass binds
 * the glow buffer and then draws at GUI coordinates through the orthographic projection, which maps the
 * screen onto whatever the viewport says — with a mismatched viewport the projection is stretched over the
 * wrong rectangle and everything outside its bottom-left corner is clipped away.
 *
 * <h2>Why the whole class and not the three call sites</h2>
 *
 * <p>The glow buffer is bound from Charta's {@code AbstractCardWidget.renderWidget}, from
 * {@code ChartaGuiGraphics.blitSuitAndGlow}/{@code blitWhiteSuitAndGlow}, and from our own card batch — and
 * each of those also has to restore the <em>main</em> target afterwards. Patching them one by one would mean
 * six redirects to keep in step with a foreign codebase. Doing it in the primitive they all funnel through
 * cannot be out of step, and it is a no-op for every target that was already the viewport's size.
 *
 * <p>Injected into the private {@code _bindWrite} rather than the public {@code bindWrite}: the latter hands
 * off to the render thread when called from elsewhere ({@code recordRenderCall}), and that path reaches
 * {@code _bindWrite} directly, bypassing an injection on its caller.
 *
 * <p>{@code remap = false} because {@code com.mojang.blaze3d} is carried unobfuscated in the release jar —
 * there is no obfuscation mapping to look {@code _bindWrite} up in.
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetViewport {

    @Shadow public int viewWidth;
    @Shadow public int viewHeight;

    @Inject(method = "_bindWrite(Z)V", at = @At("TAIL"), remap = false)
    private void trustnoone$syncViewport(boolean setViewport, CallbackInfo ci) {
        if (!setViewport) {
            // Same call vanilla makes for bindWrite(true); a post pass has already set this exact value, so
            // running it again for those costs a glViewport and changes nothing.
            GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
        }
    }
}
