package ru.silentdrop.loot;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.silentdrop.vault.LootVault;

/**
 * Owner of a dropped item.
 *
 * An item reaches the world ONLY as a fallback (full inventory / anti-spam),
 * and until the protection expires nobody except the owner can take it:
 * no other player, no mob, no hopper, no explosion.
 */
public final class DropProtector {

    private final Plugin plugin;
    private final Map<UUID, Owner> owners = new ConcurrentHashMap<UUID, Owner>();
    private volatile LootVault vault;
    private volatile boolean vaultOnExpire;

    public DropProtector(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setVault(LootVault vault, boolean storeOnExpire) {
        this.vault = vault;
        this.vaultOnExpire = storeOnExpire;
    }

    public void register(UUID itemId, UUID ownerId, long expireAtMillis) {
        register(itemId, ownerId, expireAtMillis, null);
    }

    public void register(UUID itemId, UUID ownerId, long expireAtMillis, java.util.UUID worldId) {
        if (itemId == null || ownerId == null) return;
        owners.put(itemId, new Owner(ownerId, expireAtMillis, worldId));
    }

    public void unregister(UUID itemId) {
        if (itemId != null) owners.remove(itemId);
    }

    public boolean isProtected(UUID itemId) {
        Owner o = itemId == null ? null : owners.get(itemId);
        return o != null && o.expireAt > System.currentTimeMillis();
    }

    public UUID ownerOf(UUID itemId) {
        Owner o = itemId == null ? null : owners.get(itemId);
        if (o == null) return null;
        return o.expireAt > System.currentTimeMillis() ? o.ownerId : null;
    }

    /** Is the item still protected? */
    public boolean isOwner(UUID itemId, UUID playerId) {
        UUID owner = ownerOf(itemId);
        return owner != null && owner.equals(playerId);
    }

    /** Epoch millis when the protection expires; null when the item is not registered. */
    public Long expireAt(UUID itemId) {
        Owner o = itemId == null ? null : owners.get(itemId);
        return o == null ? null : o.expireAt;
    }

    public int size() {
        return owners.size();
    }

    /**
     * Periodic task: removes expired protection.
     * With vault-on-expire the item is not lost, it moves into the owner's vault.
     */
    public int tick() {
        if (owners.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        int handled = 0;

        Iterator<Map.Entry<UUID, Owner>> it = owners.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Owner> e = it.next();
            Owner owner = e.getValue();
            if (owner.expireAt > now) continue;
            it.remove();
            handled++;

            if (!vaultOnExpire || vault == null) continue;

            org.bukkit.entity.Entity entity = findItem(e.getKey(), owner.worldId);
            if (!(entity instanceof Item)) continue;
            Item item = (Item) entity;
            org.bukkit.inventory.ItemStack stack = item.getItemStack();
            if (stack == null || stack.getAmount() <= 0) continue;

            if (vault.store(owner.ownerId, stack, "expired-drop")) {
                item.remove();
                Player p = plugin.getServer().getPlayer(owner.ownerId);
                if (p != null && p.isOnline()) {
                    plugin.getLogger().fine("[SilentDrop] Expired loot " + stack.getType()
                            + " x" + stack.getAmount() + " moved into the vault of " + p.getName());
                }
            }
        }
        return handled;
    }

    /**
     * Finds the item. When the world is known only that world is scanned (cheap),
     * otherwise every world is scanned (rare fallback path).
     */
    private org.bukkit.entity.Entity findItem(UUID itemId, UUID worldId) {
        if (worldId != null) {
            org.bukkit.World world = plugin.getServer().getWorld(worldId);
            if (world != null) {
                for (org.bukkit.entity.Entity entity : world.getEntities()) {
                    if (entity.getUniqueId().equals(itemId)) return entity;
                }
                return null;
            }
        }
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(itemId)) return entity;
            }
        }
        return null;
    }

    public void clear() {
        owners.clear();
    }

    private static final class Owner {
        final UUID ownerId;
        final long expireAt;
        final UUID worldId;

        Owner(UUID ownerId, long expireAt, UUID worldId) {
            this.ownerId = ownerId;
            this.expireAt = expireAt;
            this.worldId = worldId;
        }
    }
}
