package dev.myavalon.mixin;

import dev.lucaargolo.charta.client.render.screen.widgets.AbstractCardWidget;
import dev.myavalon.game.AvalonRole;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/**
 * Hangs one line of comment under a hovered identity card.
 *
 * <p>Charta's cards raise a tooltip of their own accord -- {@code AbstractCardWidget.renderWidget} schedules
 * the hovered card's own name -- and that is the only place a card's tooltip is built: it is inherited by
 * every card widget there is, the hand's fan children included, so it is also the only place worth hooking.
 * The redirect swaps the single name component for the name plus {@link AvalonRole#tooltipForCard}'s
 * comment, and answers nothing at all for a card that is not a role, which leaves the votes, the quest
 * cards and the closed hands exactly as Charta draws them.
 *
 * <p>The two styles are set from opposite ends on purpose: the name is left alone so Charta's following
 * {@code withColor(getCardColor())} still paints it, and the comment carries its own colours -- so the
 * faction colours inside the sentence survive that same call.
 *
 * <p>{@code require = 1}: the call site is unique, and a redirect that quietly failed to find it would
 * look exactly like a role with nothing to say. Failing loudly on the class instead is worth the crash
 * report, which names this class.
 *
 * <h2>The cache, and why it lives here</h2>
 *
 * <p>This runs on every render pass of a hovered card, i.e. sixty times a second, so the tooltip is built
 * once per translation key and handed out again from then on. The cache belongs to the <em>card</em> path
 * rather than to {@code AvalonRole} because Charta writes the card's own colour onto whatever component
 * comes back: a cached root ends up carrying the suit colour, which is right for a card and would be a
 * quietly different colour for the identity act and the reveal overlay if they shared the same instance.
 * Both call sites stay stable within themselves, which is all that shows.
 */
@Mixin(AbstractCardWidget.class)
public abstract class AbstractCardWidgetComment {

    /** {@code card.myavalon.<role>}, built once each. Never null-valued: misses are not cached. */
    private static final Map<String, MutableComponent> TOOLTIPS = new HashMap<>();

    @Redirect(
            method = "renderWidget", require = 1,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static MutableComponent avalon$cardNameWithComment(String key) {
        MutableComponent cached = TOOLTIPS.get(key);
        if (cached != null) {
            return cached;
        }
        MutableComponent named = AvalonRole.tooltipForCard(key);
        if (named == null) {
            // A vote or a quest card: its own name, with nothing to remember it by either way.
            return Component.translatable(key);
        }
        TOOLTIPS.put(key, named);
        return named;
    }
}
