package ru.silentdrop.loot;

import ru.silentdrop.config.Mode;

import java.util.Collections;
import java.util.Random;
import java.util.Set;

/**
 * Parameters of a single share calculation. Immutable.
 */
public final class LootOptions {

    public final Mode mode;
    public final boolean splitStacks;
    public final double killCredit;
    public final double itemChance;
    public final Set<String> itemBlacklist;
    public final Random random;

    public LootOptions(Mode mode, boolean splitStacks, double killCredit, double itemChance,
                       Set<String> itemBlacklist, Random random) {
        this.mode = mode != null ? mode : Mode.DAMAGE;
        this.splitStacks = splitStacks;
        this.killCredit = Math.max(0.0, killCredit);
        this.itemChance = Double.isNaN(itemChance) ? 1.0 : Math.max(0.0, Math.min(1.0, itemChance));
        this.itemBlacklist = itemBlacklist != null ? itemBlacklist : Collections.<String>emptySet();
        this.random = random != null ? random : new Random();
    }

    public static LootOptions defaults() {
        return new LootOptions(Mode.DAMAGE, true, 0.0, 1.0, Collections.<String>emptySet(), new Random());
    }
}
