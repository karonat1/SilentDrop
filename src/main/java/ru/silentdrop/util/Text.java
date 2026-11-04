package ru.silentdrop.util;

import org.bukkit.ChatColor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helpers: colors, placeholders, readable names.
 */
public final class Text {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private Text() {
    }

    /** & codes plus &#RRGGBB (hex works on 1.16+). */
    public static String color(String input) {
        if (input == null || input.isEmpty()) return "";
        Matcher m = HEX.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            String replacement = net.md_5.bungee.api.ChatColor.of("#" + hex).toString();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static String strip(String input) {
        return input == null ? "" : ChatColor.stripColor(color(input));
    }

    /** Replaces %key% with value. */
    public static String apply(String input, Map<String, String> placeholders) {
        if (input == null) return "";
        String out = input;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    /** ZOMBIE_PIGMAN -> "Zombie Pigman" (for logs and GUI). */
    public static String humanize(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "?";
        String[] parts = enumName.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

}
