package dev.myavalon.mixin;

import dev.lucaargolo.charta.common.block.CardTableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the table be laid out 4x4, 4x5 or 5x5 as well as Charta's 3x3/4x3/5x3.
 *
 * <p>Charta draws ten-seat games on a table that is always three blocks deep, so a full ten-seat
 * table has people standing on top of one another's chairs. Nothing in the renderer cares about the
 * depth -- the per-block shape is chosen from the four neighbour flags alone, and the shape table
 * already covers the interior blocks a deeper top needs. Two things do care:
 *
 * <ul>
 *   <li>{@code isValidDimensions} is a whitelist, so a 4x4 is simply "not a table";</li>
 *   <li>{@code floodFill} stops the search once it has seen more blocks than the largest whitelisted
 *       table (5x3 = 15), so a 4x4 has already been cut off at sixteen before validity is asked.</li>
 * </ul>
 *
 * <p>The cap is raised to the largest accepted layout, 5x5, rather than to infinity: the flood fill
 * only walks one y-level, and an unbounded walk would let an accidental carpet of tables be gathered
 * as one. A set larger than the cap still collects its first {@code MAX_SIZE} blocks and then fails
 * the filled-bounding-box check, which is the same answer an oversized rug gave before.
 */
@Mixin(CardTableBlock.class)
public class CardTableDimensions {

    @Shadow(remap = false)
    @Mutable
    private static int MAX_SIZE;

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void myavalon$raiseBlockCap(CallbackInfo info) {
        MAX_SIZE = 25;
    }

    /**
     * The extra layouts, both orientations. Written as a min/max pair so 4x5 and 5x4 are one case and
     * Charta's own symmetric check below still handles 3x4/3x5.
     */
    @Inject(method = "isValidDimensions", at = @At("HEAD"), cancellable = true, remap = false)
    private void myavalon$allowDeepTables(int width, int height, CallbackInfoReturnable<Boolean> info) {
        int shallow = Math.min(width, height);
        int deep = Math.max(width, height);
        if (shallow == 4 && deep == 4 || shallow == 4 && deep == 5 || shallow == 5 && deep == 5) {
            info.setReturnValue(true);
        }
    }

    /**
     * Unused parameters kept so the signature matches; the flood fill itself is untouched.
     */
    @SuppressWarnings("unused")
    private static void keepImports(LevelAccessor level, BlockPos pos) {
    }

}
