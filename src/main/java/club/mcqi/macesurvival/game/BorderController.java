package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class BorderController {
    public interface Listener {
        void onStageFinished(int stageIndex);
        void onBoundaryMoved(World world, Location center, double radius);
    }

    private final MaceSurvivalPlugin plugin;
    private final Listener listener;
    private final Random random = new Random();
    private final List<Double> radii = new ArrayList<>();
    private BukkitTask task;
    private World world;
    private long elapsedSeconds;
    private long elapsedTicks;
    private int stageIndex;
    private int segmentSeconds;
    private double hardRadius;
    private Location segmentStart;
    private Location segmentTarget;
    private Location center;
    private long segmentStartedAtTick;
    private double currentRadius;
    private double segmentDistance;

    public BorderController(MaceSurvivalPlugin plugin, Listener listener) {
        this.plugin = plugin;
        this.listener = listener;
        reloadValues();
    }

    public void reloadValues() {
        radii.clear();
        for (Integer value : plugin.getConfig().getIntegerList("border.radii")) {
            if (value > 0) radii.add(value.doubleValue());
        }
        if (radii.size() < 2) {
            radii.addAll(List.of(3000.0, 2000.0, 1000.0, 650.0, 280.0, 80.0, 24.0));
        }
        int total = plugin.getConfig().getInt("border.total-moving-seconds", 1500);
        segmentSeconds = Math.max(1, total / (radii.size() - 1));
        hardRadius = plugin.getConfig().getInt("match.hard-radius", 5000);
    }

    public void start(World world) {
        stop();
        reloadValues();
        this.world = world;
        elapsedSeconds = 0;
        elapsedTicks = 0;
        stageIndex = 0;
        currentRadius = radii.getFirst();
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(Math.max(1.0, hardRadius * 2.0));
        border.setDamageBuffer(0.0);
        border.setDamageAmount(0.0);
        border.setWarningDistance(plugin.getConfig().getInt("border.warning-distance", 32));
        segmentStart = border.getCenter();
        center = segmentStart.clone();
        beginStageTransition();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void beginStageTransition() {
        if (stageIndex >= radii.size() - 1) return;
        segmentStartedAtTick = elapsedTicks;
        segmentStart = center == null ? world.getWorldBorder().getCenter() : center.clone();
        selectContainedStageTarget();
    }

    private void tick() {
        elapsedTicks++;
        elapsedSeconds = elapsedTicks / 20L;
        if (stageIndex < radii.size() - 1) {
            long durationTicks = segmentSeconds * 20L;
            double progress = Math.min(1.0,
                    (elapsedTicks - segmentStartedAtTick) / (double) durationTicks);
            moveCenter(progress);
            double from = radii.get(stageIndex);
            double to = radii.get(stageIndex + 1);
            currentRadius = from + ((to - from) * progress);
            if (progress >= 1.0) {
                stageIndex++;
                currentRadius = radii.get(stageIndex);
                listener.onStageFinished(stageIndex);
                if (stageIndex < radii.size() - 1) beginStageTransition();
                else {
                    segmentStartedAtTick = elapsedTicks;
                    segmentStart = center == null ? world.getWorldBorder().getCenter() : center.clone();
                    selectFinalTarget();
                }
            }
        } else {
            double speed = Math.max(0.05,
                    plugin.getConfig().getDouble("border.final-center-speed", 3.0));
            long changeTicks = Math.max(1L, (long) Math.ceil((segmentDistance / speed) * 20.0));
            double progress = Math.min(1.0,
                    (elapsedTicks - segmentStartedAtTick) / (double) changeTicks);
            moveCenter(progress);
            if (progress >= 1.0) {
                segmentStartedAtTick = elapsedTicks;
                segmentStart = center == null ? world.getWorldBorder().getCenter() : center.clone();
                selectFinalTarget();
            }
        }
        if (elapsedTicks % Math.max(1L, plugin.getConfig().getLong("border.particle-wall-interval-ticks", 3L)) == 0L) {
            renderParticles();
        }
        if (elapsedTicks % 20L == 0L) {
            listener.onBoundaryMoved(world, center == null ? world.getWorldBorder().getCenter() : center.clone(), currentRadius);
        }
        if (elapsedTicks % 60L == 0L) {
            applyCircularDamage();
        }
    }

    private void moveCenter(double progress) {
        double x = segmentStart.getX() + ((segmentTarget.getX() - segmentStart.getX()) * progress);
        double z = segmentStart.getZ() + ((segmentTarget.getZ() - segmentStart.getZ()) * progress);
        center = new Location(world, x, 0.0, z);
        world.getWorldBorder().setCenter(x, z);
    }

    private void selectContainedStageTarget() {
        double fromRadius = radii.get(stageIndex);
        double targetRadius = radii.get(stageIndex + 1);
        double maximumShift = Math.max(0.0, fromRadius - targetRadius);
        double distance = Math.sqrt(random.nextDouble()) * maximumShift;
        double angle = random.nextDouble() * Math.PI * 2.0;
        segmentTarget = new Location(
                world,
                segmentStart.getX() + Math.cos(angle) * distance,
                0.0,
                segmentStart.getZ() + Math.sin(angle) * distance
        );
        clampTargetToHardBoundary(targetRadius);
        segmentDistance = horizontalDistance(segmentStart, segmentTarget);
    }

    private void selectFinalTarget() {
        int hardRadius = plugin.getConfig().getInt("match.hard-radius", 5000);
        double targetRadius = radii.getLast();
        int configuredSeconds = Math.max(1,
                plugin.getConfig().getInt("border.final-target-change-seconds", 20));
        double speed = Math.max(0.05,
                plugin.getConfig().getDouble("border.final-center-speed", 3.0));
        double maxStep = speed * configuredSeconds;
        double angle = random.nextDouble() * Math.PI * 2.0;
        segmentTarget = new Location(
                world,
                segmentStart.getX() + Math.cos(angle) * maxStep,
                0.0,
                segmentStart.getZ() + Math.sin(angle) * maxStep
        );
        double allowedCenterRadius = Math.max(0.0, hardRadius - targetRadius - 16.0);
        double targetFromOrigin = Math.hypot(segmentTarget.getX(), segmentTarget.getZ());
        if (targetFromOrigin > allowedCenterRadius && targetFromOrigin > 0.0) {
            double scale = allowedCenterRadius / targetFromOrigin;
            segmentTarget.setX(segmentTarget.getX() * scale);
            segmentTarget.setZ(segmentTarget.getZ() * scale);
        }
        segmentDistance = horizontalDistance(segmentStart, segmentTarget);
        if (segmentDistance < 0.01) {
            segmentTarget.setX(Math.max(-allowedCenterRadius,
                    Math.min(allowedCenterRadius, segmentStart.getX() + speed)));
            segmentDistance = horizontalDistance(segmentStart, segmentTarget);
        }
    }

    private void clampTargetToHardBoundary(double targetRadius) {
        int hardRadius = plugin.getConfig().getInt("match.hard-radius", 5000);
        double allowedCenterRadius = Math.max(0.0, hardRadius - targetRadius - 16.0);
        double distance = Math.hypot(segmentTarget.getX(), segmentTarget.getZ());
        if (distance <= allowedCenterRadius || distance == 0.0) {
            return;
        }
        double scale = allowedCenterRadius / distance;
        segmentTarget.setX(segmentTarget.getX() * scale);
        segmentTarget.setZ(segmentTarget.getZ() * scale);
    }

    private static double horizontalDistance(Location first, Location second) {
        return Math.hypot(second.getX() - first.getX(), second.getZ() - first.getZ());
    }

    private void renderParticles() {
        Location visualCenter = center == null ? world.getWorldBorder().getCenter() : center;
        Color inside = parseColor(plugin.getConfig().getString("border.particle-color", "#E52B2B"));
        Color outside = parseColor(plugin.getConfig().getString("border.outside-particle-color", "#650000"));
        Color transition = parseColor(plugin.getConfig().getString("border.particle-transition-color", "#FF7B7B"));
        double renderDistance = Math.max(16.0D,
            plugin.getConfig().getDouble("border.particle-render-distance", 180.0D));
        double band = Math.max(8.0D,
            plugin.getConfig().getDouble("border.particle-wall-band", 112.0D));
        int columns = Math.max(8, Math.min(96,
            plugin.getConfig().getInt("border.particle-columns-per-player", 48)));
        double stepDegrees = Math.max(0.25D,
            plugin.getConfig().getDouble("border.particle-angle-step-degrees", 2.0D));
        List<Double> heights = plugin.getConfig().getDoubleList("border.particle-height-levels");
        if (heights.isEmpty()) {
            heights = List.of(0.35D, 1.15D, 1.95D, 2.75D, 3.55D);
        }
        boolean transitionParticle = plugin.getConfig().getBoolean("border.particle-transition-enabled", true);
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double dx = player.getX() - visualCenter.getX();
            double dz = player.getZ() - visualCenter.getZ();
            double distance = Math.hypot(dx, dz);
            double distanceToRing = Math.abs(distance - currentRadius);
            if (distanceToRing > renderDistance) {
                if (elapsedTicks % 20L == 0L && distance > currentRadius) {
                    player.sendActionBar(plugin.text().message(player, "border.outside-actionbar", Map.of(
                        "distance", (int) Math.ceil(distance - currentRadius)
                    )));
                }
                continue;
            }
            double baseAngle = Math.atan2(dz, dx);
            double arcHalfRadians = Math.min(Math.PI,
                Math.max(band, renderDistance) / Math.max(16.0D, currentRadius));
            double configuredStep = Math.toRadians(stepDegrees);
            double step = Math.max(0.0025D, Math.min(configuredStep, (arcHalfRadians * 2.0D) / columns));
            Particle.DustOptions dust = new Particle.DustOptions(distance > currentRadius ? outside : inside, 1.45f);
            Particle.DustTransition transitionDust =
                new Particle.DustTransition(distance > currentRadius ? outside : inside, transition, 1.15f);
            int rendered = 0;
            for (double offset = -arcHalfRadians; offset <= arcHalfRadians && rendered < columns; offset += step) {
                double angle = baseAngle + offset;
                double x = visualCenter.getX() + Math.cos(angle) * currentRadius;
                double z = visualCenter.getZ() + Math.sin(angle) * currentRadius;
                if (Math.hypot(player.getX() - x, player.getZ() - z) > renderDistance + 24.0D) {
                    continue;
                }
                for (double height : heights) {
                    player.spawnParticle(Particle.DUST, x, player.getY() + height, z,
                        1, 0.02D, 0.03D, 0.02D, 0.0D, dust);
                }
                if (transitionParticle && rendered % 4 == 0) {
                    player.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, player.getY() + 1.6D, z,
                        1, 0.04D, 0.18D, 0.04D, 0.0D, transitionDust);
                }
                rendered++;
            }
            if (elapsedTicks % 20L == 0L) {
                if (distance > currentRadius) {
                    player.sendActionBar(plugin.text().message(player, "border.outside-actionbar", Map.of(
                        "distance", (int) Math.ceil(distance - currentRadius)
                    )));
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE,
                        SoundCategory.PLAYERS, 0.28F, 1.0F);
                } else if (distanceToRing < plugin.getConfig().getDouble("border.warning-distance", 32.0D)) {
                    player.sendActionBar(plugin.text().message(player, "border.near-actionbar", Map.of(
                        "distance", (int) Math.floor(currentRadius - distance)
                    )));
                }
            }
        }
    }

    private void applyCircularDamage() {
        double radius = currentRadius;
        double damage = plugin.getConfig().getDouble("border.damage-per-second", 2.0);
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            Location damageCenter = center == null ? world.getWorldBorder().getCenter() : center;
            double dx = player.getX() - damageCenter.getX();
            double dz = player.getZ() - damageCenter.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance <= radius) {
                continue;
            }
            player.sendActionBar(plugin.text().message(player, "border.damage-actionbar", Map.of(
                "distance", (int) Math.ceil(distance - radius)
            )));
            player.damage(Math.max(1.0, damage));
        }
    }

    private static Color parseColor(String value) {
        String normalized = value == null ? "E52B2B" : value.replace("#", "");
        try {
            return Color.fromRGB(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException exception) {
            return Color.fromRGB(229, 43, 43);
        }
    }

    public long elapsedSeconds() { return elapsedSeconds; }
    public double currentRadius() { return currentRadius; }
    public Location currentCenter() {
        return center == null ? null : center.clone();
    }
    public int stageIndex() { return stageIndex; }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
