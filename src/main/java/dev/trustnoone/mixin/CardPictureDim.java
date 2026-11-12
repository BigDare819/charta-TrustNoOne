package dev.trustnoone.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lucaargolo.charta.common.utils.ChartaGuiGraphics;
import dev.trustnoone.client.TableDim;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Dims every picture Charta draws, by multiplying the colour it writes into its quad.
 *
 * <h2>What it is for</h2>
 *
 * <p>The finished table is dimmed, and a card has to dim with it. A card is not drawn through
 * {@code GuiGraphics}: {@code innerBlit} builds its own quad on the shared {@code Tesselator} and uploads it
 * on the spot, and it hard-codes each vertex to full white. So nothing a caller sets — not the batch's
 * colour, not the shader colour — reaches it, and a translucent wash over the top lands wherever the batch
 * happens to flush relative to it. This is the one place that decides what a card's pixels are worth, so
 * {@link TableDim} is applied here and the question of order stops mattering.
 *
 * <p>Redirecting the four {@code setColor} calls rather than the whole method: the silhouette pass draws the
 * same quad in black first, and black timed by anything is still black, so one redirect serves both passes
 * and every other picture that comes through here (the suit marks, the board, the props).
 */
@Mixin(ChartaGuiGraphics.class)
public abstract class CardPictureDim {

    @Redirect(
            method = "innerBlit", require = 1,
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))    private static VertexConsumer avalon$dimCardPicture(VertexConsumer consumer, float red, float green,
                                                        float blue, float alpha) {
        float dim = TableDim.value;
        return consumer.setColor(red * dim, green * dim, blue * dim, alpha);
    }
}
