package dev.myavalon.mixin;

import dev.lucaargolo.charta.client.render.screen.TableScreen;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.myavalon.client.TableScreenAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the seat ids and deck the table screen was opened with; see {@link TableScreenAccess}. */
@Mixin(TableScreen.class)
public abstract class TableScreenFields implements TableScreenAccess {

    @Shadow(remap = false)
    @Final
    private int[] players;

    @Shadow(remap = false)
    @Final
    private Deck deck;

    @Override
    public int[] avalon$players() {
        return players;
    }

    @Override
    public Deck avalon$deck() {
        return deck;
    }

}
