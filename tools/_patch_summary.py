"""AvalonScreen + AvalonMenu half of the closing rework:

* the red act fires for any evil win that is not the knife's (sabotage and the fifth rejection alike),
* the report fades in on the table itself once the acts are done, for every ending,
* and the table no longer closes itself -- Esc is the player's.
"""
import io

# ---------------------------------------------------------------- AvalonMenu: the two counters
p = 'src/main/java/dev/myavalon/game/AvalonMenu.java'
s = io.open(p, encoding='utf-8').read()
anchor = """    /** Seats that have answered "no" in the running vote, as a bitmask. */"""
new = """    /** How many times {@code seat} voted a team carrying evil down -- see {@code AvalonGame.sharpNo}. */
    public int getSharpNo(int seat) {
        return (gameData.get(AvalonGame.SYNC_STATS_BASE + seat) >>> 8) & 0xFF;
    }

    /** How many teams with no evil on them {@code seat} proposed -- see {@code AvalonGame.cleanTeams}. */
    public int getCleanTeams(int seat) {
        return gameData.get(AvalonGame.SYNC_STATS_BASE + seat) & 0xFF;
    }

""" + anchor
assert s.count(anchor) == 1, 'menu anchor'
s = s.replace(anchor, new)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ---------------------------------------------------------------- AvalonScreen
p = 'src/main/java/dev/myavalon/game/AvalonScreen.java'
s = io.open(p, encoding='utf-8').read()

# (1) the red act's predicate: any evil win that is not the knife's
old = """    /** Whether this finished game was decided by evil's third sabotaged quest. */
    private boolean evilBySabotage() {
        if (menu.getPhase() != AvalonGame.Phase.OVER
                || menu.getWinner() != AvalonGame.SIDE_EVIL
                || menu.getPendingTarget() >= 0) {
            return false;
        }
        int failed = 0;
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (menu.getMission(i) == 2) {
                failed++;
            }
        }
        return failed >= 3;
    }"""
new = """    /**
     * Whether this finished game is evil's to announce in red: it won, and not by the knife.
     *
     * <p>That covers both of evil's material wins -- three sabotaged quests and five rejected teams -- and
     * the knife's is excluded on purpose, because it has an act of its own. Read off the mirror: the winner,
     * and the absence of a picked target.
     */
    private boolean evilEndingMine() {
        return menu.getPhase() == AvalonGame.Phase.OVER
                && menu.getWinner() == AvalonGame.SIDE_EVIL
                && menu.getPendingTarget() < 0;
    }"""
assert s.count(old) == 1, 'evilBySabotage'
s = s.replace(old, new)
s = s.replace("|| !evilBySabotage()) {", "|| !evilEndingMine()) {")

