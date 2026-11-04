package ru.silentdrop.util;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts items into a slot array, an equivalent of {@code Inventory#addItem} that works
 * with an arbitrary array.
 *
 * Why it exists:
 *  1) a dry run before the real delivery - a player must never end up with half a stack
 *     in the inventory and half in the vault because of an imprecise calculation;
 *  2) handing out vault contents (/sd vault -> "take all").
 *
 * The logic mirrors vanilla: first fill matching stacks, then occupy empty slots.
 * The core ({@link Core}) knows nothing about Bukkit and is covered by tests.
 */
public final class InventoryMath {

    private InventoryMath() {
    }

    /** Minimal stack representation so the algorithm can be tested without a server. */
    public interface SlotStack {
        boolean similar(SlotStack other);

        int amount();

        void amount(int value);

        int maxStackSize();

        SlotStack copyWithAmount(int amount);
    }

    /**
     * @param slots slot array (modified in place)
     * @param item  the item to insert
     * @return map of "index -> remainder"; empty when everything fits
     */
    public static Map<Integer, SlotStack> addItem(SlotStack[] slots, SlotStack item) {
        return Core.addItem(slots, item);
    }

    /** Bukkit wrapper. */
    public static Map<Integer, ItemStack> addItem(ItemStack[] slots, ItemStack item) {
        if (item == null || item.getAmount() <= 0 || slots == null) {
            return Collections.emptyMap();
        }

        SlotStack[] adapted = new SlotStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            adapted[i] = slots[i] == null ? null : new BukkitSlot(slots[i]);
        }

        Map<Integer, SlotStack> rest = Core.addItem(adapted, new BukkitSlot(item));

        Map<Integer, ItemStack> out = new LinkedHashMap<Integer, ItemStack>();
        for (int i = 0; i < adapted.length; i++) {
            slots[i] = adapted[i] == null ? null : ((BukkitSlot) adapted[i]).stack;
        }
        for (Map.Entry<Integer, SlotStack> e : rest.entrySet()) {
            out.put(e.getKey(), ((BukkitSlot) e.getValue()).stack);
        }
        return out;
    }

    /** Number of free slots in the array. */
    public static int freeSlots(SlotStack[] slots) {
        int n = 0;
        if (slots == null) return 0;
        for (SlotStack s : slots) {
            if (s == null || s.amount() <= 0) n++;
        }
        return n;
    }

    /** Total item units in the array. */
    public static int countUnits(SlotStack[] slots) {
        int n = 0;
        if (slots == null) return 0;
        for (SlotStack s : slots) {
            if (s != null) n += s.amount();
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Pure algorithm
    // ------------------------------------------------------------------

    static final class Core {

        private Core() {
        }

        static Map<Integer, SlotStack> addItem(SlotStack[] slots, SlotStack item) {
            Map<Integer, SlotStack> left = new LinkedHashMap<Integer, SlotStack>();
            if (item == null || item.amount() <= 0 || slots == null) return left;

            int remaining = item.amount();
            int max = item.maxStackSize() > 0 ? item.maxStackSize() : 64;

            // 1) fill matching stacks
            for (int i = 0; i < slots.length && remaining > 0; i++) {
                SlotStack slot = slots[i];
                if (slot == null || slot.amount() <= 0 || !slot.similar(item)) continue;
                int space = max - slot.amount();
                if (space <= 0) continue;
                int move = Math.min(space, remaining);
                slot.amount(slot.amount() + move);
                remaining -= move;
            }

            // 2) occupy empty slots
            for (int i = 0; i < slots.length && remaining > 0; i++) {
                if (slots[i] != null && slots[i].amount() > 0) continue;
                int move = Math.min(max, remaining);
                slots[i] = item.copyWithAmount(move);
                remaining -= move;
            }

            if (remaining > 0) {
                left.put(0, item.copyWithAmount(remaining));
            }
            return left;
        }
    }

    /** Adapter Bukkit ItemStack -> SlotStack. */
    private static final class BukkitSlot implements SlotStack {
        ItemStack stack;

        BukkitSlot(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public boolean similar(SlotStack other) {
            return other instanceof BukkitSlot && stack.isSimilar(((BukkitSlot) other).stack);
        }

        @Override
        public int amount() {
            return stack.getAmount();
        }

        @Override
        public void amount(int value) {
            stack.setAmount(value);
        }

        @Override
        public int maxStackSize() {
            return stack.getMaxStackSize();
        }

        @Override
        public SlotStack copyWithAmount(int amount) {
            ItemStack copy = stack.clone();
            copy.setAmount(amount);
            return new BukkitSlot(copy);
        }
    }
}
