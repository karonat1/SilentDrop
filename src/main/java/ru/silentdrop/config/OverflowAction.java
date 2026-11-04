package ru.silentdrop.config;

/**
 * What to do with items that did not fit into the participant's inventory.
 */
public enum OverflowAction {

    /** Put into the player's personal vault (/sd vault). */
    VAULT,

    /**
     * Drop at the owner's feet. This is the ONLY case where an item appears in the world:
     * it is marked as owned and cannot be taken by other players, mobs or hoppers.
     */
    DROP,

    /** Destroy (logged when debug is enabled). */
    VOID;

    public static OverflowAction parse(String raw, OverflowAction fallback) {
        if (raw == null) return fallback;
        String key = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (OverflowAction action : values()) {
            if (action.name().equals(key)) return action;
        }
        switch (key) {
            case "STORAGE":
            case "KEEP":
            case "DEPOSIT":
                return VAULT;
            case "GROUND":
            case "ITEM":
            case "FALLBACK":
                return DROP;
            case "DESTROY":
            case "DELETE":
            case "REMOVE":
                return VOID;
            default:
                return fallback;
        }
    }
}
