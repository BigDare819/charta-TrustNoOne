package dev.myavalon;

import dev.myavalon.client.AvalonFrame;
import dev.myavalon.game.AvalonGame;
import dev.myavalon.game.AvalonMenu;
import dev.myavalon.game.AvalonRole;
import dev.myavalon.game.AvalonScreen;
import dev.myavalon.mixin.MouseHandlerAccessor;
import dev.lucaargolo.charta.common.ChartaMod;
import dev.lucaargolo.charta.common.game.api.card.Deck;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.List;

/**
 * Dev-only shortcut that drops a self-playing Avalon table onto the screen as soon as the client is in
 * a world. It exists because reaching the real thing by hand needs a deck, a cloth, a multi-block table
 * and five occupied chairs before the table screen even opens.
 *
 * <p>The world itself is opened too ({@link #WORLD}, joined from the title screen), so the harness runs
 * unattended from a cold start: {@code --quickPlaySingleplayer} only fires when the level already
 * exists, and a run that quietly stops on the title screen looks exactly like a run that crashed.
 *
 * <p>Enabled with the {@code MYAVALON_DEBUG_SCREEN=1} environment variable:
 *
 * <pre>cd D:\deepseekharness\MyAvalon
 * $env:MYAVALON_DEBUG_SCREEN=1; .\gradlew.bat runClient</pre>
 *
 * <p>The menu is built from entity ids alone ({@code -2/-3/-4/-5} stand for bots), which is exactly the
 * path the real client takes, so the layout, the seat rotation, the bot seats and the whole deal →
 * propose → vote → quest loop all get exercised. The client-side game is ticked by hand — nothing else
 * ticks it — and because every seat resolves to an {@code AutoPlayer} on this side (see
 * {@code LivingEntityMixin}, which gives even the local player one), all five hands act themselves and
 * the screen is fully populated.
 *
 * <p>It is the only way to see the vote, the quest and the assassination run end to end without five
 * people.
 */
public final class MyAvalonDebug {

    /**
     * Bots to fill the table with, as entity ids.
     *
     * <p>Negative ids never match a real entity, so {@code GameType.getGameForMenu} turns each of them
     * into an {@code AutoPlayer}. Four of them plus the local player makes a legal five-seat game, the
     * smallest Avalon table and the fastest one to run.
     */
    private static final String[] BOT_IDS = {"-2", "-3", "-4", "-5"};

    /**
     * Game ticks per client tick.
     *
     * <p>A whole game is a few thousand ticks (dealing, five proposals, five quests, each with one
     * action per seat and a linger) and a dev client window that loses focus pauses long before that.
     * Running at 20x keeps a game inside a few seconds of real time; every bot delay is counted in game
     * ticks, so nothing about the flow changes.
     */
    private static final int SPEED = 20;

    /** Games to play before the harness stops. More than one so a lucky good-side sweep is not the only run. */
    private static final int GAMES = 8;

    /** Client ticks to sit on the finished board before dealing the next one. */
    private static final int RESTART_DELAY = 40;

    /**
     * Dev world the harness joins, as a folder under {@code runs/client/saves}.
     *
     * <p>Kept in step with the {@code --quickPlaySingleplayer} argument in {@code build.gradle}: the
     * world is opened from here as well because that argument only takes effect when the level exists,
     * and a harness that sits on the title screen doing nothing is indistinguishable from a broken one.
     */
    private static final String WORLD = "New";

    /** Client ticks to wait for the boot reload to finish before opening {@link #WORLD}. */
    private static final int JOIN_DELAY = 100;

    @Nullable
    private static AvalonScreen screen;
    @Nullable
    private static AvalonGame game;
    private static boolean live;
    private static boolean finished;
    private static boolean joined;
    private static int idleTicks;
    private static int games;
    private static int restartIn;
    private static int ticks;

    private MyAvalonDebug() {
    }

