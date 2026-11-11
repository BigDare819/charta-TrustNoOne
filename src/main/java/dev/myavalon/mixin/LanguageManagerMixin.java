package dev.myavalon.mixin;

import dev.myavalon.client.CardArt;
import net.minecraft.client.resources.language.LanguageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-binds the myavalon card art when the player picks a language, so the chinese/english character
 * cards swap live instead of waiting for a restart.
 *
 * <p>{@code setSelected} is the one point every language change funnels through (the language screen
 * calls it right before {@code reloadResourcePacks}), and it is a bare field write — injecting at its
 * tail means the new code is already readable when {@link CardArt#rebind} resolves the PNGs. That is
 * early enough to see the right art and safe against the reload that follows, because the textures we
 * re-register are mod-jar resources the reload does not touch; see {@link CardArt} for both halves of
 * that argument. The startup call from the options, before any images exist, is dropped inside
 * {@code rebind} itself.
 */
@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {

    @Inject(method = "setSelected(Ljava/lang/String;)V", at = @At("TAIL"))
    private void myavalon$rebindCardArt(String code, CallbackInfo ci) {
        CardArt.rebind();
    }
}
