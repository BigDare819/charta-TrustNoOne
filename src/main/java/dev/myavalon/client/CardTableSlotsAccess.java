package dev.myavalon.client;

import dev.lucaargolo.charta.common.game.api.GameSlot;

import java.util.List;
import java.util.Set;

/**
 * The block entity's slot list and its two dirty-index sets, for the repair mixin.
 *
 * <p>An interface rather than a cast to the mixin class: a class mixin is merged into the target but is
 * not a supertype of it at runtime, so only an interface cast is guaranteed to succeed.
 */
public interface CardTableSlotsAccess {

    List<GameSlot> avalon$trackedSlots();

    Set<Integer> avalon$dirtyCards();

    Set<Integer> avalon$dirtyPositions();

}
