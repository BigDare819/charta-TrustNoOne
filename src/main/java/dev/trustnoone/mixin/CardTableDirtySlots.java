package dev.trustnoone.mixin;

import dev.lucaargolo.charta.common.block.entity.CardTableBlockEntity;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.trustnoone.client.CardTableSlotsAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

/**
 * Stops the table ticking over a dirty slot index that has no slot behind it.
 *
 * <p>{@code CardTableBlockEntity.serverTick} walks {@code dirtySlotCards}/{@code dirtySlotPositions}
 * and calls {@code trackedSlots.get(index)} unguarded. A slot marks itself dirty through its
 * {@code parent}, and slots outlive the list they were tracked in: the hand slots handed back by
 * {@code startGame} still point at the block entity after {@code resetSlots} empties it, and a
 * position they write in that window leaves an index behind that no longer exists. On a bigger table
 * there are simply more of those indexes, so the exception is what players hit
 * ({@code IndexOutOfBoundsException: Index 5 out of bounds for length 0}) and the whole server stops.
 *
 * <p>Two repair points, both cheap:
 * <ul>
 *   <li>{@code resetSlots} detaches the slots before clearing them, so {@code clear()} cannot write
 *       an index back into the sets that are about to be emptied;</li>
 *   <li>{@code serverTick} prunes any index at or past the live slot count before reading, which also
 *       covers stale writes from anywhere else (a menu, a queued action, the previous game).</li>
 * </ul>
 */
@Mixin(CardTableBlockEntity.class)
public abstract class CardTableDirtySlots implements CardTableSlotsAccess {

    @Shadow(remap = false)
    private List<GameSlot> trackedSlots;

    @Shadow(remap = false)
    private Set<Integer> dirtySlotCards;

    @Shadow(remap = false)
    private Set<Integer> dirtySlotPositions;

    @Override
    public List<GameSlot> avalon$trackedSlots() {
        return trackedSlots;
    }

    @Override
    public Set<Integer> avalon$dirtyCards() {
        return dirtySlotCards;
    }

    @Override
    public Set<Integer> avalon$dirtyPositions() {
        return dirtySlotPositions;
    }

    @Inject(method = "resetSlots", at = @At("HEAD"), remap = false)
    private void trustnoone$detachBeforeReset(CallbackInfo info) {
        for (GameSlot slot : trackedSlots) {
            slot.setParent(null);
        }
    }

    @Inject(method = "serverTick", at = @At("HEAD"), remap = false)
    private static void trustnoone$pruneStaleDirtySlots(Level level, BlockPos pos, BlockState state,
                                                      CardTableBlockEntity blockEntity, CallbackInfo info) {
        CardTableSlotsAccess access = (CardTableSlotsAccess) (Object) blockEntity;
        int size = access.avalon$trackedSlots().size();
        access.avalon$dirtyCards().removeIf(index -> index >= size);
        access.avalon$dirtyPositions().removeIf(index -> index >= size);
    }

}