    public static void install() {
        if (!"1".equals(System.getenv("MYAVALON_DEBUG_SCREEN"))) {
            return;
        }
        MyAvalonMod.LOGGER.info("Debug table enabled (MYAVALON_DEBUG_SCREEN=1)");
        ClientTickEvents.END_CLIENT_TICK.register(MyAvalonDebug::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            joinWorld(minecraft);
            return;
        }
        if (live) {
            // Keep stepping the local copy so the bots propose, vote and quest and the screen fills up.
            if (game != null && minecraft.screen == screen) {
                sweepCursor(minecraft);
                if (restartIn > 0 && --restartIn == 0 && !finished) {
                    // Deal the next game on the same instance, which is the same call the table takes on
                    // a fresh game.
                    game.startGame();
                }
                for (int i = 0; i < SPEED; i++) {
                    game.tick();
                    // Inside the loop, not once per client tick: a proposal is opened and confirmed by
                    // the local AutoPlayer within the same tick batch, so only a per-game-tick check can
                    // land between them and exercise the screen's own click path.
                    driveLocal();
                }
                heartbeat();
                report();
            }
            return;
        }
        if (minecraft.screen != null) {
            return;
        }
        open(minecraft);
    }

    /**
     * Answers for the local chair, which has no human on a debug run.
     *
     * <p>A vote and a quest are simultaneous, so Charta's {@code AutoPlayer.tick} -- which only drives
     * the one {@code currentPlayer} -- no longer reaches every seat; the local seat would sit a round out
     * forever. This drives it exactly as the screen would: a board click to name a quest on a proposal,
     * then the same {@code getBestPlay} a bot answers with.
     */
    private static void driveLocal() {
        if (game == null || !game.isGameReady()) {
            return;
        }
        if (screen == null) {
            return;
        }
        int local = screen.getMenu().getLocalSeat();
        boolean pending = switch (game.getPhase()) {
            case VOTE -> (game.getVotedMask() & 1) == 0;
            case QUEST -> (game.getTeamMask() & 1) != 0 && (game.getQuestMask() & 1) == 0;
            case PROPOSE, ASSASSIN -> game.getSeatOfCurrentPlayer() == local;
            default -> false;
        };
        if (!pending) {
            return;
        }
        dev.lucaargolo.charta.common.game.api.CardPlayer player = game.playerAt(local);
        if (game.getPhase() == AvalonGame.Phase.PROPOSE) {
            // From the top down, so the fifth quest is exercised as soon as its gate opens.
            int open = game.openQuestMask();
            for (int i = AvalonRole.QUESTS - 1; i >= 0; i--) {
                if ((open & (1 << i)) != 0) {
                    MyAvalonMod.LOGGER.info("debug: local names quest {} (open={})", i + 1,
                            Integer.toBinaryString(open));
                    game.submitAction(player, AvalonGame.PICK_BASE + i);
                    break;
                }
            }
            dev.lucaargolo.charta.common.game.api.GamePlay proposal = game.getBestPlay(player);
            if (proposal != null) {
                // Strip the bot's quest encoding, so the answer is the exact one the screen sends: the
                // low team mask, quest high half zero, and the slot named by the click above survives.
                player.play(new dev.lucaargolo.charta.common.game.api.GamePlay(
                        List.of(), proposal.slot() & ((1 << game.getPlayers().size()) - 1)));
            }
            return;
        }
        dev.lucaargolo.charta.common.game.api.GamePlay play = game.getBestPlay(player);
        if (play != null) {
            player.play(play);
        }
    }

    /** Client ticks to wait for a join that has been started but produced no world before retrying. */
    private static final int JOIN_TIMEOUT = 1200;

