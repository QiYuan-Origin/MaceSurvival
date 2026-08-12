package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.combat.StarterLayout;
import club.mcqi.macesurvival.team.LoadoutLayout;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.world.WorldManager;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DeploymentController {
    public interface Listener {
        void onDeploymentFinished();
        void onPlayerLanded(Player player);
    }

    private final MaceSurvivalPlugin plugin;
    private final WorldManager worldManager;
    private final TeamManager teamManager;
    private final LoadoutLayoutManager loadoutLayouts;
    private final CombatManager combat;
    private final Listener listener;
    private final Map<UUID, UUID> ghastTeam = new HashMap<>();
    private final Map<UUID, TeamData> deploymentTeams = new LinkedHashMap<>();
    private final Map<UUID, Location> teamDeploymentLocations = new HashMap<>();
    private final Set<UUID> dropping = new HashSet<>();
    private final Set<UUID> landed = new HashSet<>();
    private BukkitTask steeringTask;
    private BukkitTask finishTask;
    private long deploymentStartedTick;
    private boolean systemDismounting;

    public DeploymentController(
            MaceSurvivalPlugin plugin,
            WorldManager worldManager,
            TeamManager teamManager,
            LoadoutLayoutManager loadoutLayouts,
            CombatManager combat,
            Listener listener
    ) {
        this.plugin = plugin;
        this.worldManager = worldManager;
        this.teamManager = teamManager;
        this.loadoutLayouts = loadoutLayouts;
        this.combat = combat;
        this.listener = listener;
    }

    public CompletableFuture<Void> prepareAndDeploy(Collection<Participant> participants) {
        stop();
        World world = worldManager.matchWorld();
        List<TeamData> teams = collectTeams(participants);
        Map<TeamData, SpawnPoint> points = spreadPoints(teams);
        CompletableFuture<?>[] chunkLoads = points.values().stream()
                .map(point -> world.getChunkAtAsync(point.x() >> 4, point.z() >> 4))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(chunkLoads).thenRun(() ->
                plugin.getServer().getScheduler().runTask(plugin, () -> deploy(resolveLocations(world, points))));
    }

    private List<TeamData> collectTeams(Collection<Participant> participants) {
        List<TeamData> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Participant participant : participants) {
            TeamData team = teamManager.ensureSoloTeam(participant.playerId());
            if (seen.add(team.id())) result.add(team);
        }
        return result;
    }

    private Map<TeamData, SpawnPoint> spreadPoints(List<TeamData> teams) {
        double usableRadius = plugin.getConfig().getIntegerList("border.radii").stream()
                .findFirst().orElse(3000) - 100.0;
        double spacing = Math.max(32.0,
                plugin.getConfig().getDouble("deployment.team-separation-blocks", 400.0));
        List<SpawnPoint> candidates = hexagonalPoints(usableRadius, spacing);
        while (candidates.size() < teams.size() && spacing > 32.0) {
            spacing = Math.max(32.0, spacing * 0.90);
            candidates = hexagonalPoints(usableRadius, spacing);
        }
        Collections.shuffle(candidates);
        Map<TeamData, SpawnPoint> result = new LinkedHashMap<>();
        for (int index = 0; index < teams.size(); index++) {
            SpawnPoint point = candidates.get(index % candidates.size());
            result.put(teams.get(index), point);
        }
        return result;
    }

    private List<SpawnPoint> hexagonalPoints(double radius, double spacing) {
        List<SpawnPoint> points = new ArrayList<>();
        double rowSpacing = spacing * Math.sqrt(3.0) / 2.0;
        int row = 0;
        for (double z = -radius; z <= radius; z += rowSpacing) {
            double offset = (row++ & 1) == 0 ? 0.0 : spacing / 2.0;
            for (double x = -radius + offset; x <= radius; x += spacing) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                double angle = Math.atan2(z, x);
                points.add(new SpawnPoint((int) Math.round(x), (int) Math.round(z),
                        (float) Math.toDegrees(angle)));
            }
        }
        if (points.isEmpty()) {
            points.add(new SpawnPoint(0, 0, 0.0F));
        }
        return points;
    }

    private Map<TeamData, Location> resolveLocations(World world, Map<TeamData, SpawnPoint> points) {
        Map<TeamData, Location> result = new LinkedHashMap<>();
        for (Map.Entry<TeamData, SpawnPoint> entry : points.entrySet()) {
            SpawnPoint point = refineSpawnPoint(world, entry.getValue());
            int surface = world.getHighestBlockYAt(point.x(), point.z());
            int configuredMinimum = plugin.getConfig().getInt("deployment.minimum-altitude", 300);
            int aboveSurface = plugin.getConfig().getInt("deployment.altitude-above-surface", 220);
            int y = Math.max(configuredMinimum, surface + aboveSurface);
            result.put(entry.getKey(), new Location(world, point.x() + 0.5, y, point.z() + 0.5, point.yaw(), 0.0f));
        }
        return result;
    }

    private void deploy(Map<TeamData, Location> locations) {
        deploymentStartedTick = worldManager.matchWorld().getGameTime();
        int teamNumber = 1;
        for (Map.Entry<TeamData, Location> entry : locations.entrySet()) {
            TeamData team = entry.getKey();
            Location location = entry.getValue();
            String teamLabel = team.size() <= 1 ? "SOLO" : String.format(Locale.ROOT, "T%02d", teamNumber);
            teamNumber++;
            HappyGhast ghast = (HappyGhast) location.getWorld().spawnEntity(location, EntityType.HAPPY_GHAST);
            ghast.setInvulnerable(true);
            ghast.setPersistent(false);
            ghast.setRemoveWhenFarAway(false);
            ghast.setCustomNameVisible(true);
            ghast.setGlowing(true);
            ghast.customName(plugin.text().parse(null,
                "<font:minecraft:uniform><{color}><shadow:#401818:1>{label}</shadow></{color}>"
                    + " <dark_gray>DROP</dark_gray> <gray>{size}/4</gray></font>",
                Map.of(
                    "color", net.kyori.adventure.text.format.TextColor.color(team.color().asRGB()).asHexString(),
                    "label", teamLabel,
                    "size", team.size()
                )
            ));
            if (ghast.getEquipment() != null) {
                ghast.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(harnessMaterial(team)));
            }
            ghastTeam.put(ghast.getUniqueId(), team.id());
            deploymentTeams.put(team.id(), team);
            teamDeploymentLocations.put(team.id(), location.clone());

            List<UUID> ordered = new ArrayList<>(team.members());
            ordered.remove(team.leader());
            ordered.addFirst(team.leader());
            for (UUID playerId : ordered) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player == null) continue;
                preparePlayer(player, location, teamLabel);
                ghast.addPassenger(player);
            }
            location.getWorld().playSound(location, Sound.ENTITY_GHAST_AMBIENT, 0.65f, 1.0f);
        }
        steeringTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::steerDroppingPlayers, 1L, 1L);
        int forceSeconds = plugin.getConfig().getInt("deployment.forced-jump-seconds", 30);
        finishTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::forceAllJump, forceSeconds * 20L);
    }

    private void preparePlayer(Player player, Location location, String teamLabel) {
        player.closeInventory();
        player.setGameMode(GameMode.SURVIVAL);
        player.setInvulnerable(true);
        org.bukkit.attribute.AttributeInstance maximumHealth =
                player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        player.setHealth(maximumHealth == null ? 20.0 : maximumHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        giveStartingLoadout(player);
        player.teleport(location);
        player.showTitle(Title.title(
            plugin.text().message(player, "deployment.ride-title", Map.of("team", teamLabel)),
            plugin.text().message(player, "deployment.ride-subtitle", Map.of()),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))
        ));
        player.sendActionBar(plugin.text().message(player, "deployment.ride-actionbar", Map.of()));
    }

    private void giveStartingLoadout(Player player) {
        LoadoutLayout layout = loadoutLayouts.layout(player);
        combat.equipStarterKit(player, new StarterLayout(
                layout.swordSlot(), layout.axeSlot(), layout.firstMaceSlot(), layout.secondMaceSlot()));
        player.getInventory().setBoots(new ItemStack(Material.IRON_BOOTS));
    }

    public boolean handleDismount(Player player, Entity vehicle) {
        if (systemDismounting || !(vehicle instanceof HappyGhast ghast)) return true;
        UUID teamId = ghastTeam.get(ghast.getUniqueId());
        if (teamId == null) return true;
        TeamData team = deploymentTeams.get(teamId);
        if (team == null) return true;
        long elapsed = worldManager.matchWorld().getGameTime() - deploymentStartedTick;
        int deadline = plugin.getConfig().getInt("deployment.voluntary-jump-seconds", 15) * 20;
        if (elapsed > deadline) return false;
        if (team.leader().equals(player.getUniqueId())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> jumpTeam(ghast, team));
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> jumpPlayer(ghast, player));
        }
        return true;
    }

    private void forceAllJump() {
        systemDismounting = true;
        for (World world : plugin.getServer().getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                TeamData team = deploymentTeams.get(ghastTeam.get(ghast.getUniqueId()));
                if (team != null) jumpTeam(ghast, team);
            }
        }
        systemDismounting = false;
        listener.onDeploymentFinished();
    }

    private void jumpTeam(HappyGhast ghast, TeamData team) {
        if (!ghast.isValid()) return;
        systemDismounting = true;
        for (UUID playerId : team.members()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) continue;
            boolean wasPassenger = ghast.getPassengers().contains(player);
            if (wasPassenger) {
                ghast.removePassenger(player);
            }
            if (wasPassenger || (!dropping.contains(playerId) && !landed.contains(playerId))) {
                beginDrop(player);
            }
        }
        systemDismounting = false;
        ghast.getWorld().playSound(ghast.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1.2f, 1.0f);
        ghast.remove();
    }

    private void jumpPlayer(HappyGhast ghast, Player player) {
        UUID playerId = player.getUniqueId();
        if (dropping.contains(playerId) || landed.contains(playerId)) return;
        systemDismounting = true;
        if (ghast.isValid() && ghast.getPassengers().contains(player)) {
            ghast.removePassenger(player);
        }
        systemDismounting = false;
        beginDrop(player);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1.0f, 1.0f);
    }

    private void beginDrop(Player player) {
        Vector facing = player.getEyeLocation().getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.0001) facing = new Vector(0.0, 0.0, 1.0);
        double speed = plugin.getConfig().getDouble("deployment.horizontal-speed", 0.9);
        facing.normalize().multiply(speed);
        facing.setY(plugin.getConfig().getDouble("deployment.fixed-vertical-start-speed", -1.1));
        player.setVelocity(facing);
        player.setFallDistance(0.0f);
        dropping.add(player.getUniqueId());
        player.showTitle(Title.title(
            plugin.text().message(player, "deployment.drop-title", Map.of()),
            plugin.text().message(player, "deployment.drop-subtitle", Map.of()),
            Title.Times.times(Duration.ofMillis(120), Duration.ofSeconds(2), Duration.ofMillis(350))
        ));
        player.sendActionBar(plugin.text().message(player, "deployment.drop-actionbar", Map.of()));
    }

    public void restoreDisconnected(Player player) {
        if (landed.contains(player.getUniqueId())) {
            player.setInvulnerable(false);
            return;
        }
        if (!dropping.contains(player.getUniqueId())) {
            TeamData team = teamManager.teamOf(player.getUniqueId()).orElse(null);
            if (team != null) {
                HappyGhast ghast = findDeploymentGhast(team.id());
                if (ghast != null) {
                    player.teleport(ghast.getLocation());
                    ghast.addPassenger(player);
                    return;
                }
                Location deploymentLocation = teamDeploymentLocations.get(team.id());
                if (deploymentLocation != null) {
                    player.teleport(deploymentLocation);
                }
            }
        }
        beginDrop(player);
    }

    private HappyGhast findDeploymentGhast(UUID teamId) {
        for (HappyGhast ghast : worldManager.matchWorld().getEntitiesByClass(HappyGhast.class)) {
            if (teamId.equals(ghastTeam.get(ghast.getUniqueId())) && ghast.isValid()) {
                return ghast;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void steerDroppingPlayers() {
        double speed = plugin.getConfig().getDouble("deployment.horizontal-speed", 0.9);
        for (UUID playerId : Set.copyOf(dropping)) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            if (player.isOnGround()) {
                dropping.remove(playerId);
                landed.add(playerId);
                player.setFallDistance(0.0f);
                int immunity = plugin.getConfig().getInt("deployment.fall-damage-immunity-ticks-after-landing", 60);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.setInvulnerable(false), immunity);
                listener.onPlayerLanded(player);
                continue;
            }
            Vector horizontal = player.getEyeLocation().getDirection().setY(0.0);
            if (horizontal.lengthSquared() < 0.0001) continue;
            horizontal.normalize().multiply(speed);
            double vertical = Math.min(player.getVelocity().getY(), -0.08);
            player.setVelocity(new Vector(horizontal.getX(), vertical, horizontal.getZ()));
            player.setFallDistance(0.0f);
        }
    }

    public boolean isDropping(UUID playerId) { return dropping.contains(playerId); }
    public boolean hasLanded(UUID playerId) { return landed.contains(playerId); }

    public void stop() {
        if (steeringTask != null) steeringTask.cancel();
        if (finishTask != null) finishTask.cancel();
        steeringTask = null;
        finishTask = null;
        dropping.clear();
        landed.clear();
        ghastTeam.clear();
        deploymentTeams.clear();
        teamDeploymentLocations.clear();
    }

    private SpawnPoint refineSpawnPoint(World world, SpawnPoint original) {
        if (isGoodSurface(world, original.x(), original.z())) {
            return original;
        }
        for (int radius = 32; radius <= 192; radius += 32) {
            for (int dx = -radius; dx <= radius; dx += 16) {
                for (int dz = -radius; dz <= radius; dz += 16) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = original.x() + dx;
                    int z = original.z() + dz;
                    if (isGoodSurface(world, x, z)) {
                        double angle = Math.atan2(z, x);
                        return new SpawnPoint(x, z, (float) Math.toDegrees(angle));
                    }
                }
            }
        }
        return original;
    }

    private static boolean isGoodSurface(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        Material base = world.getBlockAt(x, y, z).getType();
        Material above = world.getBlockAt(x, y + 1, z).getType();
        return base.isSolid()
            && base != Material.WATER
            && base != Material.LAVA
            && above.isAir();
    }

    private static Material harnessMaterial(TeamData team) {
        float[] hsb = java.awt.Color.RGBtoHSB(
            team.color().getRed(),
            team.color().getGreen(),
            team.color().getBlue(),
            null
        );
        float hue = hsb[0];
        if (hue < 0.04F || hue >= 0.95F) return Material.RED_HARNESS;
        if (hue < 0.10F) return Material.ORANGE_HARNESS;
        if (hue < 0.18F) return Material.YELLOW_HARNESS;
        if (hue < 0.30F) return Material.LIME_HARNESS;
        if (hue < 0.42F) return Material.GREEN_HARNESS;
        if (hue < 0.52F) return Material.CYAN_HARNESS;
        if (hue < 0.62F) return Material.LIGHT_BLUE_HARNESS;
        if (hue < 0.72F) return Material.BLUE_HARNESS;
        if (hue < 0.82F) return Material.PURPLE_HARNESS;
        if (hue < 0.90F) return Material.MAGENTA_HARNESS;
        return Material.PINK_HARNESS;
    }

    private record SpawnPoint(int x, int z, float yaw) { }
}
