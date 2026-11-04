package ru.silentdrop.loot;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@link ItemStackHolder} implementation backed by a real Bukkit ItemStack.
 */
public final class BukkitStack implements ItemStackHolder {

    private final ItemStack stack;

    public BukkitStack(ItemStack stack) {
        if (stack == null) throw new IllegalArgumentException("stack == null");
        this.stack = stack;
    }

    public static BukkitStack of(ItemStack stack) {
        return stack == null ? null : new BukkitStack(stack);
    }

    @Override
    public String typeName() {
        return stack.getType().name();
    }

    @Override
    public int amount() {
        return stack.getAmount();
    }

    @Override
    public ItemStackHolder copy() {
        return new BukkitStack(stack.clone());
    }

    @Override
    public ItemStack raw() {
        return stack;
    }

    @Override
    public String describe() {
        String base = typeName() + " x" + amount();
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                base = base + " (" + meta.getDisplayName() + ")";
            }
        }
        return base;
    }

    @Override
    public String toString() {
        return describe();
    }
}
