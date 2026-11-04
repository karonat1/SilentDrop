package ru.silentdrop.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import ru.silentdrop.config.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the distribution core. They verify the main SilentDrop invariants:
 *  - loot is never lost or duplicated (except in EVERYONE mode, where duplication is the point);
 *  - shares follow the weights with a deviation of at most one unit;
 *  - the result is deterministic for a fixed seed.
 */
class LootAllocatorTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Contributor c(UUID id, String name, double damage, long firstHit, boolean killer) {
        return new Contributor(id, name, damage, firstHit, killer);
    }

    private static Contributor c(UUID id, double damage) {
        return c(id, "p" + id.toString().substring(id.toString().length() - 1), damage, 1000L, false);
    }

    private static List<ItemStackHolder> stacks(Object... typeAmountPairs) {
        List<ItemStackHolder> out = new ArrayList<ItemStackHolder>();
        for (int i = 0; i < typeAmountPairs.length; i += 2) {
            out.add(new TestStack((String) typeAmountPairs[i], (Integer) typeAmountPairs[i + 1]));
        }
        return out;
    }

    private static LootOptions options(Mode mode, boolean splitStacks, double killCredit, long seed) {
        return new LootOptions(mode, splitStacks, killCredit, 1.0, Collections.<String>emptySet(), new Random(seed));
    }

    private static int units(SharePlan plan, UUID id) {
        int n = 0;
        for (AllocatedStack a : plan.forPlayer(id)) n += a.amount;
        return n;
    }

    private static int totalUnits(List<AllocatedStack> list) {
        int n = 0;
        for (AllocatedStack a : list) n += a.amount;
        return n;
    }

    private static int inputUnits(List<ItemStackHolder> in) {
        int n = 0;
        for (ItemStackHolder h : in) n += h.amount();
        return n;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("No participants: nothing is lost, everything ends up in unallocated")
    void noContributorsKeepsEverything() {
        List<ItemStackHolder> in = stacks("ROTTEN_FLESH", 5, "BONE", 2);
        SharePlan plan = LootAllocator.allocate(Collections.<Contributor>emptyList(), in, options(Mode.DAMAGE, true, 0, 1L));

        assertEquals(0, plan.allocatedUnits());
        assertEquals(inputUnits(in), totalUnits(plan.unallocated()));
    }

    @Test
    @DisplayName("Empty drop: empty plan, no errors")
    void emptyDrops() {
        List<Contributor> players = Arrays.asList(c(A, 10), c(B, 5));
        SharePlan plan = LootAllocator.allocate(players, Collections.<ItemStackHolder>emptyList(),
                options(Mode.DAMAGE, true, 0, 1L));
        assertEquals(0, plan.totalUnits());
        assertTrue(plan.isEmpty());
    }

    @Test
    @DisplayName("EQUAL: 7 units for 3 players = 3/2/2 without losses")
    void equalSplit() {
        List<Contributor> players = Arrays.asList(c(A, 100), c(B, 1), c(C, 1));
        List<ItemStackHolder> in = stacks("ROTTEN_FLESH", 7);

        SharePlan plan = LootAllocator.allocate(players, in, options(Mode.EQUAL, true, 0, 1L));

        assertEquals(3, units(plan, A));
        assertEquals(2, units(plan, B));
        assertEquals(2, units(plan, C));
        assertEquals(7, plan.allocatedUnits());
        assertEquals(0, totalUnits(plan.unallocated()));
    }

    @Test
    @DisplayName("EQUAL ignores damage: shares stay equal with a x100 difference")
    void equalIgnoresDamage() {
        List<Contributor> players = Arrays.asList(c(A, 1000), c(B, 10));
        SharePlan plan = LootAllocator.allocate(players, stacks("IRON_INGOT", 10), options(Mode.EQUAL, true, 0, 1L));
        assertEquals(5, units(plan, A));
        assertEquals(5, units(plan, B));
    }

    @Test
    @DisplayName("DAMAGE: 6 units with 3/2/1 damage = 3/2/1")
    void damageProportionalExact() {
        List<Contributor> players = Arrays.asList(c(A, 3), c(B, 2), c(C, 1));
        SharePlan plan = LootAllocator.allocate(players, stacks("GOLD_NUGGET", 6), options(Mode.DAMAGE, true, 0, 1L));

        assertEquals(3, units(plan, A));
        assertEquals(2, units(plan, B));
        assertEquals(1, units(plan, C));
    }

    @Test
    @DisplayName("DAMAGE: rounding never deviates by more than one unit")
    void damageProportionalMaxDeviationOne() {
        for (int total = 1; total <= 40; total++) {
            List<Contributor> players = Arrays.asList(c(A, 7), c(B, 5), c(C, 3));
            SharePlan plan = LootAllocator.allocate(players, stacks("EMERALD", total), options(Mode.DAMAGE, true, 0, total));

            double sumWeights = 15.0;
            assertEquals(total, plan.allocatedUnits() + totalUnits(plan.unallocated()), "loot lost at total=" + total);
            for (Contributor p : players) {
                double ideal = total * p.weight(0) / sumWeights;
                double deviation = Math.abs(units(plan, p.uuid) - ideal);
                assertTrue(deviation <= 1.0001,
                        "deviation " + deviation + " is bigger than 1 at total=" + total + " player=" + p.name);
            }
        }
    }

    @Test
    @DisplayName("DAMAGE: the handed out total always equals the unit count (nothing is lost)")
    void noLootIsLostRandomCases() {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 300; iteration++) {
            int players = 1 + random.nextInt(6);
            List<Contributor> list = new ArrayList<Contributor>();
            for (int i = 0; i < players; i++) {
                list.add(c(UUID.randomUUID(), "p" + i, 1 + random.nextInt(50), random.nextInt(1000), i == 0));
            }
            List<ItemStackHolder> in = new ArrayList<ItemStackHolder>();
            int stacksCount = 1 + random.nextInt(4);
            int expected = 0;
            for (int i = 0; i < stacksCount; i++) {
                int amount = 1 + random.nextInt(20);
                in.add(new TestStack("ITEM_" + i, amount));
                expected += amount;
            }

            Mode mode = Mode.values()[random.nextInt(Mode.values().length - 1)]; // everything except EVERYONE
            boolean split = random.nextBoolean();
            SharePlan plan = LootAllocator.allocate(list, in, options(mode, split, 0, iteration));

            // with split-stacks: false the accounting unit is a whole stack
            int expectedUnits = split ? expected : in.size();
            int allocated = plan.allocatedUnits() + totalUnits(plan.unallocated());
            assertEquals(expectedUnits, allocated,
                    "loot lost or duplicated, mode=" + mode + " players=" + players + " split=" + split);
        }
    }

    @Test
    @DisplayName("EVERYONE: each participant gets a full copy of the drop")
    void everyoneDuplicates() {
        List<Contributor> players = Arrays.asList(c(A, 5), c(B, 5));
        SharePlan plan = LootAllocator.allocate(players, stacks("DIAMOND", 3, "BONE", 2),
                options(Mode.EVERYONE, true, 0, 1L));

        assertEquals(5, units(plan, A));
        assertEquals(5, units(plan, B));
        assertEquals(0, totalUnits(plan.unallocated()));
    }

    @Test
    @DisplayName("TOP: all loot goes to the highest weight participant")
    void topTakesAll() {
        List<Contributor> players = Arrays.asList(c(A, 1), c(B, 99), c(C, 5));
        SharePlan plan = LootAllocator.allocate(players, stacks("DIAMOND", 4), options(Mode.TOP, true, 0, 1L));

        assertEquals(0, units(plan, A));
        assertEquals(4, units(plan, B));
        assertEquals(0, units(plan, C));
    }

    @Test
    @DisplayName("TOP: with equal weight whoever hit first wins")
    void topTieBreakByFirstHit() {
        List<Contributor> players = Arrays.asList(
                c(A, "late", 10, 5000L, false),
                c(B, "early", 10, 1000L, false));
        SharePlan plan = LootAllocator.allocate(players, stacks("DIAMOND", 1), options(Mode.TOP, true, 0, 1L));
        assertEquals(1, units(plan, B));
        assertEquals(0, units(plan, A));
    }

    @Test
    @DisplayName("RANDOM: deterministic for a fixed seed and loses nothing")
    void randomLottery() {
        List<Contributor> players = Arrays.asList(c(A, 1), c(B, 1), c(C, 1));
        List<ItemStackHolder> in = stacks("ARROW", 30);

        SharePlan first = LootAllocator.allocate(players, in, options(Mode.RANDOM, true, 0, 777L));
        SharePlan second = LootAllocator.allocate(players, in, options(Mode.RANDOM, true, 0, 777L));

        assertEquals(30, first.allocatedUnits());
        assertEquals(units(first, A), units(second, A));
        assertEquals(units(first, B), units(second, B));
        assertEquals(units(first, C), units(second, C));
    }

    @Test
    @DisplayName("RANDOM: over a long run the shares converge to equal")
    void randomIsFairOverTime() {
        List<Contributor> players = Arrays.asList(c(A, 1), c(B, 1), c(C, 1));
        SharePlan plan = LootAllocator.allocate(players, stacks("ARROW", 3000), options(Mode.RANDOM, true, 0, 12345L));
        for (UUID id : Arrays.asList(A, B, C)) {
            assertTrue(Math.abs(units(plan, id) - 1000) < 100, "unfair lottery: " + units(plan, id));
        }
    }

    @Test
    @DisplayName("split-stacks: false hands out whole stacks and never splits items")
    void noSplitKeepsStacksWhole() {
        List<Contributor> players = Arrays.asList(c(A, 1), c(B, 1), c(C, 1));
        List<ItemStackHolder> in = stacks("BREAD", 64, "BREAD", 64);

        SharePlan plan = LootAllocator.allocate(players, in, options(Mode.EQUAL, false, 0, 1L));

        for (UUID id : Arrays.asList(A, B, C)) {
            for (AllocatedStack a : plan.forPlayer(id)) {
                assertTrue(a.isWhole(), "stack was split: " + a);
            }
        }
        assertEquals(2, plan.totalUnits()); // 2 stacks (not 128 units)
    }

    @Test
    @DisplayName("Item blacklist: filtered items are not distributed and are reported in filtered")
    void itemBlacklist() {
        Set<String> blacklist = new HashSet<String>(Arrays.asList("ROTTEN_FLESH"));
        LootOptions opt = new LootOptions(Mode.EQUAL, true, 0, 1.0, blacklist, new Random(1L));

        List<ItemStackHolder> in = stacks("ROTTEN_FLESH", 5, "BONE", 4);
        SharePlan plan = LootAllocator.allocate(Arrays.asList(c(A, 1), c(B, 1)), in, opt);

        assertEquals(4, plan.allocatedUnits());
        assertEquals(5, totalUnits(plan.filtered()));
        assertEquals(2, units(plan, A));
        assertEquals(2, units(plan, B));
    }

    @Test
    @DisplayName("item-chance = 0: the whole drop goes to filtered")
    void zeroChanceFiltersEverything() {
        LootOptions opt = new LootOptions(Mode.EQUAL, true, 0, 0.0, Collections.<String>emptySet(), new Random(1L));
        SharePlan plan = LootAllocator.allocate(Arrays.asList(c(A, 1)), stacks("DIAMOND", 8), opt);
        assertEquals(0, plan.allocatedUnits());
        assertEquals(8, totalUnits(plan.filtered()));
    }

    @Test
    @DisplayName("kill-credit increases the killer share")
    void killCreditBoostsKiller() {
        List<Contributor> players = Arrays.asList(
                c(A, "killer", 5, 1000L, true),
                c(B, "helper", 5, 2000L, false));

        SharePlan without = LootAllocator.allocate(players, stacks("IRON_INGOT", 10), options(Mode.DAMAGE, true, 0, 1L));
        SharePlan with = LootAllocator.allocate(players, stacks("IRON_INGOT", 10), options(Mode.DAMAGE, true, 5, 1L));

        assertEquals(5, units(without, A));
        assertTrue(units(with, A) > 5, "kill-credit did not affect the killer share");
        assertEquals(10, with.allocatedUnits());
    }

    @Test
    @DisplayName("The permission multiplier (silentdrop.multiplier) scales the share")
    void permissionMultiplier() {
        Contributor boosted = new Contributor(A, "vip", 10, 1000L, false, 3.0);
        Contributor normal = new Contributor(B, "player", 10, 1000L, false, 1.0);

        SharePlan plan = LootAllocator.allocate(Arrays.asList(boosted, normal), stacks("GOLD_INGOT", 8),
                options(Mode.DAMAGE, true, 0, 1L));

        assertEquals(6, units(plan, A)); // 3/4 of 8
        assertEquals(2, units(plan, B)); // 1/4 of 8
    }

    @Test
    @DisplayName("Participants with zero weight get nothing and their share is not lost")
    void zeroWeightGetsNothing() {
        List<Contributor> players = Arrays.asList(c(A, 10), c(B, 0));
        SharePlan plan = LootAllocator.allocate(players, stacks("BONE", 5), options(Mode.DAMAGE, true, 0, 1L));

        assertEquals(5, units(plan, A));
        assertEquals(0, units(plan, B));
        assertEquals(5, plan.allocatedUnits());
    }

    @Test
    @DisplayName("All weights are zero: loot is not lost (goes to unallocated)")
    void allZeroWeights() {
        List<Contributor> players = Arrays.asList(c(A, 0), c(B, 0));
        SharePlan plan = LootAllocator.allocate(players, stacks("BONE", 5), options(Mode.DAMAGE, true, 0, 1L));

        assertEquals(0, plan.allocatedUnits());
        assertEquals(5, totalUnits(plan.unallocated()));
    }

    @Test
    @DisplayName("A single participant takes everything")
    void singlePlayer() {
        SharePlan plan = LootAllocator.allocate(Collections.singletonList(c(A, 7)),
                stacks("DIAMOND", 3, "BONE", 11), options(Mode.DAMAGE, true, 0, 1L));
        assertEquals(14, units(plan, A));
    }

    @Test
    @DisplayName("Determinism: identical input produces identical output")
    void deterministic() {
        List<Contributor> players = Arrays.asList(
                c(A, "killer", 12, 100L, true),
                c(B, "helper", 7, 200L, false),
                c(C, "third", 3, 300L, false));
        List<ItemStackHolder> in = stacks("ROTTEN_FLESH", 17, "BONE", 5, "IRON_INGOT", 2);

        SharePlan first = LootAllocator.allocate(players, in, options(Mode.DAMAGE, true, 2, 99L));
        SharePlan second = LootAllocator.allocate(players, in, options(Mode.DAMAGE, true, 2, 99L));

        for (UUID id : Arrays.asList(A, B, C)) {
            assertEquals(units(first, id), units(second, id));
            assertEquals(first.forPlayer(id).toString(), second.forPlayer(id).toString());
        }
    }

    @Test
    @DisplayName("Every loot unit belongs to exactly one player")
    void everyUnitHasExactlyOneOwner() {
        List<Contributor> players = Arrays.asList(c(A, 4), c(B, 9), c(C, 2));
        SharePlan plan = LootAllocator.allocate(players, stacks("WHEAT", 23, "SEEDS", 9),
                options(Mode.DAMAGE, true, 0, 5L));

        int total = 0;
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<UUID, List<AllocatedStack>> e : plan.shares().entrySet()) {
            for (AllocatedStack a : e.getValue()) {
                total += a.amount;
                String key = a.originIndex + "#" + System.identityHashCode(a.source) + "#" + total;
                assertTrue(seen.add(key), "loot unit duplicated");
            }
        }
        assertEquals(32, total);
    }

    @Test
    @DisplayName("Large volume: 10 000 units for 5 players are distributed without losses")
    void stressLargeLoot() {
        List<Contributor> players = Arrays.asList(c(A, 10), c(B, 20), c(C, 30), c(D(), 40), c(E(), 50));
        List<ItemStackHolder> in = new ArrayList<ItemStackHolder>();
        for (int i = 0; i < 200; i++) {
            in.add(new TestStack("ITEM_" + i, 50));
        }

        long start = System.nanoTime();
        SharePlan plan = LootAllocator.allocate(players, in, options(Mode.DAMAGE, true, 0, 1L));
        long millis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(10_000, plan.allocatedUnits());
        assertTrue(millis < 2000, "too slow: " + millis + " ms");
        assertNotNull(plan.shares());
    }

    private static UUID D() {
        return UUID.fromString("00000000-0000-0000-0000-00000000000d");
    }

    private static UUID E() {
        return UUID.fromString("00000000-0000-0000-0000-00000000000e");
    }
}
