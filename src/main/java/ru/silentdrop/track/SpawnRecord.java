package ru.silentdrop.track;

/**
 * Record of an entity appearing in the world. Used by the anti-farm logic:
 * a mob killed right after spawning is most likely part of a farm conveyor.
 */
public final class SpawnRecord {

    public final long spawnedAtMillis;
    public final String reason;
    public final boolean baby;

    public SpawnRecord(long spawnedAtMillis, String reason, boolean baby) {
        this.spawnedAtMillis = spawnedAtMillis;
        this.reason = reason != null ? reason : "UNKNOWN";
        this.baby = baby;
    }

    public long ageMillis(long now) {
        return now - spawnedAtMillis;
    }
}
