package ru.silentdrop.loot;

import java.util.UUID;

/**
 * A kill participant. Plain data without Bukkit dependencies, easy to test.
 */
public final class Contributor {

    public final UUID uuid;
    public final String name;

    /** Total damage dealt (0 when the player got into the list without damage, e.g. as the killer). */
    public final double damage;

    /** Time of the first recorded hit (millis). Used as a tie breaker. */
    public final long firstHitMillis;

    /** Whether this player landed the last hit. */
    public final boolean killer;

    /**
     * Multiplier from permissions (silentdrop.multiplier.X). 1.0 = no bonus.
     * Applied to the weight, not to the minimal damage threshold.
     */
    public final double permissionMultiplier;

    public Contributor(UUID uuid, String name, double damage, long firstHitMillis, boolean killer, double permissionMultiplier) {
        this.uuid = uuid;
        this.name = name != null ? name : "unknown";
        this.damage = Math.max(0.0, damage);
        this.firstHitMillis = firstHitMillis;
        this.killer = killer;
        this.permissionMultiplier = permissionMultiplier > 0 ? permissionMultiplier : 1.0;
    }

    public Contributor(UUID uuid, String name, double damage, long firstHitMillis, boolean killer) {
        this(uuid, name, damage, firstHitMillis, killer, 1.0);
    }

    /**
     * Participant weight = (damage + last hit bonus) * permission multiplier.
     */
    public double weight(double killCredit) {
        double base = damage + (killer ? Math.max(0.0, killCredit) : 0.0);
        return base * permissionMultiplier;
    }

    @Override
    public String toString() {
        return name + "(dmg=" + damage + (killer ? ",killer" : "") + ",mult=" + permissionMultiplier + ")";
    }
}
