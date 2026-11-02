package org.qiyuan.macesurvival.drop;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GameState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** One cancellable, identity-checked task per drop, including blackout and landing. */
public final class DropManager implements Listener {
    private final MaceSurvival plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long generation;
    private int nextSpawn;

    public DropManager(MaceSurvival plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void startDropForAll() {
        Bukkit.getOnlinePlayers().stream().filter(player -> plugin.game().getPlayer(player).isAlive()).forEach(this::startDrop);
    }

    public void startDrop(Player player) {
        startDrop(player, plugin.configs().main().getDouble("drop.height", 320D));
    }

    /** The compatibility height argument is clamped to the generated world's build range. */
    public void startDrop(Player player, double height) {
        cancel(player);
        if (!activeRound() || !plugin.game().participant(player)) return;
        long token = generation;
        Session session = null;
        try {
            if (!player.isOnline() || !plugin.arenas().isReady()) {
                notifyFailure(player, token);
                return;
            }
            Location destination = plugin.arenas().spawnFor(nextSpawn++, Math.max(1, plugin.game().aliveCount()));
            World world = destination.getWorld();
            if (world == null) { notifyFailure(player, token); return; }
            double ground = world.getHighestBlockYAt(destination.getBlockX(), destination.getBlockZ(), HeightMap.MOTION_BLOCKING);
            destination.setY(Math.min(world.getMaxHeight() - 4D, Math.max(ground + 50D, height)));
            int blackout = plugin.configs().main().getBoolean("drop.blackout", true)
                ? Math.max(0, Math.min(1200, plugin.configs().main().getInt("drop.blackout-ticks", 25))) : 0;
            session = new Session(player, destination, token, blackout);
            sessions.put(player.getUniqueId(), session);
            player.closeInventory();
            // Closing an inventory can synchronously call another plugin and reset this round.
            if (!sameRound(session)) { cancelSession(session); return; }
            player.setGravity(false);
            player.setVelocity(new Vector());
            player.setFallDistance(0F);
            if (blackout > 0) {
                int effectTicks = blackout + 5;
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, effectTicks, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, effectTicks, 0, false, false, false));
            }
            Session scheduled = session;
            session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try { tick(scheduled); }
                catch (RuntimeException error) {
                    plugin.getLogger().log(Level.WARNING, "Drop task failed for " + player.getUniqueId(), error);
                    abort(scheduled);
                }
            }, 1L, 1L);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Cannot start drop for " + player.getUniqueId(), error);
            if (session != null) abort(session);
            else notifyFailure(player, token);
        }
    }

    private void tick(Session session) {
        Player player = session.player;
        if (!sameRound(session)) {
            cancelSession(session);
            return;
        }
        if (!player.isOnline() || player.isDead() || !plugin.game().participant(player)) {
            abort(session);
            return;
        }
        session.ticks++;
        if (session.ticks > 2400) {
            // Guard every phase, including blackout/teleport, so a failed drop cannot stall the round.
            abort(session);
            return;
        }
        if (session.phase == Phase.BLACKOUT) {
            player.setVelocity(new Vector());
            if (session.ticks >= session.blackoutTicks) teleport(session);
            return;
        }
        if (session.phase == Phase.TELEPORTING) {
            return;
        }
        if (!player.getWorld().equals(session.destination.getWorld()) || player.getGameMode() != GameMode.SURVIVAL) {
            abort(session);
            return;
        }
        Location location = player.getLocation();
        if (landed(player)) {
            finish(session);
            return;
        }
        double floor = location.getWorld().getHighestBlockYAt(location.getBlockX(), location.getBlockZ(), HeightMap.MOTION_BLOCKING) + 1D;
        double openAt = Math.max(5D, plugin.configs().main().getDouble("drop.open-at-height", 14D));
        if (session.phase == Phase.DESCENT && location.getY() - floor <= openAt) {
            session.phase = Phase.PARACHUTE;
            player.setFallDistance(0F);
            player.playSound(location, Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8F, 1F);
            player.sendMessage(plugin.messages().prefixed("drop.parachute-opened"));
        }
        double fall = session.phase == Phase.PARACHUTE
            ? Math.max(0.12D, Math.min(0.4D, plugin.configs().main().getDouble("drop.parachute-fall-speed", 0.22D)))
            : Math.max(0.5D, Math.min(3D, plugin.configs().main().getDouble("drop.fall-speed", 1.6D)));
        double steer = Math.max(0D, Math.min(0.8D, plugin.configs().main().getDouble("drop.steer-speed", 0.42D)));
        if (session.phase == Phase.PARACHUTE) steer *= 0.65D;
        double yaw = Math.toRadians(location.getYaw());
        // Only yaw contributes to steering. Looking up/down never changes descent momentum.
        player.setVelocity(new Vector(-Math.sin(yaw) * steer, -fall, Math.cos(yaw) * steer));
        player.setFallDistance(0F);
        if (session.ticks % 4 == 0 && plugin.configs().main().getBoolean("drop.trail-particles", true))
            player.getWorld().spawnParticle(Particle.CLOUD, location.clone().add(0, 0.6, 0), 2, 0.15, 0.1, 0.15, 0.005);
    }

    private void teleport(Session session) {
        session.phase = Phase.TELEPORTING;
        Player player = session.player;
        // prepareArena has already loaded every drop chunk. A synchronous move is atomic with
        // round cancellation; an outstanding teleportAsync could otherwise finish after reset.
        boolean moved = player.teleport(session.destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!sameRound(session)) { cancelSession(session); return; }
        if (!moved || !player.isOnline() || !plugin.game().participant(player)) {
            abort(session);
            return;
        }
        session.phase = Phase.DESCENT;
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGravity(false);
        player.setFallDistance(0F);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
    }

    private boolean landed(Player player) {
        Location feet = player.getLocation();
        if (feet.getBlock().isLiquid()) return true;
        return !feet.clone().subtract(0D, 0.09D, 0D).getBlock().isPassable();
    }

    private void finish(Session session) {
        if (!sameRound(session)) { cancelSession(session); return; }
        Player player = session.player;
        cancelSession(session);
        if (session.generation != generation || !player.isOnline() || !activeRound() || !plugin.game().participant(player)
            || plugin.arenas().getArenaWorld() != session.destination.getWorld()
            || !player.getWorld().equals(session.destination.getWorld())) {
            if (plugin.arenas().getArenaWorld() == session.destination.getWorld()) notifyFailure(player, session.generation);
            return;
        }
        try {
            player.setVelocity(new Vector());
            player.setFallDistance(0F);
            plugin.game().onLanded(player);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5F, 1F);
        } catch (RuntimeException error) {
            // The task has already been removed. Report explicitly if landing/kit application fails.
            notifyFailure(player, session.generation);
            throw error;
        }
    }

    private boolean current(Session session) { return sessions.get(session.player.getUniqueId()) == session; }
    private boolean sameRound(Session session) {
        return current(session) && session.generation == generation && activeRound()
            && plugin.arenas().getArenaWorld() == session.destination.getWorld();
    }
    private void abort(Session session) {
        boolean notify = sameRound(session);
        try { cancelSession(session); }
        finally {
            if (notify && plugin.arenas().getArenaWorld() == session.destination.getWorld()) notifyFailure(session.player, session.generation);
        }
    }
    private void notifyFailure(Player player, long token) {
        if (token == generation && !sessions.containsKey(player.getUniqueId()) && activeRound() && plugin.game().participant(player))
            plugin.game().onDropFailed(player.getUniqueId());
    }
    private boolean activeRound() {
        GameState state = plugin.game().state();
        return state == GameState.DROPPING || state == GameState.RUNNING;
    }

    public boolean isDropping(Player player) { return sessions.containsKey(player.getUniqueId()); }
    public void cancel(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) cancelSession(session);
    }
    public void cancelAll() {
        // Invalidate first: cancellation events or stale task callbacks cannot report into a later round.
        generation++;
        for (Session session : List.copyOf(sessions.values())) cancelSession(session);
        nextSpawn = 0;
    }

    private void cancelSession(Session session) {
        if (!sessions.remove(session.player.getUniqueId(), session)) return;
        if (session.task != null) session.task.cancel();
        Player player = session.player;
        player.setGravity(session.originalGravity);
        player.setFallDistance(0F);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectDrop(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isDropping(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void steer(PlayerMoveEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getTo() == null) return;
        if (session.phase == Phase.BLACKOUT) {
            Location frozen = event.getFrom().clone();
            frozen.setYaw(event.getTo().getYaw());
            frozen.setPitch(event.getTo().getPitch());
            event.setTo(frozen);
        } else if (session.phase == Phase.DESCENT || session.phase == Phase.PARACHUTE) {
            if (plugin.configs().main().getBoolean("drop.lock-vertical-look", true)) event.getTo().setPitch(55F);
            double limit = Math.max(8D, plugin.arenas().getRadius() - 3D);
            event.getTo().setX(Math.max(-limit, Math.min(limit, event.getTo().getX())));
            event.getTo().setZ(Math.max(-limit, Math.min(limit, event.getTo().getZ())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void externalTeleport(PlayerTeleportEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && session.phase != Phase.TELEPORTING && event.getTo() != null
            && !event.getTo().getWorld().equals(session.destination.getWorld())) abort(session);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) abort(session);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent event) {
        Session session = sessions.get(event.getEntity().getUniqueId());
        if (session != null) abort(session);
    }

    private enum Phase { BLACKOUT, TELEPORTING, DESCENT, PARACHUTE }
    private static final class Session {
        private final Player player;
        private final Location destination;
        private final boolean originalGravity;
        private final long generation;
        private final int blackoutTicks;
        private Phase phase = Phase.BLACKOUT;
        private BukkitTask task;
        private int ticks;
        private Session(Player player, Location destination, long generation, int blackoutTicks) {
            this.player = player;
            this.destination = destination;
            originalGravity = player.hasGravity();
            this.generation = generation;
            this.blackoutTicks = blackoutTicks;
        }
    }
}
