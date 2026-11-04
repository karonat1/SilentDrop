package ru.silentdrop.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for item placement into slots (delivery dry run and vault withdrawal).
 * The key invariant: the total amount of items before and after never changes,
 * so loot can neither be duplicated nor lost.
 */
class InventoryMathTest {

    /** ItemStack without Bukkit. */
    private static final class TestSlot implements InventoryMath.SlotStack {
        final String type;
        final int max;
        int amount;

        TestSlot(String type, int amount, int max) {
            this.type = type;
            this.amount = amount;
            this.max = max;
        }

        @Override
        public boolean similar(InventoryMath.SlotStack other) {
            return other instanceof TestSlot && ((TestSlot) other).type.equals(type);
        }

        @Override
        public int amount() {
            return amount;
        }

        @Override
        public void amount(int value) {
            this.amount = value;
        }

        @Override
        public int maxStackSize() {
            return max;
        }

        @Override
        public InventoryMath.SlotStack copyWithAmount(int amount) {
            return new TestSlot(type, amount, max);
        }

        @Override
        public String toString() {
            return type + ":" + amount + "/" + max;
        }
    }

    private static TestSlot item(String type, int amount) {
        return new TestSlot(type, amount, 64);
    }

    private static TestSlot[] slots(int size) {
        return new TestSlot[size];
    }

    private static int units(InventoryMath.SlotStack[] slots) {
        int n = 0;
        for (InventoryMath.SlotStack s : slots) {
            if (s != null) n += s.amount();
        }
        return n;
    }

    private static int restUnits(Map<Integer, InventoryMath.SlotStack> rest) {
        int n = 0;
        for (InventoryMath.SlotStack s : rest.values()) n += s.amount();
        return n;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Empty inventory: the item goes into the first slot as a whole")
    void emptyInventory() {
        TestSlot[] slots = slots(9);
        Map<Integer, InventoryMath.SlotStack> rest = InventoryMath.addItem(slots, item("BONE", 10));

        assertTrue(rest.isEmpty());
        assertNotNull(slots[0]);
        assertEquals(10, slots[0].amount());
        assertEquals("BONE", slots[0].type);
    }

    @Test
    @DisplayName("Fills an existing stack up to its maximum")
    void mergesIntoExistingStack() {
        TestSlot[] slots = slots(9);
        slots[0] = item("BONE", 60);

        Map<Integer, InventoryMath.SlotStack> rest = InventoryMath.addItem(slots, item("BONE", 10));

        assertTrue(rest.isEmpty());
        assertEquals(64, slots[0].amount());
        assertNotNull(slots[1]);
        assertEquals(6, slots[1].amount());
    }

    @Test
    @DisplayName("Full inventory: everything comes back as a remainder, nothing is lost")
    void fullInventoryReturnsEverything() {
        TestSlot[] slots = slots(3);
        slots[0] = item("STONE", 64);
        slots[1] = item("DIRT", 64);
        slots[2] = item("SAND", 64);

        Map<Integer, InventoryMath.SlotStack> rest = InventoryMath.addItem(slots, item("BONE", 5));

        assertEquals(1, rest.size());
        assertEquals(5, restUnits(rest));
        assertEquals(192, units(slots)); // the original content is untouched
    }

    @Test
    @DisplayName("Different items never merge into each other")
    void differentItemsDoNotMerge() {
        TestSlot[] slots = slots(2);
        slots[0] = item("STONE", 10);

        InventoryMath.addItem(slots, item("BONE", 5));

        assertEquals(10, slots[0].amount());
        assertEquals("STONE", slots[0].type);
        assertNotNull(slots[1]);
        assertEquals("BONE", slots[1].type);
        assertEquals(5, slots[1].amount());
    }

    @Test
    @DisplayName("Invariant: units before = units in slots + remainder (random cases)")
    void conservationInvariant() {
        java.util.Random random = new java.util.Random(7);
        String[] types = {"BONE", "STONE", "DIRT", "GOLD_NUGGET"};

        for (int iteration = 0; iteration < 500; iteration++) {
            int size = 1 + random.nextInt(9);
            TestSlot[] slots = slots(size);
            int before = 0;
            for (int i = 0; i < size; i++) {
                if (random.nextInt(3) == 0) continue;
                slots[i] = item(types[random.nextInt(types.length)], 1 + random.nextInt(64));
                before += slots[i].amount();
            }

            TestSlot incoming = item(types[random.nextInt(types.length)], 1 + random.nextInt(128));
            int adding = incoming.amount();

            Map<Integer, InventoryMath.SlotStack> rest = InventoryMath.addItem(slots, incoming);

            assertEquals(before + adding, units(slots) + restUnits(rest),
                    "item balance violated on iteration " + iteration);
        }
    }

    @Test
    @DisplayName("Null and empty items are no-ops")
    void nullAndEmptyAreNoOps() {
        TestSlot[] slots = slots(3);
        assertTrue(InventoryMath.addItem(slots, item("BONE", 0)).isEmpty());
        assertNull(slots[0]);
        assertTrue(InventoryMath.addItem(null, item("BONE", 1)).isEmpty());
        assertTrue(InventoryMath.addItem(slots, null).isEmpty());
    }

    @Test
    @DisplayName("Free slots and total units are counted correctly")
    void freeSlotsCounting() {
        TestSlot[] slots = slots(4);
        slots[1] = item("BONE", 1);
        slots[3] = item("STONE", 64);

        assertEquals(2, InventoryMath.freeSlots(slots));
        assertEquals(65, InventoryMath.countUnits(slots));
        assertEquals(0, InventoryMath.freeSlots(null));
        assertEquals(0, InventoryMath.countUnits(null));
    }

    @Test
    @DisplayName("Item bigger than the inventory: part is stored, part is returned")
    void oversizedItem() {
        TestSlot[] slots = slots(1);
        Map<Integer, InventoryMath.SlotStack> rest = InventoryMath.addItem(slots, item("BONE", 100));

        assertEquals(64, slots[0].amount());
        assertEquals(36, restUnits(rest));
    }
}
