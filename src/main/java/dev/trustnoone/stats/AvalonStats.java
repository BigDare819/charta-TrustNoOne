package dev.trustnoone.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.trustnoone.TrustNoOneMod;
import dev.trustnoone.game.AvalonRole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-server win/loss records, and the queries the stats command is built on.
 *
 * <h2>What is stored</h2>
 *
 * <p>One file per world, {@code trustnoone-stats.json}, holding one record per player UUID: the last
 * name they played under, the overall tally, a tally per faction and a tally per role. That is enough
 * to answer every question the command asks -- "my overall win rate", "my win rate as the Allay",
 * "who has the best record on this server" -- without guessing a role from a card or a faction from a
 * role at read time.
 *
 * <h2>What is deliberately not stored</h2>
 *
 * <p>No match history and no per-seat detail beyond the aggregation. The table already prints its own
 * report, and a file that only grows makes every load slower for no query it supports. A record is
 * four counters wide, so a world with years of games stays small.
 *
 * <h2>When it is written</h2>
 *
 * <p>Once per finished game, immediately, and again when the server stops. Immediate writes are cheap
 * at this size and mean a crash costs at most the game in progress; a lazy save would cost every game
 * since the last clean shutdown.
 */
public final class AvalonStats {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** One store per live server, dropped when the server stops (or is collected). */
    private static final Map<MinecraftServer, Store> STORES = new WeakHashMap<>();

    private AvalonStats() {
    }

    // ---------------------------------------------------------------------------------------------
    // The file
    // ---------------------------------------------------------------------------------------------

    /** One player's counters. Mutable and serialised by Gson directly. */
    public static final class Tally {
        public int games;
        public int wins;

        public void add(boolean won) {
            games++;
            if (won) {
                wins++;
            }
        }

        public float rate() {
            return games == 0 ? 0f : wins * 100f / games;
        }
    }

    /** Everything the file knows about one player. */
    public static final class PlayerRecord {
        public String name = "";
        public Tally total = new Tally();
        /** Keys are {@link AvalonRole.Faction#name()}: {@code GOOD} / {@code EVIL}. */
        public Map<String, Tally> factions = new LinkedHashMap<>();
        /** Keys are {@link AvalonRole#name()}. */
        public Map<String, Tally> roles = new LinkedHashMap<>();

        @Nullable
        public Tally faction(AvalonRole.Faction faction) {
            return factions.get(faction.name());
        }

        @Nullable
        public Tally role(AvalonRole role) {
            return roles.get(role.name());
        }
    }

    private static final class Store {
        public int version = 1;
        public Map<String, PlayerRecord> players = new LinkedHashMap<>();
    }

    /**
     * A row of the ranking, detached from the mutable store.
     *
     * <p>{@code games}/{@code wins} are the tally the row was <em>ranked</em> on: overall, one faction,
     * or one role. The total and both faction tallies ride along so the overall ranking can print the
     * three rates on one line without a second lookup.
     */
    public record Entry(String uuid, String name,
                        int games, int wins,
                        int totalGames, int totalWins,
                        int evilGames, int evilWins,
                        int goodGames, int goodWins) {

        public float rate() {
            return rate(games, wins);
        }

        public float totalRate() {
            return rate(totalGames, totalWins);
        }

        public float evilRate() {
            return rate(evilGames, evilWins);
        }

        public float goodRate() {
            return rate(goodGames, goodWins);
        }

        private static float rate(int games, int wins) {
            return games == 0 ? 0f : wins * 100f / games;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    /**
     * Credits one finished game to one real player.
     *
     * <p>Called from the server-side game once per seat, at the reveal. A {@code null} server or a
     * client-side stand-in is ignored rather than fatal: the same code path runs on both sides.
     */
    public static void record(@Nullable MinecraftServer server, ServerPlayer player, AvalonRole role,
                              boolean won) {
        if (server == null || player == null || role == null) {
            return;
        }
        UUID id = player.getGameProfile().getId();
        if (id == null) {
            return;
        }
        Store store = store(server);
        synchronized (store) {
            PlayerRecord record = store.players.computeIfAbsent(id.toString(), key -> new PlayerRecord());
            record.name = player.getGameProfile().getName();
            record.total.add(won);
            record.factions.computeIfAbsent(role.getFaction().name(), key -> new Tally()).add(won);
            record.roles.computeIfAbsent(role.name(), key -> new Tally()).add(won);
            save(server, store);
        }
    }

    /** Writes every live store for {@code server} and forgets it. Called when the server stops. */
    public static void forget(MinecraftServer server) {
        Store store;
        synchronized (STORES) {
            store = STORES.remove(server);
        }
        if (store != null) {
            synchronized (store) {
                save(server, store);
            }
        }
    }

    private static Store store(MinecraftServer server) {
        synchronized (STORES) {
            return STORES.computeIfAbsent(server, AvalonStats::load);
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("trustnoone-stats.json");
    }

    private static Store load(MinecraftServer server) {
        Path file = file(server);
        if (!Files.exists(file)) {
            return new Store();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Store store = GSON.fromJson(reader, Store.class);
            if (store == null) {
                return new Store();
            }
            normalize(store);
            return store;
        } catch (Exception exception) {
            // Never overwrite evidence of what went wrong: park the unreadable file beside the new one.
            TrustNoOneMod.LOGGER.warn("Could not read {} - starting a fresh record", file, exception);
            keepCorruptCopy(file);
            return new Store();
        }
    }

    private static void keepCorruptCopy(Path file) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + stamp),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            TrustNoOneMod.LOGGER.warn("Could not move the unreadable stats file aside", exception);
        }
    }

