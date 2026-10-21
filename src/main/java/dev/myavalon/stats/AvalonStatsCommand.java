package dev.myavalon.stats;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.myavalon.game.AvalonRole;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /myavalon rate ...}: one command with five answers.
 *
 * <pre>
 *   /myavalon rate total [count]        overall win-rate ranking
 *   /myavalon rate good [count]         Good faction ranking
 *   /myavalon rate evil [count]         Evil faction ranking
 *   /myavalon rate role &lt;mob id&gt; [count] one identity's ranking
 *   /myavalon rate personal [player]    one player's full record
 * </pre>
 *
 * <p>Everything except {@code personal} is a ranking. The role argument is the bare id of the
 * Minecraft mob the role is drawn as ({@code allay}, {@code creeper}, ...); the ids are listed in the
 * usage line and the how-to-play page. The Librarian is the one id that is a villager profession
 * rather than an entity type -- see {@link AvalonRole#getMobId()}.
 *
 * <h2>One record, four slices</h2>
 *
 * <p>{@code personal} prints the overall rate, the Evil rate, the Good rate and a rate for every role.
 * A ranking is asked for one slice at a time and only shows that slice; the breakdown is what
 * {@code personal} is for.
 *
 * <h2>Why a command rather than a screen</h2>
 *
 * <p>The records are a server fact, not a table fact: a player asks about themselves between games, or
 * about somebody who is not at the table, and the answer has to work from the chat bar. A screen would
 * also have to be built twice (the client's menu is not where the file lives), while a command is one
 * server-side handler whose text the client already knows how to draw.
 *
 * <h2>Querying an offline player</h2>
 *
 * <p>{@code personal} takes a UUID or the name the player last used. A UUID resolves straight to the
 * record; a name resolves through an online player first and then the records, which is what makes the
 * offline case work.
 */
public final class AvalonStatsCommand {

    private AvalonStatsCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("myavalon")
                        .executes(AvalonStatsCommand::usage)
                        .then(rateRoot())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rateRoot() {
        LiteralArgumentBuilder<CommandSourceStack> rate =
                Commands.literal("rate").executes(AvalonStatsCommand::usage);
        rateChildren(rate);
        return rate.then(viewer());
    }

    // ---------------------------------------------------------------------------------------------
    // Command tree
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@code rate} answers, shared by the plain form and the form with a viewer in front.
     *
     * <p>The viewer is the player the answer is <em>shown to</em>, not a filter: on a server one person
     * often runs the query while everyone at the table wants to see it, and the console has no chat bar
     * at all. Without it, every reply goes to whoever typed the command.
     */
    private static void rateChildren(LiteralArgumentBuilder<CommandSourceStack> rate) {
        rate.then(ranking("total", null, null))
                .then(ranking("good", AvalonRole.Faction.GOOD, null))
                .then(ranking("evil", AvalonRole.Faction.EVIL, null))
                .then(roleRoot())
                .then(personalRoot());
    }

    /**
     * {@code rate <viewer> ...}. The literal branches are tried first by Brigadier, so a player literally
     * named {@code total} needs the viewer form spelled another way -- accepted as the one ambiguity.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, EntitySelector> viewer() {
        return Commands.argument("viewer", EntityArgument.players())
                .executes(context -> usage(context))
                .then(Commands.literal("total")
                        .executes(context -> ranking(context, null, null, 10))
                        .then(count().executes(context -> ranking(context, null, null, count(context)))))
                .then(Commands.literal("good")
                        .executes(context -> ranking(context, AvalonRole.Faction.GOOD, null, 10))
                        .then(count().executes(context ->
                                ranking(context, AvalonRole.Faction.GOOD, null, count(context)))))
                .then(Commands.literal("evil")
                        .executes(context -> ranking(context, AvalonRole.Faction.EVIL, null, 10))
                        .then(count().executes(context ->
                                ranking(context, AvalonRole.Faction.EVIL, null, count(context)))))
                .then(viewerRoles())
                .then(Commands.literal("personal")
                        .executes(AvalonStatsCommand::self)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(AvalonStatsCommand::suggestPlayers)
                                .executes(context ->
                                        target(context, StringArgumentType.getString(context, "player")))));
    }

    /**
     * The player form of {@code role} -- every id again, because the literal branch cannot be shared
     * between two parents. The arguments still belong to the outer viewer node.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> viewerRoles() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("role");
        for (AvalonRole role : AvalonRole.values()) {
            root.then(Commands.literal(role.getMobId())
                    .executes(context -> ranking(context, null, role, 10))
                    .then(count().executes(context -> ranking(context, null, role, count(context)))));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> ranking(String name,
                                                                       @Nullable AvalonRole.Faction faction,
                                                                       @Nullable AvalonRole role) {
        return Commands.literal(name)
                .executes(context -> ranking(context, faction, role, 10))
                .then(count().executes(context -> ranking(context, faction, role, count(context))));
    }

    /**
     * {@code role <mob id>}. The bare id is a literal rather than a {@code word()} argument so the
     * suggestions are the eight real ids instead of free text. Only the bare form is registered: the
     * namespaced spelling would double every completion entry with a {@code minecraft:} prefix.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> roleRoot() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("role");
        for (AvalonRole role : AvalonRole.values()) {
            root.then(roleRanking(role.getMobId(), role));
        }
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> roleRanking(String name, AvalonRole role) {
        return Commands.literal(name)
                .executes(context -> ranking(context, null, role, 10))
                .then(count().executes(context -> ranking(context, null, role, count(context))));
    }

    /**
     * {@code personal [player]}. The argument is a UUID or a name; omitted means the caller.
     *
     * <p>{@code word()} is enough for both: it allows the dashes of a UUID and the letters of a name,
     * and neither ever contains a space.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> personalRoot() {
        return Commands.literal("personal")
                .executes(AvalonStatsCommand::self)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(AvalonStatsCommand::suggestPlayers)
                        .executes(context -> target(context, StringArgumentType.getString(context, "player"))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> count() {
        return Commands.argument("count", IntegerArgumentType.integer(1, 50));
    }

    private static int count(CommandContext<CommandSourceStack> context) {
        return IntegerArgumentType.getInteger(context, "count");
    }

    private static int usage(CommandContext<CommandSourceStack> context) {
        for (CommandSourceStack source : outputs(context)) {
            source.sendSuccess(() -> Component.translatable("stats.myavalon.usage"), false);
        }
        return 0;
    }

    /**
     * Where the answer goes: every named viewer, otherwise the caller.
     *
     * <p>A selector rather than one player, so {@code @a} shows the board to the whole table. Never
     * broadcast through the server: a ranking is a wall of lines and the admin flag would send it to
     * every chat bar, including people the command was not aimed at. Each viewer's own source keeps
     * the sends local to that player.
     */
    private static List<CommandSourceStack> outputs(CommandContext<CommandSourceStack> context) {
        try {
            return EntityArgument.getPlayers(context, "viewer").stream()
                    .map(ServerPlayer::createCommandSourceStack)
                    .toList();
        } catch (CommandSyntaxException ignored) {
            return List.of(context.getSource());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A player's record
    // ---------------------------------------------------------------------------------------------

    private static int self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int result = 0;
        for (CommandSourceStack source : outputs(context)) {
            ServerPlayer player = source.getPlayerOrException();
            Optional<AvalonStats.PlayerRecord> record =
                    AvalonStats.byId(source.getServer(), player.getGameProfile().getId());
            print(source, player.getGameProfile().getName(), record);
            result = record.isPresent() ? 1 : 0;
        }
        return result;
    }

    private static int target(CommandContext<CommandSourceStack> context, String input) {
        List<CommandSourceStack> sinks = outputs(context);
        CommandSourceStack source = sinks.getFirst();
        MinecraftServer server = source.getServer();
        Optional<AvalonStats.PlayerRecord> record = Optional.empty();

        // A UUID is unambiguous and works for somebody who is offline; a name has to go through the
        // online list first so a player who is currently connected is never confused with an alt.
        UUID id = parseUuid(input);
        if (id != null) {
            record = AvalonStats.byId(server, id);
        } else {
            ServerPlayer online = server.getPlayerList().getPlayerByName(input);
            if (online != null) {
                record = AvalonStats.byId(server, online.getGameProfile().getId());
            }
            if (record.isEmpty()) {
                record = AvalonStats.byName(server, input);
            }
        }

        String display = record.map(row -> row.name == null || row.name.isEmpty() ? input : row.name)
                .orElse(input);
        for (CommandSourceStack sink : sinks) {
            print(sink, display, record);
        }
        return record.isPresent() ? 1 : 0;
    }

    @Nullable
    private static UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            // A copy-pasted UUID sometimes arrives without its dashes; accept the 32-hex form too.
            if (input.length() != 32) {
                return null;
            }
            try {
                return new UUID(Long.parseUnsignedLong(input.substring(0, 16), 16),
                        Long.parseUnsignedLong(input.substring(16), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    /**
     * The whole record: total, both factions and every role.
     *
     * <p>The role list is complete rather than "roles this player has played": a 0/0 role is the same
     * fact as an unplayed one, and a breakdown with holes in it is harder to check than one with dashes.
     */
    private static void print(CommandSourceStack source, String name,
                              Optional<AvalonStats.PlayerRecord> found) {
        source.sendSuccess(() -> Component.translatable("stats.myavalon.header", name)
                .withStyle(ChatFormatting.BOLD)
                .withStyle(style -> style.withColor(0xFFFFE97F)), false);
        if (found.isEmpty() || found.get().total.games <= 0) {
            source.sendSuccess(() -> Component.translatable("stats.myavalon.empty", name)
                    .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        AvalonStats.PlayerRecord record = found.get();
        source.sendSuccess(() -> Component.translatable("stats.myavalon.overall",
                record.total.games, record.total.wins, rate(record.total)), false);
        // Evil then good, the order the printed rulebook names the sides; each label carries its colour.
        sendFaction(source, record, AvalonRole.Faction.EVIL);
        sendFaction(source, record, AvalonRole.Faction.GOOD);

        source.sendSuccess(() -> Component.translatable("stats.myavalon.roles_header")
                .withStyle(ChatFormatting.GRAY), false);
        for (AvalonRole role : AvalonRole.values()) {
            AvalonStats.Tally tally = record.role(role);
            int games = tally == null ? 0 : tally.games;
            int wins = tally == null ? 0 : tally.wins;
            source.sendSuccess(() -> Component.translatable("stats.myavalon.role",
                    role.coloredName(), games, wins, rate(tally)), false);
        }
    }

    private static void sendFaction(CommandSourceStack source, AvalonStats.PlayerRecord record,
                                    AvalonRole.Faction faction) {
        AvalonStats.Tally tally = record.faction(faction);
        int games = tally == null ? 0 : tally.games;
        int wins = tally == null ? 0 : tally.wins;
        Component label = Component.translatable(faction == AvalonRole.Faction.EVIL
                        ? "stats.myavalon.label_evil" : "stats.myavalon.label_good")
                .withStyle(style -> style.withColor(faction.textColor()));
        source.sendSuccess(() -> Component.translatable("stats.myavalon.faction",
                label, games, wins, rate(tally)), false);
    }

    // ---------------------------------------------------------------------------------------------
    // Rankings
    // ---------------------------------------------------------------------------------------------

    private static int ranking(CommandContext<CommandSourceStack> context,
                               @Nullable AvalonRole.Faction faction, @Nullable AvalonRole role, int count) {
        List<CommandSourceStack> sinks = outputs(context);
        List<AvalonStats.Entry> rows = AvalonStats.ranking(sinks.getFirst().getServer(), faction, role);
        Component category = category(faction, role);
        if (rows.isEmpty()) {
            for (CommandSourceStack sink : sinks) {
                sink.sendSuccess(() -> Component.translatable("stats.myavalon.ranking_empty")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            return 0;
        }
        int shown = Math.min(count, rows.size());
        for (CommandSourceStack sink : sinks) {
            sink.sendSuccess(() -> Component.translatable("stats.myavalon.ranking_header",
                            category, shown, rows.size())
                    .withStyle(ChatFormatting.BOLD)
                    .withStyle(style -> style.withColor(0xFFFFE97F)), false);
        }

        boolean overall = faction == null && role == null;
        for (int i = 0; i < shown; i++) {
            AvalonStats.Entry row = rows.get(i);
            int place = i + 1;
            MutableComponent placeText = Component.literal(Integer.toString(place))
                    .withStyle(style -> style.withColor(place <= 3 ? 0xFFFFE97F : 0xFF9A9A9A));
            for (CommandSourceStack sink : sinks) {
                if (overall) {
                    // One slice per query: the total board shows the total rate only, the faction and
                    // role boards show theirs. The full split is what personal is for.
                    sink.sendSuccess(() -> Component.translatable("stats.myavalon.ranking_line_overall",
                            placeText,
                            Component.literal(row.name().isEmpty() ? "?" : row.name()),
                            Component.literal(Integer.toString(row.totalGames())),
                            Component.literal(Integer.toString(row.totalWins())),
                            rate(row.totalGames(), row.totalWins())), false);
                } else {
                    sink.sendSuccess(() -> Component.translatable("stats.myavalon.ranking_line",
                            placeText,
                            Component.literal(row.name().isEmpty() ? "?" : row.name()),
                            Component.literal(Integer.toString(row.games())),
                            Component.literal(Integer.toString(row.wins())),
                            rate(row.games(), row.wins())), false);
                }
            }
        }
        return shown;
    }

    private static Component category(@Nullable AvalonRole.Faction faction, @Nullable AvalonRole role) {
        if (role != null) {
            return Component.translatable("stats.myavalon.category_role", role.coloredName());
        }
        if (faction == AvalonRole.Faction.EVIL) {
            return Component.translatable("stats.myavalon.category_evil");
        }
        if (faction == AvalonRole.Faction.GOOD) {
            return Component.translatable("stats.myavalon.category_good");
        }
        return Component.translatable("stats.myavalon.category_overall");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** A rate with one decimal, or a dash when there is nothing to divide by. */
    private static Component rate(@Nullable AvalonStats.Tally tally) {
        return tally == null
                ? Component.literal("—").withStyle(ChatFormatting.GRAY)
                : rate(tally.games, tally.wins);
    }

    private static Component rate(int games, int wins) {
        if (games <= 0) {
            return Component.literal("—").withStyle(ChatFormatting.GRAY);
        }
        float rate = wins * 100f / games;
        int color = rate >= 50f ? 0xFF6FD98C : 0xFFE2716F;
        return Component.literal(String.format(Locale.ROOT, "%.1f%%", rate))
                .withStyle(style -> style.withColor(color));
    }

    /**
     * Names to offer after {@code /myavalon rate personal }: everybody online, then every name in the
     * records that is not already in the list.
     */
    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context,
                                                                 SuggestionsBuilder builder) {
        Set<String> names = new LinkedHashSet<>();
        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
            names.add(player.getGameProfile().getName());
        }
        for (AvalonStats.Entry row : AvalonStats.ranking(context.getSource().getServer())) {
            if (!row.name().isEmpty()) {
                names.add(row.name());
            }
        }
        return SharedSuggestionProvider.suggest(new ArrayList<>(names), builder);
    }
}
