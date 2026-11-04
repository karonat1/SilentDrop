package ru.silentdrop.vault;

import org.bukkit.inventory.ItemStack;

/**
 * A single vault entry.
 */
public final class VaultEntry {

    private final String data;
    private final long storedAt;
    private final String mobName;
    private final String reason;

    public VaultEntry(String data, long storedAt, String mobName, String reason) {
        this.data = data;
        this.storedAt = storedAt;
        this.mobName = mobName != null ? mobName : "";
        this.reason = reason != null ? reason : "";
    }

    public static VaultEntry of(ItemStack stack, String mobName, String reason) {
        return new VaultEntry(ru.silentdrop.util.ItemCodec.encode(stack), System.currentTimeMillis(), mobName, reason);
    }

    public String data() {
        return data;
    }

    public long storedAt() {
        return storedAt;
    }

    public String mobName() {
        return mobName;
    }

    public String reason() {
        return reason;
    }

    /** @return the item, or {@code null} when the data is corrupted. */
    public ItemStack item() {
        return ru.silentdrop.util.ItemCodec.decode(data);
    }

    public boolean isValid() {
        return data != null && !data.isEmpty() && item() != null;
    }
}
