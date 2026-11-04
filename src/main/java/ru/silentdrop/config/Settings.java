package ru.silentdrop.config;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full snapshot of the plugin configuration. Rebuilt on every /sd reload and
 * never modified afterwards (thread safe).
 */
public final class Settings {

    /** Global options. */
    public final boolean enabled;
    public final boolean debug;
    public final boolean bStats;
    public final String prefix;
    /** Message language: "en" (default), "ru", "tr" or any bundled messages_<lang>.yml. */
    public final String language;

    /** Worlds. */
    public final boolean worldsWhitelistMode;
    public final Set<String> worlds;

    /** Entity types that are never processed. */
    public final Set<String> entityBlacklist;

    /** Rules in declaration order. The first match wins. */
    public final List<RuleEntry> rules;
    public final RuleSet defaultRule;

    /** Loot vault (/sd vault). */
    public final boolean vaultEnabled;
    public final int vaultMaxEntries;
    public final int vaultExpireDays;
    public final boolean vaultMessageOnStore;

    /** Protection of dropped (overflow) loot. */
    public final boolean protectionEnabled;
    public final int protectionSeconds;
    public final boolean protectionBlockHoppers;
    public final boolean protectionBlockMobs;
    public final boolean protectionBlockOtherPlayers;
    public final boolean protectionCancelExplosionLoss;

    /** Global limits against lag and spam. */
    public final int globalPerTickBudget;
    public final boolean skipIfDropsTampered;
    public final int trackerWindowSeconds;
    public final int trackerMaxContributors;
    public final boolean trackSpawns;
    public final boolean trackOnlyPlayerCausedSpawns;

    /** Misc. */
    public final boolean soundEnabled;
    public final String soundName;
    public final float soundVolume;
    public final float soundPitch;
    public final boolean actionBarEnabled;
    public final boolean chatEnabled;
    public final boolean titleEnabled;
    public final String titleText;
    public final String ownerTagFormat;
    public final boolean permissionMultiplierEnabled;
    public final String permissionMultiplierNode;
    public final double permissionMultiplierCap;
    public final boolean bypassSkipsDistribution;

