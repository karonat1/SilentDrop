package ru.silentdrop.loot;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import ru.silentdrop.config.ExpMode;
import ru.silentdrop.config.Messages;
import ru.silentdrop.config.OverflowAction;
import ru.silentdrop.config.RuleSet;
import ru.silentdrop.config.Settings;
import ru.silentdrop.track.DamageTracker;
import ru.silentdrop.track.SpawnRecord;
import ru.silentdrop.util.Text;
import ru.silentdrop.vault.LootVault;

/**
 * Orchestrator: turns an entity death into addressed loot delivery.
 *
 * Steps inside {@link #onEntityDeath(EntityDeathEvent)}:
 *  1) filters (world, entity type, anti-farm, anti-spam, conflicts with other plugins);
 *  2) atomic claim of the event - the same drop can never be processed twice;
 *  3) snapshot of the drops + instant list clearing (nothing reaches the ground);
 *  4) share calculation ({@link LootAllocator});
 *  5) delivery into inventories, leftovers go to the vault or to the owner's feet;
 *  6) experience, sound, action bar, log.
 */
public final class LootDistributor {

    private final org.bukkit.plugin.Plugin plugin;
    private final DamageTracker tracker;
    private final LootVault vault;
    private final DropProtector protector;

    private volatile Settings settings;
    private volatile Messages messages;

    /** Guards against processing the same death twice (other plugins, repeated calls). */
    private final Set<UUID> processed = ConcurrentHashMap.newKeySet();

    /** Limit of silent distributions per tick (TPS protection on mass deaths). */
    private final AtomicInteger tickBudget = new AtomicInteger(0);

    /** Sliding window of distributions per player (anti-spam). */
    private final Map<UUID, java.util.ArrayDeque<Long>> playerRate = new ConcurrentHashMap<UUID, java.util.ArrayDeque<Long>>();

    // --- counters ---
    private final AtomicLong deathsSeen = new AtomicLong();
    private final AtomicLong distributions = new AtomicLong();
    private final AtomicLong itemsGiven = new AtomicLong();
    private final AtomicLong fallbackVanilla = new AtomicLong();
    private final AtomicLong skippedFilter = new AtomicLong();
    private final AtomicLong skippedFarm = new AtomicLong();
    private final AtomicLong skippedTampered = new AtomicLong();
    private final AtomicLong skippedNoPlayers = new AtomicLong();
    private final AtomicLong overflowItems = new AtomicLong();
    private final AtomicLong vaultStored = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong expGiven = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final long startedAt = System.currentTimeMillis();

