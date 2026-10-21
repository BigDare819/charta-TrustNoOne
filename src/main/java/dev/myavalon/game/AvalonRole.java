package dev.myavalon.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * One role of the Avalon setup, plus the two tables that decide what a table of a given size is dealt
 * and how big its quests are.
 *
 * <p>Everything here is static data; the rules themselves live in {@link AvalonGame}. Keeping the
 * composition out of the game class means a table of nine gets its roles from one readable table
 * rather than from a chain of {@code if (players.size() > ...)} guards.
 *
 * <h2>The composition</h2>
 *
 * <p>Merlin and the Assassin are always seated, and the extra seats are filled from the plain ranks.
 * The numbers are the ones the printed game uses, so a table of seven really is four good against
 * three evil and the Assassin really is the only evil seat that gets a shot at the end.
 *
 * <p>Percival and Morgana are <b>temporarily out of the deal</b> -- see {@link #compose}. Both are
 * still declared here, and every table size keeps the good/evil split it always had, so putting them
 * back is a two-line change rather than a rules rewrite.
 */
public enum AvalonRole {

    MERLIN(Faction.GOOD, "merlin", true),
    PERCIVAL(Faction.GOOD, "percival", false),
    LOYALIST(Faction.GOOD, "loyalist", false),

    ASSASSIN(Faction.EVIL, "assassin", false),
    MORGANA(Faction.EVIL, "morgana", false),
    MORDRED(Faction.EVIL, "mordred", false),
    OBERON(Faction.EVIL, "oberon", false),
    MINION(Faction.EVIL, "minion", false);

    public enum Faction {
        GOOD, EVIL;

        /** {@code faction.myavalon.good}: the side's name as the stats command prints it. */
        public String getTranslationKey() {
            return "faction.myavalon." + name().toLowerCase(java.util.Locale.ROOT);
        }

        public int textColor() {
            return this == GOOD ? GOOD_TEXT : EVIL_TEXT;
        }
    }

    private final Faction faction;
    private final String path;
    private final boolean special;

    AvalonRole(Faction faction, String path, boolean special) {
        this.faction = faction;
        this.path = path;
        this.special = special;
    }

    public Faction getFaction() {
        return faction;
    }

    public boolean isGood() {
        return faction == Faction.GOOD;
    }

    public boolean isEvil() {
        return faction == Faction.EVIL;
    }

    /** Whether this seat has a rule that fires at the end of the game. Only the two here do. */
    public boolean isSpecial() {
        return special;
    }

    public String getPath() {
        return path;
    }

    /**
     * The Minecraft mob this role is drawn as, without the namespace.
     *
     * <p>The ids are what {@code /myavalon rate role} takes. Seven are real entity types; the Librarian
     * is a villager <em>profession</em> rather than an entity type, so it is given the profession id
     * {@code librarian}. That also keeps it apart from the plain Villager, which owns {@code villager}:
     * both cards show a villager, and one id cannot answer for both.
     */
    public String getMobId() {
        return switch (this) {
            case MERLIN -> "allay";
            case PERCIVAL -> "librarian";
            case LOYALIST -> "villager";
            case ASSASSIN -> "creeper";
            case MORGANA -> "illusioner";
            case MORDRED -> "ender_dragon";
            case OBERON -> "phantom";
            case MINION -> "zombie";
        };
    }

    /** {@code card.myavalon.merlin}: the role's own name, shown on the card and in chat. */
    public String getTranslationKey() {
        return "card.myavalon." + path;
    }

    /** {@code role.myavalon.merlin}: the one-line reminder of what the role knows. */
    public String getHintKey() {
        return "role.myavalon." + path;
    }

    // ---------------------------------------------------------------------------------------------
    // The hover comment
    // ---------------------------------------------------------------------------------------------

    /** Good's and evil's text colours, shared by the identity act, the summary and every comment. */
    public static final int GOOD_TEXT = 0xFF6FA8FF;
    public static final int EVIL_TEXT = 0xFFFF6B6B;

    /** The comment's own text, between the coloured names. */
    private static final int COMMENT_TEXT = 0xFFCFCFCF;

    /**
     * Which roles each comment names, in the order its {@code %s} placeholders appear.
     *
     * <p>The names are arguments rather than part of the sentence so that the sentence stays a lang value
     * the player owns while the <em>colour</em> stays a rule: a role named inside a comment is always drawn
     * in its own faction's colour, which is what makes the line readable at a glance. The price is that the
     * order here and in the lang text have to agree; both are short enough to keep side by side.
     */
    private static final Map<AvalonRole, AvalonRole[]> MENTIONS = new EnumMap<>(AvalonRole.class);

    static {
        MENTIONS.put(MERLIN, new AvalonRole[]{MORDRED});
        MENTIONS.put(PERCIVAL, new AvalonRole[]{MERLIN, MORGANA});
        MENTIONS.put(ASSASSIN, new AvalonRole[]{ASSASSIN, MERLIN});
        MENTIONS.put(MORGANA, new AvalonRole[]{MORGANA, MERLIN, PERCIVAL});
        MENTIONS.put(OBERON, new AvalonRole[]{OBERON, OBERON, MERLIN, OBERON});
        MENTIONS.put(MORDRED, new AvalonRole[]{MORDRED, MERLIN});
        MENTIONS.put(MINION, new AvalonRole[]{MINION});
    }

    /** {@code card.myavalon.merlin} -> {@link #MERLIN}, so a hovered card can find its own comment. */
    private static final Map<String, AvalonRole> BY_CARD_KEY = new HashMap<>();

    static {
        for (AvalonRole role : values()) {
            BY_CARD_KEY.put(role.getTranslationKey(), role);
        }
    }

    /** This role's name in its faction's colour. */
    public MutableComponent coloredName() {
        return Component.translatable(getTranslationKey()).withStyle(style -> style.withColor(textColor()));
    }

    /** {@link #GOOD_TEXT} or {@link #EVIL_TEXT}, by faction. */
    public int textColor() {
        return isEvil() ? EVIL_TEXT : GOOD_TEXT;
    }

    /**
     * Built lazily and kept, because this is read once per frame for as long as anything of this role is
     * under the pointer: every card tooltip is rebuilt by Charta on every render pass, so without the cache
     * a hovered card would re-resolve the sentence, its colour and every name inside it sixty times a
     * second. Safe to share -- nothing mutates a comment, and a component can sit in any number of trees.
     */
    @Nullable
    private MutableComponent cachedComment;

    /**
     * The one-line comment under a role's name on hover.
     *
     * <p>Reads {@code comment.myavalon.<role>}, whose {@code %s} placeholders are filled with the roles
     * listed in {@link #MENTIONS}, each already coloured. A role that names nobody needs no entry there.
     */
    public MutableComponent comment() {
        if (cachedComment == null) {
            cachedComment = buildComment();
        }
        return cachedComment;
    }

    private MutableComponent buildComment() {
        AvalonRole[] named = MENTIONS.get(this);
        Object[] args = new Object[named == null ? 0 : named.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = named[i].coloredName();
        }
        // An unset key renders as the raw key, which is visible enough in a tooltip to be fixed; the name
        // line above it still says which role the card is either way.
        return Component.translatable("comment.myavalon." + path, args)
                .withStyle(style -> style.withColor(COMMENT_TEXT));
    }

    /** The tooltip an identity card shows: the role's name, then its comment under it. */
    public MutableComponent tooltip() {
        return coloredName().append(Component.literal("\n")).append(comment());
    }

    /**
     * The tooltip for a hovered card of translation key {@code cardKey}, or {@code null} if it is not a role
     * card at all (a vote or a quest card, whose names want no comment).
     */
    @Nullable
    public static MutableComponent tooltipForCard(@Nullable String cardKey) {
        AvalonRole role = cardKey == null ? null : BY_CARD_KEY.get(cardKey);
        return role == null ? null : role.tooltip();
    }

    // ---------------------------------------------------------------------------------------------
    // Composition tables
    // ---------------------------------------------------------------------------------------------

    /** Extra good seats, i.e. plain loyalists, indexed by the number of players at the table. */
    private static final int[] EXTRA_LOYALISTS = {0, 0, 0, 0, 0, 1, 2, 2, 3, 4, 4};

    /** Extra evil seats beyond the Assassin and Morgana, indexed the same way. */
    private static final AvalonRole[][] EXTRA_EVIL = {
            {}, {}, {}, {}, {},
            {},                 // 5: Assassin, Morgana
            {},                 // 6: one more loyalist
            {MINION},           // 7
            {MORDRED},          // 8
            {MORDRED},          // 9
            {MORDRED, OBERON},  // 10
    };

    /** Quest team sizes, {@code [players][quest]}, matching the printed board. */
    private static final int[][] TEAM_SIZES = {
            {}, {}, {}, {}, {},
            {2, 3, 2, 3, 3},    // 5
            {2, 3, 4, 3, 4},    // 6
            {2, 3, 3, 4, 4},    // 7
            {3, 4, 4, 5, 5},    // 8
            {3, 4, 4, 5, 5},    // 9
            {3, 4, 4, 5, 5},    // 10
    };

    public static final int MIN_PLAYERS = 5;
    public static final int MAX_PLAYERS = 10;
    public static final int QUESTS = 5;

    /**
     * The roles dealt to a table of {@code players}, in seat-independent order and <em>not</em> shuffled.
     *
     * <p><b>Percival and Morgana are temporarily not dealt.</b> The pair only exists to give Percival
     * something to see, so with Percival gone Morgana has nothing to do either -- and the two seats she
     * and Percival occupied are handed instead to a plain loyalist and a plain minion, which is what
     * keeps every table size's good/evil split (and therefore {@link #goodCount}, {@link #evilCount} and
     * all five quest sizes) exactly what it was.
     *
     * <p>To deal them again: put {@code PERCIVAL} and {@code MORGANA} back in place of the {@code LOYALIST}
     * and {@code MINION} below, and drop the two {@code roles.add} lines that stand in for them. Nothing
     * else in the rules needs touching -- {@code AvalonGame.knownMask} and {@code AvalonBots} still carry
     * their Percival and Morgana branches, dormant.
     *
     * <p>{@link #compose(int, int)} is the same idea promoted to a switch the player can throw, which is
     * what the identity setup on the options screen writes.
     */
    public static List<AvalonRole> compose(int players) {
        List<AvalonRole> roles = new ArrayList<>();
        roles.add(MERLIN);
        roles.add(LOYALIST);        // stands in for PERCIVAL
        roles.add(ASSASSIN);
        roles.add(MINION);          // stands in for MORGANA
        if (players < MIN_PLAYERS || players > MAX_PLAYERS) {
            return roles;
        }
        for (int i = 0; i < EXTRA_LOYALISTS[players]; i++) {
            roles.add(LOYALIST);
        }
        Collections.addAll(roles, EXTRA_EVIL[players]);
        return roles;
    }

    // ---------------------------------------------------------------------------------------------
    // The custom setup
    // ---------------------------------------------------------------------------------------------

    /**
     * The roles a table always has, in the order the setup screen lists them: ticked, greyed and locked.
     *
     * <p>Merlin and the Assassin are structural -- without the first there is nothing to know, without the
     * second nothing to stab. The Minion and the Villager used to be locked here as well (evil wanted a
     * plain seat, good wanted a filler); both are ordinary rows now, so a set can be built out of chosen
     * identities alone -- Merlin, Percival, the Assassin and Morgana is a legal five-seat table with no
     * plain role in it at all.
     */
    public static final List<AvalonRole> LOCKED = List.of(MERLIN, ASSASSIN);

    /**
     * The roles a player may switch on and off, in the order the setup screen lists them.
     *
     * <p>The two plain ones come last because they are the seats that carry no information, so they read as
     * the tail of the list rather than as peers of the roles above them. Unticking the Villager means "do
     * not count one in" rather than "let the spares be something else": it is also the filler a short set
     * is padded with, since there is nothing plainer to pad with.
     */
    public static final List<AvalonRole> OPTIONAL =
            List.of(PERCIVAL, MORGANA, MORDRED, OBERON, MINION, LOYALIST);

    /**
     * Bit {@code 7} of a setup mask: set means "the mask <em>is</em> the setup", clear means "use the
     * printed table". The optional roles' bits sit below it, which is what lets a whole setup ride in the
     * one byte an option is stored in.
     */
    public static final int CUSTOM_FLAG = 0x80;

    /**
     * The bit a role's tick rides in, or {@code -1} for a role that cannot be ticked.
     *
     * <p>Its <em>index in {@link #OPTIONAL}</em> rather than its enum ordinal, and that is the whole point:
     * ordinals were used first, and the Minion's is {@code 7} — which is {@link #CUSTOM_FLAG}'s own bit. A
     * toggle XORs the role's bit and then ORs the flag, so on that one bit the two cancelled out and the
     * Minion could never be switched off: the row went back to ticked on every click.
     */
    private static final int[] SETUP_BITS = new int[values().length];

    static {
        java.util.Arrays.fill(SETUP_BITS, -1);
        for (int i = 0; i < OPTIONAL.size(); i++) {
            SETUP_BITS[OPTIONAL.get(i).ordinal()] = i;
        }
    }

    public static int setupBit(AvalonRole role) {
        return SETUP_BITS[role.ordinal()];
    }

    public static boolean isCustom(int mask) {
        return (mask & CUSTOM_FLAG) != 0;
    }

    /** The roles a custom mask asks for, the {@link #LOCKED} three always included and always first. */
    public static List<AvalonRole> compose(int players, int mask) {
        if (!isCustom(mask)) {
            return compose(players);
        }
        List<AvalonRole> roles = new ArrayList<>(LOCKED);
        for (AvalonRole role : OPTIONAL) {
            if ((mask & (1 << setupBit(role))) != 0) {
                roles.add(role);
            }
        }
        // The optional ones go over the side first when a set does not fit: they are the ones that were
        // chosen, while the locked three are the ones the table always has. Only a table below
        // {@link #MIN_PLAYERS} is ever short enough to reach them.
        while (roles.size() > players && roles.size() > 2) {
            roles.remove(roles.size() - 1);
        }
        while (roles.size() < players) {
            roles.add(LOYALIST);
        }
        return roles;
    }

    /** The roles a custom mask asks for, the colours of the summary come from the same list. */
    public static int goodCount(int players, int mask) {
        int count = 0;
        for (AvalonRole role : compose(players, mask)) {
            if (role.isGood()) {
                count++;
            }
        }
        return count;
    }

    public static int evilCount(int players, int mask) {
        return compose(players, mask).size() - goodCount(players, mask);
    }

    /**
     * Ticks or unticks an optional role; the flag comes on with the first edit and stays.
     *
     * <p>The flag is bit 7 and every role's bit sits below it, which is what lets a whole setup ride in the
     * one byte an option is stored in — and what makes {@link #setupBit} the only safe place to take a bit
     * from, rather than a role's ordinal.
     */
    public static int toggle(int mask, AvalonRole role) {
        int bit = setupBit(role);
        if (bit < 0) {
            return mask;
        }
        return (mask ^ (1 << bit)) | CUSTOM_FLAG;
    }

    /**
     * The ticks the printed table implies for {@code players}, without the flag.
     *
     * <p>What the setup screen shows while the game is still on "built-in", so that the first toggle edits
     * the set the player can already see rather than starting from nothing.
     */
    public static int builtInMask(int players) {
        int mask = 0;
        List<AvalonRole> roles = compose(players);
        for (AvalonRole role : OPTIONAL) {
            if (roles.contains(role)) {
                mask |= 1 << setupBit(role);
            }
        }
        return mask;
    }

    public static int goodCount(int players) {
        int count = 0;
        for (AvalonRole role : compose(players)) {
            if (role.isGood()) {
                count++;
            }
        }
        return count;
    }

    public static int evilCount(int players) {
        return compose(players).size() - goodCount(players);
    }

    /** How many people go on the quest, or {@code 0} for an out-of-range table. */
    public static int teamSize(int players, int quest) {
        if (players < MIN_PLAYERS || players > MAX_PLAYERS || quest < 0 || quest >= QUESTS) {
            return 0;
        }
        return TEAM_SIZES[players][quest];
    }

    /**
     * Whether this quest needs two fail cards rather than one.
     *
     * <p>The printed game adds the rule on the fourth quest of a seven-player table only, and it is
     * exactly the kind of detail a reimplementation drops: without it seven players is the one size
     * where the evil side wins on raw arithmetic alone.
     */
    public static boolean needsTwoFails(int players, int quest) {
        return players >= 7 && quest == 3;
    }
}
