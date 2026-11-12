package dev.trustnoone;

import dev.trustnoone.game.AvalonScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.MenuScreens;

/**
 * Client side. {@code AvalonScreen} extends Charta's {@code GameScreen}, so every card widget,
 * hover animation, drag/drop interaction and the table history panel come from Charta for free.
 *
 * <p>This is a {@code client} entrypoint, which Fabric runs after every {@code main} entrypoint,
 * so Charta is guaranteed to be fully initialised by the time this runs — no deferral needed.
 */
@Environment(EnvType.CLIENT)
public class TrustNoOneClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MenuScreens.register(TrustNoOneMod.AVALON_MENU, AvalonScreen::new);
    }

}
