package ru.silentdrop.loot;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of the share calculation: who gets what, and what stayed unallocated.
 */
public final class SharePlan {

    /** Participant UUID -> their items (insertion order preserved). */
    private final Map<UUID, List<AllocatedStack>> shares;

    /** Items that went to nobody (for example when there are no participants). */
    private final List<AllocatedStack> unallocated;

    /** Items filtered out before distribution (item blacklist / item-chance). */
    private final List<AllocatedStack> filtered;

    private final List<Contributor> eligible;
    private final double totalWeight;
    private final int totalUnits;

    public SharePlan(Map<UUID, List<AllocatedStack>> shares,
                     List<AllocatedStack> unallocated,
                     List<AllocatedStack> filtered,
                     List<Contributor> eligible,
                     double totalWeight,
                     int totalUnits) {
        this.shares = Collections.unmodifiableMap(shares);
        this.unallocated = Collections.unmodifiableList(unallocated);
        this.filtered = Collections.unmodifiableList(filtered);
        this.eligible = Collections.unmodifiableList(eligible);
        this.totalWeight = totalWeight;
        this.totalUnits = totalUnits;
    }

    public Map<UUID, List<AllocatedStack>> shares() {
        return shares;
    }

    public List<AllocatedStack> forPlayer(UUID id) {
        List<AllocatedStack> list = shares.get(id);
        return list == null ? Collections.<AllocatedStack>emptyList() : list;
    }

    public List<AllocatedStack> unallocated() {
        return unallocated;
    }

    public List<AllocatedStack> filtered() {
        return filtered;
    }

    public List<Contributor> eligible() {
        return eligible;
    }

    public double totalWeight() {
        return totalWeight;
    }

    public int totalUnits() {
        return totalUnits;
    }

    public boolean isEmpty() {
        for (List<AllocatedStack> v : shares.values()) {
            if (!v.isEmpty()) return false;
        }
        return unallocated.isEmpty() && filtered.isEmpty();
    }

    public int allocatedUnits() {
        int n = 0;
        for (List<AllocatedStack> v : shares.values()) {
            n += v.size();
        }
        return n;
    }

    /** Shares as "UUID -> list of ItemStack" (for delivery and debug output). */
    public Map<UUID, List<ItemStack>> asItemStacks() {
        Map<UUID, List<ItemStack>> out = new LinkedHashMap<UUID, List<ItemStack>>();
        for (Map.Entry<UUID, List<AllocatedStack>> e : shares.entrySet()) {
            List<ItemStack> list = new ArrayList<ItemStack>();
            for (AllocatedStack a : e.getValue()) {
                ItemStack stack = a.toItemStack();
                if (stack != null) list.add(stack);
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** Total loot units received by a player. */
    public int unitsFor(UUID id) {
        int n = 0;
        for (AllocatedStack a : forPlayer(id)) n += a.amount;
        return n;
    }
}
