package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BorderController {
    public interface Listener {
        void onStageFinished(int stageIndex);
        void onBoundaryMoved(WorldBorder border);
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
    private Location segmentStart;
    private Location segmentTarget;
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
        border.setSize(currentRadius * 2.0);
        border.setDamageBuffer(0.0);
        border.setDamageAmount(plugin.getConfig().getDouble("border.damage-per-second", 2.0));
        border.setWarningDistance(plugin.getConfig().getInt("border.warning-distance", 32));
        segmentStart = border.getCenter();
        beginStageTransition();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void beginStageTransition() {
        if (stageIndex >= radii.size() - 1) return;
        segmentStartedAtTick = elapsedTicks;
        segmentStart = world.getWorldBorder().getCenter();
        selectContainedStageTarget();
        world.getWorldBorder().changeSize(radii.get(stageIndex + 1) * 2.0, segmentSeconds);
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
                    segmentStart = world.getWorldBorder().getCenter();
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
                segmentStart = world.getWorldBorder().getCenter();
                selectFinalTarget();
            }
        }
        if (elapsedTicks % 5L == 0L) {
            renderParticles();
        }
        if (elapsedTicks % 20L == 0L) {
            listener.onBoundaryMoved(world.getWorldBorder());
        }
    }

    private void moveCenter(double progress) {
        double x = segmentStart.getX() + ((segmentTarget.getX() - segmentStart.getX()) * progress);
        double z = segmentStart.getZ() + ((segmentTarget.getZ() - segmentStart.getZ()) * progress);
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
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        Color inside = parseColor(plugin.getConfig().getString("border.particle-color", "#E52B2B"));
        Color outside = parseColor(plugin.getConfig().getString("border.outside-particle-color", "#650000"));
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double dx = player.getX() - center.getX();
            double dz = player.getZ() - center.getZ();
            double distance = Math.hypot(dx, dz);
            if (Math.abs(distance - currentRadius) > 72.0) continue;
            double baseAngle = Math.atan2(dz, dx);
            Particle.DustOptions dust = new Particle.DustOptions(distance > currentRadius ? outside : inside, 1.25f);
            for (int offset = -5; offset <= 5; offset++) {
                double angle = baseAngle + (offset * 0.0125);
                double x = center.getX() + Math.cos(angle) * currentRadius;
                double z = center.getZ() + Math.sin(angle) * currentRadius;
                player.spawnParticle(Particle.DUST, x, player.getY() + 1.0, z, 1, 0.0, 0.8, 0.0, 0.0, dust);
            }
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
    public int stageIndex() { return stageIndex; }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
