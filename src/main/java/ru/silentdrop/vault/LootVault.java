package ru.silentdrop.vault;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import ru.silentdrop.util.ItemCodec;

/**
 * Personal loot vault: items that did not fit into the inventory end up here,
 * as well as loot of players who hit the anti-spam rate limit.
 *
 * Stored in vault.yml (Base64 ItemStack serialization), autosaved when the dirty flag is set.
 */
public final class LootVault {

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, List<VaultEntry>> data = new ConcurrentHashMap<UUID, List<VaultEntry>>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private volatile int maxEntries = 54;
    private volatile long expireMillis = 0L;

    public LootVault(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "vault.yml");
    }

    public void configure(int maxEntriesPerPlayer, int expireAfterDays) {
        this.maxEntries = Math.max(1, maxEntriesPerPlayer);
        this.expireMillis = expireAfterDays > 0 ? expireAfterDays * 24L * 3600L * 1000L : 0L;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public void load() {
        data.clear();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int broken = 0;
        for (String key : yaml.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                broken++;
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;

            List<VaultEntry> entries = new ArrayList<VaultEntry>();
            for (String idx : section.getKeys(false)) {
                ConfigurationSection e = section.getConfigurationSection(idx);
                if (e == null) continue;

                String base64 = e.getString("item");
                ItemStack stack = ItemCodec.decode(base64);
                if (stack == null) {
                    // fallback path: serialization map
                    Map<String, Object> map = new HashMap<String, Object>();
                    ConfigurationSection legacy = e.getConfigurationSection("item-legacy");
                    if (legacy != null) {
                        for (String k : legacy.getKeys(true)) {
                            if (!legacy.isConfigurationSection(k)) map.put(k, legacy.get(k));
                        }
                        stack = ItemCodec.decodeMap(map);
                    }
                }
                if (stack == null) {
                    broken++;
                    continue;
                }
                entries.add(new VaultEntry(ItemCodec.encode(stack),
                        e.getLong("time", System.currentTimeMillis()),
                        e.getString("mob", ""),
                        e.getString("reason", "")));
            }
            if (!entries.isEmpty()) {
                data.put(id, Collections.synchronizedList(entries));
            }
        }
        if (broken > 0) {
            plugin.getLogger().warning("[SilentDrop] vault.yml: skipped corrupted entries: " + broken);
        }
        purgeExpired();
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<VaultEntry>> e : data.entrySet()) {
            List<VaultEntry> entries = e.getValue();
            List<VaultEntry> snapshot;
            synchronized (entries) {
                snapshot = new ArrayList<VaultEntry>(entries);
            }
            if (snapshot.isEmpty()) continue;

            int i = 0;
            for (VaultEntry entry : snapshot) {
                String path = e.getKey().toString() + "." + i;
                yaml.set(path + ".item", entry.data());
                yaml.set(path + ".time", entry.storedAt());
                if (!entry.mobName().isEmpty()) yaml.set(path + ".mob", entry.mobName());
                if (!entry.reason().isEmpty()) yaml.set(path + ".reason", entry.reason());
                i++;
            }
        }

        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            yaml.save(file);
            dirty.set(false);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "[SilentDrop] Could not save vault.yml", ex);
        }
    }

    public void saveIfDirty() {
        if (dirty.get()) save();
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /**
     * Puts an item into the vault.
     *
     * @return true when stored, false when the item could not be serialized.
     */
    public boolean store(UUID player, ItemStack stack, String mobName, String reason) {
        if (player == null || stack == null || stack.getAmount() <= 0) return false;
        String encoded = ItemCodec.encode(stack);
        if (encoded == null) return false;

        List<VaultEntry> entries = list(player);
        synchronized (entries) {
            entries.add(new VaultEntry(encoded, System.currentTimeMillis(), mobName, reason));
            // vault overflow: drop the oldest entries
            while (entries.size() > maxEntries) {
                VaultEntry dropped = entries.remove(0);
                plugin.getLogger().warning("[SilentDrop] Vault of " + player + " is full ("
                        + maxEntries + "), removed the oldest entry: "
                        + (dropped.item() != null ? dropped.item().getType() : "?") + " / " + dropped.reason());
            }
        }
        dirty.set(true);
        return true;
    }

    public boolean store(UUID player, ItemStack stack, String reason) {
        return store(player, stack, "", reason);
    }

    /**
     * Snapshot of the player's entries (a copy of the list).
     * IMPORTANT: every list inside {@link #data} is a Collections.synchronizedList(...).
     */
    public List<VaultEntry> entries(UUID player) {
        List<VaultEntry> entries = data.get(player);
        if (entries == null) return Collections.emptyList();
        synchronized (entries) {
            return new ArrayList<VaultEntry>(entries);
        }
    }

    public int size(UUID player) {
        List<VaultEntry> entries = data.get(player);
        if (entries == null) return 0;
        synchronized (entries) {
            return entries.size();
        }
    }

    public int totalSize() {
        int n = 0;
        for (List<VaultEntry> entries : data.values()) {
            synchronized (entries) {
                n += entries.size();
            }
        }
        return n;
    }

    public boolean isEmpty(UUID player) {
        return size(player) == 0;
    }

    /**
     * Takes a single entry by index.
     *
     * @return the item, or {@code null} when the index is wrong or the entry is broken.
     */
    public ItemStack take(UUID player, int index) {
        List<VaultEntry> entries = data.get(player);
        if (entries == null) return null;

        VaultEntry removed;
        synchronized (entries) {
            if (index < 0 || index >= entries.size()) return null;
            removed = entries.remove(index);
            if (entries.isEmpty()) data.remove(player, entries);
        }
        dirty.set(true);
        ItemStack item = removed.item();
        return item;
    }

    /**
     * Takes everything that fits into {@code destination}.
     *
     * @return how many entries were handed out; the rest stays in the vault.
     */
    /**
     * Like {@link #takeAll(UUID, ItemStack[])} but also reports how many entries
     * stayed in the vault (did not fit into the inventory).
     */
    public TakeResult takeAllReport(UUID player, ItemStack[] destination) {
        int taken = takeAll(player, destination);
        return new TakeResult(taken, size(player));
    }

    /** Result of a bulk vault withdrawal. */
    public static final class TakeResult {
        public final int taken;
        public final int remaining;

        public TakeResult(int taken, int remaining) {
            this.taken = taken;
            this.remaining = remaining;
        }
    }

    public int takeAll(UUID player, ItemStack[] destination) {
        List<VaultEntry> entries = data.get(player);
        if (entries == null || entries.isEmpty()) return 0;

        List<VaultEntry> snapshot;
        synchronized (entries) {
            snapshot = new ArrayList<VaultEntry>(entries);
        }

        int taken = 0;
        List<VaultEntry> leftovers = new ArrayList<VaultEntry>();
        for (VaultEntry entry : snapshot) {
            ItemStack item = entry.item();
            if (item == null) {
                continue; // corrupted entry - discard it
            }
            Map<Integer, ItemStack> rest = destination == null ? null : addItem(destination, item);
            if (rest == null || rest.isEmpty()) {
                taken++;
            } else {
                // only a part fits - put the rest back into the vault
                for (ItemStack left : rest.values()) {
                    leftovers.add(new VaultEntry(ItemCodec.encode(left), entry.storedAt(), entry.mobName(), entry.reason()));
                }
            }
        }

        synchronized (entries) {
            entries.clear();
            entries.addAll(leftovers);
            if (entries.isEmpty()) data.remove(player, entries);
        }
        dirty.set(true);
        return taken;
    }

    /** Inventory#addItem equivalent for an arbitrary slot array. */
    private static Map<Integer, ItemStack> addItem(ItemStack[] slots, ItemStack item) {
        return ru.silentdrop.util.InventoryMath.addItem(slots, item);
    }

    public void clear(UUID player) {
        if (data.remove(player) != null) dirty.set(true);
    }

    /** Removes entries older than expire-after-days. @return how many were removed. */
    public int purgeExpired() {
        if (expireMillis <= 0) return 0;
        long cutoff = System.currentTimeMillis() - expireMillis;
        int removed = 0;
        for (Map.Entry<UUID, List<VaultEntry>> e : data.entrySet()) {
            List<VaultEntry> entries = e.getValue();
            synchronized (entries) {
                java.util.Iterator<VaultEntry> it = entries.iterator();
                while (it.hasNext()) {
                    if (it.next().storedAt() < cutoff) {
                        it.remove();
                        removed++;
                    }
                }
                if (entries.isEmpty()) data.remove(e.getKey(), entries);
            }
        }
        if (removed > 0) dirty.set(true);
        return removed;
    }

    private List<VaultEntry> list(UUID player) {
        List<VaultEntry> entries = data.get(player);
        if (entries == null) {
            entries = Collections.synchronizedList(new ArrayList<VaultEntry>());
            List<VaultEntry> prev = data.putIfAbsent(player, entries);
            if (prev != null) entries = prev;
        }
        return entries;
    }
}
