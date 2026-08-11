package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.combat.StarterLayout;
import club.mcqi.macesurvival.team.LoadoutLayout;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.world.WorldManager;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
            SpawnPoint point = entry.getValue();
            int x = point.x();
            int z = point.z();
            int surface = world.getHighestBlockYAt(x, z);
            int configuredMinimum = plugin.getConfig().getInt("deployment.minimum-altitude", 300);
            int aboveSurface = plugin.getConfig().getInt("deployment.altitude-above-surface", 220);
            int y = Math.max(configuredMinimum, surface + aboveSurface);
            result.put(entry.getKey(), new Location(world, x + 0.5, y, z + 0.5, point.yaw(), 0.0f));
        }
        return result;
    }

    private void deploy(Map<TeamData, Location> locations) {
        deploymentStartedTick = worldManager.matchWorld().getGameTime();
        for (Map.Entry<TeamData, Location> entry : locations.entrySet()) {
            TeamData team = entry.getKey();
            Location location = entry.getValue();
            HappyGhast ghast = (HappyGhast) location.getWorld().spawnEntity(location, EntityType.HAPPY_GHAST);
            ghast.setInvulnerable(true);
            ghast.setPersistent(false);
            ghast.setRemoveWhenFarAway(false);
            ghast.setCustomNameVisible(false);
            if (ghast.getEquipment() != null) {
                ghast.getEquipment().setItem(EquipmentSlot.BODY, new ItemStack(Material.WHITE_HARNESS));
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
                preparePlayer(player, location);
                ghast.addPassenger(player);
            }
        }
        steeringTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::steerDroppingPlayers, 1L, 1L);
        int forceSeconds = plugin.getConfig().getInt("deployment.forced-jump-seconds", 30);
        finishTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::forceAllJump, forceSeconds * 20L);
    }

    private void preparePlayer(Player player, Location location) {
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

    private record SpawnPoint(int x, int z, float yaw) { }
}
