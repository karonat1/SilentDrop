package ru.silentdrop.loot;

import org.bukkit.inventory.ItemStack;

/**
 * ItemStack without a Bukkit runtime, so the distribution core can run in unit tests.
 */
final class TestStack implements ItemStackHolder {

    private final String type;
    private final int amount;

    TestStack(String type, int amount) {
        this.type = type;
        this.amount = amount;
    }

    @Override
    public String typeName() {
        return type;
    }

    @Override
    public int amount() {
        return amount;
    }

    @Override
    public ItemStackHolder copy() {
        return new TestStack(type, amount);
    }

    @Override
    public ItemStack raw() {
        return null;
    }

    @Override
    public String describe() {
        return type + " x" + amount;
    }

    @Override
    public String toString() {
        return describe();
    }
}
