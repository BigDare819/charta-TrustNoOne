package dev.myavalon.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The identity setup: one row per role, ticked or not, over a line saying what the table would be.
 *
 * <h2>What it writes</h2>
 *
 * <p>The mask described on {@code AvalonGame.ROLES}, straight back through the option — so the options
 * screen behind it updates its own button the moment anything is toggled, and the game's next deal reads
 * the new set. There is no separate "apply": every click <em>is</em> the edit. {@code 重置} puts the mask
 * back to the value the printed table implies, which is also the only way back to "follow the seat count".
 *
 * <h2>Three roles are locked on</h2>
 *
 * <p>Merlin, the Assassin and the Minion are shown first as genuinely disabled buttons — vanilla's dark
 * grey plate and vanilla's grey label — so the locked block reads as a block before its text is read. The
 * rules lean on all three: with no Merlin there is nothing for the knowing roles to know, with no Assassin
 * the evil side loses the stab it wins most of its games with, and with no Minion the evil half would be
 * short a seat. Everything else only decides who is holding information, is green or red, and can be
 * clicked.
 *
 * <h2>Hover answers with the comment, not the rules text</h2>
 *
 * <p>Each row's tooltip is the same one line its card shows on hover, so a role is described in the same
 * words wherever it is met; the longer reminder lives on the card itself.
 *
 * <h2>The set is sized for the options screen, not for the roster</h2>
 *
 * <p>{@link AvalonGame#tableSize} rather than the players: the seat slider writes its value straight into
 * the option and nothing rebuilds the roster until a game starts, so reading the roster here is how the
 * count line used to sit still while the player moved the slider. A set that does not fill the table is
 * padded there and then — the count line is what says so, before a game is started with it.
 *
 * <h2>Nothing here is computed per frame</h2>
 *
 * <p>The rows, the ticks and the count line are all decided in {@link #init}, which {@code rebuildWidgets}
 * runs again on every toggle, so a click is the only thing that can change any of them. {@code render}
 * therefore only draws: it hands over a component built once instead of re-deriving the set, its split and
 * its two counts sixty times a second.
 */
public class AvalonRolesScreen extends Screen {

    private final Screen parent;
    private final AvalonGame game;
    /**
     * The consumer the options screen handed to the identity option's widget, and the reason it is carried
     * down here at all.
     *
     * <p>{@code GameOption.consumer} is package-private, so the only way to tell that screen that something
     * changed is to keep the callback it passed and call it. It re-checks the options screen's own buttons
     * -- Save in particular -- which is what makes an identity edit worth pressing Save for; the call on
     * {@link #onClose} matters twice over, because returning re-inits that screen and its Init puts Save
     * back to grey.
     */
    @Nullable
    private final Consumer<Integer> onEdit;

    /**
     * The mask the option held when this screen opened.
     *
     * <p>{@code onEdit} is what marks the option edited, and with it the options screen's Save button.
     * It has to be called when the screen <em>closes</em> -- returning re-inits the options screen and
     * that init greys Save again -- but calling it unconditionally lit Save for a visit in which nothing
     * was touched. Comparing against this makes "opened and closed" leave the option clean.
     */
    private final int openedMask;

    private static final int ROW_H = 20;
    private static final int ROW_GAP = 2;
    private static final int COL_W = 220;

    /** How many of an identity will be dealt: grey, so it reads as a figure beside the name, not as one. */
    private static final int DEALT_TEXT = 0xFF9E9E9E;

    /** {@code 本局共 N 人…} for the set as it stands, built once per {@link #init}. */
    private Component countLine = Component.empty();

    /** The identities and their dealt counts, so "what is actually on this table" reads at a glance. */
    private Component lineup = Component.empty();

    /**
     * Why this set cannot be played, or {@code null} when it can. A setup that would deal a good/evil
     * split other than the printed one (or that switched the wolf rule on without the two plain seats it
     * takes over) is refused by the server; saying so here is what turns that refusal into a sentence a
     * player can act on instead of a Play button that simply will not light.
     */
    @Nullable
    private Component unusable;

    /** Top of the button row, kept for the count line, which hangs under it. */
    private int buttonsY;

    public AvalonRolesScreen(@Nullable Screen parent, AvalonGame game, @Nullable Consumer<Integer> onEdit) {
        super(Component.translatable("roles.myavalon.title"));
        this.parent = parent;
        this.game = game;
        this.onEdit = onEdit;
        this.openedMask = game.roleMask();
    }

    @Override
    protected void init() {
        // The table this setup is being chosen for: the chairs that actually have someone in them. The
        // seat slider is only a target -- it is what the roster is rebuilt to, not who is sitting here --
        // so reading it made the screen describe a table nobody was at. A roster that is still empty
        // (the screen opened away from a table) falls back to the slider.
        int seated = game.getPlayers().size();
        int seats = seated > 0
                ? Math.max(AvalonRole.MIN_PLAYERS, Math.min(AvalonRole.MAX_PLAYERS, seated))
                : game.tableSize();
        int mask = AvalonRole.isCustom(game.roleMask())
                ? game.roleMask() : AvalonRole.builtInMask(seats);
        this.countLine = Component.translatable("roles.myavalon.count", seats,
                AvalonRole.goodCount(seats, mask), AvalonRole.evilCount(seats, mask));

        // How many of each identity the set actually deals, counted off the very list {@code dealRoles}
        // uses -- so the numbers are the deal's, not a second opinion: the padding of a short set and the
        // trimming of an over-full one are both visible here, on the row they land on. The optional-rule
        // edits ride along too: Oberon vanishes when the cunning rule is off, and the wolves take their
        // plain seat each, exactly as the deal will build it.
        int[] dealt = AvalonRole.lineup(seats, mask, game.cunningAssassin(), game.lancelotOption());

        // The concrete set, not just a total: which identities, and how many of each the deal makes.
        MutableComponent list = Component.empty();
        boolean first = true;
        for (AvalonRole role : AvalonRole.values()) {
            int count = dealt[role.ordinal()];
            if (count <= 0) {
                continue;
            }
            if (!first) {
                list.append(Component.translatable("reveal.myavalon.census_sep"));
            }
            first = false;
            list.append(Component.translatable("reveal.myavalon.role_count",
                    role.coloredName(), Integer.toString(count)));
        }
        this.lineup = Component.translatable("reveal.myavalon.roles_in_play", list);
        this.unusable = AvalonRole.setupMatches(seats, mask, game.cunningAssassin(), game.lancelotOption())
                ? null
                : Component.translatable("roles.myavalon.invalid",
                AvalonRole.goodCount(seats, mask), AvalonRole.evilCount(seats, mask),
                AvalonRole.goodCount(seats), AvalonRole.evilCount(seats));

        // The wolves are not part of the mask -- the optional rule seats them over one Villager and one
        // Minion -- so they get their own read-only rows at the tail whenever that rule is on. Locked,
        // like Merlin and the Assassin: clicking cannot change them, the rule is what does. Without this
        // the setup never named 善狼/恶狼 at all, and a player turning the rule on had to infer the pair
        // from the villager count quietly dropping by one.
        List<AvalonRole> wolves = new ArrayList<>();
        if (game.lancelotOption()) {
            wolves.add(AvalonRole.LANCELOT_GOOD);
            wolves.add(AvalonRole.LANCELOT_EVIL);
        }
        int rows = AvalonRole.LOCKED.size() + AvalonRole.OPTIONAL.size() + wolves.size();
        int block = rows * (ROW_H + ROW_GAP);
        int top = Math.max(26, (height - block) / 2 - 12);
        int left = width / 2 - COL_W / 2;

        // The two that cannot be switched off first, so the locked block reads as a header for the rest.
        int row = 0;
        for (AvalonRole role : AvalonRole.LOCKED) {
            roleRow(role, row++, left, top, mask, true, dealt[role.ordinal()]);
        }
        for (AvalonRole role : AvalonRole.OPTIONAL) {
            roleRow(role, row++, left, top, mask, false, dealt[role.ordinal()]);
        }
        for (AvalonRole role : wolves) {
            roleRow(role, row++, left, top, mask, true, dealt[role.ordinal()]);
        }

        int bottom = top + block + 6;
        this.buttonsY = bottom;
        addRenderableWidget(Button.builder(Component.translatable("roles.myavalon.reset"), b -> {
            // A stored zero already means the printed table; resetting to it again is not an edit.
            int before = game.roleMask();
            game.setRoleMask(0);
            if (onEdit != null && before != 0) {
                onEdit.accept(0);
            }
            rebuildWidgets();
        }).bounds(width / 2 - COL_W / 2 - 2, bottom, COL_W / 2 - 2, ROW_H).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 + 2, bottom, COL_W / 2 - 2, ROW_H)
                .tooltip(Tooltip.create(Component.translatable("roles.myavalon.hint")))
                .build());
    }

    /**
     * One role row.
     *
     * <p>A locked row is a genuinely <em>disabled</em> button, so it gets vanilla's disabled sprite and
     * vanilla's greyed label. That still shows its tooltip, which is worth spelling out because the two
     * obvious checks say otherwise: {@code isMouseOver} answers false for an inactive button, but nothing
     * in the render path asks it — {@code AbstractWidget.render} decides the hover flag from the widget's
     * own bounds and drives the tooltip holder from that. So the row is dead to clicks and grey to the eye,
     * and the comment is still there when the pointer lands on it.
     */
    private void roleRow(AvalonRole role, int row, int left, int top, int mask, boolean locked, int dealt) {
        boolean on = locked || (mask & (1 << AvalonRole.setupBit(role))) != 0;
        MutableComponent tick = Component.literal(on ? "\u2714 " : "\u2718 ");
        // A locked label keeps no colour of its own, so vanilla's disabled text grey covers both halves of
        // it; the optional ones are green or red, which is what tells the two blocks apart at a glance.
        Component name = Component.translatable(role.getTranslationKey());
        if (!locked) {
            name = name.copy().withStyle(style -> style.withColor(role.isEvil() ? 0xE2716F : 0x7BD98C));
        }
        // How many of this identity the table will deal, right beside its name. The symbol rather than a
        // word so it reads the same in any language; a row at zero is a role that is simply not in play.
        Component seats = Component.literal(" \u00d7" + dealt)
                .withStyle(style -> style.withColor(DEALT_TEXT));

        Button button = Button.builder(tick.append(name).append(seats), b -> {
                    int changed = AvalonRole.toggle(mask, role);
                    game.setRoleMask(changed);
                    if (onEdit != null) {
                        onEdit.accept(changed);
                    }
                    rebuildWidgets();
                })
                .bounds(left, top + row * (ROW_H + ROW_GAP), COL_W, ROW_H)
                .tooltip(Tooltip.create(role.comment()))
                .build();
        button.active = !locked;
        addRenderableWidget(button);
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        // Under the buttons, unless the window is short enough that the two would meet -- in which case the
        // line follows them down instead of being drawn through them.
        int countY = Math.max(buttonsY + ROW_H + 4, height - 38);
        guiGraphics.drawCenteredString(font, countLine, width / 2, countY, 0xFFCFCFCF);
        if (unusable != null) {
            // Only the verdict, no role list: when the set cannot be played, "what it would deal" is noise.
            guiGraphics.drawCenteredString(font, unusable, width / 2, countY + 12, 0xFFE2716F);
        } else {
            guiGraphics.drawCenteredString(font, lineup, width / 2, countY + 12, 0xFFAFAFAF);
        }
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(parent);
        // After the screen switch, never before: restoring the options screen runs its init again, and that
        // init puts its Save button back to grey. The call has to land on top of that, not under it -- and
        // only when the mask really differs from the one the screen opened on.
        if (onEdit != null && game.roleMask() != openedMask) {
            onEdit.accept(game.roleMask());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
