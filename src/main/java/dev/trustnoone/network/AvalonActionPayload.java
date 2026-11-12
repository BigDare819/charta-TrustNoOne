package dev.trustnoone.network;

import dev.trustnoone.TrustNoOneMod;
import dev.trustnoone.game.AvalonMenu;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.mixed.LivingEntityMixed;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * One out-of-hand action — toggling a seat onto the team, picking an assassination target, or pressing
 * confirm — from the client to the server.
 *
 * <p>Everything else about the game rides Charta's container-data sync; an action is the only thing the
 * client has to <em>send</em>, so this is the only payload the addon needs. It is registered straight
 * with Fabric's networking API rather than through Charta's packet manager, which keeps the addon free
 * of any dependency on Charta's packet internals.
 *
 * <p>The server validates everything: an action from the wrong seat is ignored and a malformed one
 * simply does nothing, so a desynced or spamming client can never wedge a table.
 */
public record AvalonActionPayload(int containerId, int action) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AvalonActionPayload> TYPE =
            new CustomPacketPayload.Type<>(TrustNoOneMod.id("action"));

    public static final StreamCodec<ByteBuf, AvalonActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            AvalonActionPayload::containerId,
            ByteBufCodecs.VAR_INT,
            AvalonActionPayload::action,
            AvalonActionPayload::new
    );

    public static void handleServer(AvalonActionPayload payload, ServerPlayer player, Executor executor) {
        executor.execute(() -> {
            if (!(player.containerMenu instanceof AvalonMenu menu) || menu.containerId != payload.containerId()) {
                return;
            }
            CardPlayer cardPlayer = ((LivingEntityMixed) player).charta_getCardPlayer();
            menu.getGame().submitAction(cardPlayer, payload.action());
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
