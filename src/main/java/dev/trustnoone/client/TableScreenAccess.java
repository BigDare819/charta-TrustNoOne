package dev.trustnoone.client;

import dev.lucaargolo.charta.common.game.api.card.Deck;

/**
 * Read-only view of the {@code TableScreen} fields the game tile validates against.
 *
 * <p>{@code players} and {@code deck} are private and only ever read here: the mixin that keeps an
 * Avalon tile clickable when the table is short needs the same inputs Charta's own check used, without
 * recomputing the seat list or holding another copy of the deck.
 */
public interface TableScreenAccess {

    int[] avalon$players();

    Deck avalon$deck();

}
