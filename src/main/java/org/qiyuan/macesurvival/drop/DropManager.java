package org.qiyuan.macesurvival.drop;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GamePlayer;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 开局跳伞。流程是：黑屏遮住地图生成 -> 传送到高空 -> 每 tick 接管速度 -> 接近地面自动开伞。
 * 下落靠直接写速度而不是靠重力，这样速度可控且不会被视角影响；
 * 水平转向只取视角的水平分量，Y 分量丢掉，所以低头不会加速、抬头不会飘。
 */
public final class DropManager {

    /** 一个人的跳伞状态。 */
    private static final class Dive {
        private final UUID uuid;
        private boolean parachute;
        private int ticks;

        Dive(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private final MaceSurvival plugin;
    private final Map<UUID, Dive> dives = new LinkedHashMap<>();
    private BukkitTask ticker;

    public DropManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public boolean isDiving(Player player) {
        return dives.containsKey(player.getUniqueId());
    }

    /** 黑屏。地图生成有可见的方块变化，用黑屏盖住整个过程。 */
    public void blackout(Player player) {
        if (!plugin.configs().main().getBoolean("drop.blackout", true)) {
            return;
        }
        int fadeIn = plugin.configs().main().getInt("drop.blackout-fade-in", 10);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                20 * 60, 1, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                20 * 60, 0, false, false, false));
        plugin.messages().sendTitle(player, "drop.blackout-title", "drop.blackout-subtitle");
        if (fadeIn > 0) {
            player.setWalkSpeed(0f);
        }
    }

    private void clearBlackout(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setWalkSpeed(0.2f);
    }

    /** 传送到高空并开始接管下落。 */
    public void beginDive(Player player, GamePlayer gp) {
        World world = plugin.arena().world();
        if (world == null) {
            return;
        }
        int height = plugin.configs().main().getInt("drop.height", 320);
        // 起跳点随机散开，避免所有人叠在同一根柱子上互砸
        int radius = plugin.configs().main().getInt("arena.radius", 400);
        double angle = Math.random() * Math.PI * 2;
        double distance = Math.random() * (radius * 0.55);
        Location start = new Location(world,
                Math.cos(angle) * distance,
                height,
                Math.sin(angle) * distance);
        start.setYaw((float) Math.toDegrees(-angle) + 90f);
        start.setPitch(35f);

        player.setGameMode(GameMode.SURVIVAL);
        player.setFallDistance(0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.teleport(start);
        clearBlackout(player);

        gp.parachuteOpen(false);
        gp.landed(false);
        dives.put(player.getUniqueId(), new Dive(player.getUniqueId()));

        Sounds.play(plugin, player, Sounds.dropStart());
        plugin.messages().sendTitle(player, "drop.title", "drop.subtitle");
        startTicker();
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    /** 每 tick 接管所有正在跳伞的玩家。 */
    private void tick() {
        if (dives.isEmpty()) {
            stopTicker();
            return;
        }
        for (Dive dive : Map.copyOf(dives).values()) {
            Player player = plugin.getServer().getPlayer(dive.uuid);
            GamePlayer gp = plugin.game().of(dive.uuid);
            if (player == null || gp == null) {
                dives.remove(dive.uuid);
                continue;
            }
            dive.ticks++;
            steer(player, gp, dive);
        }
    }

    /**
     * 核心：水平方向跟随视角，垂直方向由配置固定。
     * 未开伞时快速下落，开伞后减速，落地则交还控制权。
     */
    private void steer(Player player, GamePlayer gp, Dive dive) {
        double groundY = highestGround(player.getLocation());
        double altitude = player.getLocation().getY() - groundY;
        int openAt = plugin.configs().main().getInt("drop.open-at-height", 14);

        if (!dive.parachute && altitude <= openAt) {
            openParachute(player, gp, dive);
        }

        double fall = dive.parachute
                ? -plugin.configs().main().getDouble("drop.parachute-fall-speed", 0.22)
                : -plugin.configs().main().getDouble("drop.fall-speed", 1.6);
        double steerSpeed = plugin.configs().main().getDouble("drop.steer-speed", 0.42);

        Vector look = player.getLocation().getDirection();
        if (plugin.configs().main().getBoolean("drop.lock-vertical-look", true)) {
            // 只取水平分量，低头不会额外加速，抬头也不会把人往上带
            look.setY(0);
        }
        if (look.lengthSquared() > 1.0E-4) {
            look.normalize().multiply(steerSpeed);
        } else {
            look.zero();
        }
        player.setVelocity(new Vector(look.getX(), fall, look.getZ()));
        player.setFallDistance(0f);

        if (plugin.configs().main().getBoolean("drop.trail-particles", true) && dive.ticks % 2 == 0) {
            player.getWorld().spawnParticle(Particle.CLOUD,
                    player.getLocation().add(0, 0.2, 0), 2, 0.12, 0.05, 0.12, 0.0);
        }

        plugin.messages().actionBar(player,
                dive.parachute ? "drop.actionbar-parachute" : "drop.actionbar-diving",
                org.qiyuan.macesurvival.config.MessageManager.vars(
                        "altitude", String.valueOf(Math.max(0, (int) altitude))));

        // 不用 isOnGround，那个值由客户端上报且已废弃，直接看脚下的方块
        if (standingOnSolid(player) || altitude <= 0.6) {
            land(player, gp);
        }
    }

    private void openParachute(Player player, GamePlayer gp, Dive dive) {
        dive.parachute = true;
        gp.parachuteOpen(true);
        Sounds.play(plugin, player, Sounds.parachute());
        plugin.messages().send(player, "drop.parachute-open");
    }

    /** 落地。清掉坠落伤害并把控制权还给玩家。 */
    private void land(Player player, GamePlayer gp) {
        dives.remove(player.getUniqueId());
        player.setFallDistance(0f);
        player.setVelocity(new Vector(0, 0, 0));
        player.setWalkSpeed(0.2f);
        plugin.game().onLanded(gp);
    }

    /** 玩家脚下最高的实心方块高度。跳伞时区块已加载，直接查即可。 */
    /** 脚下半格内有实心方块就算落地，服务端自己判定不依赖客户端。 */
    private boolean standingOnSolid(Player player) {
        return player.getLocation().subtract(0, 0.1, 0).getBlock().getType().isSolid();
    }

    private double highestGround(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        for (int y = Math.min(location.getBlockY(), world.getMaxHeight() - 1); y > world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return y + 1;
            }
        }
        return world.getMinHeight();
    }

    /** 玩家掉线或强制结束时清状态。 */
    public void cancel(UUID uuid) {
        dives.remove(uuid);
        if (dives.isEmpty()) {
            stopTicker();
        }
    }

    public void clear() {
        dives.clear();
        stopTicker();
    }
}