    /**
     * Drags the OS cursor left to right across the local player's hand.
     *
     * <p>A headless run never moves the mouse, so nothing is ever hovered and the card tooltip -- which
     * only exists while a card is under the pointer -- is never exercised. This walks the cursor along the
     * bottom hand so a tooltip fires a few times a second.
     */
    private static void sweepCursor(Minecraft minecraft) {
        if (minecraft.getWindow() == null) {
            return;
        }
        double t = (ticks % 240) / 240.0;
        double guiW = minecraft.getWindow().getGuiScaledWidth();
        double guiH = minecraft.getWindow().getGuiScaledHeight();
        double gx = guiW * (0.5 + 0.45 * Math.sin(t * Math.PI * 6));
        double gy = guiH * (0.5 + 0.42 * Math.sin(t * Math.PI * 10));
        if (HOLD >= 0) {
            gx = AvalonFrame.current().toGuiX((float) HOLD);
            gy = AvalonFrame.current().toGuiY((float) HOLD_Y);
        }
        double sx = gx * minecraft.getWindow().getScreenWidth() / guiW;
        double sy = gy * minecraft.getWindow().getScreenHeight() / guiH;
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) minecraft.mouseHandler;
        mouse.myavalon$setXpos(sx);
        mouse.myavalon$setYpos(sy);
        if (ticks % 20 == 0) {
            MyAvalonMod.LOGGER.info("sweep gui=({}, {}) frame=({}, {}) screen={} hovered={}",
                    (int) gx, (int) gy,
                    (int) AvalonFrame.current().toFrameX(gx), (int) AvalonFrame.current().toFrameY(gy),
                    minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName(),
                    screen == null ? "no-screen" : screen.getHoveredProbe());
        }
        if (screen != null && screen.getHoverablePeek() && ticks - lastShot >= 40) {
            lastShot = ticks;
            Screenshot.grab(minecraft.gameDirectory, minecraft.getMainRenderTarget(), component -> { });
        }
    }

    /** Client tick of the last hover screenshot, so the harness does not flood the folder. */
    private static int lastShot = -100000;

    /**
     * Fixed pointer, in <em>frame</em> coordinates -- 360,45 sits on the top-right seat's hand at any
     * GUI scale -- while {@code MYAVALON_HOLD} is set; {@code -1} to sweep instead.
     */
    private static final double HOLD = Double.parseDouble(System.getenv().getOrDefault("MYAVALON_HOLD", "360"));
    private static final double HOLD_Y = Double.parseDouble(System.getenv().getOrDefault("MYAVALON_HOLD_Y", "45"));

    /**
     * Opens {@link #WORLD} once the game has settled on a menu with no world in it, so the harness runs
     * unattended.
     *
     * <p>Waits a few seconds and for the loading overlay to clear rather than testing for a title screen
     * by class: the client spends its first seconds behind a resource-reload overlay with no world and
     * no screen of its own, and opening from under that forks the load. The wait also means an
     * already-running {@code --quickPlaySingleplayer} wins the race instead of being doubled up. On
     * failure the flag clears so the next tick retries, rather than the harness stopping silently on a
     * menu that looks exactly like a crash.
     *
     * <p>A world that has never been generated is <em>created</em> rather than opened: {@code openWorld}
     * reads the level data up front and, on a save folder with no {@code level.dat} in it, ends on a
     * {@code RecoverWorldDataScreen} that waits for a click — and this harness has nobody to click.
     * {@code createFreshLevel} generates it with plain creative defaults instead. {@code
     * --quickPlaySingleplayer} is no help here either: it only fires when the level already exists.
     */
    private static void joinWorld(Minecraft minecraft) {
        if (joined) {
            // The load screen can swallow a failure without throwing; if no world has appeared after a
            // generous wait, drop the flag and try again instead of sitting on a dead screen forever.
            if (++idleTicks > JOIN_DELAY + JOIN_TIMEOUT && minecraft.getOverlay() == null) {
                MyAvalonMod.LOGGER.warn("debug: world {} never loaded (screen={}), retrying", WORLD, minecraft.screen);
                joined = false;
                idleTicks = JOIN_DELAY;
            }
            return;
        }
        if (++idleTicks % 100 == 0) {
            MyAvalonMod.LOGGER.info("debug: waiting for a world (screen={}, overlay={})",
                    minecraft.screen, minecraft.getOverlay());
        }
        if (idleTicks < JOIN_DELAY || minecraft.getOverlay() != null) {
            return;
        }
        joined = true;
        try {
            if (Files.isRegularFile(minecraft.gameDirectory.toPath()
                    .resolve("saves").resolve(WORLD).resolve("level.dat"))) {
                MyAvalonMod.LOGGER.info("debug: opening world {}", WORLD);
                minecraft.createWorldOpenFlows().openWorld(WORLD, () -> joined = false);
                return;
            }
            MyAvalonMod.LOGGER.info("debug: creating world {}", WORLD);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false,
                    Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            // The dimension function is handed the datapack *worldgen* registry, which has no
            // minecraft:dimension registry in it -- building WorldDimensions straight off it throws
            // "Missing registry: minecraft:dimension". WorldPresets.createNormalWorldDimensions goes
            // through the world-preset registry instead and is what vanilla's own Demo World button
            // passes here.
            minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings,
                    WorldOptions.defaultWithRandomSeed(),
                    WorldPresets::createNormalWorldDimensions,
                    null);
        } catch (Exception exception) {
            MyAvalonMod.LOGGER.warn("debug: opening world {} failed, retrying", WORLD, exception);
            joined = false;
        }
    }

    /** One line when the game is over, so a run can be checked without reading a chat log. */
    private static void report() {
        if (finished || game == null || !game.isGameOver() || restartIn > 0) {
            return;
        }
        games++;
        MyAvalonMod.LOGGER.info("debug: GAME DONE {}/{} winner={} quests={} rejects={} roles={}",
                games, GAMES, sideName(game.getWinner()), missions(), game.getRejects(), roles());
        if (games >= GAMES) {
            finished = true;
            return;
        }
        restartIn = RESTART_DELAY;
    }

    /** Periodic proof that the deal, the proposals, the votes and the quests are actually running. */
    private static void heartbeat() {
        if (game == null || ++ticks % 10 != 0) {
            return;
        }
        MyAvalonMod.LOGGER.info("debug: phase={} quest={} leader={} rejects={} team={} current={} hands={}",
                game.getPhase(), game.getQuest() + 1, game.getLeaderSeat(), game.getRejects(),
                Integer.toBinaryString(game.getPendingMask()),
                game.getCurrentPlayer() == null ? "-" : game.getSeatOfCurrentPlayer(), handSizes());
    }

    private static String handSizes() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(game.getPlayerHand(game.playerAt(i)).size());
        }
        return builder.toString();
    }

    /** The per-quest outcomes as {@code P/S/F}, so a sabotaged run is distinguishable from a swept one. */
    private static String missions() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            builder.append(switch (game.missionOf(i)) {
                case 1 -> 'S';
                case 2 -> 'F';
                default -> 'P';
            });
        }
        return builder.toString();
    }

    private static String roles() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < game.getPlayers().size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(game.roleAt(i).getPath());
        }
        return builder.toString();
    }

    private static String sideName(int side) {
        return switch (side) {
            case AvalonGame.SIDE_GOOD -> "good";
            case AvalonGame.SIDE_EVIL -> "evil";
            default -> "none";
        };
    }

    private static void open(Minecraft minecraft) {
        // The deck comes out of the reloadable deck registry, i.e. straight from
        // data/myavalon/decks/avalon.json, so the debug table and a crafted deck agree -- Deck.simple
        // would hand back a 52-card standard deck instead of the custom Avalon one.
        Deck deck = ChartaMod.CARD_DECKS.getDeck(MyAvalonMod.id("avalon"));

        int[] ids = new int[1 + BOT_IDS.length];
        ids[0] = minecraft.player.getId();
        for (int i = 0; i < BOT_IDS.length; i++) {
            ids[i + 1] = Integer.parseInt(BOT_IDS[i]);
        }

        // options, in getOptions() order: [enable bots, seats, bot strength (0 easy / 1 normal / 2 hard),
        // cunning assassin, role mask, designated quests]. Positional, so the newest option goes last; a
        // short array is still valid -- setRawOptions just leaves the missing tail at its default.
        byte designated = (byte) ("1".equals(System.getenv("MYAVALON_DEBUG_DESIGNATED")) ? 1 : 0);
        AbstractCardMenu.Definition definition = new AbstractCardMenu.Definition(
                minecraft.player.blockPosition(), deck, ids, new byte[]{1, (byte) 5, 1, 1, 0, designated});

        AvalonMenu menu = new AvalonMenu(0, minecraft.player.getInventory(), definition);
        screen = new AvalonScreen(menu, minecraft.player.getInventory(), Component.empty());
        game = menu.getGame();
        live = true;

        minecraft.setScreen(screen);
        game.startGame();
    }

}