# (2) no auto-close: closeWhenFinished becomes the report's timer
old_close = """    /**
     * Shuts the table once the server says the game is over.
     *
     * <p>The last beat of every ending: the reveal has been up for its full hold and the report is in the
     * chat, so the finished table is left behind rather than waiting for an Esc. Read off the mirrored
     * {@code SYNC_OVER} rather than inferred from the phase, because the hold is the server's business —
     * a client closing the moment the phase turns {@code OVER} would shut the reveal it is having.
     *
     * <p>Queued rather than called: switching screens from inside a render pass leaves that pass running
     * against a screen which is already gone. Same thread, its next turn.
     */
    private void closeWhenFinished() {
        if (closed || !menu.isOver()) {
            return;
        }
        closed = true;
        if (minecraft != null) {
            minecraft.execute(() -> {
                if (minecraft.screen == this) {
                    minecraft.setScreen(null);
                }
            });
        }
    }

    /** Whether this screen has already queued its own close. */
    private boolean closed;
"""
new_close = """    /**
     * The report: the same lines the chat gets, faded in over the finished table.
     *
     * <h2>When</h2>
     *
     * <p>On every ending, and only once the closing acts are done -- they own the screen until then, and
     * the report is the last thing to say. A game that ended without one of them (a good win, a table packed
     * up mid-game) starts it as soon as the phase turns {@code OVER}.
     *
     * <h2>Why the screen stays open</h2>
     *
     * <p>Because the report is worth reading and there is nothing behind it to hurry back to: the table is
     * swept by the server either way and a rematch is one right-click away, so the player leaves with Esc
     * when they are done rather than being thrown out mid-sentence.
     *
     * <h2>What is drawn</h2>
     *
     * <p>{@code AvalonGame.summaryLines} — the builder the chat uses, called with this side's copy of the
     * same facts: the seats, the revealed roles, the missions, the winner and the two counters that exist on
     * the client only for this ({@code SYNC_STATS_BASE}). One list, two places to read it.
     */
    private void renderSummary(GuiGraphics guiGraphics) {
        int match = menu.getMatchId();
        if (match != summaryMatch) {
            summaryMatch = match;
            summaryStartMs = -1L;
            summaryLines = null;
        }
        if (menu.getPhase() != AvalonGame.Phase.OVER || closingActUp()) {
            return;
        }
        if (summaryStartMs < 0L) {
            summaryStartMs = Util.getMillis();
        }
        float alpha = smooth((Util.getMillis() - summaryStartMs) / (float) SUMMARY_IN_MS);
        if (alpha <= 0.01f) {
            return;
        }
        if (summaryLines == null) {
            summaryLines = buildSummary();
        }

        // Frame coordinates, inside its own pose: this is part of the table's composition rather than
        // something laid over it, so it scales with the frame like the board does.
        AvalonFrame frame = AvalonFrame.current();
        frame.push(guiGraphics);
        float scale = SUMMARY_SCALE;
        int lineHeight = Math.round((font.lineHeight + 1) * scale);
        int widest = 0;
        for (Component line : summaryLines) {
            widest = Math.max(widest, font.width(line));
        }
        int blockHeight = summaryLines.size() * lineHeight;
        int pad = 6;
        int boxW = Math.round(widest * scale) + pad * 2;
        int boxH = blockHeight + pad * 2;
        int x = (AvalonFrame.WIDTH - boxW) / 2;
        int y = Math.max(4, (AvalonFrame.HEIGHT - boxH) / 2);
        guiGraphics.fill(x, y, x + boxW, y + boxH, withAlpha(SUMMARY_BG, alpha));
        frame(guiGraphics, x, y, boxW, boxH, withAlpha(SUMMARY_BORDER, alpha));

        int textY = y + pad;
        for (Component line : summaryLines) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(x + (boxW - font.width(line) * scale) / 2f, textY, 0f);
            guiGraphics.pose().scale(scale, scale, 1f);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            guiGraphics.drawString(font, line, 0, 0, 0xFFFFFFFF, true);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            guiGraphics.pose().popPose();
            textY += lineHeight;
        }
        frame.pop(guiGraphics);
    }

    /** The report for this table, built off the mirror — see {@link #renderSummary}. */
    private List<Component> buildSummary() {
        List<Component> names = new ArrayList<>();
        List<AvalonRole> revealed = new ArrayList<>();
        for (int seat = 0; seat < ring.length; seat++) {
            AvalonRole role = menu.getRevealedRole(seat);
            CardPlayer player = menu.getGame().playerAt(seat);
            if (role == null || player == null) {
                continue;
            }
            names.add(player.getName());
            revealed.add(role);
        }
        int[] missions = new int[AvalonRole.QUESTS];
        int[] sharp = new int[names.size()];
        int[] clean = new int[names.size()];
        for (int i = 0; i < missions.length; i++) {
            missions[i] = menu.getMission(i);
        }
        for (int seat = 0; seat < names.size(); seat++) {
            sharp[seat] = menu.getSharpNo(seat);
            clean[seat] = menu.getCleanTeams(seat);
        }
        return AvalonGame.summaryLines(names, revealed, missions, sharp, clean, menu.getWinner());
    }

    /** The report's state: the lines, the fade clock and the match they belong to. */
    @Nullable
    private List<Component> summaryLines;
    @Nullable
    private long summaryStartMs = -1L;
    private int summaryMatch = -1;

    /** How long the report takes to fade in. Nothing closes it afterwards; Esc does. */
    private static final long SUMMARY_IN_MS = 1500;

    /** Small enough that a ten-seat report — winner, roles, quests and two rankings — fits one screen. */
    private static final float SUMMARY_SCALE = 0.8f;

    private static final int SUMMARY_BG = 0xC0101014;
    private static final int SUMMARY_BORDER = 0x80FFFFFF;

    /** {@code argb} with its alpha scaled by {@code alpha}, for the fade. */
    private static int withAlpha(int argb, float alpha) {
        return (Math.round(((argb >>> 24) & 0xFF) * alpha) << 24) | (argb & 0xFFFFFF);
    }
"""
assert s.count(old_close) == 1, 'close block'
s = s.replace(old_close, new_close)

# the call site
s = s.replace("        closeWhenFinished();", "        renderSummary(guiGraphics);")
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('screen ok')
