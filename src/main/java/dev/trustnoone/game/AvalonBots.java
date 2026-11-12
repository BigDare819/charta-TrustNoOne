package dev.trustnoone.game;

import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GamePlay;
import dev.lucaargolo.charta.common.game.api.card.Card;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
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
        if (role != null && game.isEvilNow(leader) && (open & (1 << 3)) != 0
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
        if (game.isEvilNow(leader)) {
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
        if (game.isEvilNow(voter)) {
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
        if (!game.isEvilNow(member)) {
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
    // Plot cards and the Lady of the Lake
    // ---------------------------------------------------------------------------------------------

    /**
     * Who the leader hands its drawn cards to.
     *
     * <p>Distinct seats, because the rule forbids piling the batch onto one player; the order is
     * shuffled so a bot's hand-outs are not always read off the ring's low seats.
     */
    public static List<Integer> choosePlotRecipients(AvalonGame game, int leader) {
        List<Integer> seats = new ArrayList<>();
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if (seat != leader) {
                seats.add(seat);
            }
        }
        Collections.shuffle(seats, RANDOM);
        return seats;
    }

    /**
     * One plot-card play from {@code seat}, encoded for the action payload, or {@code -1}.
     *
     * <p>Bots do not model the card game deeply: a takeover is worth a try now and then, and an
     * information card is simply spent on a legal target. The value of plot cards is in what the
     * <em>humans</em> do with them; a bot spending one is mostly here so a table of bots does not sit
     * on a hand forever.
     */
    public static int choosePlotUse(AvalonGame game, int seat) {
        List<AvalonPlot> hand = game.plotHand(seat);
        if (hand.isEmpty()) {
            return -1;
        }
        if (hand.contains(AvalonPlot.STRONG_LEADER)
                && game.canUsePlot(seat, AvalonPlot.STRONG_LEADER, -1)
                && RANDOM.nextInt(3) == 0) {
            return AvalonGame.plotUseAction(AvalonPlot.STRONG_LEADER, -1);
        }
        List<AvalonPlot> options = new ArrayList<>(hand);
        Collections.shuffle(options, RANDOM);
        for (AvalonPlot card : options) {
            // Passive (Opinion Maker) and the veto card have no target and are not this path's business.
            if (!card.needsTarget()) {
                continue;
            }
            int target = choosePlotTarget(game, seat, card);
            if (target >= 0) {
                return AvalonGame.plotUseAction(card, target);
            }
        }
        return -1;
    }

    private static int choosePlotTarget(AvalonGame game, int seat, AvalonPlot card) {
        List<Integer> candidates = new ArrayList<>();
        for (int target = 0; target < game.seatCount(); target++) {
            if (game.canUsePlot(seat, card, target)) {
                candidates.add(target);
            }
        }
        return candidates.isEmpty() ? -1 : candidates.get(RANDOM.nextInt(candidates.size()));
    }

    /** Whether a bot holding No Confidence spends it on the approved team. */
    public static boolean chooseVeto(AvalonGame game, int seat) {
        AvalonRole role = game.roleAt(seat);
        if (role == null) {
            return false;
        }
        // An evil seat wants the team sent back; a good seat only vetoes what looks plainly dirty.
        return game.isEvilNow(seat) ? RANDOM.nextInt(3) != 0 : game.teamEvilCount() > 0 && RANDOM.nextInt(4) == 0;
    }

    /**
     * Who the Lady examines: a good holder checks its strongest suspicion, an evil holder checks a
     * seat it believes is good (checking an ally tells it nothing).
     */
    public static int chooseLadyTarget(AvalonGame game, int holder) {
        int mask = game.ladyTargetMask();
        if (mask == 0) {
            return -1;
        }
        AvalonRole role = game.roleAt(holder);
        boolean evil = role != null && game.isEvilNow(holder);
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if ((mask & (1 << seat)) == 0) {
                continue;
            }
            int score = suspensionFor(game, holder, seat, evil) + RANDOM.nextInt(2);
            if (score > bestScore) {
                bestScore = score;
                best = seat;
            }
        }
        return best;
    }

    private static int suspensionFor(AvalonGame game, int holder, int seat, boolean holderEvil) {
        if (holderEvil) {
            // Reading a fellow evil tells the holder nothing it does not already know.
            return game.isEvilNow(seat) ? -10 : 10;
        }
        return suspicionOf(game, seat);
    }

    // ---------------------------------------------------------------------------------------------
    // The Royal Sword
    // ---------------------------------------------------------------------------------------------

    /**
     * Who a bot leader gives the sword to: a team member on its own side if it can, otherwise any.
     *
     * <p>The sword is a public trust, so an evil leader wants it on an ally and a good leader wants it
     * away from one -- the same truth-reading shortcut the rest of this class uses.
     */
    public static int chooseSwordHolder(AvalonGame game, int leader) {
        int mask = game.swordEligibleMask();
        if (mask == 0) {
            return -1;
        }
        AvalonRole leaderRole = game.roleAt(leader);
        boolean evil = leaderRole != null && game.isEvilNow(leader);
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if ((mask & (1 << seat)) == 0) {
                continue;
            }
            int score = RANDOM.nextInt(3);
            if (game.isEvilNow(seat) == evil) {
                score += 10;
            }
            if (score > bestScore) {
                bestScore = score;
                best = seat;
            }
        }
        return best;
    }

    /**
     * Whether a bot holder flips a card, and which one: only when the flip changes the quest's outcome.
     *
     * <p>An evil holder turns a success into the fail that breaks the quest; a good holder turns the fail
     * that would have broken it back into a success. When the quest is already decided either way the
     * sword is worth more kept than spent, so the bot passes. Bots read the cards directly, as the rest of
     * this class does.
     */
    public static int chooseSwordTarget(AvalonGame game, int holder) {
        int mask = game.swordTargetMask();
        if (mask == 0) {
            return -1;
        }
        AvalonRole role = game.roleAt(holder);
        boolean evil = role != null && game.isEvilNow(holder);
        int fails = game.getQuestFails();
        int required = game.requiredFails();
        // The outcome is already settled the holder's way; the sword would only add information.
        if (evil ? fails >= required : fails < required) {
            return -1;
        }
        for (int seat = 0; seat < game.seatCount(); seat++) {
            if ((mask & (1 << seat)) == 0) {
                continue;
            }
            Card card = game.questCardOf(seat);
            if (card == null) {
                continue;
            }
            boolean success = AvalonCards.isSuccess(card);
            if (evil) {
                // A success becomes the fail that sinks the quest.
                if (success && fails + 1 >= required) {
                    return seat;
                }
            } else {
                // A fail becomes a success, but only if that pulls the quest back above the line.
                if (!success && fails - 1 < required) {
                    return seat;
                }
            }
        }
        return -1;
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
        return game.isEvilNow(seat);
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
