package ru.silentdrop.listener;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

import ru.silentdrop.config.Settings;
import ru.silentdrop.loot.LootDistributor;
import ru.silentdrop.track.DamageTracker;

/**
 * Intercepts mob deaths.
 *
 * Priority LOWEST: we take the drops first, so other loot plugins
 * (LootPlugins, DropParty, ...) see an already empty list and cannot
 * duplicate the items. Conflict monitoring lives in the distributor itself.
 */
public final class DeathListener implements Listener {

    private final LootDistributor distributor;
    private final DamageTracker tracker;

    public DeathListener(LootDistributor distributor, DamageTracker tracker) {
        this.distributor = distributor;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Settings cfg = distributor.settings();
        if (cfg == null || !cfg.enabled) return;

        Entity entity = event.getEntity();
        if (entity == null || entity instanceof Player) return;
        if (!(entity instanceof LivingEntity)) return;

        distributor.onEntityDeath(event);

        // spawn records are no longer needed
        tracker.clear(entity.getUniqueId());
    }

    /** Debug helper: what is left in the world after the death. */
    public static String describeDrops(List<ItemStack> drops) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack s : drops) {
            if (s == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.getType()).append(" x").append(s.getAmount());
        }
        return sb.length() == 0 ? "<empty>" : sb.toString();
    }
}
