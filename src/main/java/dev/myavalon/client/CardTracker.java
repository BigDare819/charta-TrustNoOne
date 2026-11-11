package dev.myavalon.client;

import dev.myavalon.game.AvalonGame;
import dev.myavalon.game.AvalonMenu;
import dev.myavalon.game.AvalonRole;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact card tracker: one block per quest round, one line per event worth remembering, kept on
 * the client from the public mirror.
 *
 * <p>This is the model behind the top-right tracker screen (the button Charta's table puts there). It
 * is not a play log: a row of "somebody played a card" is unreadable after five minutes, so only the
 * things a player actually reconstructs the game from are kept — who holds the Angel and the King's
 * Sword, the Angel's check, each vote's leader/team/outcome and no-voters, the sword's flip, and the
 * quest's result with its sabotage count. Every fact comes off the same data slots the table already
 * reads; nothing extra is recorded server-side.
 *
 * <h2>Rounds are chronological, not keyed on the quest index</h2>
 *
 * <p>The previous model filed events into a block looked up by the mission index. That is wrong twice
 * over: a designated table may play quest 5 before quest 2, and the Angel window sits <em>between</em>
 * two quests while the mirror still says the old quest number — so an Angel line could be filed into
 * the round that had just ended, and blocks could be created out of order. Here there is exactly one
 * open block; a quest's result closes it, and the next event opens a new one. The block's quest number
 * is filled in when the following proposal names it, so the header never disagrees with the order of
 * the blocks.
 */
public final class CardTracker {

    /** One quest round's block: the yellow header and the events inside it. */
    public static final class Round {
        public final int round;
        /** The mission index, or {@code -1} until the round's proposal names it. */
        public int quest = -1;
        public final List<Component> lines = new ArrayList<>();

        Round(int round) {
            this.round = round;
        }
    }

    private static final List<Round> ROUNDS = new ArrayList<>();
    private static Round open;
    private static int match = -1;
    private static int roundSeq;
    private static int attempts;
    private static int missionsSeen;
    private static int ladyUses = -1;
    private static int ladyHolder = -1;
    private static int angelSeen = -1;
    private static int swordSeen = -1;
    private static int voteLeader = -1;
    private static int voteTeam;
    private static int voteSword = -1;
    private static int voteNo;
    private static int aimSeen = -1;
    private static AvalonGame.Phase phase;

    /** The tracker's palette: the brackets in the design note name the colour, not the text. */
    public static final int GOLD = 0xFFFFC75F;
    public static final int BLUE = 0xFF6FA8FF;
    public static final int RED = 0xFFFF6B6B;
    public static final int GREEN = 0xFF7BD98C;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DIM = 0xFF9A9A9A;

    private CardTracker() {
    }

    public static List<Round> rounds() {
        return ROUNDS;
    }

    /** Writes the current mirror state into the blocks; call it once per frame while a table is open. */
    public static void update(AvalonMenu menu) {
        int currentMatch = menu.getMatchId();
        if (currentMatch <= 0) {
            return;
        }
        if (currentMatch != match) {
            match = currentMatch;
            ROUNDS.clear();
            open = null;
            roundSeq = 0;
            attempts = 0;
            missionsSeen = 0;
            ladyUses = -1;
            ladyHolder = menu.getLadyHolder();
            angelSeen = -1;
            phase = null;
            aimSeen = -1;
            voteLeader = -1;
            voteTeam = 0;
            voteSword = -1;
            voteNo = 0;
        }
        AvalonGame.Phase current = menu.getPhase();

        // How many missions have already resolved: what classic play is about to play next.
        int resolved = 0;
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if (menu.getMission(i) != 0) {
                resolved++;
            }
        }

        if (open != null && open.quest < 0) {
            if (menu.isDesignatedQuests()) {
                int picked = menu.getQuestNumber();
                if (picked >= 0) {
                    open.quest = picked;
                }
            } else {
                open.quest = Math.min(resolved, AvalonRole.QUESTS - 1);
            }
        }

        // The Angel block is gated on the rule itself: a mirror that carries a stale holder for one
        // frame after a new deal must never plant an "Angel held by" line in a game without the rule.
        if (menu.isLadyOfTheLake()) {
            int uses = menu.getLadyUses();
            boolean usedNow = false;
            if (ladyUses < 0 || uses < ladyUses) {
                ladyUses = uses;
            } else if (uses > ladyUses) {
                ladyUses = uses;
                usedNow = true;
                int examiner = ladyHolder;
                int target = menu.getLadyLastTarget();
                if (examiner >= 0 && target >= 0) {
                    open().lines.add(angel(menu, examiner, target));
                }
            }
            // Who holds the Angel: the opening assignment and a hand-over that was not itself a check
            // (a check already names both seats in its own line).
            int ladyNow = menu.getLadyHolder();
            if (ladyNow >= 0) {
                if (ladyNow != angelSeen) {
                    if (!usedNow) {
                        open().lines.add(holderLine(menu, "tracker.myavalon.angel_holder", ladyNow));
                    }
                    angelSeen = ladyNow;
                }
            } else {
                angelSeen = -1;
            }
            ladyHolder = ladyNow;
        } else {
            ladyUses = menu.getLadyUses();
            angelSeen = -1;
            ladyHolder = menu.getLadyHolder();
        }

        if (current == AvalonGame.Phase.VOTE) {
            voteLeader = menu.getLeaderSeat();
            voteTeam = menu.getPendingMask();
            voteSword = menu.getSwordHolder();
            voteNo = menu.getNoVoterMask();
            Round round = open();
            if (round.quest < 0 && menu.getQuestNumber() >= 0) {
                round.quest = menu.getQuestNumber();
            }
        }
        if (phase == AvalonGame.Phase.VOTE && current != AvalonGame.Phase.VOTE) {
            boolean approved = current == AvalonGame.Phase.QUEST || current == AvalonGame.Phase.VETO;
            attempts++;
            open().lines.add(vote(menu, attempts, voteLeader, voteTeam, voteSword, approved, voteNo));
        }
        phase = current;

        // Whom the sword was pointed at, once the holder commits. The wording stops at the pointing:
        // the tracker does not say what happened to the card.
        int aim = menu.getSwordFlipTarget();
        if (aim != aimSeen) {
            if (aim >= 0) {
                open().lines.add(aimLine(menu, menu.getSwordHolder(), aim));
            }
            aimSeen = aim;
        }

        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            int mission = menu.getMission(i);
            boolean seen = (missionsSeen & (1 << i)) != 0;
            if (mission != 0 && !seen) {
                missionsSeen |= 1 << i;
                Round round = open();
                round.lines.add(result(mission == 1, menu.getQuestFails()));
                // The result ends the round: the next event belongs to the next block.
                round.quest = round.quest >= 0 ? round.quest : i;
                open = null;
                attempts = 0;
            }
        }
    }

    /** The round being written, opened on demand and always the newest. */
    private static Round open() {
        if (open == null) {
            roundSeq++;
            open = new Round(roundSeq);
            ROUNDS.add(open);
        }
        return open;
    }

    /** A name in bold white -- the tracker's player IDs. */
    private static Component name(AvalonMenu menu, int seat) {
        return menu.getGame().playerAt(seat).getName().copy()
                .withStyle(style -> style.withColor(WHITE).withBold(true));
    }

    /** A name in bold gold: the Angel's holder and the King's Sword's bearer. */
    private static Component goldName(AvalonMenu menu, int seat) {
        return menu.getGame().playerAt(seat).getName().copy()
                .withStyle(style -> style.withColor(GOLD).withBold(true));
    }

    private static Component holderLine(AvalonMenu menu, String key, int seat) {
        return Component.translatable(key, goldName(menu, seat));
    }

    private static Component angel(AvalonMenu menu, int holder, int target) {
        return Component.translatable("tracker.myavalon.angel",
                goldName(menu, holder), name(menu, target));
    }

    /** The team's names, the bearer's in gold, separated by the text's own comma. */
    private static Component team(AvalonMenu menu, int mask, int sword) {
        MutableComponent names = Component.empty();
        boolean first = true;
        for (int seat = 0; seat < menu.getGame().getPlayers().size(); seat++) {
            if ((mask & (1 << seat)) == 0) {
                continue;
            }
            if (!first) {
                names.append(Component.literal("、"));
            }
            first = false;
            names.append(seat == sword ? goldName(menu, seat) : name(menu, seat));
        }
        return names;
    }

    private static Component vote(AvalonMenu menu, int attempt, int leader, int teamMask, int sword,
                                  boolean approved, int against) {
        MutableComponent line = Component.empty();
        line.append(Component.translatable("tracker.myavalon.attempt", attempt)
                .withStyle(style -> style.withColor(GOLD)));
        line.append(separator());
        line.append(Component.translatable("tracker.myavalon.leader", name(menu, leader)));
        line.append(separator());
        line.append(Component.translatable("tracker.myavalon.picked", team(menu, teamMask, sword)));
        if (sword >= 0) {
            line.append(Component.translatable("tracker.myavalon.sword_bearer", goldName(menu, sword)));
        }
        line.append(separator());
        line.append(Component.translatable(approved
                        ? "tracker.myavalon.approved" : "tracker.myavalon.rejected")
                .withStyle(style -> style.withColor(approved ? GREEN : RED)));
        if (against != 0) {
            line.append(separator());
            line.append(Component.translatable("tracker.myavalon.against", team(menu, against, -1))
                    .withStyle(style -> style.withColor(RED)));
        }
        return line;
    }

    private static Component aimLine(AvalonMenu menu, int holder, int target) {
        return Component.translatable("tracker.myavalon.sword_aim",
                holder < 0 ? Component.empty() : goldName(menu, holder), name(menu, target));
    }

    private static Component result(boolean success, int fails) {
        MutableComponent line = Component.translatable(success
                        ? "tracker.myavalon.quest_success" : "tracker.myavalon.quest_fail")
                .withStyle(style -> style.withColor(success ? BLUE : RED));
        line.append(separator());
        line.append(Component.translatable("tracker.myavalon.sabotage", fails)
                .withStyle(style -> style.withColor(RED)));
        return line;
    }

    private static Component separator() {
        return Component.literal(" · ").withStyle(style -> style.withColor(DIM));
    }
}
