package dev.trustnoone.mixin;

import dev.trustnoone.client.CardSlotAccess;
import dev.lucaargolo.charta.common.menu.CardSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Injects a mutable view of {@link CardSlot}'s geometry into Charta's own slot class.
 *
 * <p>{@code x} and {@code y} are {@code public final}, and width/height are not stored at all — the
 * widget derives them from {@link CardSlot.Type} through two <em>static</em> methods, and uses the
 * declared width as the box the cards fan inside. That makes the declared width the only lever that
 * spreads a hand out, so the F9 layout editor needs a per-slot size. The static lookups cannot be
 * overridden, so this records the size on the instance and {@link CardSlotAccess} reads it back.
 *
 * <p>Only ever set from the client layout; a slot that was never sized falls straight through to the
 * vanilla value. See {@link CardSlotAccess} for why the interface lives outside the mixin package.
 *
 * <h2>Why the fields are named {@code bridge$...} and are not {@code @Unique}</h2>
 *
 * <p>Charta addons by the same author overlap, and a sibling addon declares the very same three fields.
 * Mixin merges any field that is <em>not</em> {@code @Unique} into an identically named field already on
 * the target, so keeping the exact name and descriptor makes every addon read and write <em>one</em> slot
 * size. That is why the fields are {@code bridge$...} and not {@code avalon$...}: the name is the sharing
 * key, and it has to be byte-for-byte the same in every addon. It matters because a {@code @Redirect}
 * collision keeps only the higher priority config and skips the other outright, so whichever addon's
 * metric redirect survives must still see the size the other addon's layout editor wrote.
 */
@Mixin(CardSlot.class)
public abstract class CardSlotGeometry implements CardSlotAccess {

    /** Shared with the sibling addons; see the class note. Not {@code @Unique}, on purpose. */
    private float bridge$width;

    private float bridge$height;

    /** Marks the field as set, so a width of 0 can stay legal. */
    private boolean bridge$sized;

    @Override
    public void avalon$setSize(float width, float height) {
        this.bridge$width = width;
        this.bridge$height = height;
        this.bridge$sized = true;
    }

    @Override
    public float avalon$width() {
        return this.bridge$sized ? this.bridge$width : CardSlot.getWidth(avalon$type());
    }

    @Override
    public float avalon$height() {
        return this.bridge$sized ? this.bridge$height : CardSlot.getHeight(avalon$type());
    }

    /**
     * {@code @Mutable} is required, not decorative: {@code x} and {@code y} are {@code final}, and a
     * plain accessor setter fails at runtime with
     * {@code IllegalAccessError: Update to non-static final field ... attempted from a different method}
     * because the generated setter is not the constructor.
     */
    @Mutable
    @Accessor(value = "x", remap = false)
    abstract void avalon$setFieldX(float x);

    @Mutable
    @Accessor(value = "y", remap = false)
    abstract void avalon$setFieldY(float y);

    @Accessor(value = "type", remap = false)
    abstract CardSlot.Type avalon$type();

    @Override
    public void avalon$setX(float x) {
        this.avalon$setFieldX(x);
    }

    @Override
    public void avalon$setY(float y) {
        this.avalon$setFieldY(y);
    }
}
