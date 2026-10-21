package dev.myavalon.mixin;

import dev.lucaargolo.charta.client.render.screen.TableScreen;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.game.api.game.Game;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.myavalon.MyAvalonMod;
import dev.myavalon.client.TableScreenAccess;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the Avalon tile clickable when the table is only short of players, so the option screen
 * behind Shift can still be reached.
 *
 * <p>Charta disables the tile, and the Shift-held configuration button with it ({@code
 * configWidget.active = entry.active}), for every failed check at once. Being short of people is the
 * one failure a host can fix later, and with bot fill-in deliberately off an empty table is the
 * normal way to begin -- so only that case is un-greyed. An invalid deck, too many people, or any
 * other seat predicate still leaves the tile dead, which is the feedback the host needs.
 *
 * <p>Pressing Play with too few seats still goes to the server, which answers with the same "not
 * enough players" message the tooltip was already giving.
 */
@Mixin(TableScreen.GameButton.class)
public abstract class GameButtonConfig {

    @Shadow(remap = false)
    @Final
    private Game<?, ?> game;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void myavalon$keepConfigurable(TableScreen screen, ResourceLocation gameId,
                                           GameType<?, ?> gameType, CallbackInfo info) {
        if (!MyAvalonMod.GAME_ID.equals(gameId)) {
            return;
        }
        TableScreenAccess access = (TableScreenAccess) screen;
        int[] seated = access.avalon$players();
        Deck deck = access.avalon$deck();
        // Same shortfall Charta checked; a wrong deck must stay grey even when it is also short.
        if (seated.length < game.getMinPlayers() && Game.isValidDeck(game, deck)) {
            ((AbstractWidget) (Object) this).active = true;
        }
    }

}
