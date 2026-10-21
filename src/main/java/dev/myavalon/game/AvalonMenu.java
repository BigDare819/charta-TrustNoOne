package dev.myavalon.game;

import dev.myavalon.MyAvalonMod;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.common.game.api.GameSlot;
import dev.lucaargolo.charta.common.game.api.game.GameType;
import dev.lucaargolo.charta.common.menu.AbstractCardMenu;
import dev.lucaargolo.charta.common.menu.CardSlot;
import dev.lucaargolo.charta.common.menu.HandSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Menu for Avalon. The base class synchronises every card slot, the carried card and the turn, so all
 * this has to do is declare the slots in a fixed order and mirror the phase state.
 *
 * <h2>Slots are positional</h2>
 *
 * <p>Slot {@code 0} is the pile and is the drop target {@code PlaySlot} validates a play against;
 * slots {@code 1 .. n} are the seats in the local viewer's own ring order, {@code 1} being their own
 * hand. The layout writes the coordinates, but the <em>order</em> is the contract, so it is produced by
 * {@link #seats()} in one place and both the constructor and the layout walk it.
 *
 * <h2>Mirroring has to be per-viewer</h2>
 *
 * <p>Everything secret about the game — your role, who you know — depends on which seat is looking, so
 * {@link #gameData}'s server side reads {@code game.syncValue(index, getCardPlayer())} rather than the
 * viewer-less overload. Charta ships the container-data channel, and a menu exists per player, so that
 * is enough to keep two seats' private views apart without a packet of our own.
 */
public class AvalonMenu extends AbstractCardMenu<AvalonGame, AvalonMenu> {

    /** Pile slot coordinates. Only placeholders: {@code AvalonLayout} overwrites both every frame. */
    public static final int PILE_X = 0;
    public static final int PILE_Y = 0;

    /** Values pushed by the server, indexed exactly like {@link AvalonGame#syncValue}. */
    private final int[] mirror = new int[AvalonGame.SYNC_SIZE];
    private boolean mirrored;

    /**
     * Turn/seat mirror.
     *
     * <p>{@code AbstractCardMenu} already ships a slot for the current player, but its getter is
     * {@code players.indexOf(currentPlayer)} — and {@link AvalonBot#equals} makes {@code indexOf}
     * answer with the first real seat for every bot, so a bot's turn would be drawn as the local
     * player's. Our own slot wins because the client applies data slots in order.
     */
    private final ContainerData turnData = new ContainerData() {
        @Override
        public int get(int index) {
            return game.getSeatOfCurrentPlayer();
        }

        @Override
        public void set(int index, int value) {
            game.setCurrentPlayer(value);
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    /** Phase, quest, votes, missions and the viewer's own role — everything the screen draws. */
    private final ContainerData gameData = new ContainerData() {
        @Override
        public int get(int index) {
            return mirrored ? mirror[index] : game.syncValue(index, getCardPlayer());
        }

        @Override
        public void set(int index, int value) {
            mirrored = true;
            if (index >= 0 && index < mirror.length) {
                mirror[index] = value;
            }
        }

        @Override
        public int getCount() {
            return AvalonGame.SYNC_SIZE;
        }
    };

    public AvalonMenu(int containerId, Inventory inventory, AbstractCardMenu.Definition definition) {
        super(MyAvalonMod.AVALON_MENU, containerId, inventory, definition);

        // Slot 0: the pile every vote and quest card lands on. PREVIEW so the screen can place it with
        // an absolute y and its own fan widget stays out of the way (the pile is drawn by hand).
        addCardSlot(new CardSlot<>(this.game, game -> game.getSlot(0), PILE_X, PILE_Y, CardSlot.Type.PREVIEW));

        // Slots 1..n: the seats, local first, then clockwise.
        for (int seat : seats()) {
            addCardSlot(seatSlot(seat));
        }

        addDataSlots(turnData);
        addDataSlots(gameData);
    }

    /**
     * Seats in the local viewer's ring order: their own chair first, then clockwise.
     *
     * <p>Slot {@code 1 + i} belongs to {@code seats()[i]}, which is what lets the layout put the local
     * fan at the bottom and still walk the same list for everybody else.
     */
    public int[] seats() {
        return this.game.viewRing(getLocalSeat());
    }

    /**
     * The local player's hand as a real fan, everybody else's as a face-down one.
     *
     * <p>A {@link HandSlot} is used for the local seat because it is the only slot type whose
     * {@code postUpdate} keeps the other viewers' copies in step, and because its getter is the real
     * hand rather than the censored one.
     *
     * <p>Everybody else gets {@code PREVIEW}, which is what Charta's own Crazy Eights uses for the hands
     * across the top of its table. The type is doing real work rather than being a label: a PREVIEW slot
     * is "small", and {@code CardSlotWidget} branches on that to draw its cards at a third scale in a
     * tight overlapping row instead of fanning full-size cards the way {@code HORIZONTAL} does. Naming
     * the same type Charta names is therefore the whole of "look like Crazy Eights" — the drawing code is
     * Charta's, not a copy of it.
     */
    private CardSlot<AvalonGame, AvalonMenu> seatSlot(int seat) {
        if (seat == getLocalSeat()) {
            return new HandSlot<>(
                    this.game,
                    game -> true,
                    this.getCardPlayer(),
                    PILE_X,
                    PILE_Y,
                    CardSlot.Type.HORIZONTAL
            );
        }
        CardPlayer other = this.game.playerAt(seat);
        CardPlayer viewer = this.getCardPlayer();
        return new CardSlot<>(this.game, game -> game.getCensoredHand(viewer, other),
                PILE_X, PILE_Y, CardSlot.Type.PREVIEW);
    }

    /** Slot index of the seat at ring position {@code handOrderIndex}; slot 0 is the pile. */
    public int handSlotIndex(int handOrderIndex) {
        return 1 + handOrderIndex;
    }

    /** Seat of the hand in ring position {@code handOrderIndex}, or {@code -1}. */
    public int seatOfHand(int handOrderIndex) {
        int[] ring = seats();
        return handOrderIndex >= 0 && handOrderIndex < ring.length ? ring[handOrderIndex] : -1;
    }

    public int handCount() {
        return this.game.getPlayers().size();
    }

    /** The panel is the whole frame, so a slot's stored coordinate is already a screen coordinate. */
    public int panelLeft(int screenWidth) {
        return 0;
    }

    public int panelTop(int screenHeight) {
        return 0;
    }

    public int getCurrentSeat() {
        return turnData.get(0);
    }

    public int getLocalSeat() {
        return Math.max(0, this.game.getSeat(this.getCardPlayer()));
    }

    public GameSlot getPileCards() {
        return this.game.getPile();
    }

    // ---------------------------------------------------------------------------------------------
    // Mirrored state
    // ---------------------------------------------------------------------------------------------

    public AvalonGame.Phase getPhase() {
        AvalonGame.Phase[] phases = AvalonGame.Phase.values();
        return phases[Math.max(0, Math.min(phases.length - 1, gameData.get(AvalonGame.SYNC_PHASE)))];
    }

    public int getQuestNumber() {
        return gameData.get(AvalonGame.SYNC_QUEST);
    }

    /** {@code true} while the table plays the designated-quest optional rule. */
    public boolean isDesignatedQuests() {
        return gameData.get(AvalonGame.SYNC_DESIGNATED) == 1;
    }

    /** The quests the leader may name right now, as a bitmask. */
    public int getOpenQuestMask() {
        return gameData.get(AvalonGame.SYNC_OPEN_QUESTS);
    }

    /**
     * Four bits per {@link AvalonRole} ordinal: how many of that role this deal put on the table.
     *
     * <p>Two data slots, reassembled here. Both are masked because a container data value travels as a
     * signed short, so the upper half would otherwise come through sign-extended.
     */
    public int getRoleCensus() {
        int low = gameData.get(AvalonGame.SYNC_CENSUS) & 0xFFFF;
        int high = gameData.get(AvalonGame.SYNC_CENSUS_HIGH) & 0xFFFF;
        return low | (high << 16);
    }

    public int getLeaderSeat() {
        return gameData.get(AvalonGame.SYNC_LEADER);
    }

    public int getRejects() {
        return gameData.get(AvalonGame.SYNC_REJECTS);
    }

    public int getTeamMask() {
        return gameData.get(AvalonGame.SYNC_TEAM);
    }

    public int getPendingMask() {
        return gameData.get(AvalonGame.SYNC_PENDING);
    }

    public int getVotedMask() {
        return gameData.get(AvalonGame.SYNC_VOTED);
    }

    public int getYesVotes() {
        return gameData.get(AvalonGame.SYNC_YES);
    }

    public int getNoVotes() {
        return gameData.get(AvalonGame.SYNC_NO);
    }

    public int getQuestMask() {
        return gameData.get(AvalonGame.SYNC_QUESTED);
    }

    public int getQuestFails() {
        return gameData.get(AvalonGame.SYNC_FAILS);
    }

    public int getTeamSize() {
        return gameData.get(AvalonGame.SYNC_TEAM_SIZE);
    }

    /** {@code true} when the local player is the assassin and it is their call. */
    public boolean isLocalAssassin() {
        return gameData.get(AvalonGame.SYNC_ASSA_SELF) == 1;
    }

    public int getPendingTarget() {
        return gameData.get(AvalonGame.SYNC_TARGET);
    }

    /** {@code 0} undecided, {@code 1} good, {@code 2} evil. */
    public int getWinner() {
        return gameData.get(AvalonGame.SYNC_WINNER);
    }

    /** Seats the local viewer knows the faction of. */
    public int getKnownMask() {
        return gameData.get(AvalonGame.SYNC_KNOWN);
    }

    /**
     * Id of the deal in progress, bumped by the server on every fresh one.
     *
     * <p>What the identity curtain keys off instead of the menu or the game object: both of those are
     * rebuilt whenever the player closes and reopens the table, so neither can answer "have I already been
     * shown my card this game?". See {@code AvalonGame.SYNC_MATCH}.
     */
    public int getMatchId() {
        return gameData.get(AvalonGame.SYNC_MATCH);
    }

    /** {@code true} once the server has finished holding the table and is about to sweep it. */
    public boolean isOver() {
        return gameData.get(AvalonGame.SYNC_OVER) == 1;
    }

    /** How many times {@code seat} voted a team carrying evil down -- see {@code AvalonGame.sharpNo}. */
    public int getSharpNo(int seat) {
        return (gameData.get(AvalonGame.SYNC_STATS_BASE + seat) >>> 8) & 0xFF;
    }

    /** How many teams with no evil on them {@code seat} proposed -- see {@code AvalonGame.cleanTeams}. */
    public int getCleanTeams(int seat) {
        return gameData.get(AvalonGame.SYNC_STATS_BASE + seat) & 0xFF;
    }

    /** Seats that have answered "no" in the running vote, as a bitmask. */
    public int getNoVoterMask() {
        return gameData.get(AvalonGame.SYNC_NO_VOTERS);
    }

    /** The local viewer's own role, or {@code null} before the deal. */
    @Nullable
    public AvalonRole getLocalRole() {
        int ordinal = gameData.get(AvalonGame.SYNC_ROLE);
        return ordinal <= 0 ? null : AvalonRole.values()[ordinal - 1];
    }

    /** {@code 0} pending, {@code 1} succeeded, {@code 2} sabotaged. */
    public int getMission(int index) {
        return gameData.get(AvalonGame.SYNC_MISSION_BASE + index);
    }

    /** The role revealed on {@code seat} once the game is over, or {@code null} while it is secret. */
    @Nullable
    public AvalonRole getRevealedRole(int seat) {
        int ordinal = gameData.get(AvalonGame.SYNC_ROLE_BASE + seat);
        return ordinal <= 0 ? null : AvalonRole.values()[ordinal - 1];
    }

    public boolean isGameReadyAndRunning() {
        return isGameReady() && !this.game.isGameOver();
    }

    /** {@code true} when the local seat is the one that has to act right now. */
    public boolean isLocalTurn() {
        return getCurrentSeat() == getLocalSeat();
    }

    /**
     * {@code true} while the local seat still owes the table something.
     *
     * <p>Everything that reads "may I click now?" wants this rather than {@link #isLocalTurn}: a quest card
     * is still handed around, so there the two agree, but a vote is taken all at once — the turn no longer
     * names one seat, and what makes the local player an actor is simply that their own answer is not in
     * yet. Reading the mirrored vote mask is what keeps every seat's screen telling its own owner the
     * truth, which is the same reason the role and the known-seat reads go through the menu.
     */
    public boolean isLocalPending() {
        if (getPhase() == AvalonGame.Phase.VOTE) {
            return (getVotedMask() & (1 << getLocalSeat())) == 0;
        }
        if (getPhase() == AvalonGame.Phase.QUEST) {
            // The quest is not a turn any more either: what makes this seat an actor is that it is on the
            // team and its card is not on the pile yet. Both masks are the mirrored ones, so a seat that
            // was not picked is never told it owes a card.
            int local = getLocalSeat();
            return (getTeamMask() & (1 << local)) != 0 && (getQuestMask() & (1 << local)) == 0;
        }
        return isLocalTurn();
    }

    @Override
    public GameType<AvalonGame, AvalonMenu> getGameType() {
        return MyAvalonMod.AVALON;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * Whether the table is still worth keeping open for {@code player}.
     *
     * <p>Deliberately <em>not</em> "and the game is still running", which is what Charta's own games use. A
     * finished game is exactly when this table has the most to show — the winner, the roster, and every
     * seat's card turned face-up — and the check is polled by the server, which closes the container and
     * with it the client's screen the moment it answers false. Ending the game therefore used to slam the
     * window shut on the reveal that was being set up for it.
     *
     * <p>The player closes it themselves, and a rematch on the same table reuses it either way.
     */
    @Override
    public boolean stillValid(@NotNull Player player) {
        return this.game != null && this.cardPlayer != null;
    }

}
