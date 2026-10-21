package dev.myavalon.mixin;

import dev.lucaargolo.charta.client.render.screen.DeckScreen;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.card.Suit;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Drops the row of suit icons from the deck preview page's header.
 *
 * <p>Charta prints the deck's name, then one suit icon per suit under it, then one row of cards per suit.
 * The icons are a table of contents for rows that are already grouped by suit and already start with a card
 * face: on a five-suit Avalon deck they are five glyphs of standing furniture above the title's own line,
 * and the pack's suits are named by the cards below them anyway.
 *
 * <p>One redirect rather than a re-implementation of {@code renderFg}: the call sits in a loop over the
 * suits, so the single static call site serves all five -- and everything else that method draws (the
 * title, and the "N cards | M suits" footer) is left exactly as Charta wrote it.
 */
@Mixin(DeckScreen.class)
public abstract class DeckScreenSuitIcons {

    @Redirect(
            method = "renderFg", require = 1,
            at = @At(value = "INVOKE",
                    target = "Ldev/lucaargolo/charta/common/utils/ChartaGuiGraphics;blitSuitAndGlow(Lnet/minecraft/client/gui/GuiGraphics;Ldev/lucaargolo/charta/common/game/api/card/Deck;Ldev/lucaargolo/charta/common/game/api/card/Suit;FFFFFFFF)V"))
    private static void avalon$skipSuitIcons(GuiGraphics guiGraphics, Deck deck, Suit suit, float x, float y,
                                              float u, float v, float width, float height,
                                              float textureWidth, float textureHeight) {
        // Deliberately empty: the icons are drawn, just not kept.
    }
}
