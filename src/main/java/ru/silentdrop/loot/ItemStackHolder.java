package ru.silentdrop.loot;

import org.bukkit.inventory.ItemStack;

/**
 * Thin wrapper around ItemStack so the distribution core ({@link LootAllocator})
 * can be tested without a Bukkit runtime.
 */
public interface ItemStackHolder {

    /** Item type in upper case, like Material.name() (for example "ROTTEN_FLESH"). */
    String typeName();

    /** Stack amount. */
    int amount();

    /** Deep copy. */
    ItemStackHolder copy();

    /** The original ItemStack (null in tests). */
    ItemStack raw();

    /** Human readable name for logs. */
    String describe();
}
