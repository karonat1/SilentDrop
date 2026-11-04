package ru.silentdrop.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.silentdrop.SilentDropPlugin;
import ru.silentdrop.config.Messages;
import ru.silentdrop.config.RuleSet;
import ru.silentdrop.loot.AllocatedStack;
import ru.silentdrop.loot.Contributor;
import ru.silentdrop.loot.LootDistributor;
import ru.silentdrop.util.Text;

/**
 * /silentdrop (aliases: /sd, /sdrop)
 *
 * reload - reload config and messages
 * test   - calculate the distribution for the mob under the crosshair (gives NOTHING)
 * stats  - counters and diagnostics
 * vault  - open the loot vault
 * give   - give an item to a player through the same code path as real loot
 * clear  - clear a vault
 */
public final class SilentDropCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            Arrays.asList("reload", "test", "stats", "vault", "give", "clear", "help");

    private final SilentDropPlugin plugin;

    public SilentDropCommand(SilentDropPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.messages();
        if (msg == null) return true;

        if (args.length == 0) {
            msg.send(sender, "usage");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) return reload(sender, msg);
        if (sub.equals("test")) return test(sender, msg, args);
        if (sub.equals("stats")) return stats(sender, msg);
        if (sub.equals("vault")) return vault(sender, msg, args);
        if (sub.equals("give")) return give(sender, msg, args);
        if (sub.equals("clear")) return clear(sender, msg, args);
        if (sub.equals("help")) {
            msg.send(sender, "usage");
            return true;
        }

        msg.send(sender, "usage");
        return true;
    }

    // ------------------------------------------------------------------

    private boolean reload(CommandSender sender, Messages msg) {
        if (!sender.hasPermission("silentdrop.admin")) {
            msg.send(sender, "no-permission");
            return true;
        }
        long start = System.currentTimeMillis();
        int rules = plugin.reloadAll();
        Map<String, String> ph = new LinkedHashMap<String, String>();
        ph.put("ms", String.valueOf(System.currentTimeMillis() - start));
        ph.put("rules", String.valueOf(rules));
        ph.put("language", plugin.messages() != null ? plugin.messages().language() : "en");
        msg.send(sender, "reload-ok", ph);
        return true;
    }

    private boolean stats(CommandSender sender, Messages msg) {
        if (!sender.hasPermission("silentdrop.admin")) {
            msg.send(sender, "no-permission");
            return true;
        }
        msg.send(sender, "stats.header");
        for (Map.Entry<String, String> e : plugin.distributor().statsSnapshot().entrySet()) {
            Map<String, String> ph = new LinkedHashMap<String, String>();
            ph.put("key", e.getKey());
            ph.put("value", e.getValue());
            msg.send(sender, "stats.line", ph);
        }
        return true;
    }

    private boolean vault(CommandSender sender, Messages msg, String[] args) {
        if (args.length >= 2 && sender.hasPermission("silentdrop.admin")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg.send(sender, "vault.player-offline", single("player", args[1]));
                return true;
            }
            plugin.vaultGui().open(target, 1);
            return true;
        }

        if (!(sender instanceof Player)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (!sender.hasPermission("silentdrop.use")) {
            msg.send(sender, "no-permission");
            return true;
        }
        Player player = (Player) sender;
        if (plugin.vault().isEmpty(player.getUniqueId())) {
            msg.send(player, "vault.empty");
            return true;
        }
        plugin.vaultGui().open(player, 1);
        return true;
    }

    private boolean clear(CommandSender sender, Messages msg, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("silentdrop.admin")) {
                msg.send(sender, "no-permission");
                return true;
            }
            if (args[1].equalsIgnoreCase("all")) {
                int total = 0;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    total += plugin.vault().size(online.getUniqueId());
                    plugin.vault().clear(online.getUniqueId());
                }
                msg.send(sender, "clear.ok", pair("player", "ALL", "count", String.valueOf(total)));
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            if (!(sender instanceof Player)) {
                msg.send(sender, "clear.usage");
                return true;
            }
            target = (Player) sender;
        }

        if (target == null) {
            msg.send(sender, "clear.usage");
            return true;
        }
        int count = plugin.vault().size(target.getUniqueId());
        plugin.vault().clear(target.getUniqueId());
        msg.send(sender, "clear.ok", pair("player", target.getName(), "count", String.valueOf(count)));
        return true;
    }

    /**
     * /sd give <player> <MATERIAL> [amount]
     * Goes through the same giving code as real loot, which makes it handy for
     * testing the overflow behaviour (vault / drop / void) with a full inventory.
     */
    private boolean give(CommandSender sender, Messages msg, String[] args) {
        if (!sender.hasPermission("silentdrop.admin")) {
            msg.send(sender, "no-permission");
            return true;
        }
        if (args.length < 3) {
            msg.send(sender, "give.usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.send(sender, "give.player-offline", single("player", args[1]));
            return true;
        }
        Material material = Material.matchMaterial(args[2]);
        if (material == null || !material.isItem()) {
            msg.send(sender, "give.bad-material", single("material", args[2]));
            return true;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                msg.send(sender, "give.usage");
                return true;
            }
        }

        List<ItemStack> items = new ArrayList<ItemStack>();
        int left = amount;
        int max = material.getMaxStackSize() > 0 ? material.getMaxStackSize() : 64;
        while (left > 0) {
            int take = Math.min(max, left);
            items.add(new ItemStack(material, take));
            left -= take;
        }

        LootDistributor distributor = plugin.distributor();
        RuleSet rule = distributor.settings() != null ? distributor.settings().defaultRule : null;
        Map<Integer, ItemStack> rest = distributor.giveItems(target, items);

        if (!rest.isEmpty() && rule != null) {
            LootDistributor.Ctx ctx = new LootDistributor.Ctx(distributor.settings(), rule,
                    target.getLocation(), target.getWorld(), target.getType(), "give");
            distributor.handleOverflowPublic(target, ctx, rule, new ArrayList<ItemStack>(rest.values()),
                    rule.overflow, "command /sd give");
        }

        Map<String, String> ph = new LinkedHashMap<String, String>();
        ph.put("player", target.getName());
        ph.put("item", material.name());
        ph.put("count", String.valueOf(amount));
        msg.send(sender, "give.ok", ph);
        return true;
    }

    /**
     * /sd test [MATERIAL:COUNT ...]
     *
     * Shows how the loot of the mob under the crosshair would be distributed.
     * Nothing is given and nothing dies: pure arithmetic for config verification.
     */
    private boolean test(CommandSender sender, Messages msg, String[] args) {
        if (!(sender instanceof Player)) {
            msg.send(sender, "players-only");
            return true;
        }
        if (!sender.hasPermission("silentdrop.admin")) {
            msg.send(sender, "no-permission");
            return true;
        }
        Player player = (Player) sender;

        LivingEntity target = findTarget(player);
        if (target == null) {
            msg.send(player, "test.no-entity");
            return true;
        }

        List<ItemStack> sample = new ArrayList<ItemStack>();
        for (int i = 1; i < args.length; i++) {
            ItemStack parsed = parseStack(args[i]);
            if (parsed != null) sample.add(parsed);
        }
        if (sample.isEmpty()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getType() != Material.AIR) {
                sample.add(hand.clone());
            } else {
                sample.add(new ItemStack(Material.ROTTEN_FLESH, 3));
                sample.add(new ItemStack(Material.GOLD_NUGGET, 1));
                msg.send(player, "test.sample-hint");
            }
        }

        LootDistributor.Report report = plugin.distributor().simulate(player, target, sample);
        if (report == null) {
            msg.send(player, "test.no-rule", single("mob", LootDistributor.mobName(target)));
            return true;
        }

        RuleSet rule = plugin.distributor().settings().ruleFor(target.getLocation(), target.getWorld(), target.getType());
        double killCredit = rule != null ? rule.killCredit : 0.0;

        msg.send(player, "test.header");

        Map<String, String> mobPh = new LinkedHashMap<String, String>();
        mobPh.put("mob", LootDistributor.mobName(target));
        mobPh.put("type", target.getType().name());
        msg.send(player, "test.mob", mobPh);

        Map<String, String> locPh = new LinkedHashMap<String, String>();
        locPh.put("world", target.getWorld().getName());
        locPh.put("x", fmt(target.getLocation().getX()));
        locPh.put("y", fmt(target.getLocation().getY()));
        locPh.put("z", fmt(target.getLocation().getZ()));
        msg.send(player, "test.location", locPh);

        if (rule != null) {
            Map<String, String> rulePh = new LinkedHashMap<String, String>();
            rulePh.put("mode", String.valueOf(rule.mode));
            rulePh.put("min", String.valueOf(rule.minPlayers));
            rulePh.put("split", String.valueOf(rule.splitStacks));
            rulePh.put("credit", String.format(Locale.ROOT, "%.1f", rule.killCredit));
            msg.send(player, "test.rule-line", rulePh);

            Map<String, String> rule2 = new LinkedHashMap<String, String>();
            rule2.put("antifarm", rule.antiFarmEnabled ? "on" : "off");
            rule2.put("overflow", String.valueOf(rule.overflow));
            rule2.put("ratelimit", rule.rateLimitEnabled
                    ? rule.rateLimitMaxDrops + "/" + rule.rateLimitWindowSeconds + "s"
                    : "off");
            msg.send(player, "test.rule-line-2", rule2);

            String farm = plugin.distributor().farmBlockReason(target, rule, player);
            if (farm != null) {
                msg.send(player, "test.antifarm-blocked", single("reason", farm));
            }
        }

        double totalWeight = 0.0;
        for (Contributor c : report.eligible) {
            totalWeight += Math.max(0.0, c.weight(killCredit));
        }

        Map<String, String> sumPh = new LinkedHashMap<String, String>();
        sumPh.put("players", String.valueOf(report.eligible.size()));
        sumPh.put("units", String.valueOf(report.plan != null ? report.plan.totalUnits() : 0));
        sumPh.put("weight", String.format(Locale.ROOT, "%.2f", totalWeight));
        msg.send(player, "test.summary", sumPh);

        if (report.plan != null) {
            for (Contributor c : report.sorted(killCredit)) {
                double weight = Math.max(0.0, c.weight(killCredit));
                double percent = totalWeight > 0.0 ? weight / totalWeight * 100.0 : 0.0;

                List<AllocatedStack> share = report.plan.forPlayer(c.uuid);
                StringBuilder items = new StringBuilder();
                int units = 0;
                for (AllocatedStack a : share) {
                    if (items.length() > 0) items.append("&7, ");
                    items.append("&f").append(a.source.typeName()).append(" x").append(a.amount);
                    units += a.amount;
                }

                Map<String, String> ph = new LinkedHashMap<String, String>();
                ph.put("player", c.name);
                ph.put("damage", String.format(Locale.ROOT, "%.1f", c.damage));
                ph.put("weight", String.format(Locale.ROOT, "%.2f", weight));
                ph.put("percent", String.format(Locale.ROOT, "%.1f", percent));
                ph.put("percent_sign", "%");
                ph.put("killer", c.killer ? msg.format("test.killer-tag") : "");
                ph.put("mult", c.permissionMultiplier != 1.0
                        ? Text.apply(msg.raw("test.mult-tag"), single("mult", String.valueOf(c.permissionMultiplier)))
                        : "");
                msg.send(player, "test.player-line", ph);

                Map<String, String> sharePh = new LinkedHashMap<String, String>();
                sharePh.put("units", String.valueOf(units));
                sharePh.put("items", items.length() == 0 ? msg.format("test.nothing") : items.toString());
                msg.send(player, "test.player-share", sharePh);
            }
        }

        for (String note : report.notes) {
            msg.send(player, "test.note", single("text", note));
        }
        msg.send(player, report.fallback() ? "test.result-vanilla" : "test.result-silent");
        msg.send(player, "test.footer");
        return true;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static ItemStack parseStack(String raw) {
        String[] parts = raw.split(":");
        Material material = Material.matchMaterial(parts[0]);
        if (material == null || !material.isItem()) return null;
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Math.min(material.getMaxStackSize() > 0 ? material.getMaxStackSize() : 64,
                        Integer.parseInt(parts[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ItemStack(material, amount);
    }

    private static LivingEntity findTarget(Player player) {
        try {
            RayTraceResult result = player.getWorld().rayTraceEntities(player.getEyeLocation(),
                    player.getLocation().getDirection(), 24);
            if (result != null && result.getHitEntity() instanceof LivingEntity) {
                return (LivingEntity) result.getHitEntity();
            }
        } catch (Throwable ignored) {
        }
        // fallback: the nearest living entity along the view direction
        List<Entity> near = player.getNearbyEntities(16, 16, 16);
        LivingEntity best = null;
        double bestDot = 0.75;
        org.bukkit.util.Vector dir = player.getLocation().getDirection().normalize();
        for (Entity e : near) {
            if (!(e instanceof LivingEntity) || e instanceof Player) continue;
            org.bukkit.util.Vector to = e.getLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
            double dot = to.dot(dir);
            if (dot > bestDot) {
                bestDot = dot;
                best = (LivingEntity) e;
            }
        }
        return best;
    }

    private static Map<String, String> single(String key, String value) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(key, value);
        return map;
    }

    private static Map<String, String> pair(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            for (String s : SUBS) {
                if (s.startsWith(prefix)) {
                    if (s.equals("reload") || s.equals("stats") || s.equals("give") || s.equals("test")) {
                        if (!sender.hasPermission("silentdrop.admin")) continue;
                    }
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("vault") || args[0].equalsIgnoreCase("clear"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            if (args[0].equalsIgnoreCase("clear")) out.add("all");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toUpperCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            for (Material m : Material.values()) {
                if (!m.isItem()) continue;
                if (m.name().startsWith(prefix)) out.add(m.name());
                if (out.size() > 60) break;
            }
            return out;
        }
        return Collections.emptyList();
    }
}
