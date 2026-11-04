package ru.silentdrop.track;

import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ru.silentdrop.loot.Contributor;

/**
 * Tracks player contribution to a kill and mob spawn times.
 *
 * Stores ONLY primitives and UUIDs (no Entity references), so it cannot leak memory.
 * All structures are thread safe: Bukkit events arrive on the main thread,
 * while purging may be triggered by the scheduler.
 */
public final class DamageTracker {

    private static final Method SHOT_AT;

    static {
        Method m = null;
        try {
            // AreaEffectCloud#getShotAt() appeared in Spigot API 1.17.
            // It does not exist on 1.16.5, so it is resolved reflectively.
            m = AreaEffectCloud.class.getMethod("getShotAt");
        } catch (Throwable ignored) {
            m = null;
        }
        SHOT_AT = m;
    }

    private final Map<UUID, Deque<Hit>> damage = new ConcurrentHashMap<UUID, Deque<Hit>>();
    private final Map<UUID, SpawnRecord> spawns = new ConcurrentHashMap<UUID, SpawnRecord>();

    private volatile long windowMillis = 15_000L;
    private volatile int maxContributors = 24;
    private volatile boolean enabled = true;

    public void updateLimits(int windowSeconds, int maxContributorsPerMob) {
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.maxContributors = Math.max(1, maxContributorsPerMob);
    }

    /** Damage is not tracked while the plugin is disabled: saves memory and CPU. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            damage.clear();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------
    // Damage
    // ------------------------------------------------------------------

    /**
     * Records a hit. Called from EntityDamageByEntityEvent.
     *
     * @param victim   the entity being damaged
     * @param damager  the entity or projectile that dealt the damage
     * @param amount   actual damage
     */
    public void recordDamage(Entity victim, Entity damager, double amount) {
        if (!enabled) return;
        if (victim == null || !(victim instanceof LivingEntity)) return;
        if (victim.getType() == EntityType.PLAYER) return;
        if (amount <= 0.0 || Double.isNaN(amount)) return;

        Player player = resolvePlayer(damager, victim, 0);
        if (player == null) return;
        if (player.getUniqueId().equals(victim.getUniqueId())) return; // self damage for fake "contribution"

        long now = System.currentTimeMillis();
        UUID id = victim.getUniqueId();
        Deque<Hit> deque = damage.get(id);
        if (deque == null) {
            deque = new ArrayDeque<Hit>();
            Deque<Hit> prev = damage.putIfAbsent(id, deque);
            if (prev != null) deque = prev;
        }
        synchronized (deque) {
            prune(deque, now);
            deque.addLast(new Hit(player.getUniqueId(), player.getName(), amount, now));
            // too many entries - drop the oldest ones (they barely matter anyway)
            while (deque.size() > maxContributors * 4) {
                deque.pollFirst();
            }
        }
    }

    /**
     * Player contributions to the death of an entity. The data is removed after reading.
     *
     * @param entityId UUID of the dead entity
     * @param killer   whoever landed the last hit (may be null)
     */
    public List<Contributor> takeContributors(UUID entityId, Player killer) {
        Deque<Hit> deque = entityId == null ? null : damage.remove(entityId);
        return merge(deque, killer, true);
    }

    /** Merges all hits into a participant list: total damage + first hit time. */
    private List<Contributor> merge(Deque<Hit> deque, Player killer, boolean pruneNow) {
        long now = System.currentTimeMillis();

        // per player aggregates: total damage / first hit time / latest known name
        Map<UUID, double[]> sums = new java.util.LinkedHashMap<UUID, double[]>();
        Map<UUID, long[]> times = new java.util.HashMap<UUID, long[]>();
        Map<UUID, String> names = new java.util.HashMap<UUID, String>();

        if (deque != null) {
            synchronized (deque) {
                prune(deque, now);
                for (Hit h : deque) {
                    UUID id = h.playerId;

                    double[] sum = sums.get(id);
                    if (sum == null) {
                        sum = new double[1];
                        sums.put(id, sum);
                    }
                    sum[0] += h.amount;

                    long[] t = times.get(id);
                    if (t == null) {
                        times.put(id, new long[]{h.timestamp});
                    } else if (h.timestamp < t[0]) {
                        t[0] = h.timestamp;
                    }

                    names.put(id, h.playerName);
                }
            }
        }

        List<Contributor> out = new ArrayList<Contributor>();
        for (Map.Entry<UUID, double[]> e : sums.entrySet()) {
            UUID id = e.getKey();
            long first = times.containsKey(id) ? times.get(id)[0] : now;
            boolean isKiller = killer != null && id.equals(killer.getUniqueId());
            out.add(new Contributor(id, names.get(id), e.getValue()[0], first, isKiller));
        }

        // the killer is always added, even without recorded damage (mob hit, fall damage, etc.)
        if (killer != null && !sums.containsKey(killer.getUniqueId())) {
            out.add(new Contributor(killer.getUniqueId(), killer.getName(), 0.0, now, true));
        }

        return out;
    }