    public LootDistributor(org.bukkit.plugin.Plugin plugin, DamageTracker tracker, LootVault vault, DropProtector protector) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.vault = vault;
        this.protector = protector;
    }

    public void configure(Settings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    /** Called every tick: resets the global distribution budget. */
    public void resetTickBudget() {
        tickBudget.set(0);
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    public void onEntityDeath(EntityDeathEvent event) {
        Settings cfg = this.settings;
        if (cfg == null || !cfg.enabled) return;

        deathsSeen.incrementAndGet();

        Entity entity = event.getEntity();
        if (entity == null) return;
        if (!(entity instanceof LivingEntity)) return;
        if (entity.getType() == EntityType.PLAYER) return;

        Location loc = entity.getLocation();
        World world = entity.getWorld();

        RuleSet rule = cfg.ruleFor(loc, world, entity.getType());
        if (rule == null) {
            skippedFilter.incrementAndGet();
            return;
        }

        List<ItemStack> drops = event.getDrops();

        // Conflict with other plugins: if the drop list is already empty at LOWEST priority,
        // somebody else took the loot - do not interfere, keep the vanilla behaviour.
        int snapshotSize = drops.size();
        if (cfg.skipIfDropsTampered && snapshotSize == 0) {
            skippedTampered.incrementAndGet();
            debug("DROP_EMPTY " + entity.getType() + " - drops already cleared by another plugin, skipping");
            return;
        }

        // Anti-farm: a mob killed right after spawning keeps its vanilla drop.
        String farmReason = farmBlockReason(entity, rule, event.getEntity().getKiller());
        if (farmReason != null) {
            skippedFarm.incrementAndGet();
            debug("SKIP_FARM " + entity.getType() + " : " + farmReason);
            return;
        }

        // Atomic claim: exactly one handling per death.
        if (!processed.add(entity.getUniqueId())) {
            debug("ALREADY_PROCESSED " + entity.getUniqueId());
            return;
        }

        List<ItemStack> snapshot;
        int capturedExp;
        try {
            snapshot = new ArrayList<ItemStack>(drops);
            capturedExp = event.getDroppedExp();
            drops.clear();                                   // <- nothing hits the ground
            if (rule.expMode != ExpMode.DISABLED) {
                event.setDroppedExp(0);                      // <- no experience orb spawns either
            }
        } catch (Throwable t) {
            processed.remove(entity.getUniqueId());
            errors.incrementAndGet();
            plugin.getLogger().log(Level.SEVERE, "[SilentDrop] Could not intercept the drops", t);
            return;
        }

        try {
            Player killer = ((LivingEntity) event.getEntity()).getKiller();
            List<Contributor> contributors = tracker.takeContributors(entity.getUniqueId(), killer);

            Ctx ctx = new Ctx(cfg, rule, loc, world, entity.getType(), mobName(entity));

            // global per-tick budget: the tick is overloaded with deaths - fall back to vanilla
            if (tickBudget.incrementAndGet() > cfg.globalPerTickBudget) {
                restore(event, snapshot, capturedExp, rule);
                fallbackVanilla.incrementAndGet();
                debug("BUDGET_EXCEEDED per-tick budget, vanilla drop for " + entity.getType());
                return;
            }

            Outcome outcome = distribute(ctx, contributors, snapshot, capturedExp, killer);
            if (outcome == Outcome.FALLBACK) {
                restore(event, snapshot, capturedExp, rule);
                fallbackVanilla.incrementAndGet();
            }
        } catch (Throwable t) {
            // emergency rollback: a plugin error must never destroy loot
            restore(event, snapshot, capturedExp, rule);
            errors.incrementAndGet();
            plugin.getLogger().log(Level.SEVERE, "[SilentDrop] Loot distribution failed, drops restored to the world", t);
        } finally {
            // detect another plugin writing items back into the list after us
            if (cfg.skipIfDropsTampered && !event.getDrops().isEmpty() && event.getDrops().size() != snapshotSize) {
                debug("DROPS_MODIFIED_AFTER_HANDLING " + entity.getType()
                        + " size=" + event.getDrops().size() + " (was " + snapshotSize + ")");
            }
            processed.remove(entity.getUniqueId());
        }
    }

    public enum Outcome {
        HANDLED, FALLBACK, TEST_ONLY
    }

    /** Puts the drops and the experience back into the event (vanilla behaviour). */
    private void restore(EntityDeathEvent event, List<ItemStack> snapshot, int exp, RuleSet rule) {
        try {
            List<ItemStack> drops = event.getDrops();
            drops.clear();
            for (ItemStack s : snapshot) {
                drops.add(s.clone());
            }
            if (rule.expMode != ExpMode.DISABLED) {
                event.setDroppedExp(exp);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "[SilentDrop] Could not restore the drops", t);
        }
    }

    // ------------------------------------------------------------------
    // Distribution
    // ------------------------------------------------------------------

    /**
     * Main calculation + delivery. Used both for a real death and for /sd test.
     *
     * @param dryRun when true nothing is given and nothing is modified (plan only)
     */
    public Report distribute(Ctx ctx, List<Contributor> contributors, List<ItemStack> drops, int exp, Player killer, boolean dryRun) {
        Settings cfg = ctx.cfg;
        RuleSet rule = ctx.rule;

        // --- 1. who takes part ---
        List<Contributor> eligible = filterEligible(cfg, rule, ctx, contributors, killer);
        List<String> notes = new ArrayList<String>();

        if (eligible.isEmpty()) {
            skippedNoPlayers.incrementAndGet();
            return new Report(Outcome.FALLBACK, eligible, null, notes, 0, 0, exp);
        }
        if (eligible.size() < rule.minPlayers) {
            boolean soloOk = rule.soloKillerGetsAll && eligible.size() == 1 && killer != null
                    && eligible.get(0).uuid.equals(killer.getUniqueId());
            if (!soloOk) {
                notes.add("min-players=" + rule.minPlayers + ", participants=" + eligible.size() + " -> vanilla");
                return new Report(Outcome.FALLBACK, eligible, null, notes, 0, 0, exp);
            }
            notes.add("min-players=" + rule.minPlayers + ", but solo-killer-gets-all=true");
        }

        // --- 2. share plan ---
        Set<String> blacklist = normalizeTypes(rule.itemBlacklist);
        LootOptions options = new LootOptions(rule.mode, rule.splitStacks, rule.killCredit, rule.itemChance,
                blacklist, new java.util.Random());
        List<ItemStackHolder> holders = new ArrayList<ItemStackHolder>();
        for (ItemStack s : drops) {
            if (s != null && s.getAmount() > 0) holders.add(new BukkitStack(s));
        }
        SharePlan plan = LootAllocator.allocate(eligible, holders, options);

        if (dryRun) {
            return new Report(Outcome.TEST_ONLY, eligible, plan, notes, 0, 0, exp);
        }

        // --- 3. delivery ---
        distributions.incrementAndGet();
        int delivered = 0;
        int overflowTotal = 0;

        for (Map.Entry<UUID, List<AllocatedStack>> entry : plan.shares().entrySet()) {
            List<AllocatedStack> share = entry.getValue();
            if (share.isEmpty()) continue;

            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                for (AllocatedStack a : share) overflowTotal += a.amount;
                storeOffline(entry.getKey(), share, ctx, rule, "player offline");
                continue;
            }

            List<ItemStack> items = materialize(share);
            if (items.isEmpty()) continue;

            // anti-spam: too many distributions for this player
            boolean limited = rule.rateLimitEnabled && isRateLimited(entry.getKey(), rule);
            if (limited) {
                rateLimited.incrementAndGet();
                int limitedUnits = 0;
                for (ItemStack s : items) limitedUnits += s.getAmount();
                handleOverflow(player, ctx, rule, items, rule.rateLimitAction, "rate-limit");
                overflowTotal += limitedUnits;
                continue;
            }

            Map<Integer, ItemStack> rest = giveItems(player, items);
            int givenUnits = 0;
            for (AllocatedStack a : share) givenUnits += a.amount;
            if (rest.isEmpty()) {
                delivered += givenUnits;
                itemsGiven.addAndGet(items.size());
            } else {
                List<ItemStack> leftovers = new ArrayList<ItemStack>(rest.values());
                int leftUnits = 0;
                for (ItemStack s : leftovers) leftUnits += s.getAmount();
                delivered += Math.max(0, givenUnits - leftUnits);
                overflowTotal += leftUnits;
                overflowItems.addAndGet(leftUnits);
                handleOverflow(player, ctx, rule, leftovers, rule.overflow, "inventory full");
            }

            feedback(player, ctx, share.size(), givenUnits);
        }

        // Unallocated items (possible when every participant has zero weight).
        // Loot must never vanish: hand it over through the standard overflow path.
        if (!plan.unallocated().isEmpty()) {
            List<ItemStack> leftovers = materialize(plan.unallocated());
            Player receiver = killer != null && killer.isOnline() ? killer : firstOnline(plan.eligible());
            if (receiver != null) {
                handleOverflow(receiver, ctx, rule, leftovers,
                        rule.overflow == OverflowAction.VOID ? OverflowAction.DROP : rule.overflow,
                        "unallocated");
            } else if (!leftovers.isEmpty()) {
                dropAtLocation(ctx, eligible.isEmpty() ? null : eligible.get(0).uuid, rule, leftovers, "unallocated");
            }
            overflowTotal += leftovers.size();
        }

        // --- 4. experience ---
        int expHandled = giveExperience(rule, plan, exp, killer);

        debug("DISTRIBUTED " + ctx.mobName + " mode=" + rule.mode + " players=" + eligible.size()
                + " units=" + plan.totalUnits() + " delivered=" + delivered + " overflow=" + overflowTotal
                + " exp=" + expHandled);

        return new Report(Outcome.HANDLED, eligible, plan, notes, delivered, overflowTotal, expHandled);
    }

    /** Short wrapper used by the death handler. */
    private Outcome distribute(Ctx ctx, List<Contributor> contributors, List<ItemStack> drops, int exp, Player killer) {
        Report report = distribute(ctx, contributors, drops, exp, killer, false);
        return report.outcome;
    }

    // ------------------------------------------------------------------
    // Participant filters
    // ------------------------------------------------------------------

    private List<Contributor> filterEligible(Settings cfg, RuleSet rule, Ctx ctx, List<Contributor> raw, Player killer) {
        List<Contributor> out = new ArrayList<Contributor>();
        if (raw == null) return out;

        double totalDamage = 0.0;
        for (Contributor c : raw) totalDamage += c.damage;
        double percentBase = rule.percentOfTrackedOnly ? totalDamage : Math.max(totalDamage, 1.0);

        Set<UUID> seen = new LinkedHashSet<UUID>();
        for (Contributor c : raw) {
            if (c == null || c.uuid == null || !seen.add(c.uuid)) continue;

            Player p = plugin.getServer().getPlayer(c.uuid);

            // offline player: keep only the killer (their share goes to the vault)
            if (p == null || !p.isOnline()) {
                if (c.killer && rule.includeKiller) {
                    out.add(withMultiplier(cfg, c, null));
                } else if (rule.includeAssistNoDamage) {
                    out.add(withMultiplier(cfg, c, null));
                }
                continue;
            }

            if (!rule.includeKiller && c.killer && c.damage <= 0.0) continue;
            if (!rule.includeDamagers && !c.killer) continue;
            if (c.damage <= 0.0 && !c.killer && !rule.includeAssistNoDamage) continue;

            if (rule.skipSpectator && p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.isDead()) continue;

            if (rule.requireSameWorld && !p.getWorld().getUID().equals(ctx.world.getUID())) continue;
            if (rule.maxDistance > 0.0) {
                World pw = p.getWorld();
                if (pw == null || !pw.getUID().equals(ctx.world.getUID())) continue;
                if (p.getLocation().distanceSquared(ctx.location) > rule.maxDistance * rule.maxDistance) continue;
            }

            if (cfg.bypassSkipsDistribution && p.hasPermission("silentdrop.bypass")) continue;

            if (rule.minDamageFlat > 0.0 && c.damage < rule.minDamageFlat && !c.killer) continue;
            if (rule.minDamagePercent > 0.0 && percentBase > 0.0) {
                double share = c.damage / percentBase * 100.0;
                if (share < rule.minDamagePercent && !c.killer) continue;
            }

            out.add(withMultiplier(cfg, c, p));
        }

        if (out.isEmpty() && killer != null && killer.isOnline() && rule.includeKiller) {
            out.add(new Contributor(killer.getUniqueId(), killer.getName(), 0.0,
                    System.currentTimeMillis(), true, multiplierOf(cfg, killer)));
        }
        return out;
    }

    private Contributor withMultiplier(Settings cfg, Contributor base, Player player) {
        double mult = player == null ? 1.0 : multiplierOf(cfg, player);
        if (mult == base.permissionMultiplier) return base;
        return new Contributor(base.uuid, base.name, base.damage, base.firstHitMillis, base.killer, mult);
    }

    private double multiplierOf(Settings cfg, Player player) {
        if (!cfg.permissionMultiplierEnabled || player == null) return 1.0;
        String node = cfg.permissionMultiplierNode;
        double best = 1.0;
        try {
            for (org.bukkit.permissions.PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (perm == null || !info.getValue()) continue;
                if (!perm.toLowerCase(Locale.ROOT).startsWith(node.toLowerCase(Locale.ROOT) + ".")) continue;
                String tail = perm.substring(node.length() + 1).replace(',', '.');
                try {
                    double value = Double.parseDouble(tail);
                    if (value > best) best = value;
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return Math.min(best, cfg.permissionMultiplierCap);
    }

    // ------------------------------------------------------------------
    // Item delivery
    // ------------------------------------------------------------------

    /**
     * Gives items to a player without breaking anything: first a dry run on a copy of
     * the slots, then the real delivery. Whatever does not fit is returned to the caller.
     */
    public Map<Integer, ItemStack> giveItems(Player player, List<ItemStack> items) {
        if (player == null || items == null || items.isEmpty()) return Collections.emptyMap();

        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] sim = new ItemStack[storage.length];
        for (int i = 0; i < storage.length; i++) {
            sim[i] = storage[i] == null ? null : storage[i].clone();
        }

        Map<Integer, ItemStack> rest = new LinkedHashMap<Integer, ItemStack>();
        List<ItemStack> fits = new ArrayList<ItemStack>();
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            Map<Integer, ItemStack> left = addItemTo(sim, item);
            if (left.isEmpty()) {
                fits.add(item);
            } else {
                for (ItemStack l : left.values()) rest.put(rest.size(), l);
            }
        }

        if (!fits.isEmpty()) {
            Map<Integer, ItemStack> realRest = player.getInventory().addItem(fits.toArray(new ItemStack[0]));
            if (realRest != null && !realRest.isEmpty()) {
                // extremely unlikely (single thread), but stay safe
                for (ItemStack l : realRest.values()) rest.put(rest.size(), l);
            }
        }
        return rest;
    }

    /** Inventory#addItem for an arbitrary slot array (used by the dry run). */
    private static Map<Integer, ItemStack> addItemTo(ItemStack[] slots, ItemStack item) {
        return ru.silentdrop.util.InventoryMath.addItem(slots, item);
    }

    private List<ItemStack> materialize(List<AllocatedStack> share) {
        List<ItemStack> out = new ArrayList<ItemStack>();
        for (AllocatedStack a : share) {
            ItemStack s = a.toItemStack();
            if (s != null && s.getAmount() > 0) out.add(s);
        }
        return out;
    }

    private boolean vaultUsable(Ctx ctx) {
        return vault != null && ctx.cfg.vaultEnabled;
    }

    /**
     * Share of a player who went offline. The vault is preferred; if it is disabled,
     * the item drops at the death point under the owner's protection
     * (better than losing the loot).
     */
    private void storeOffline(UUID playerId, List<AllocatedStack> share, Ctx ctx, RuleSet rule, String reason) {
        if (vaultUsable(ctx)) {
            List<ItemStack> failed = new ArrayList<ItemStack>();
            for (AllocatedStack a : share) {
                ItemStack s = a.toItemStack();
                if (s == null) continue;
                if (vault.store(playerId, s, ctx.mobName, reason)) {
                    vaultStored.incrementAndGet();
                } else {
                    failed.add(s); // not serializable - do not lose it, drop it protected
                }
            }
            if (!failed.isEmpty()) dropAtLocation(ctx, playerId, rule, failed, reason);
            return;
        }
        List<ItemStack> items = materialize(share);
        if (!items.isEmpty()) dropAtLocation(ctx, playerId, rule, items, reason);
    }

    /** Public wrapper for commands (for example /sd give with a full inventory). */
    public void handleOverflowPublic(Player player, Ctx ctx, RuleSet rule, List<ItemStack> items,
                                     OverflowAction action, String reason) {
        handleOverflow(player, ctx, rule, items, action, reason);
    }

    /**
     * Leftover handling: vault / drop at the owner's feet / destroy.
     */
    private void handleOverflow(Player player, Ctx ctx, RuleSet rule, List<ItemStack> items,
                                OverflowAction action, String reason) {
        if (items == null || items.isEmpty()) return;

        if (action == OverflowAction.VAULT && !vaultUsable(ctx)) {
            debug("VAULT disabled - leftovers fall back to DROP (" + reason + ")");
            action = OverflowAction.DROP;
        }

        if (action == OverflowAction.VAULT && vaultUsable(ctx)) {
            int stored = 0;
            List<ItemStack> failed = new ArrayList<ItemStack>();
            for (ItemStack s : items) {
                if (vault.store(player.getUniqueId(), s, ctx.mobName, reason)) {
                    stored++;
                    vaultStored.incrementAndGet();
                } else {
                    failed.add(s);
                }
            }
            if (stored > 0 && ctx.cfg.vaultMessageOnStore && player.isOnline() && messages != null) {
                Map<String, String> ph = new LinkedHashMap<String, String>();
                ph.put("count", String.valueOf(stored));
                ph.put("player", player.getName());
                ph.put("mob", ctx.mobName);
                ph.put("reason", reason);
                messages.send(player, "vault.stored", ph);
            }
            if (!failed.isEmpty()) dropForOwner(player, ctx, rule, failed, reason);
            return;
        }

        if (action == OverflowAction.DROP) {
            dropForOwner(player, ctx, rule, items, reason);
            return;
        }

        // VOID
        if (ctx.cfg.debug) {
            int n = 0;
            for (ItemStack s : items) n += s.getAmount();
            plugin.getLogger().info("[SilentDrop][debug] Destroyed " + n + " loot unit(s) (" + reason
                    + ") for " + player.getName() + ", mob " + ctx.mobName);
        }
    }

    /**
     * The only path that ever puts an item into the world.
     * The item is tagged with the owner's name and protected from other players.
     */
    private void dropForOwner(Player player, Ctx ctx, RuleSet rule, List<ItemStack> items, String reason) {
        if (items == null || items.isEmpty()) return;
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return;

        int delay = Math.max(0, rule.overflowPickupDelay);
        int protect = rule.overflowProtectSeconds > 0 ? rule.overflowProtectSeconds : ctx.cfg.protectionSeconds;

        for (ItemStack stack : items) {
            if (stack == null || stack.getAmount() <= 0) continue;

            ItemStack toDrop = stack.clone();
            if (rule.overflowOwnerTag && ctx.cfg.ownerTagFormat != null && !ctx.cfg.ownerTagFormat.isEmpty()) {
                ItemMeta meta = toDrop.hasItemMeta() ? toDrop.getItemMeta() : null;
                boolean fresh = meta == null;
                if (meta == null) meta = plugin.getServer().getItemFactory().getItemMeta(toDrop.getType());
                if (meta != null) {
                    Map<String, String> ph = new LinkedHashMap<String, String>();
                    ph.put("player", player.getName());
                    ph.put("item", displayName(toDrop));
                    meta.setDisplayName(Text.color(Text.apply(ctx.cfg.ownerTagFormat, ph)));
                    toDrop.setItemMeta(meta);
                    if (fresh && ctx.cfg.debug) {
                        plugin.getLogger().fine("[SilentDrop] owner tag applied to " + toDrop.getType());
                    }
                }
            }

            Item dropped = world.dropItem(base.clone().add(0, 0.3, 0), toDrop);
            dropped.setPickupDelay(delay);
            dropped.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

            if (ctx.cfg.protectionEnabled && protect > 0) {
                protector.register(dropped.getUniqueId(), player.getUniqueId(),
                        System.currentTimeMillis() + protect * 1000L, world.getUID());
            }
            if (ctx.cfg.debug) {
                plugin.getLogger().info("[SilentDrop][debug] Dropped for the owner " + player.getName()
                        + ": " + toDrop.getType() + " x" + toDrop.getAmount() + " (" + reason + ")");
            }
        }
    }

    /** Drop at an exact point (the death location) bound to an owner. */
    private void dropAtLocation(Ctx ctx, UUID ownerId, RuleSet rule, List<ItemStack> items, String reason) {
        if (items == null || items.isEmpty() || ctx.location == null || ctx.location.getWorld() == null) return;
        World world = ctx.location.getWorld();

        for (ItemStack stack : items) {
            if (stack == null || stack.getAmount() <= 0) continue;
            Item dropped = world.dropItem(ctx.location.clone().add(0, 0.3, 0), stack.clone());
            dropped.setPickupDelay(Math.max(0, rule.overflowPickupDelay));
            dropped.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            if (ownerId != null && ctx.cfg.protectionEnabled && ctx.cfg.protectionSeconds > 0) {
                protector.register(dropped.getUniqueId(), ownerId,
                        System.currentTimeMillis() + ctx.cfg.protectionSeconds * 1000L, world.getUID());
            }
            overflowItems.addAndGet(stack.getAmount());
            debug("DROP_AT_LOCATION " + stack.getType() + " x" + stack.getAmount() + " owner=" + ownerId
                    + " (" + reason + ")");
        }
    }

    private Player firstOnline(List<Contributor> contributors) {
        if (contributors == null) return null;
        for (Contributor c : contributors) {
            Player p = plugin.getServer().getPlayer(c.uuid);
            if (p != null && p.isOnline()) return p;
        }
        return null;
    }

    private static String displayName(ItemStack stack) {
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        }
        return Text.humanize(stack.getType().name());
    }

    // ------------------------------------------------------------------
    // Experience
    // ------------------------------------------------------------------

    private int giveExperience(RuleSet rule, SharePlan plan, int exp, Player killer) {
        if (exp <= 0 || rule.expMode == ExpMode.DISABLED) return 0;

        List<Contributor> eligible = plan != null ? plan.eligible() : Collections.<Contributor>emptyList();
        if (eligible.isEmpty()) {
            if (killer != null && killer.isOnline()) killer.giveExp(exp);
            return exp;
        }

        switch (rule.expMode) {
            case KILLER: {
                Player target = killer != null && killer.isOnline() ? killer : onlineOf(eligible);
                if (target != null) {
                    target.giveExp(exp);
                    expGiven.addAndGet(exp);
                    return exp;
                }
                return 0;
            }
            case TOP_SHARE: {
                Contributor top = topByWeight(eligible, rule.killCredit);
                Player target = top == null ? null : plugin.getServer().getPlayer(top.uuid);
                if (target != null && target.isOnline()) {
                    target.giveExp(exp);
                    expGiven.addAndGet(exp);
                    return exp;
                }
                return 0;
            }
            case SPLIT: {
                double total = 0.0;
                for (Contributor c : eligible) total += Math.max(0.0, c.weight(rule.killCredit));
                if (total <= 0.0) {
                    Player target = onlineOf(eligible);
                    if (target != null) {
                        target.giveExp(exp);
                        expGiven.addAndGet(exp);
                        return exp;
                    }
                    return 0;
                }
                int assigned = 0;
                Player best = null;
                double bestWeight = -1;
                for (Contributor c : eligible) {
                    double w = Math.max(0.0, c.weight(rule.killCredit));
                    int part = (int) Math.floor(exp * w / total);
                    if (part <= 0) continue;
                    Player p = plugin.getServer().getPlayer(c.uuid);
                    if (p == null || !p.isOnline()) continue;
                    p.giveExp(part);
                    assigned += part;
                    expGiven.addAndGet(part);
                    if (w > bestWeight) {
                        bestWeight = w;
                        best = p;
                    }
                }
                int rest = exp - assigned;
                if (rest > 0 && best != null) {
                    best.giveExp(rest);
                    expGiven.addAndGet(rest);
                    assigned += rest;
                }
                return assigned;
            }
            default:
                return 0;
        }
    }

    private Player onlineOf(List<Contributor> eligible) {
        for (Contributor c : eligible) {
            Player p = plugin.getServer().getPlayer(c.uuid);
            if (p != null && p.isOnline()) return p;
        }
        return null;
    }

    private Contributor topByWeight(List<Contributor> eligible, double killCredit) {
        Contributor best = null;
        for (Contributor c : eligible) {
            if (best == null || c.weight(killCredit) > best.weight(killCredit)
                    || (c.weight(killCredit) == best.weight(killCredit) && c.firstHitMillis < best.firstHitMillis)) {
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Per-player anti-spam
    // ------------------------------------------------------------------

    private boolean isRateLimited(UUID playerId, RuleSet rule) {
        if (!rule.rateLimitEnabled || rule.rateLimitMaxDrops <= 0) return false;
        long now = System.currentTimeMillis();
        long window = rule.rateLimitWindowSeconds * 1000L;

        java.util.ArrayDeque<Long> deque = playerRate.get(playerId);
        if (deque == null) {
            deque = new java.util.ArrayDeque<Long>();
            java.util.ArrayDeque<Long> prev = playerRate.putIfAbsent(playerId, deque);
            if (prev != null) deque = prev;
        }
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > window) deque.pollFirst();
            if (deque.size() >= rule.rateLimitMaxDrops) return true;
            deque.addLast(now);
            return false;
        }
    }

    /** Periodic cleanup of the anti-spam windows. */
    public int purgeRateWindows() {
        long now = System.currentTimeMillis();
        int removed = 0;
        java.util.Iterator<Map.Entry<UUID, java.util.ArrayDeque<Long>>> it = playerRate.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, java.util.ArrayDeque<Long>> e = it.next();
            java.util.ArrayDeque<Long> deque = e.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > 60_000L) deque.pollFirst();
                if (deque.isEmpty()) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Anti-farm
    // ------------------------------------------------------------------

    /** @return the blocking reason, or null when the mob passes the checks. */
    public String farmBlockReason(Entity entity, RuleSet rule, Player killer) {
        if (!rule.antiFarmEnabled) return null;
        boolean bypass = killer != null && killer.hasPermission("silentdrop.bypass.antifarm");
        if (bypass) return null;

        if (rule.skipBabies && entity instanceof Ageable && !((Ageable) entity).isAdult()) {
            return "baby mob";
        }
        if (rule.skipNamedMobs && entity.getCustomName() != null && !entity.getCustomName().isEmpty()) {
            return "named mob";
        }

        SpawnRecord record = tracker.spawnRecord(entity.getUniqueId());
        if (record != null) {
            if (rule.skipSpawnerMobs && "SPAWNER".equalsIgnoreCase(record.reason)) {
                return "spawner";
            }
            if (rule.skipSpawnerEggMobs && ("SPAWNER_EGG".equalsIgnoreCase(record.reason)
                    || "EGG".equalsIgnoreCase(record.reason)
                    || "DISPENSE_EGG".equalsIgnoreCase(record.reason))) {
                return "spawn egg";
            }
            if (rule.skipSpawnReasons.contains(record.reason.toUpperCase(Locale.ROOT))) {
                return "spawn-reason=" + record.reason;
            }
            if (rule.spawnGraceSeconds > 0) {
                long ageSeconds = record.ageMillis(System.currentTimeMillis()) / 1000L;
                if (ageSeconds < rule.spawnGraceSeconds) {
                    return "killed " + ageSeconds + "s after spawn (grace " + rule.spawnGraceSeconds + "s)";
                }
            }
        } else if (rule.requireKnownSpawnTime) {
            return "spawn time unknown";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Feedback
    // ------------------------------------------------------------------

    private void feedback(Player player, Ctx ctx, int stacks, int units) {
        if (player == null || !player.isOnline()) return;
        if (!player.hasPermission("silentdrop.notify")) return;
        Settings cfg = ctx.cfg;

        Map<String, String> ph = new LinkedHashMap<String, String>();
        ph.put("player", player.getName());
        ph.put("mob", ctx.mobName);
        ph.put("count", String.valueOf(units));
        ph.put("stacks", String.valueOf(stacks));

        if (cfg.actionBarEnabled && messages != null) {
            messages.actionBar(player, "loot.action-bar", ph);
        }
        if (cfg.chatEnabled && messages != null) {
            messages.send(player, "loot.chat", ph);
        }
        if (cfg.titleEnabled && messages != null) {
            messages.title(player, "loot.title", "loot.subtitle", ph);
        }
        if (cfg.soundEnabled && cfg.soundName != null && !cfg.soundName.isEmpty()) {
            try {
                Sound sound = Sound.valueOf(cfg.soundName.toUpperCase(Locale.ROOT));
                player.playSound(player.getLocation(), sound, cfg.soundVolume, cfg.soundPitch);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[SilentDrop] Unknown sound in config.yml: " + cfg.soundName);
            }
        }
    }

    public void debug(String message) {
        Settings cfg = this.settings;
        if (cfg != null && cfg.debug) {
            plugin.getLogger().info("[SilentDrop][debug] " + message);
        }
    }

    // ------------------------------------------------------------------
    // /sd test - a kill simulation without killing the mob
    // ------------------------------------------------------------------

    /**
     * Calculates a distribution plan for a living entity: nearby players are taken as
     * participants and the sample loot is supplied by the caller. Nothing is given.
     */
    public Report simulate(Player viewer, LivingEntity target, Collection<ItemStack> sampleDrops) {
        Settings cfg = this.settings;
        Location loc = target.getLocation();
        World world = target.getWorld();
        RuleSet rule = cfg.ruleFor(loc, world, target.getType());
        if (rule == null) {
            return null;
        }

        List<Contributor> contributors = new ArrayList<Contributor>();
        List<Contributor> tracked = tracker.peekContributors(target.getUniqueId(), viewer);
        Set<UUID> added = new LinkedHashSet<UUID>();
        for (Contributor c : tracked) {
            added.add(c.uuid);
            contributors.add(c);
        }
        // add nearby players without damage - handy to check min-players and EQUAL
        for (Player p : world.getPlayers()) {
            if (added.contains(p.getUniqueId())) continue;
            if (p.getLocation().distanceSquared(loc) > 16 * 16) continue;
            contributors.add(new Contributor(p.getUniqueId(), p.getName(), 0.0, System.currentTimeMillis(),
                    p.getUniqueId().equals(viewer.getUniqueId())));
        }

        List<ItemStack> drops = new ArrayList<ItemStack>();
        if (sampleDrops != null) {
            for (ItemStack s : sampleDrops) {
                if (s != null && s.getAmount() > 0) drops.add(s.clone());
            }
        }

        Ctx ctx = new Ctx(cfg, rule, loc, world, target.getType(), mobName(target));
        return distribute(ctx, contributors, drops, 0, viewer, true);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Set<String> normalizeTypes(Set<String> in) {
        Set<String> out = new LinkedHashSet<String>();
        if (in == null) return out;
        for (String raw : in) {
            if (raw == null) continue;
            String key = raw.trim().toUpperCase(Locale.ROOT);
            int colon = key.indexOf(':');
            if (colon >= 0) key = key.substring(colon + 1);
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }

    public static String mobName(Entity entity) {
        if (entity == null) return "?";
        String custom = entity.getCustomName();
        if (custom != null && !custom.isEmpty()) return custom;
        return Text.humanize(entity.getType().name());
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

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Map<String, String> statsSnapshot() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("uptime", formatUptime(System.currentTimeMillis() - startedAt));
        out.put("deaths-seen", String.valueOf(deathsSeen.get()));
        out.put("distributions", String.valueOf(distributions.get()));
        out.put("items-given", String.valueOf(itemsGiven.get()));
        out.put("fallback-vanilla", String.valueOf(fallbackVanilla.get()));
        out.put("skipped-filter", String.valueOf(skippedFilter.get()));
        out.put("skipped-antifarm", String.valueOf(skippedFarm.get()));
        out.put("skipped-tampered", String.valueOf(skippedTampered.get()));
        out.put("skipped-no-players", String.valueOf(skippedNoPlayers.get()));
        out.put("overflow-items", String.valueOf(overflowItems.get()));
        out.put("vault-stored", String.valueOf(vaultStored.get()));
        out.put("rate-limited", String.valueOf(rateLimited.get()));
        out.put("exp-given", String.valueOf(expGiven.get()));
        out.put("errors", String.valueOf(errors.get()));
        out.put("protected-drops", String.valueOf(protector.size()));
        out.put("tracked-entities", String.valueOf(tracker.trackedEntities()));
        out.put("tracked-spawns", String.valueOf(tracker.trackedSpawns()));
        out.put("vault-entries", String.valueOf(vault.totalSize()));
        return out;
    }

    private static String formatUptime(long millis) {
        long s = millis / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        return h + "h " + m + "m " + (s % 60L) + "s";
    }

    /** Context of a single distribution. */
    public static final class Ctx {
        public final Settings cfg;
        public final RuleSet rule;
        public final Location location;
        public final World world;
        public final EntityType type;
        public final String mobName;

        public Ctx(Settings cfg, RuleSet rule, Location location, World world, EntityType type, String mobName) {
            this.cfg = cfg;
            this.rule = rule;
            this.location = location;
            this.world = world;
            this.type = type;
            this.mobName = mobName;
        }
    }

    /** Distribution result, used by /sd test and debugging. */
    public static final class Report {
        public final Outcome outcome;
        public final List<Contributor> eligible;
        public final SharePlan plan;
        public final List<String> notes;
        public final int delivered;
        public final int overflow;
        public final int exp;

        Report(Outcome outcome, List<Contributor> eligible, SharePlan plan, List<String> notes,
               int delivered, int overflow, int exp) {
            this.outcome = outcome;
            this.eligible = eligible != null ? eligible : Collections.<Contributor>emptyList();
            this.plan = plan;
            this.notes = notes != null ? notes : Collections.<String>emptyList();
            this.delivered = delivered;
            this.overflow = overflow;
            this.exp = exp;
        }

        public boolean handled() {
            return outcome == Outcome.HANDLED;
        }

        public boolean fallback() {
            return outcome == Outcome.FALLBACK;
        }

        /** Participants sorted by weight, for a readable /sd test output. */
        public List<Contributor> sorted(double killCredit) {
            List<Contributor> copy = new ArrayList<Contributor>(eligible);
            final double credit = killCredit;
            Collections.sort(copy, new Comparator<Contributor>() {
                @Override
                public int compare(Contributor a, Contributor b) {
                    return Double.compare(b.weight(credit), a.weight(credit));
                }
            });
            return copy;
        }
    }
}
