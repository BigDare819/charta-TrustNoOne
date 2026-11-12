package dev.trustnoone.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.trustnoone.TrustNoOneMod;

import java.util.List;

/**
 * Ignores a container-data slot this side does not have, instead of crashing on it.
 *
 * <h2>What goes wrong without it</h2>
 *
 * <p>{@code AbstractContainerMenu.setData} indexes {@code dataSlots} with the id that came down the wire, and
 * a menu is built from one side's idea of how many slots there are. When the two sides disagree -- which for
 * this mod means an older build of <em>itself</em> on the other end, since the sync channel is our own slab
 * of container data and it grows whenever a screen learns something new -- the first packet about a slot the
 * receiver never registered is an {@code IndexOutOfBoundsException} in the middle of a connection, and the
 * client is thrown out of the world with a protocol error it cannot act on. The report reads
 * {@code Index 37 out of bounds for length 37}: one side four slots ahead of the other.
 *
 * <h2>Why swallow rather than resize</h2>
 *
 * <p>The slots are positional, so a mismatched tail cannot be re-homed faithfully anyway -- what a client can
 * do is keep the slots it has and drop the news about the ones it lacks, which leaves a mostly working table
 * instead of a disconnect. The fix for a table that reads oddly is still to run the same build on both sides;
 * this is the safety net under it.
 *
 * <p>Only the two-int overload is hooked: the descriptor is spelled out because the name alone also matches
 * the three-argument {@code setData(int, int, boolean)} in older shapes of the class.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class MenuDataSlotBounds {

    @Shadow
    private List<DataSlot> dataSlots;

    /** Set once the mismatch has been reported, so a table does not log a line per packet. */
    private static boolean reported;

    @Inject(method = "setData(II)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void avalon$ignoreUnknownDataSlot(int id, int value, CallbackInfo info) {
        if (id < 0 || id >= this.dataSlots.size()) {
            if (!reported) {
                reported = true;
                // Once, and loud enough to find: it names the skew, which is otherwise invisible in play.
                TrustNoOneMod.LOGGER.warn("container data slot {} arrived with only {} slots here - the other"
                        + " side is a different build (dropping the extras, see MenuDataSlotBounds)",
                        id, this.dataSlots.size());
            }
            info.cancel();
        }
    }
}
