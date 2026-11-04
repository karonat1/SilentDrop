package ru.silentdrop.loot;

import org.bukkit.inventory.ItemStack;

/**
 * A single distribution unit: a part of (or the whole) source stack.
 */
public final class AllocatedStack {

    public final ItemStackHolder source;
    public final int amount;
    /** Index of the source stack in the drop list - stable ordering and debugging. */
    public final int originIndex;

    public AllocatedStack(ItemStackHolder source, int amount, int originIndex) {
        this.source = source;
        this.amount = Math.max(0, amount);
        this.originIndex = originIndex;
    }

    /** Whether this is the whole stack (nothing was split off). */
    public boolean isWhole() {
        return amount == source.amount();
    }

    /**
     * Materializes the item for delivery to a player.
     * In unit tests (no Bukkit) source.raw() == null, so null is returned.
     */
    public ItemStack toItemStack() {
        ItemStack raw = source.raw();
        if (raw == null) return null;
        ItemStack out = raw.clone();
        out.setAmount(amount);
        return out;
    }

    @Override
    public String toString() {
        return source.typeName() + " x" + amount + (isWhole() ? "" : "/" + source.amount());
    }
}