    private final Map<String, RuleSet> resolveCache =
            Collections.synchronizedMap(new LinkedHashMap<String, RuleSet>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RuleSet> eldest) {
                    return size() > 512;
                }
            });

    public Settings(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        YamlConfiguration c = (YamlConfiguration) plugin.getConfig();

        this.enabled = c.getBoolean("general.enabled", true);
        this.debug = c.getBoolean("general.debug", false);
        this.bStats = c.getBoolean("general.bstats", true);
        this.prefix = c.getString("general.prefix", "&8[&bSilentDrop&8]&r ");
        this.language = c.getString("general.language", "en") == null
                ? "en" : c.getString("general.language", "en").trim().toLowerCase(java.util.Locale.ROOT);

        this.worldsWhitelistMode = "whitelist".equalsIgnoreCase(c.getString("worlds.mode", "blacklist"));
        this.worlds = lowerSet(c.getStringList("worlds.list"));

        this.entityBlacklist = upperSet(c.getStringList("blacklist.entities"));

        RuleSet def = new RuleSet(c.getConfigurationSection("default-rule"));
        this.defaultRule = def;

        List<RuleEntry> parsed = new ArrayList<RuleEntry>();
        ConfigurationSection rulesSection = c.getConfigurationSection("rules");
        if (rulesSection != null) {
            for (String key : rulesSection.getKeys(false)) {
                ConfigurationSection rs = rulesSection.getConfigurationSection(key);
                if (rs == null) continue;
                RuleEntry entry = RuleEntry.parse(key, rs, def, plugin);
                if (entry != null) parsed.add(entry);
            }
        }
        this.rules = Collections.unmodifiableList(parsed);

        this.vaultEnabled = c.getBoolean("vault.enabled", true);
        this.vaultMaxEntries = Math.max(1, c.getInt("vault.max-entries-per-player", 54));
        this.vaultExpireDays = Math.max(0, c.getInt("vault.expire-after-days", 0));
        this.vaultMessageOnStore = c.getBoolean("vault.notify-on-store", true);

        this.protectionEnabled = c.getBoolean("drop-protection.enabled", true);
        this.protectionSeconds = Math.max(0, c.getInt("drop-protection.protect-seconds", 120));
        this.protectionBlockHoppers = c.getBoolean("drop-protection.block-hoppers", true);
        this.protectionBlockMobs = c.getBoolean("drop-protection.block-mob-pickup", true);
        this.protectionBlockOtherPlayers = c.getBoolean("drop-protection.block-other-players", true);
        this.protectionCancelExplosionLoss = c.getBoolean("drop-protection.cancel-explosion-loss", true);

        this.globalPerTickBudget = Math.max(1, c.getInt("limits.global-distributions-per-tick", 64));
        this.skipIfDropsTampered = c.getBoolean("limits.skip-if-drops-tampered", true);
        this.trackerWindowSeconds = Math.max(1, c.getInt("tracker.damage-window-seconds", 15));
        this.trackerMaxContributors = Math.max(1, c.getInt("tracker.max-contributors-per-mob", 24));
        this.trackSpawns = c.getBoolean("tracker.track-spawns", true);
        this.trackOnlyPlayerCausedSpawns = c.getBoolean("tracker.track-only-player-caused-spawns", false);

        this.soundEnabled = c.getBoolean("feedback.sound.enabled", true);
        this.soundName = c.getString("feedback.sound.name", "ENTITY_ITEM_PICKUP");
        this.soundVolume = (float) c.getDouble("feedback.sound.volume", 0.35);
        this.soundPitch = (float) c.getDouble("feedback.sound.pitch", 1.6);
        this.actionBarEnabled = c.getBoolean("feedback.action-bar.enabled", true);
        this.chatEnabled = c.getBoolean("feedback.chat.enabled", false);
        this.titleEnabled = c.getBoolean("feedback.title.enabled", false);
        this.titleText = c.getString("feedback.title.text", "");
        this.ownerTagFormat = c.getString("overflow-display-name-format", "&7[&e%player%&7]&r &o%item%");

        this.permissionMultiplierEnabled = c.getBoolean("share.permission-multiplier.enabled", true);
        this.permissionMultiplierNode = c.getString("share.permission-multiplier.node", "silentdrop.multiplier");
        this.permissionMultiplierCap = Math.max(1.0, c.getDouble("share.permission-multiplier.cap", 4.0));
        this.bypassSkipsDistribution = c.getBoolean("share.bypass-skips-distribution", true);
    }

    /**
     * Picks the rule for a concrete death.
     *
     * @return the rule, or {@code null} when the loot must stay vanilla.
     */
    public RuleSet ruleFor(org.bukkit.Location location, World world, EntityType type) {
        if (!enabled) return null;
        if (type == null) return null;

        String worldName = world != null ? world.getName() : "";
        boolean worldAllowed = worldsWhitelistMode ? worlds.contains(worldName.toLowerCase(Locale.ROOT))
                : !worlds.contains(worldName.toLowerCase(Locale.ROOT));
        if (!worldAllowed) return null;

        String typeName = type.name();
        if (entityBlacklist.contains(typeName)) return null;

        String cacheKey = worldName + "|" + typeName;
        RuleSet cached = resolveCache.get(cacheKey);
        if (cached != null) {
            // the DISABLED sentinel is cached when no rule matched
            return cached == DISABLED ? null : cached;
        }

        RuleSet found = null;
        for (RuleEntry entry : rules) {
            if (entry.matches(location, world, type)) {
                found = entry.rule;
                break;
            }
        }
        if (found == null && defaultRule != null && !entityBlacklist.contains(typeName)) {
            found = defaultRule;
        }
        resolveCache.put(cacheKey, found == null ? DISABLED : found);
        return found;
    }

    public void clearCache() {
        resolveCache.clear();
    }

    private static Set<String> upperSet(List<String> in) {
        Set<String> out = new LinkedHashSet<String>();
        if (in != null) {
            for (String raw : in) {
                if (raw == null) continue;
                String key = raw.trim().toUpperCase(Locale.ROOT);
                int colon = key.indexOf(':');
                if (colon >= 0) key = key.substring(colon + 1);
                if (!key.isEmpty()) out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Set<String> lowerSet(List<String> in) {
        Set<String> out = new LinkedHashSet<String>();
        if (in != null) {
            for (String raw : in) {
                if (raw == null) continue;
                String key = raw.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Sentinel for negative caching ("no rule for this world + entity pair").
     * Compared by reference (==) against real rules.
     */
    static final RuleSet DISABLED = new RuleSet(new YamlConfiguration());

    /** A single named rule from the rules.* section. */
    public static final class RuleEntry {
        public final String key;
        public final Set<String> entities;
        public final boolean entitiesInverted;
        public final Set<String> worlds;
        public final boolean worldsInverted;
        public final List<Region> regions;
        public final RuleSet rule;

        RuleEntry(String key, Set<String> entities, boolean entitiesInverted,
                  Set<String> worlds, boolean worldsInverted,
                  List<Region> regions, RuleSet rule) {
            this.key = key;
            this.entities = entities;
            this.entitiesInverted = entitiesInverted;
            this.worlds = worlds;
            this.worldsInverted = worldsInverted;
            this.regions = regions;
            this.rule = rule;
        }

        static RuleEntry parse(String key, ConfigurationSection rs, RuleSet parent, Plugin plugin) {
            Set<String> entities = upperSet(rs.getStringList("entities"));
            boolean entitiesInverted = rs.getBoolean("entities-inverted", false);
            Set<String> worlds = lowerSet(rs.getStringList("worlds"));
            boolean worldsInverted = rs.getBoolean("worlds-inverted", false);

            List<Region> regions = new ArrayList<Region>();
            ConfigurationSection regs = rs.getConfigurationSection("regions");
            if (regs != null) {
                for (String rKey : regs.getKeys(false)) {
                    ConfigurationSection r = regs.getConfigurationSection(rKey);
                    if (r == null) continue;
                    World w = Bukkit.getWorld(r.getString("world", ""));
                    if (w == null) {
                        plugin.getLogger().warning("[SilentDrop] rules." + key + ".regions." + rKey
                                + ": world '" + r.getString("world", "") + "' not found, region skipped.");
                        continue;
                    }
                    regions.add(new Region(w,
                            r.getDouble("center-x", 0), r.getDouble("center-y", 128), r.getDouble("center-z", 0),
                            r.getDouble("radius-x", 0), r.getDouble("radius-y", 0), r.getDouble("radius-z", 0)));
                }
            }

            // merge the rule on top of default-rule: every unset key is inherited
            YamlConfiguration merged = new YamlConfiguration();
            copyInto(parentSection(plugin, "default-rule"), merged);
            copyInto(rs, merged);

            return new RuleEntry(key, entities, entitiesInverted, worlds, worldsInverted, regions, new RuleSet(merged));
        }

        private static ConfigurationSection parentSection(Plugin plugin, String path) {
            ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
            return s != null ? s : new YamlConfiguration();
        }

        @SuppressWarnings("unchecked")
        private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
            if (from == null) return;
            for (String k : from.getKeys(true)) {
                if (from.isConfigurationSection(k)) continue;
                Object v = from.get(k);
                if (v instanceof List) v = new ArrayList<Object>((List<Object>) v);
                to.set(k, v);
            }
        }

        boolean matches(org.bukkit.Location location, World world, EntityType type) {
            if (!entities.isEmpty()) {
                boolean hit = entities.contains(type.name());
                if (entitiesInverted ? hit : !hit) return false;
            } else if (entitiesInverted) {
                return false;
            }

            if (!worlds.isEmpty()) {
                String name = world != null ? world.getName().toLowerCase(Locale.ROOT) : "";
                boolean hit = worlds.contains(name);
                if (worldsInverted ? hit : !hit) return false;
            } else if (worldsInverted) {
                return false;
            }

            if (!regions.isEmpty()) {
                // regions are an ADDITIONAL constraint: the death must land in at least one of them
                boolean any = false;
                for (Region r : regions) {
                    if (r.contains(location, world)) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
            }
            return true;
        }
    }

    /** Box region (no WorldGuard, so no extra dependency). */
    public static final class Region {
        final World world;
        final double cx, cy, cz, rx, ry, rz;

        Region(World world, double cx, double cy, double cz, double rx, double ry, double rz) {
            this.world = world;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.rx = Math.abs(rx);
            this.ry = Math.abs(ry);
            this.rz = Math.abs(rz);
        }

        boolean sameWorld(World w) {
            return world != null && w != null && world.getUID().equals(w.getUID());
        }

        /**
         * Whether the death point is inside the region. When the location is unknown
         * (loc == null) a matching world is enough, so the rule never silently disappears.
         */
        boolean contains(org.bukkit.Location loc, World w) {
            if (!sameWorld(w)) return false;
            if (loc == null) return true;
            return Math.abs(loc.getX() - cx) <= rx
                    && Math.abs(loc.getY() - cy) <= ry
                    && Math.abs(loc.getZ() - cz) <= rz;
        }
    }
}
