package dev.myavalon.game;

import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.card.Card;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * What a {@link AvalonBot} actually thinks about.
 *
 * <h2>Where this sits</h2>
 *
 * <p>Charta drives a bot for us: {@code AutoPlayer.tick} waits a beat on the bot's turn and then asks
 * the game for {@code getBestPlay}, and {@link AvalonGame#getBestPlay} forwards that straight here.
 * So there is no second turn engine and no scheduler in this class — it is four pure decisions, each
 * a function of the board and the seat that is being asked:
 *
 * <ul>
 *   <li>{@link #chooseTeam} — the leader's proposal, as a seat mask.</li>
 *   <li>{@link #chooseVote} — a yes / no card off the voter's own hand.</li>
 *   <li>{@link #chooseQuest} — a success / fail card off a quest member's own hand.</li>
 *   <li>{@link #chooseTarget} — the assassin's pick, as a seat.</li>
 * </ul>
 *
 * <h2>What each strength buys</h2>
 *
 * <p>{@link AvalonStrength} is read off the bot and only ever widens what is looked at:
 * <ul>
 *   <li>{@link AvalonStrength#EASY} mostly rolls dice — the table still finishes, it just plays like a
 *       distracted human.</li>
 *   <li>{@link AvalonStrength#NORMAL} tracks which quests were sabotaged and who was on them, so it
 *       avoids putting a suspect back on a team and an evil seat fails a quest when it is safe.</li>
 *   <li>{@link AvalonStrength#HARD} additionally uses the role knowledge a real seat would have —
 *       Merlin avoids known evil, Percival protects the Merlin/Morgana pair — and an evil seat holds
 *       its sabotage back until more than one fail is needed.</li>
 * </ul>
 *
 * <h2>Why a bot is allowed to know more than a human</h2>
 *
 * <p>Team composition and quest decisions read {@code roleAt} (the truth) rather than
 * {@link AvalonGame#knownMask} (what the seat has been told). Using the truth keeps the bots
 * competent without inventing a belief model, and the decisions they feed are exactly the ones where
 * the difference is invisible in play: whether <em>this</em> evil seat should sabotage, and whether a
 * target would make a plausible Merlin. The two places where knowledge is genuinely public — who
 * failed a quest ({@code missionOf}) and who was on it ({@code wasOnQuest}) — use the real, shared
 * history instead.
 */
public final class AvalonBots {

    private static final Random RANDOM = new Random();

    private AvalonBots() {
    }

    // ---------------------------------------------------------------------------------------------
    // Team proposal
    // ---------------------------------------------------------------------------------------------

    /**
     * The leader's whole proposal: the quest it is for (when the table plays the designated rule) and the
     * team.
     *
     * <p>The quest rides in the high half of the answer's slot, because a proposal answer has one int and
     * the team mask already owns its low ten bits. {@code quest + 1} rather than {@code quest} so that a
     * plain human confirmation -- high half zero -- means "keep the choice I already clicked" instead of
     * "quest one".
     */
    public static GamePlay chooseProposal(AvalonGame game, int leader) {
        int quest = game.designatedQuests() ? chooseQuestSlot(game, leader) : -1;
        int mask = chooseTeam(game, leader, quest);
        return new GamePlay(List.of(), mask | ((quest + 1) << 16));
    }

    /**
     * Which slot a bot leader names.
     *
     * <p>Good takes the available slots in board order: the earlier quests are the ones every table has
     * to get through anyway, and the fifth is only offered once two have succeeded. Evil has one reason to
     * jump ahead -- the fourth quest needs two fail cards on a table of seven or more, which is an
     * advantage exactly when evil can put two of its own seats on that team.
     */
    private static int chooseQuestSlot(AvalonGame game, int leader) {
        int open = game.openQuestMask();
        if (open == 0) {
            return -1;
        }
        AvalonRole role = game.roleAt(leader);
        if (role != null && role.isEvil() && (open & (1 << 3)) != 0
                && game.teamSizeFor(3) >= 2 && evilSeats(game) >= 2) {
            return 3;
        }
        for (int i = 0; i < AvalonRole.QUESTS; i++) {
            if ((open & (1 << i)) != 0) {
                return i;
            }
        }
        return -1;
    }

    /** The mask {@code leader} proposes, exactly the chosen quest's team size wide. */
    private static int chooseTeam(AvalonGame game, int leader, int quest) {
        int seats = game.seatCount();
        int size = Math.min(quest < 0 ? game.teamSize() : game.teamSizeFor(quest), seats);
        AvalonRole role = game.roleAt(leader);
        if (size <= 0 || role == null) {
            return 0;
        }
        int[] score = new int[seats];
        for (int seat = 0; seat < seats; seat++) {
            score[seat] = teamScore(game, leader, seat, role);
        }
        // Greedy top-n by score; ties fall back to the low seat, which keeps a bot's proposals stable
        // across the frames of one turn instead of flickering between equivalent teams.
        int mask = 0;
        for (int picked = 0; picked < size; picked++) {
            int best = -1;
            for (int seat = 0; seat < seats; seat++) {
                if ((mask & (1 << seat)) != 0) {
                    continue;
                }
                if (best < 0 || score[seat] > score[best]) {
                    best = seat;
                }
            }
            if (best < 0) {
                break;
            }
            mask |= 1 << best;
        }
        return mask;
    }

    private static int teamScore(AvalonGame game, int leader, int seat, AvalonRole leaderRole) {
        if (game.strength() == AvalonStrength.EASY) {
            return (seat == leader ? 10 : 0) + RANDOM.nextInt(5);
        }
        int score = seat == leader ? 12 : 0;
        int suspicion = suspicionOf(game, seat);
        if (leaderRole.isEvil()) {
            // Evil wants to be on the quest, and wants at least one ally there so a sabotage is
            // available; a clean-looking team is still worth having, so suspicion is a mild penalty.
            if (seat != leader && isEvil(game, seat)) {
                score += game.strength() == AvalonStrength.HARD ? 8 : 5;
            }
            score -= suspicion;
        } else {
            int known = game.knownMask(leader);
            if (leaderRole == AvalonRole.MERLIN && (known & (1 << seat)) != 0) {
                // Merlin is the only seat that sees evil, so it simply keeps them off.
                score -= 5;
            } else if (leaderRole == AvalonRole.PERCIVAL && (known & (1 << seat)) != 0) {
                // Percival sees the Merlin/Morgana pair; either is a decent bet.
                score += 5;
            }
            score -= suspicion * 2;
        }
        return score + RANDOM.nextInt(3);
    }

    // ---------------------------------------------------------------------------------------------
    // Votes and quest cards
    // ---------------------------------------------------------------------------------------------

    /** A yes or no card from {@code voter}'s hand, or {@code null} if it holds neither. */
    @Nullable
    public static GamePlay chooseVote(AvalonGame game, int voter) {
        AvalonRole role = game.roleAt(voter);
        if (role == null) {
            return null;
        }
        boolean yes = decideVote(game, voter, role);
        Card card = findCard(game, voter, c -> AvalonCards.isVote(c) && AvalonCards.isYes(c) == yes);
        if (card == null) {
            card = findCard(game, voter, AvalonCards::isVote);
        }
        return card == null ? null : new GamePlay(List.of(card), 0);
    }

    private static boolean decideVote(AvalonGame game, int voter, AvalonRole role) {
        if (game.strength() == AvalonStrength.EASY) {
            return RANDOM.nextInt(4) != 0;
        }
        int team = game.getTeamMask();
        boolean onTeam = (team & (1 << voter)) != 0;
        if (role.isEvil()) {
            boolean allyOnTeam = false;
            boolean selfOnTeam = onTeam;
            for (int seat = 0; seat < game.seatCount(); seat++) {
                if ((team & (1 << seat)) != 0 && seat != voter && isEvil(game, seat)) {
                    allyOnTeam = true;
                }
            }
            // A team with no evil on it cannot be sabotaged, so reject it while there is still room to
            // ask for another — unless the reject counter is close to handing the game away.
            if (!selfOnTeam && !allyOnTeam && game.getRejects() < 3) {
                return false;
            }
            return RANDOM.nextInt(6) != 0;
        }
        int suspicion = 0;
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if ((team & (1 << seat)) != 0) {
                suspicion += suspicionOf(game, seat);
            }
        }
        if (role == AvalonRole.MERLIN && (team & game.knownMask(voter)) != 0) {
            // A known-evil seat on the team is the strongest reason a Merlin has to say no.
            suspicion += 6;
        }
        if (suspicion == 0) {
            return true;
        }
        return game.getRejects() >= 4 || RANDOM.nextInt(8) != 0;
    }

    /** A success or fail card from {@code member}'s hand, or {@code null} if it holds neither. */
    @Nullable
    public static GamePlay chooseQuest(AvalonGame game, int member) {
        AvalonRole role = game.roleAt(member);
        if (role == null) {
            return null;
        }
        boolean fail = decideFail(game, member, role);
        Card card = findCard(game, member, c -> AvalonCards.isQuest(c) && AvalonCards.isSuccess(c) != fail);
        if (card == null) {
            card = findCard(game, member, AvalonCards::isQuest);
        }
        return card == null ? null : new GamePlay(List.of(card), 0);
    }

    private static boolean decideFail(AvalonGame game, int member, AvalonRole role) {
        if (!role.isEvil()) {
            return false;
        }
        if (game.strength() == AvalonStrength.EASY) {
            return true;
        }
        int needed = game.requiredFails();
        int evilOnTeam = game.teamEvilCount();
        if (evilOnTeam <= needed) {
            // Every evil has to fail, or the quest might pass anyway.
            return true;
        }
        if (game.strength() == AvalonStrength.NORMAL) {
            // One fail is enough and there are spare evil seats: the lowest-seated evil takes the blame
            // so the others can look clean on later quests.
            return member == firstEvilOnTeam(game);
        }
        // HARD holds the sabotage back on an early quest when evil is already ahead, so a late fail
        // cannot be anticipated from the first two quests.
        int failsSoFar = game.getMissionCount(2);
        if (failsSoFar >= 2 && game.resolvedQuests() < AvalonRole.QUESTS - 1) {
            return false;
        }
        return member == firstEvilOnTeam(game);
    }

    private static int firstEvilOnTeam(AvalonGame game) {
        int team = game.getTeamMask();
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if ((team & (1 << seat)) != 0 && isEvil(game, seat)) {
                return seat;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Assassination
    // ---------------------------------------------------------------------------------------------

    /** The seat the assassin stabs: the non-evil seat that reads most like a Merlin. */
    public static int chooseTarget(AvalonGame game, int assassin) {
        int seats = game.seatCount();
        if (seats <= 1) {
            return assassin;
        }
        if (game.strength() == AvalonStrength.EASY) {
            int pick = RANDOM.nextInt(seats - 1);
            return pick >= assassin ? pick + 1 : pick;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int seat = 0; seat < seats; seat++) {
            if (seat == assassin || isEvil(game, seat)) {
                // The assassin knows its own side; Oberon is the one it might get wrong, and Oberon is
                // never Merlin either way.
                continue;
            }
            // Merlin steers clear of sabotaged quests and turns up on the successful ones — that is
            // exactly the pattern this counts.
            int score = 0;
            // Every resolved quest, not a prefix: the designated rule finishes slots out of order.
            for (int quest = 0; quest < AvalonRole.QUESTS; quest++) {
                if (game.missionOf(quest) == 0 || !game.wasOnQuest(quest, seat)) {
                    continue;
                }
                score += game.missionOf(quest) == 1 ? 2 : -3;
            }
            score += RANDOM.nextInt(3);
            if (score > bestScore) {
                bestScore = score;
                best = seat;
            }
        }
        return best < 0 ? (assassin + 1) % seats : best;
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @Nullable
    private static Card findCard(AvalonGame game, int seat, Predicate<Card> filter) {
        CardPlayer player = game.playerAt(seat);
        if (player == null) {
            return null;
        }
        for (Card card : game.getPlayerHand(player).getCards()) {
            if (filter.test(card)) {
                return card;
            }
        }
        return null;
    }

    private static boolean isEvil(AvalonGame game, int seat) {
        AvalonRole role = game.roleAt(seat);
        return role != null && role.isEvil();
    }

    /** How many sabotaged quests {@code seat} was part of — the table's only hard evidence. */
    private static int suspicionOf(AvalonGame game, int seat) {
        int suspicion = 0;
        for (int quest = 0; quest < AvalonRole.QUESTS; quest++) {
            if (game.missionOf(quest) == 2 && game.wasOnQuest(quest, seat)) {
                suspicion++;
            }
        }
        return suspicion;
    }

    /** How many evil seats the table was dealt, for the fourth-quest decision. */
    private static int evilSeats(AvalonGame game) {
        int count = 0;
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if (isEvil(game, seat)) {
                count++;
            }
        }
        return count;
    }

}
