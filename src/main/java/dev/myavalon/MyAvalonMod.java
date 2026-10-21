package dev.myavalon;

import dev.myavalon.game.AvalonCards;
import dev.myavalon.game.AvalonGame;
import dev.myavalon.game.AvalonMenu;
import dev.myavalon.network.AvalonActionPayload;
import dev.myavalon.stats.AvalonStats;
import dev.myavalon.stats.AvalonStatsCommand;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.FabricChartaMod;
import dev.lucaargolo.charta.common.game.Games;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Charta addon entrypoint.
 *
 * <p>A playable game needs three things, in this order: the deck's suits and ranks registered, a
 * {@link GameType} in Charta's {@code charta:game_type} registry, and a {@link MenuType} in
 * {@code minecraft:menu}. Charta's table screen enumerates {@code Games.getRegistry()} when it opens,
 * so nothing else is required to make the game show up.
 *
 * <h2>All three have to happen right here, and that needs Charta already up</h2>
 *
 * Fabric freezes every registry — {@code minecraft:menu}, {@code charta:game_type} and the suit/rank
 * registries <b>and</b> dynamically registered ones — as soon as the {@code main} entrypoint stage
 * ends. So none of the registrations can be postponed to a lifecycle event; they must run inside
 * {@link #onInitialize()}.
 *
 * <p>But Charta's classes cannot be touched until Charta's own {@code main} entrypoint has run:
 * everything funnels through {@code ChartaMod.loadPlatformClass}, which dereferences
 * {@code ChartaMod.instance}, a field only assigned in Charta's {@code ModInitializer} constructor.
 * Several ordinary-looking references reach it:
 *
 * <pre>
 *   Games.getRegistry()                      -&gt; Games.&lt;clinit&gt;                      -&gt; ChartaMod.registry
 *   Suits.getRegistry() / Ranks.getRegistry() -&gt; Suits.&lt;clinit&gt;                    -&gt; ChartaMod.registry
 *   AbstractCardMenu.Definition.STREAM_CODEC -&gt; Deck.&lt;clinit&gt; -&gt; Suits.&lt;clinit&gt; -&gt; ChartaMod.registry
 * </pre>
 *
 * <p>Fabric Loader does <b>not</b> guarantee that a dependant mod's entrypoint runs after its
 * dependency's, and the order is not even stable between runs: newer loaders derive it from an
 * identity-hashed map, so {@code charta} loads before {@code myavalon} on some launches and after it
 * on others. The unlucky launch dies with {@code NullPointerException: ChartaMod.instance is null}
 * and, because a failed static initializer poisons the class permanently
 * ({@code NoClassDefFoundError: Could not initialize class ...}), catching and retrying does not help.
 *
 * <p>{@link #ensureChartaInitialised()} removes the ordering requirement entirely: if Charta has not
 * been constructed yet, construct it. That only sets {@code ChartaMod.instance} and creates its
 * packet manager — {@code init()} itself is left to Charta's own entrypoint, so nothing is registered
 * twice. By the time the real entrypoint runs, {@code instance} already points at it.
 */
public class MyAvalonMod implements ModInitializer {

    public static final String MOD_ID = "myavalon";
    public static final String MOD_NAME = "MyAvalon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Registry id of the game, used for both {@code charta:game_type} and {@code minecraft:menu}.
     *
     * <p>The path matters beyond bookkeeping: Charta derives the table button label from
     * {@code gameId.toLanguageKey()} ({@code myavalon.avalon}), the button texture from
     * {@code myavalon:textures/gui/game/avalon.png} and the how-to-play page from
     * {@code charta_md:myavalon.how_to_play_avalon}.
     */
    public static final ResourceLocation GAME_ID = id("avalon");

    /** Registered into {@code charta:game_type}. */
    public static GameType<AvalonGame, AvalonMenu> AVALON;
    /** Registered into {@code minecraft:menu}. */
    public static MenuType<AvalonMenu> AVALON_MENU;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ensureChartaInitialised();

        // The deck's four suits and twelve ranks. Must run before any deck is parsed or any game is
        // built, and it registers straight into the live registries rather than through Charta's
        // deferred ModRegistry; see AvalonCards for why that matters.
        AvalonCards.init();

        AVALON_MENU = Registry.register(
                BuiltInRegistries.MENU,
                GAME_ID,
                new ExtendedScreenHandlerType<AvalonMenu, AbstractCardMenu.Definition>(
                        AvalonMenu::new,
                        AbstractCardMenu.Definition.STREAM_CODEC
                )
        );

        AVALON = Registry.register(Games.getRegistry(), GAME_ID, AvalonGame::new);

        PayloadTypeRegistry.playC2S().register(AvalonActionPayload.TYPE, AvalonActionPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AvalonActionPayload.TYPE, (payload, context) ->
                AvalonActionPayload.handleServer(payload, context.player(), context.server()));

        // The win/loss record and the two queries over it. The command tree is registered for both an
        // integrated server and a dedicated one; the file itself is loaded lazily, per world.
        AvalonStatsCommand.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(AvalonStats::forget);

        LOGGER.info("Registered {} as a playable charta game", GAME_ID);
    }

    /**
     * Makes {@code ChartaMod.instance} non-null so Charta's static initializers work, regardless of
     * whether Fabric decided to run Charta's entrypoint before ours.
     *
     * <p>Constructing a second {@code FabricChartaMod} is safe and deliberate: the constructor does
     * nothing but assign {@code instance} and create a packet manager, while all actual registration
     * lives in {@code onInitialize()} -> {@code ChartaMod.init()}, which only Fabric's own instance
     * receives. Calling {@code init()} ourselves as well would double-register every Charta entry.
     */
    private static void ensureChartaInitialised() {
        if (ChartaMod.getInstance() != null) {
            return;
        }
        LOGGER.info("Charta's entrypoint has not run yet; constructing it early to unlock its registries");
        new FabricChartaMod();
    }

}
