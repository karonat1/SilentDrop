package ru.silentdrop;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;

import ru.silentdrop.command.SilentDropCommand;
import ru.silentdrop.config.ConfigHolder;
import ru.silentdrop.config.Messages;
import ru.silentdrop.config.Settings;
import ru.silentdrop.listener.CombatListener;
import ru.silentdrop.listener.DeathListener;
import ru.silentdrop.listener.ItemOwnershipListener;
import ru.silentdrop.loot.DropProtector;
import ru.silentdrop.loot.LootDistributor;
import ru.silentdrop.track.DamageTracker;
import ru.silentdrop.vault.LootVault;
import ru.silentdrop.vault.VaultGui;

/**
 * SilentDrop - smart loot system with anti-spam and conflict protection.
 *
 * Runs on Spigot/Paper/Purpur 1.16.5 - 1.20.1 without NMS and without external dependencies.
 */
public final class SilentDropPlugin extends JavaPlugin {

    /** How long mob spawn records are kept (anti-farm), in millis. */
    private static final long SPAWN_RECORD_TTL_MILLIS = 10L * 60L * 1000L;

    private ConfigHolder holder;
    private Messages messages;
    private DamageTracker tracker;
    private LootVault vault;
    private DropProtector protector;
    private LootDistributor distributor;
    private VaultGui vaultGui;
    private ItemOwnershipListener ownershipListener;

    private int budgetTaskId = -1;
    private int purgeTaskId = -1;
    private int saveTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messages = new Messages(this);
        this.holder = new ConfigHolder();
        this.tracker = new DamageTracker();
        this.vault = new LootVault(this);
        this.protector = new DropProtector(this);
        this.distributor = new LootDistributor(this, tracker, vault, protector);
        this.vaultGui = new VaultGui(this, vault, holder);

        reloadAll();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new DeathListener(distributor, tracker), this);
        pm.registerEvents(new CombatListener(tracker, holder), this);
        this.ownershipListener = new ItemOwnershipListener(protector, holder);
        pm.registerEvents(ownershipListener, this);
        pm.registerEvents(vaultGui, this);

        SilentDropCommand executor = new SilentDropCommand(this);
        PluginCommand command = getCommand("silentdrop");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("Command /silentdrop is missing in plugin.yml - check the jar integrity.");
        }

        // reset the global per-tick distribution budget (TPS protection)
        budgetTaskId = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                distributor.resetTickBudget();
            }
        }, 1L, 1L).getTaskId();

        // purge stale tracker records, anti-spam windows and expired drop protection
        purgeTaskId = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                int spawns = tracker.purge(SPAWN_RECORD_TTL_MILLIS);
                int rate = distributor.purgeRateWindows();
                int drops = protector.tick();
                ownershipListener.purgeNotifications();
                if (settings().debug && (spawns + rate + drops) > 0) {
                    getLogger().info("[SilentDrop][debug] purge: spawns=" + spawns
                            + " rateWindows=" + rate + " expiredDrops=" + drops);
                }
            }
        }, 100L, 100L).getTaskId();

        // vault autosave
        saveTaskId = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                vault.saveIfDirty();
                if (settings().vaultExpireDays > 0 && vault.purgeExpired() > 0) {
                    vault.save();
                }
            }
        }, 600L, 600L).getTaskId();

        registerMetrics();

        getLogger().info("SilentDrop v" + getDescription().getVersion() + " enabled. Language: "
                + settings().language + ", distribution rules: " + settings().rules.size()
                + ", default mode: " + settings().defaultRule.mode + ".");
    }

    @Override
    public void onDisable() {
        cancel(budgetTaskId);
        cancel(purgeTaskId);
        cancel(saveTaskId);

        if (vault != null) {
            vault.save();
            getLogger().info("Loot vault saved (" + vault.totalSize() + " entries).");
        }
        if (protector != null) protector.clear();
    }

    private void cancel(int taskId) {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
        }
    }

    /**
     * Reloads config.yml and the message bundle, rebuilds the rule cache.
     *
     * @return number of named rules
     */
    public int reloadAll() {
        Settings settings = new Settings(this);
        messages.reload(settings.language);
        holder.update(settings, messages);

        tracker.updateLimits(settings.trackerWindowSeconds, settings.trackerMaxContributors);
        tracker.setEnabled(settings.enabled);
        vault.configure(settings.vaultMaxEntries, settings.vaultExpireDays);
        vault.load();
        protector.setVault(settings.vaultEnabled ? vault : null, settings.vaultEnabled);
        distributor.configure(settings, messages);
        settings.clearCache();

        return settings.rules.size();
    }

    /** bStats through reflection: no dependency means no metrics, nothing breaks. */
    private void registerMetrics() {
        Settings cfg = settings();
        if (cfg == null || !cfg.bStats) return;
        try {
            Class<?> metricsClass = Class.forName("org.bstats.bukkit.Metrics");
            Constructor<?> constructor = metricsClass.getConstructor(org.bukkit.plugin.Plugin.class, int.class);
            constructor.newInstance(this, 21465);
        } catch (Throwable ignored) {
            // bStats is missing or disabled - not critical
        }
    }

    // ------------------------------------------------------------------

    public Settings settings() {
        return holder != null ? holder.settings() : null;
    }

    public Messages messages() {
        return messages;
    }

    public ConfigHolder holder() {
        return holder;
    }

    public DamageTracker tracker() {
        return tracker;
    }

    public LootVault vault() {
        return vault;
    }

    public DropProtector protector() {
        return protector;
    }

    public LootDistributor distributor() {
        return distributor;
    }

    public VaultGui vaultGui() {
        return vaultGui;
    }
}
