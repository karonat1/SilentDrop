package ru.silentdrop.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;

import ru.silentdrop.config.ConfigHolder;
import ru.silentdrop.config.Messages;
import ru.silentdrop.loot.DropProtector;
import ru.silentdrop.util.Text;

/**
 * Protects dropped loot from thieves.
 *
 * An item reaches the world only as a fallback (full inventory / anti-spam),
 * and while the protection is active:
 *  - other players cannot pick it up;
 *  - mobs cannot pick it up (zombies, foxes, ...);
 *  - hoppers and hopper minecarts cannot collect it;
 *  - explosions, fire and lava cannot destroy it;
 *  - when stacks merge, the ownership mark is transferred to the merged item.
 */
public final class ItemOwnershipListener implements Listener {

    private final DropProtector protector;
    private final ConfigHolder holder;
    /** Pickup delay (ticks) applied after a cancellation - prevents event spam. */
    private static final int DENY_PICKUP_DELAY_TICKS = 20;

    private final long notifyCooldownMillis = 3000L;
    private final java.util.Map<UUID, Long> lastNotify = new java.util.concurrent.ConcurrentHashMap<UUID, Long>();

    public ItemOwnershipListener(DropProtector protector, ConfigHolder holder) {
        this.protector = protector;
        this.holder = holder;
    }

    // --------------------------------------------------------------
    // A player tries to pick up somebody else's loot
    // --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            onMobPickup(event);
            return;
        }
        Player player = (Player) event.getEntity();
        Item item = event.getItem();
        UUID itemId = item.getUniqueId();

        UUID owner = protector.ownerOf(itemId);
        if (owner == null) return;

        if (owner.equals(player.getUniqueId())) {
            // own loot: remove the protection and the service display name,
            // so the item merges with normal stacks again
            protector.unregister(itemId);
            stripOwnerTag(item);
            return;
        }

        if (!protect(player, "block-other-players")) return;
        event.setCancelled(true);

        // Without this the server fires EntityPickupItemEvent every tick while a stranger
        // stands on the item. A short pickup delay limits it to roughly once per second.
        // The owner barely notices the delay.
        if (item.getPickupDelay() < DENY_PICKUP_DELAY_TICKS) {
            item.setPickupDelay(DENY_PICKUP_DELAY_TICKS);
        }

        notify(player, "protection.denied-pickup", owner);
    }

    private void onMobPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        UUID owner = protector.ownerOf(item.getUniqueId());
        if (owner == null) return;
        if (!protect(null, "block-mob-pickup")) return;
        event.setCancelled(true);
    }

    // --------------------------------------------------------------
    // Hoppers
    // --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        UUID owner = protector.ownerOf(item.getUniqueId());
        if (owner == null) return;
        if (!protect(null, "block-hoppers")) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMinecart(org.bukkit.event.vehicle.VehicleEntityCollisionEvent event) {
        if (!(event.getVehicle() instanceof HopperMinecart)) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Item)) return;
        if (protector.ownerOf(entity.getUniqueId()) == null) return;
        if (!protect(null, "block-hoppers")) return;
        event.setCancelled(true);
    }

    // --------------------------------------------------------------
    // Stack merging: the ownership mark must survive
    // --------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        Item target = event.getTarget();
        Item source = event.getEntity();
        if (target == null || source == null) return;

        UUID targetOwner = protector.ownerOf(target.getUniqueId());
        UUID sourceOwner = protector.ownerOf(source.getUniqueId());
        if (targetOwner == null && sourceOwner == null) return;

        UUID owner = targetOwner != null ? targetOwner : sourceOwner;
        long expire = Math.max(expireOf(target.getUniqueId()), expireOf(source.getUniqueId()));
        protector.unregister(source.getUniqueId());
        protector.register(target.getUniqueId(), owner, expire);
    }

    private long expireOf(UUID itemId) {
        // DropProtector keeps the deadline internally; for merging we take the later one
        Long value = protector.expireAt(itemId);
        return value == null ? System.currentTimeMillis() : value;
    }

    // --------------------------------------------------------------
    // Explosions must not destroy protected loot
    // --------------------------------------------------------------

    /**
     * Explosions, fire and lava must not destroy protected loot.
     * Handled by {@link #onItemDamage(EntityDamageEvent)}.
     */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item)) return;
        if (protector.ownerOf(event.getEntity().getUniqueId()) == null) return;
        if (!protect(null, "cancel-explosion-loss")) return;
        if (event instanceof EntityDamageByEntityEvent || event instanceof EntityDamageByBlockEvent
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------------------
    // Despawn: forget the record to avoid garbage
    // --------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        if (item != null) protector.unregister(item.getUniqueId());
    }

    // --------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------

    private boolean protect(Player player, String flag) {
        ru.silentdrop.config.Settings cfg = holder.settings();
        if (cfg == null || !cfg.protectionEnabled) return false;
        switch (flag) {
            case "block-other-players":
                return cfg.protectionBlockOtherPlayers;
            case "block-mob-pickup":
                return cfg.protectionBlockMobs;
            case "block-hoppers":
                return cfg.protectionBlockHoppers;
            case "cancel-explosion-loss":
                return cfg.protectionCancelExplosionLoss;
            default:
                return true;
        }
    }

    private void notify(Player player, String key, UUID owner) {
        Messages messages = holder.messages();
        if (messages == null || player == null) return;
        long now = System.currentTimeMillis();
        Long last = lastNotify.get(player.getUniqueId());
        if (last != null && now - last < notifyCooldownMillis) return;
        lastNotify.put(player.getUniqueId(), now);

        String ownerName = null;
        Player ownerPlayer = player.getServer().getPlayer(owner);
        if (ownerPlayer != null) {
            ownerName = ownerPlayer.getName();
        } else {
            // cached lookup only - getOfflinePlayer(UUID) may hit the disk
            for (org.bukkit.OfflinePlayer cached : player.getServer().getOfflinePlayers()) {
                if (owner.equals(cached.getUniqueId())) {
                    ownerName = cached.getName();
                    break;
                }
            }
            if (ownerName == null) ownerName = owner.toString().substring(0, 8);
        }
        java.util.Map<String, String> ph = new java.util.LinkedHashMap<String, String>();
        ph.put("owner", ownerName);
        messages.send(player, key, ph);
    }

    /** Cleans the notification cache (called by the shared periodic task). */
    public int purgeNotifications() {
        if (lastNotify.isEmpty()) return 0;
        long cutoff = System.currentTimeMillis() - Math.max(60_000L, notifyCooldownMillis * 20L);
        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = lastNotify.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < cutoff) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Removes the service display name set by the distributor when the owner picks the item up. */
    private void stripOwnerTag(Item item) {
        ItemStack stack = item.getItemStack();
        if (stack == null || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        ru.silentdrop.config.Settings cfg = holder.settings();
        String format = cfg != null ? cfg.ownerTagFormat : null;
        if (format == null || format.isEmpty()) return;

        // only strip the name when it really matches our template (prefix/suffix check)
        String plain = Text.strip(format);
        String current = Text.strip(meta.getDisplayName());
        String prefix = plain.contains("%player%") ? plain.substring(0, plain.indexOf("%player%")) : "";
        String suffix = plain.contains("%item%") ? plain.substring(plain.indexOf("%item%") + "%item%".length()) : "";
        boolean looksLikeOurs = (prefix.isEmpty() || current.startsWith(prefix))
                && (suffix.isEmpty() || current.endsWith(suffix));
        if (!looksLikeOurs) return;

        meta.setDisplayName(null);
        stack.setItemMeta(meta);
        item.setItemStack(stack);
    }
}
