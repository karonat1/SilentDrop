package ru.silentdrop.config;

/**
 * Who receives the mob experience.
 */
public enum ExpMode {

    /** No experience is given (the vanilla orb is cancelled). */
    DISABLED,

    /** All experience to the killer. */
    KILLER,

    /** All experience to the participant with the highest weight. */
    TOP_SHARE,

    /** Experience is split proportionally to weight (the remainder goes to the top player). */
    SPLIT;

    public static ExpMode parse(String raw, ExpMode fallback) {
        if (raw == null) return fallback;
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (ExpMode mode : values()) {
            if (mode.name().equals(key)) return mode;
        }
        switch (key) {
            case "NONE":
            case "OFF":
                return DISABLED;
            case "LAST_HIT":
            case "MURDERER":
                return KILLER;
            case "BEST":
            case "HIGHEST":
                return TOP_SHARE;
            case "SHARE":
            case "PROPORTIONAL":
                return SPLIT;
            default:
                return fallback;
        }
    }
}
