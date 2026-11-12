import io

p = 'src/main/java/dev/trustnoone/game/AvalonGame.java'
s = io.open(p, encoding='utf-8').read()

# (a) stats mirror slots
old_slots = """    public static final int SYNC_NO_VOTERS = SYNC_OVER + 1;
    public static final int SYNC_SIZE = SYNC_NO_VOTERS + 1;"""
new_slots = """    public static final int SYNC_NO_VOTERS = SYNC_OVER + 1;
    /**
     * One slot per seat, two counters packed into it: how many times that seat voted a team carrying evil
     * down, and how many teams with no evil on them it proposed.
     *
     * <p>Mirrored rather than recomputed because the report names both and the report is drawn on the
     * <em>client</em> -- see {@link #summaryLines}. A byte each is plenty: five quests mean five proposals,
     * and a seat cannot vote down more teams than were proposed.
     */
    public static final int SYNC_STATS_BASE = SYNC_NO_VOTERS + 1;
    public static final int SYNC_SIZE = SYNC_STATS_BASE + MAX_SEATS;"""
assert s.count(old_slots) == 1, 'slots'
s = s.replace(old_slots, new_slots)

old_seat_slots = """        if (index >= SYNC_ROLE_BASE && index < SYNC_ROLE_BASE + MAX_SEATS) {"""
new_seat_slots = """        if (index >= SYNC_STATS_BASE && index < SYNC_STATS_BASE + MAX_SEATS) {
            int at = index - SYNC_STATS_BASE;
            return at < sharpNo.length ? (sharpNo[at] << 8) | cleanTeams[at] : 0;
        }
        if (index >= SYNC_ROLE_BASE && index < SYNC_ROLE_BASE + MAX_SEATS) {"""
assert s.count(old_seat_slots) == 1, 'seat slots'
s = s.replace(old_seat_slots, new_seat_slots)

# (b) postSummary -> shared static builder
start = s.index("    private void postSummary(boolean goodWon) {")
end = s.index("    /**\n     * One summary line, to every seat's <em>chat</em>.")
new_post = '''    private void postSummary() {
        List<Component> names = new ArrayList<>();
        for (CardPlayer player : players) {
            names.add(player.getName());
        }
        for (Component line : summaryLines(names, roles, missions, sharpNo, cleanTeams, winner)) {
            summaryLine(line);
        }
    }

    /**
     * The report, one component per line, exactly as the chat prints it.
     *
     * <p>Static and pure so the <em>client</em> can build the same lines for the panel it fades in over the
     * finished table: every ingredient is either on both sides already -- the seats, the missions, the
     * revealed roles, the winner -- or mirrored for this purpose alone ({@code SYNC_STATS_BASE}). One
     * builder read twice beats two versions that agree by luck: the panel and the chat cannot drift apart,
     * because they are the same list.
     */
    public static List<Component> summaryLines(List<Component> names, List<AvalonRole> roles, int[] missions,
                                               int[] sharpNo, int[] cleanTeams, int winner) {
        List<Component> lines = new ArrayList<>();
        boolean goodWon = winner == SIDE_GOOD;
        lines.add(Component.translatable(goodWon
                        ? "status.trustnoone.good_wins" : "status.trustnoone.evil_wins")
                .withStyle(ChatFormatting.BOLD)
                .withStyle(style -> style.withColor(goodWon ? SUMMARY_GOOD : SUMMARY_EVIL)));

        for (int i = 0; i < roles.size(); i++) {
            boolean evil = roles.get(i).isEvil();
            lines.add(Component.translatable("message.trustnoone.summary_role", names.get(i),
                            Component.translatable(roles.get(i).getTranslationKey()))
                    .withStyle(style -> style.withColor(evil ? SUMMARY_EVIL : SUMMARY_GOOD)));
        }

        int succeeded = 0;
        int failed = 0;
        for (int mission : missions) {
            if (mission == 1) {
                succeeded++;
            } else if (mission == 2) {
                failed++;
            }
        }
        lines.add(Component.translatable("message.trustnoone.summary_quest_success", succeeded)
                .withStyle(style -> style.withColor(SUMMARY_GOOD)));
        lines.add(Component.translatable("message.trustnoone.summary_quest_fail", failed)
                .withStyle(style -> style.withColor(SUMMARY_EVIL)));

        // Two tables of judgement, one about reading a team and one about building it, printed the same way.
        rankInto(lines, names, roles, sharpNo, "message.trustnoone.summary_ranking");
        rankInto(lines, names, roles, cleanTeams, "message.trustnoone.summary_clean_teams");
        return lines;
    }

    /**
     * One ranking block appended to {@code lines}: a header, then every seat in order with its count.
     *
     * <p>Ranked by count and by seat where two seats tie -- a stable order, so the same table always prints
     * the same way and a seat that scored nothing is still named rather than dropped. Each line is coloured
     * by the seat's faction, which is the only thing that makes a good seat's count comparable with an evil
     * one's: an evil seat that builds clean teams is telling the table something different.
     */
    private static void rankInto(List<Component> lines, List<Component> names, List<AvalonRole> roles,
                                 int[] counts, String headerKey) {
        lines.add(Component.translatable(headerKey).withStyle(style -> style.withColor(SUMMARY_GOOD)));
        Integer[] order = new Integer[names.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> counts[b] - counts[a] != 0 ? counts[b] - counts[a] : a - b);
        for (int place = 0; place < order.length; place++) {
            int seat = order[place];
            boolean evil = roles.get(seat).isEvil();
            lines.add(Component.translatable("message.trustnoone.summary_rank", place + 1,
                            names.get(seat), counts[seat])
                    .withStyle(style -> style.withColor(evil ? SUMMARY_EVIL : SUMMARY_GOOD)));
        }
    }

'''
s = s[:start] + new_post + s[end:]

