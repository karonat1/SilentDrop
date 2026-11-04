package ru.silentdrop.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Locale;

import ru.silentdrop.config.ConfigHolder;
import ru.silentdrop.config.Settings;
import ru.silentdrop.track.DamageTracker;

/**
 * Tracks player contribution (who dealt how much damage) and mob spawn times.
 */
public final class CombatListener implements Listener {

    private final DamageTracker tracker;
    private final ConfigHolder holder;

    public CombatListener(DamageTracker tracker, ConfigHolder holder) {
        this.tracker = tracker;
        this.holder = holder;
    }

    private Settings cfg() {
        return holder != null ? holder.settings() : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Settings cfg = cfg();
        if (cfg == null || !cfg.enabled) return;

        Entity victim = event.getEntity();
        if (victim == null || victim instanceof Player) return;
        if (!(victim instanceof org.bukkit.entity.LivingEntity)) return;

        tracker.recordDamage(victim, event.getDamager(), event.getDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Settings cfg = cfg();
        if (cfg == null || !cfg.enabled || !cfg.trackSpawns) return;

        Entity entity = event.getEntity();
        if (entity == null || entity instanceof Player) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (cfg.trackOnlyPlayerCausedSpawns && !isPlayerCaused(reason)) return;

        tracker.recordSpawn(entity, reason);
    }

    private static boolean isPlayerCaused(CreatureSpawnEvent.SpawnReason reason) {
        if (reason == null) return false;
        switch (reason.name().toUpperCase(Locale.ROOT)) {
            case "SPAWNER":
            case "SPAWNER_EGG":
            case "EGG":
            case "DISPENSE_EGG":
            case "BUILD_WITHER":
            case "BUILD_IRONGOLEM":
            case "BUILD_SNOWMAN":
            case "COMMAND":
            case "CUSTOM":
                return true;
            default:
                return false;
        }
    }
}
