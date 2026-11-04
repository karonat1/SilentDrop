package ru.silentdrop.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable rule set for a single configuration slice
 * (the default-rule section or one of the rules.* entries).
 *
 * A rule section is merged on top of default-rule beforehand (see {@link Settings}),
 * so here it is enough to read the values with hard coded defaults.
 *
 * All fields are final, the object is safe to read from any thread after creation.
 */
public final class RuleSet {

    // --- distribution ---
    public final Mode mode;
    public final int minPlayers;
    public final boolean soloKillerGetsAll;
    public final boolean splitStacks;

    // --- who counts as a participant ---
    public final boolean includeKiller;
    public final boolean includeDamagers;
    public final boolean includeAssistNoDamage;
    public final double maxDistance;
    public final boolean requireSameWorld;
    public final boolean skipSpectator;
    public final double minDamagePercent;
    public final double minDamageFlat;
    public final boolean percentOfTrackedOnly;
    public final double killCredit;
    public final Set<String> itemBlacklist;
    public final double itemChance;

    // --- inventory overflow ---
    public final OverflowAction overflow;
    public final boolean overflowOwnerTag;
    public final int overflowPickupDelay;
    public final int overflowProtectSeconds;

    // --- anti-spam ---
    public final boolean rateLimitEnabled;
    public final int rateLimitMaxDrops;
    public final int rateLimitWindowSeconds;
    public final OverflowAction rateLimitAction;

    // --- anti-farm ---
    public final boolean antiFarmEnabled;
    public final int spawnGraceSeconds;
    public final boolean requireKnownSpawnTime;
    public final boolean skipBabies;
    public final boolean skipSpawnerMobs;
    public final boolean skipSpawnerEggMobs;
    public final boolean skipNamedMobs;
    public final Set<String> skipSpawnReasons;

    // --- experience ---
    public final ExpMode expMode;

    public RuleSet(ConfigurationSection section) {
        ConfigurationSection s = section != null ? section : new YamlConfiguration();

        this.mode = Mode.parse(s.getString("distribution.mode"), Mode.DAMAGE);
        this.minPlayers = Math.max(1, s.getInt("distribution.min-players", 1));
        this.soloKillerGetsAll = s.getBoolean("distribution.solo-killer-gets-all", true);
        this.splitStacks = s.getBoolean("distribution.split-stacks", true);

        this.includeKiller = s.getBoolean("eligibility.include-killer", true);
        this.includeDamagers = s.getBoolean("eligibility.include-damagers", true);
        this.includeAssistNoDamage = s.getBoolean("eligibility.include-assist-without-damage", false);
        this.maxDistance = Math.max(0.0, s.getDouble("eligibility.max-distance", 48.0));
        this.requireSameWorld = s.getBoolean("eligibility.require-same-world", true);
        this.skipSpectator = s.getBoolean("eligibility.skip-spectator", true);
        this.minDamagePercent = Math.max(0.0, s.getDouble("eligibility.min-damage-percent", 0.0));
        this.minDamageFlat = Math.max(0.0, s.getDouble("eligibility.min-damage-flat", 0.0));
        this.percentOfTrackedOnly = s.getBoolean("eligibility.min-damage-percent-of-tracked-only", true);
        this.killCredit = Math.max(0.0, s.getDouble("eligibility.kill-credit", 0.0));
        this.itemBlacklist = upperSet(s.getStringList("eligibility.item-blacklist"));
        this.itemChance = clamp01(s.getDouble("eligibility.item-chance", 1.0));

        this.overflow = OverflowAction.parse(s.getString("overflow.action"), OverflowAction.VAULT);
        this.overflowOwnerTag = s.getBoolean("overflow.owner-tag-name", true);
        this.overflowPickupDelay = Math.max(0, s.getInt("overflow.pickup-delay-ticks", 40));
        this.overflowProtectSeconds = Math.max(0, s.getInt("overflow.protect-seconds", 120));

        this.rateLimitEnabled = s.getBoolean("anti-spam.rate-limit.enabled", true);
        this.rateLimitMaxDrops = Math.max(1, s.getInt("anti-spam.rate-limit.max-distributions", 30));
        this.rateLimitWindowSeconds = Math.max(1, s.getInt("anti-spam.rate-limit.window-seconds", 10));
        this.rateLimitAction = OverflowAction.parse(s.getString("anti-spam.rate-limit.action"), OverflowAction.VAULT);

        this.antiFarmEnabled = s.getBoolean("anti-farm.enabled", true);
        this.spawnGraceSeconds = Math.max(0, s.getInt("anti-farm.spawn-grace-seconds", 10));
        this.requireKnownSpawnTime = s.getBoolean("anti-farm.require-known-spawn-time", false);
        this.skipBabies = s.getBoolean("anti-farm.skip-babies", true);
        this.skipSpawnerMobs = s.getBoolean("anti-farm.skip-spawner-mobs", false);
        this.skipSpawnerEggMobs = s.getBoolean("anti-farm.skip-spawner-egg-mobs", false);
        this.skipNamedMobs = s.getBoolean("anti-farm.skip-named-mobs", false);
        this.skipSpawnReasons = upperSet(s.getStringList("anti-farm.skip-spawn-reasons"));

        this.expMode = ExpMode.parse(s.getString("experience.mode"), ExpMode.SPLIT);
    }

    private static Set<String> upperSet(List<String> in) {
        Set<String> out = new LinkedHashSet<String>();
        if (in != null) {
            for (String raw : in) {
                if (raw == null) continue;
                String key = raw.trim().toUpperCase(Locale.ROOT);
                if (!key.isEmpty()) out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 1.0;
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