    /**
     * Same as {@link #takeContributors(UUID, Player)} but WITHOUT removing the data.
     * Used by /sd test on a living mob.
     */
    public List<Contributor> peekContributors(UUID entityId, Player killer) {
        Deque<Hit> deque = entityId == null ? null : damage.get(entityId);
        return merge(deque, killer, false);
    }

    /** Drop all data of an entity (for example when the death was cancelled). */
    public void clear(UUID entityId) {
        if (entityId == null) return;
        damage.remove(entityId);
        spawns.remove(entityId);
    }

    private void prune(Deque<Hit> deque, long now) {
        long cutoff = now - windowMillis;
        Iterator<Hit> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next().timestamp < cutoff) it.remove();
            else break; // the deque is ordered by time
        }
    }

    // ------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------

    public void recordSpawn(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        if (entity == null) return;
        boolean baby = false;
        if (entity instanceof org.bukkit.entity.Ageable) {
            baby = !((org.bukkit.entity.Ageable) entity).isAdult();
        }
        spawns.put(entity.getUniqueId(),
                new SpawnRecord(System.currentTimeMillis(), reason != null ? reason.name() : "UNKNOWN", baby));
    }

    public SpawnRecord spawnRecord(UUID entityId) {
        return entityId == null ? null : spawns.get(entityId);
    }

    /** Removes stale records. Called by the periodic task. */
    public int purge(long maxSpawnRecordAgeMillis) {
        long now = System.currentTimeMillis();
        int removed = 0;

        Iterator<Map.Entry<UUID, Deque<Hit>>> dit = damage.entrySet().iterator();
        while (dit.hasNext()) {
            Map.Entry<UUID, Deque<Hit>> e = dit.next();
            Deque<Hit> deque = e.getValue();
            boolean empty;
            synchronized (deque) {
                prune(deque, now);
                empty = deque.isEmpty();
            }
            if (empty) {
                dit.remove();
                removed++;
            }
        }

        Iterator<Map.Entry<UUID, SpawnRecord>> sit = spawns.entrySet().iterator();
        while (sit.hasNext()) {
            Map.Entry<UUID, SpawnRecord> e = sit.next();
            if (now - e.getValue().spawnedAtMillis > maxSpawnRecordAgeMillis) {
                sit.remove();
                removed++;
            }
        }
        return removed;
    }

    public int trackedEntities() {
        return damage.size();
    }

    public int trackedSpawns() {
        return spawns.size();
    }

    public List<UUID> trackedEntityIds() {
        return Collections.unmodifiableList(new ArrayList<UUID>(damage.keySet()));
    }

    // ------------------------------------------------------------------
    // Resolving the damage author
    // ------------------------------------------------------------------

    /**
     * Recursively resolves the player responsible for the damage:
     * arrows/tridents/fireballs, TNT, potion clouds, wolves and other pets,
     * projectiles launched by a player through a dispenser and so on.
     */
    public static Player resolvePlayer(Entity damager, Entity victim, int depth) {
        if (damager == null || depth > 6) return null;
        if (damager instanceof Player) return (Player) damager;
        if (victim != null && damager.getUniqueId().equals(victim.getUniqueId())) return null;

        if (damager instanceof Projectile) {
            ProjectileSource source = ((Projectile) damager).getShooter();
            if (source instanceof Player) return (Player) source;
            if (source instanceof Entity) return resolvePlayer((Entity) source, victim, depth + 1);
            return null;
        }

        if (damager instanceof TNTPrimed) {
            return resolvePlayer(((TNTPrimed) damager).getSource(), victim, depth + 1);
        }

        if (damager instanceof AreaEffectCloud) {
            AreaEffectCloud cloud = (AreaEffectCloud) damager;
            ProjectileSource source = cloud.getSource();
            if (source instanceof Player) return (Player) source;
            if (SHOT_AT != null) {
                try {
                    Object shotAt = SHOT_AT.invoke(cloud);
                    if (shotAt instanceof Entity) {
                        Player p = resolvePlayer((Entity) shotAt, victim, depth + 1);
                        if (p != null) return p;
                    }
                } catch (Throwable ignored) {
                    // the method may not exist on 1.16.5 - not critical
                }
            }
            if (source instanceof Entity) return resolvePlayer((Entity) source, victim, depth + 1);
            return null;
        }

        if (damager instanceof Tameable) {
            Tameable tameable = (Tameable) damager;
            if (tameable.isTamed()) {
                Object owner = tameable.getOwner();
                if (owner instanceof Player) return (Player) owner;
                if (owner instanceof Entity) return resolvePlayer((Entity) owner, victim, depth + 1);
            }
            return null;
        }

        // other projectile sources (a shulker bullet, for example, belongs to nobody)
        if (damager instanceof ProjectileSource) {
            ProjectileSource source = (ProjectileSource) damager;
            if (source instanceof Player) return (Player) source;
        }

        return null;
    }

    /** A single recorded hit. */
    private static final class Hit {
        final UUID playerId;
        final String playerName;
        final double amount;
        final long timestamp;

        Hit(UUID playerId, String playerName, double amount, long timestamp) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }
}
