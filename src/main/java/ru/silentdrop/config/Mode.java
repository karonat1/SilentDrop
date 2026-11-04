package ru.silentdrop.config;

/**
 * Item distribution modes between participants.
 */
public enum Mode {

    /** Everything to the player with the highest weight (usually the top damage dealer). */
    TOP,

    /** Every item goes to a random participant with equal chance. */
    RANDOM,

    /** Items are divided equally between all participants (by count, not by damage). */
    EQUAL,

    /**
     * Items are divided proportionally to participant weight (damage + bonuses).
     * Remainders go one by one to whoever is still missing the most.
     */
    DAMAGE,

    /** Every participant gets a full copy of the drop. WARNING: this multiplies items. */
    EVERYONE;

    public static Mode parse(String raw, Mode fallback) {
        if (raw == null) return fallback;
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Mode mode : values()) {
            if (mode.name().equals(key)) return mode;
        }
        // human readable synonyms
        switch (key) {
            case "BY_DAMAGE":
            case "WEIGHTED":
            case "PROPORTIONAL":
                return DAMAGE;
            case "EQUAL_SPLIT":
            case "ROUND_ROBIN":
            case "FAIR":
                return EQUAL;
            case "TOP_DAMAGE":
            case "WINNER":
            case "MOST_DAMAGE":
                return TOP;
            case "LOTTERY":
            case "RANDOM_PLAYER":
                return RANDOM;
            case "ALL":
            case "COPY":
            case "DUPLICATE":
                return EVERYONE;
            default:
                return fallback;
        }
    }
}