    /** Fills in anything an older or hand-edited file left out, so no read can hit a null map. */
    private static void normalize(Store store) {
        if (store.players == null) {
            store.players = new LinkedHashMap<>();
            return;
        }
        for (Map.Entry<String, PlayerRecord> entry : store.players.entrySet()) {
            PlayerRecord record = entry.getValue();
            if (record == null) {
                record = new PlayerRecord();
                entry.setValue(record);
            }
            if (record.name == null) {
                record.name = "";
            }
            if (record.total == null) {
                record.total = new Tally();
            }
            if (record.factions == null) {
                record.factions = new LinkedHashMap<>();
            }
            if (record.roles == null) {
                record.roles = new LinkedHashMap<>();
            }
        }
    }

    private static void save(MinecraftServer server, Store store) {
        Path file = file(server);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(store, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            TrustNoOneMod.LOGGER.warn("Could not save Avalon stats to {}", file, exception);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    public static Optional<PlayerRecord> byId(MinecraftServer server, UUID id) {
        Store store = store(server);
        synchronized (store) {
            PlayerRecord record = store.players.get(id.toString());
            return record == null ? Optional.empty() : Optional.of(record);
        }
    }

    /** Finds a player by the name they last played under, case-insensitively. */
    public static Optional<PlayerRecord> byName(MinecraftServer server, String name) {
        Store store = store(server);
        synchronized (store) {
            PlayerRecord best = null;
            for (PlayerRecord record : store.players.values()) {
                if (record == null || record.name == null || !record.name.equalsIgnoreCase(name)) {
                    continue;
                }
                // A renamed account can leave two records behind; the busier one is the real one.
                if (best == null || record.total.games > best.total.games) {
                    best = record;
                }
            }
            return best == null ? Optional.empty() : Optional.of(best);
        }
    }

    /** The server's ranking by overall win rate. See {@link #ranking(MinecraftServer, Faction, AvalonRole)}. */
    public static List<Entry> ranking(MinecraftServer server) {
        return ranking(server, null, null);
    }

    /**
     * The server's ranking by one category: overall ({@code faction == null && role == null}), one
     * faction, or one role.
     *
     * <p>Ties go to more wins, then more games, then name: a 3/3 record beats 1/1, and a stable tail
     * means two queries over the same file print the same order. A player with no game in the category
     * is not ranked at all -- a 0/0 tally has no rate to rank.
     */
    public static List<Entry> ranking(MinecraftServer server,
                                      @Nullable AvalonRole.Faction faction, @Nullable AvalonRole role) {
        Store store = store(server);
        List<Entry> rows = new ArrayList<>();
        synchronized (store) {
            for (Map.Entry<String, PlayerRecord> entry : store.players.entrySet()) {
                PlayerRecord record = entry.getValue();
                if (record == null || record.total == null) {
                    continue;
                }
                Tally ranked = role != null ? record.role(role)
                        : faction != null ? record.faction(faction)
                        : record.total;
                if (ranked == null || ranked.games <= 0) {
                    continue;
                }
                Tally evil = record.faction(AvalonRole.Faction.EVIL);
                Tally good = record.faction(AvalonRole.Faction.GOOD);
                rows.add(new Entry(entry.getKey(), record.name == null ? "" : record.name,
                        ranked.games, ranked.wins,
                        record.total.games, record.total.wins,
                        evil == null ? 0 : evil.games, evil == null ? 0 : evil.wins,
                        good == null ? 0 : good.games, good == null ? 0 : good.wins));
            }
        }
        rows.sort(Comparator.comparingDouble((Entry row) -> row.rate()).reversed()
                .thenComparing(Comparator.comparingInt((Entry row) -> row.wins).reversed())
                .thenComparing(Comparator.comparingInt((Entry row) -> row.games).reversed())
                .thenComparing(row -> row.name().toLowerCase(java.util.Locale.ROOT)));
        return rows;
    }
}
