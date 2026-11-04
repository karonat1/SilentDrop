package ru.silentdrop.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import ru.silentdrop.util.Text;

/**
 * Plugin messages.
 *
 * The plugin is English by default. The active language is chosen by the
 * "general.language" option in config.yml ("en", "ru", "tr", ...).
 *
 * Lookup order for every key:
 *   1. plugins/SilentDrop/messages_&lt;lang&gt;.yml  (editable copy on disk)
 *   2. messages_&lt;lang&gt;.yml inside the jar       (shipped template)
 *   3. messages_en.yml inside the jar               (missing translation)
 *
 * IMPORTANT: a YamlConfiguration must never be set as its own defaults.
 * MemorySection#get() walks into getDefault() and a self-reference produces
 * infinite recursion (StackOverflowError). That is why the "en" bundle below
 * is always a separate object.
 */
public final class Messages {

    private final Plugin plugin;

    private volatile YamlConfiguration config;
    private volatile String language = "en";

    public Messages(Plugin plugin) {
        this.plugin = plugin;
        reload("en");
    }

    /** Currently active language code. */
    public String language() {
        return language;
    }

    /**
     * Reloads messages for the given language.
     *
     * @param langCode "en", "ru", "tr", ... Anything unknown falls back to "en".
     */
    public void reload(String langCode) {
        String lang = normalize(langCode);
        this.language = lang;

        migrateLegacyFile();

        // 1) English bundle from the jar. It is the base for missing keys and
        //    it is NEVER the same object as the active config (see class note).
        YamlConfiguration en = new YamlConfiguration();
        InputStream enStream = plugin.getResource("messages_en.yml");
        if (enStream != null) {
            try {
                en = YamlConfiguration.loadConfiguration(new InputStreamReader(enStream, StandardCharsets.UTF_8));
            } finally {
                close(enStream);
            }
        }

        if ("en".equals(lang)) {
            // English: disk copy wins, jar template fills the gaps.
            YamlConfiguration disk = loadDiskFile("messages_en.yml");
            if (disk == null) {
                exportTemplate("messages_en.yml");
                disk = en;
            } else {
                disk.setDefaults(en);
                disk.options().copyDefaults(true);
            }
            this.config = disk;
            return;
        }

        // 2) Target language: jar template first, then the editable copy on disk.
        YamlConfiguration langBundle = en;
        InputStream langStream = plugin.getResource("messages_" + lang + ".yml");
        if (langStream != null) {
            try {
                langBundle = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(langStream, StandardCharsets.UTF_8));
            } finally {
                close(langStream);
            }
        } else {
            plugin.getLogger().warning("[SilentDrop] No bundled language file messages_" + lang
                    + ".yml, falling back to English.");
        }

        YamlConfiguration loaded = loadDiskFile("messages_" + lang + ".yml");
        if (loaded == null) {
            if (exportTemplate("messages_" + lang + ".yml")) {
                loaded = loadDiskFile("messages_" + lang + ".yml");
            }
        }
        if (loaded == null) {
            loaded = new YamlConfiguration();
        }

        loaded.setDefaults(langBundle);
        loaded.options().copyDefaults(true);
        this.config = loaded;
    }

    /**
     * Older builds stored everything in a single messages.yml. Rename it to
     * messages_ru.yml once, so custom texts are not lost after the upgrade.
     */
    private void migrateLegacyFile() {
        File legacy = new File(plugin.getDataFolder(), "messages.yml");
        if (!legacy.exists()) return;

        File target = new File(plugin.getDataFolder(), "messages_ru.yml");
        if (target.exists()) {
            File backup = new File(plugin.getDataFolder(), "messages.yml.old");
            if (!backup.exists() && legacy.renameTo(backup)) {
                plugin.getLogger().info("[SilentDrop] Legacy messages.yml moved to messages.yml.old "
                        + "(messages_ru.yml already exists).");
            }
            return;
        }
        if (legacy.renameTo(target)) {
            plugin.getLogger().info("[SilentDrop] Legacy messages.yml renamed to messages_ru.yml. "
                    + "Set general.language: ru in config.yml to use it.");
        }
    }

    private YamlConfiguration loadDiskFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) return null;
        return YamlConfiguration.loadConfiguration(file);
    }

    /** Copies a template from the jar into the plugin folder. @return true on success. */
    private boolean exportTemplate(String name) {
        try {
            plugin.saveResource(name, false);
            return new File(plugin.getDataFolder(), name).exists();
        } catch (IllegalArgumentException ex) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().warning("[SilentDrop] Could not create " + name + ": " + t.getMessage());
            return false;
        }
    }

    private static String normalize(String langCode) {
        if (langCode == null) return "en";
        String lang = langCode.trim().toLowerCase(Locale.ROOT);
        int dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash); // "ru-RU" -> "ru"
        if (lang.isEmpty()) return "en";
        return lang;
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Access
    // ------------------------------------------------------------------

    /** Raw (uncolored) message. Empty string if the key does not exist. */
    public String raw(String key) {
        YamlConfiguration current = this.config;
        String value = current != null ? current.getString(key) : null;
        return value == null ? "" : value;
    }

    public String format(String key, Map<String, String> placeholders) {
        return Text.color(Text.apply(raw(key), placeholders));
    }

    public String format(String key) {
        return Text.color(raw(key));
    }

    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        if (to == null) return;
        String text = format(key, placeholders);
        if (text.isEmpty()) return;
        to.sendMessage(text);
    }

    public void send(CommandSender to, String key) {
        send(to, key, null);
    }

    public void actionBar(Player player, String key, Map<String, String> placeholders) {
        if (player == null) return;
        String text = format(key, placeholders);
        if (text.isEmpty()) return;
        try {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
        } catch (Throwable ignored) {
            player.sendMessage(text);
        }
    }

    public void title(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        if (player == null) return;
        String title = Text.color(Text.apply(raw(titleKey), placeholders));
        String subtitle = Text.color(Text.apply(raw(subtitleKey), placeholders));
        if (title.isEmpty() && subtitle.isEmpty()) return;
        try {
            player.sendTitle(title, subtitle, 5, 40, 10);
        } catch (Throwable t) {
            player.sendMessage(ChatColor.stripColor(title) + " " + ChatColor.stripColor(subtitle));
        }
    }
}
