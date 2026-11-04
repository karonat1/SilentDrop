package ru.silentdrop.loot;

import ru.silentdrop.config.Mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Loot distribution core. A pure function of (participants, items, options) -> plan.
 * It never touches Bukkit and holds no state, so it is fully covered by unit tests.
 *
 * Guarantees:
 *  - every item (or its part) goes to exactly one participant, except in EVERYONE mode;
 *  - nothing is lost: unallocated items end up in {@link SharePlan#unallocated()};
 *  - the result is deterministic for identical input and the same seed.
 */
public final class LootAllocator {

    private LootAllocator() {
    }

    public static SharePlan allocate(List<Contributor> contributors,
                                     List<? extends ItemStackHolder> stacks,
                                     LootOptions options) {
        LootOptions opt = options != null ? options : LootOptions.defaults();
        List<Contributor> src = contributors != null ? contributors : Collections.<Contributor>emptyList();
        List<? extends ItemStackHolder> raw = stacks != null ? stacks : Collections.<ItemStackHolder>emptyList();

        // 1) Item filter: blacklist + chance.
        List<AllocatedStack> filtered = new ArrayList<AllocatedStack>();
        List<Unit> units = new ArrayList<Unit>();
        for (int i = 0; i < raw.size(); i++) {
            ItemStackHolder holder = raw.get(i);
            if (holder == null || holder.amount() <= 0) continue;

            String type = holder.typeName() == null ? "" : holder.typeName().toUpperCase(Locale.ROOT);
            if (opt.itemBlacklist.contains(type)) {
                filtered.add(new AllocatedStack(holder, holder.amount(), i));
                continue;
            }
            if (opt.itemChance < 1.0 && opt.random.nextDouble() >= opt.itemChance) {
                filtered.add(new AllocatedStack(holder, holder.amount(), i));
                continue;
            }

            if (opt.splitStacks) {
                for (int n = 0; n < holder.amount(); n++) {
                    units.add(new Unit(holder, 1, i));
                }
            } else {
                units.add(new Unit(holder, holder.amount(), i));
            }
        }

        // 2) Participant weights.
        List<Weighted> weighted = new ArrayList<Weighted>();
        double totalWeight = 0.0;
        for (Contributor c : src) {
            if (c == null || c.uuid == null) continue;
            double w = c.weight(opt.killCredit);
            if (w <= 0.0) {
                // a participant with no damage and no kill bonus gets zero weight
                w = 0.0;
            }
            weighted.add(new Weighted(c, w));
            totalWeight += w;
        }

        // 3) Modes where weights do not matter by design.
        Mode effective = opt.mode;
        if (effective == Mode.EQUAL || effective == Mode.RANDOM) {
            boolean allZero = true;
            for (Weighted w : weighted) {
                if (w.weight > 0.0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                for (Weighted w : weighted) w.weight = 1.0;
                totalWeight = weighted.size();
            }
        }

        Map<UUID, List<AllocatedStack>> shares = new LinkedHashMap<UUID, List<AllocatedStack>>();
        List<Contributor> eligible = new ArrayList<Contributor>();
        for (Weighted w : weighted) {
            shares.put(w.c.uuid, new ArrayList<AllocatedStack>());
            eligible.add(w.c);
        }

        List<AllocatedStack> unallocated = new ArrayList<AllocatedStack>();

        if (weighted.isEmpty() || totalWeight <= 0.0 || units.isEmpty()) {
            for (Unit u : units) {
                unallocated.add(new AllocatedStack(u.holder, u.amount, u.index));
            }
            return new SharePlan(shares, unallocated, filtered, eligible, totalWeight, units.size());
        }

        switch (effective) {
            case TOP:
                allocateTop(weighted, units, shares);
                break;
            case RANDOM:
                allocateRandom(weighted, totalWeight, units, shares, opt);
                break;
            case EQUAL:
                allocateByRemaining(weighted, units, shares, true);
                break;
            case DAMAGE:
                allocateByRemaining(weighted, units, shares, false);
                break;
            case EVERYONE:
                allocateEveryone(weighted, units, shares);
                break;
            default:
                allocateByRemaining(weighted, units, shares, false);
                break;
        }

        return new SharePlan(shares, unallocated, filtered, eligible, totalWeight, units.size());
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    /** Everything to the highest weight player (tie break: whoever hit first). */
    private static void allocateTop(List<Weighted> weighted, List<Unit> units, Map<UUID, List<AllocatedStack>> shares) {
        Weighted best = pickBest(weighted);
        List<AllocatedStack> target = shares.get(best.c.uuid);
        for (Unit u : units) {
            target.add(new AllocatedStack(u.holder, u.amount, u.index));
        }
    }

    /** Lottery: every unit goes to a random participant, chance proportional to weight. */
    private static void allocateRandom(List<Weighted> weighted, double totalWeight, List<Unit> units,
                                       Map<UUID, List<AllocatedStack>> shares, LootOptions opt) {
        for (Unit u : units) {
            Weighted winner = roll(weighted, totalWeight, opt);
            shares.get(winner.c.uuid).add(new AllocatedStack(u.holder, u.amount, u.index));
        }
    }

    /** Every participant gets a full copy of the drop (this multiplies items!). */
    private static void allocateEveryone(List<Weighted> weighted, List<Unit> units, Map<UUID, List<AllocatedStack>> shares) {
        for (Weighted w : weighted) {
            List<AllocatedStack> target = shares.get(w.c.uuid);
            for (Unit u : units) {
                target.add(new AllocatedStack(u.holder, u.amount, u.index));
            }
        }
    }

    /**
     * Distribution by remaining entitlement: on each step the item goes to whoever
     * is still missing the most. In EQUAL mode all weights are flattened.
     *
     * This produces fair rounding: the total handed out always equals the unit count,
     * and no player deviates from their ideal share by more than one unit.
     */
    private static void allocateByRemaining(List<Weighted> weighted, List<Unit> units,
                                            Map<UUID, List<AllocatedStack>> shares, boolean equal) {
        double total = 0.0;
        for (Weighted w : weighted) {
            total += equal ? 1.0 : w.weight;
        }
        if (total <= 0.0) {
            for (Weighted w : weighted) w.remaining = 1.0;
            total = weighted.size();
        }

        int totalUnits = units.size();
        PriorityQueue<Weighted> queue = new PriorityQueue<Weighted>(weighted.size(), new Comparator<Weighted>() {
            @Override
            public int compare(Weighted a, Weighted b) {
                int cmp = Double.compare(b.remaining, a.remaining);       // bigger remaining first
                if (cmp != 0) return cmp;
                cmp = Double.compare(a.given, b.given);                    // fewer already given first
                if (cmp != 0) return cmp;
                cmp = Long.compare(a.c.firstHitMillis, b.c.firstHitMillis); // whoever hit first wins
                if (cmp != 0) return cmp;
                return a.c.uuid.compareTo(b.c.uuid);                        // fully deterministic
            }
        });

        for (Weighted w : weighted) {
            w.remaining = (equal ? 1.0 : w.weight) / total * totalUnits;
            w.given = 0;
            queue.add(w);
        }

        for (Unit u : units) {
            Weighted top = queue.poll();
            if (top == null) break; // theoretically impossible
            shares.get(top.c.uuid).add(new AllocatedStack(u.holder, u.amount, u.index));
            top.given++;
            top.remaining -= 1.0;
            queue.add(top);
        }
    }

    private static Weighted pickBest(List<Weighted> weighted) {
        Weighted best = weighted.get(0);
        for (int i = 1; i < weighted.size(); i++) {
            Weighted w = weighted.get(i);
            if (w.weight > best.weight
                    || (w.weight == best.weight && w.c.firstHitMillis < best.c.firstHitMillis)) {
                best = w;
            }
        }
        return best;
    }

    private static Weighted roll(List<Weighted> weighted, double totalWeight, LootOptions opt) {
        double r = opt.random.nextDouble() * totalWeight;
        double acc = 0.0;
        for (Weighted w : weighted) {
            acc += w.weight;
            if (r < acc) return w;
        }
        return weighted.get(weighted.size() - 1);
    }

    // ------------------------------------------------------------------

    /** A single distribution unit. */
    private static final class Unit {
        final ItemStackHolder holder;
        final int amount;
        final int index;

        Unit(ItemStackHolder holder, int amount, int index) {
            this.holder = holder;
            this.amount = amount;
            this.index = index;
        }
    }

    /** Participant plus weight and current distribution state. */
    private static final class Weighted {
        final Contributor c;
        double weight;
        double remaining;
        int given;

        Weighted(Contributor c, double weight) {
            this.c = c;
            this.weight = weight;
        }
    }
}
