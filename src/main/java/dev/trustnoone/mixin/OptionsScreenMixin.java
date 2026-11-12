package dev.trustnoone.mixin;

import dev.lucaargolo.charta.client.render.screen.OptionsScreen;
import dev.trustnoone.game.AvalonGame;
import dev.trustnoone.game.AvalonRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows what the current options would deal, on the options screen itself.
 *
 * <p>The identity setup has its own screen with a per-role count, but the first-level screen — the one
 * with Save — only lists the rules as words: a player turning the seat slider or flipping the wolf rule
 * never sees how many 悦灵, 村民 and friends the table would actually seat until they open the sub-screen
 * or start the game. This draws the composed lineup under the title, one line, counted off the very
 * helper {@code AvalonGame.dealRoles} uses, so the two screens and the deal can never disagree.
 *
 * <p>It is a tail inject on {@code render} rather than a widget: the widget list owns the middle of the
 * screen and the buttons the bottom, and a line that only re-derives a tally per frame is cheaper than
 * wiring it into Charta's option rows. Long tables scale the line down to fit rather than clipping.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {

    @Shadow(remap = false)
    @Final
    private dev.lucaargolo.charta.common.game.api.game.Game<?, ?> game;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"))
    private void trustnoone$drawLineup(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                     CallbackInfo ci) {
        if (!(game instanceof AvalonGame avalon)) {
            return;
        }
        OptionsScreen<?, ?> self = (OptionsScreen<?, ?>) (Object) this;
        // The same size the identity setup sizes itself for: the roster if anyone is seated, otherwise
        // the seat slider -- which is what the roster will be rebuilt to.
        int seated = avalon.getPlayers().size();
        int seats = seated > 0
                ? Math.max(AvalonRole.MIN_PLAYERS, Math.min(AvalonRole.MAX_PLAYERS, seated))
                : avalon.tableSize();
        int mask = AvalonRole.isCustom(avalon.roleMask())
                ? avalon.roleMask() : AvalonRole.builtInMask(seats);
        boolean usable = AvalonRole.setupMatches(seats, mask, avalon.cunningAssassin(), avalon.lancelotOption());
        int[] counts = AvalonRole.lineup(seats, mask, avalon.cunningAssassin(), avalon.lancelotOption());
        MutableComponent list = Component.empty();
        boolean first = true;
        for (AvalonRole role : AvalonRole.values()) {
            int count = counts[role.ordinal()];
            if (count <= 0) {
                continue;
            }
            if (!first) {
                list.append(Component.translatable("reveal.trustnoone.census_sep"));
            }
            first = false;
            list.append(Component.translatable("reveal.trustnoone.role_count",
                    role.coloredName(), Integer.toString(count)));
        }
        Component line = usable
                ? Component.translatable("reveal.trustnoone.roles_in_play", list)
                : Component.translatable("roles.trustnoone.invalid_short");

        Minecraft client = Minecraft.getInstance();
        int textWidth = client.font.width(line);
        int room = self.width - 12;
        float scale = textWidth > room ? Math.max(0.5f, room / (float) textWidth) : 1f;
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(self.width / 2f, 20f, 0f);
        guiGraphics.pose().scale(scale, scale, 1f);
        guiGraphics.drawString(client.font, line, -textWidth / 2, 0,
                usable ? 0xFF9A9A9A : 0xFFE2716F, true);
        guiGraphics.pose().popPose();
    }
}