# (c) hoist the names list out of postSummary's caller
s = s.replace("        postSummary(goodWon);", "        postSummary();")

# (d) the flag: both material evil endings get the red act, and the linger is reframed
s = s.replace("endedBySabotage", "endedByRedAct")
s = s.replace("    private static final int EVIL_CLOSE_LINGER = 64;",
              "    private static final int RED_ACT_LINGER = 50;")
old_doc = """     * Ticks the finished table is held after the sabotage announcement.
     *
     * <p>Kept in step with the client's red act ({@code EV_TOTAL_MS}, about 5.6 s, counted from the moment
     * the phase reaches {@code OVER} -- i.e. from about {@code RESULT_LINGER} before this count starts).
     * The block sweeps the table when this expires, which lands just after the last screen has closed
     * itself, rather than nine seconds later with nobody left watching."""
new_doc = """     * Ticks the finished table is held after the red announcement.
     *
     * <p>Short, and timed to land inside the client's red act ({@code EV_TOTAL_MS}, about 5.6 s from the
     * moment the phase reaches {@code OVER}): what expires here is the block sweeping the table, and under
     * the act's frost and wash nobody can see it happen. Waiting the full {@link #REVEAL_LINGER} would put
     * that sweep on screen after the report has already faded in."""
assert s.count(old_doc) == 1, 'linger doc'
s = s.replace(old_doc, new_doc)

old_flag_doc = """     * Set when the game ended on evil's third sabotaged quest.
     *
     * <p>The one ending whose announcement belongs to the client: it fades the whole screen red over the
     * winner's name and then closes the table itself, so this side leaves the titles to it and hands the
     * finished table back that much sooner."""
new_flag_doc = """     * Set when the game ended on evil's third sabotaged quest or on the fifth rejected team.
     *
     * <p>The endings whose announcement belongs to the client: it fades the whole screen red over the
     * winner's name, so this side leaves the titles to it. Neither has an act of its own the way the knife's
     * does, and a win by arithmetic deserves saying out loud rather than a title over a live-looking board."""
if s.count(old_flag_doc) == 1:
    s = s.replace(old_flag_doc, new_flag_doc)
else:
    print('WARN: flag doc not matched')

# the rejection branch joins the red act
old_rejects = """            if (rejects >= MAX_REJECTS) {
                finish(SIDE_EVIL, "message.trustnoone.evil_by_rejects");"""
new_rejects = """            if (rejects >= MAX_REJECTS) {
                endedByRedAct = true;
                finish(SIDE_EVIL, "message.trustnoone.evil_by_rejects");"""
assert s.count(old_rejects) == 1, 'rejects'
s = s.replace(old_rejects, new_rejects)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('game ok')
