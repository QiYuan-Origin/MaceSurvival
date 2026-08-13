package club.mcqi.macesurvival.world;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;
import java.util.Random;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Queue;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.scheduler.BukkitTask;

public final class WorldManager {
    private final MaceSurvivalPlugin plugin;
    private final Random random = new Random();
    private World lobbyWorld;
    private World matchWorld;
    private BukkitTask preloadTask;
    private int preloadedChunks;
    private int totalPreloadChunks;

    public WorldManager(MaceSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public void prepareLobby() {
        FileConfiguration config = plugin.getConfig();
        String name = config.getString("lobby.world", "world");
        lobbyWorld = plugin.getServer().getWorld(name);
        if (lobbyWorld == null) {
            lobbyWorld = WorldCreator.name(name)
                    .environment(World.Environment.NORMAL)
                    .generator(new VoidChunkGenerator())
                    .generateStructures(false)
                    .createWorld();
        }
        Objects.requireNonNull(lobbyWorld, "Lobby world could not be created");
        configureLobby(lobbyWorld);
    }

    @SuppressWarnings("removal")
    private void configureLobby(World world) {
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnFlags(false, false);
        Location spawn = lobbyLocation();
        world.setSpawnLocation(spawn);
        buildGlassLobbyBox(world, spawn);
    }

    private static void buildGlassLobbyBox(World world, Location spawn) {
        int minX = spawn.getBlockX() - 15;
        int maxX = spawn.getBlockX() + 16;
        int minZ = spawn.getBlockZ() - 15;
        int maxZ = spawn.getBlockZ() + 16;
        int floorY = spawn.getBlockY() - 1;
        int ceilingY = floorY + 9;
        for (int x = minX; x <= maxX; x++) {
            for (int y = floorY; y <= ceilingY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ
                        || y == floorY || y == ceilingY;
                    world.getBlockAt(x, y, z).setType(shell ? Material.GLASS : Material.AIR, false);
                }
            }
        }
    }

    public World prepareMatch() {
        if (matchWorld != null) return matchWorld;
        FileConfiguration config = plugin.getConfig();
        String prefix = config.getString("match.world-prefix", "macesurvival_match");
        cleanupStaleMatchWorlds(prefix);
        String worldName = prefix + "_" + Long.toUnsignedString(System.currentTimeMillis(), 36);
        long seed = config.getBoolean("match.random-seed", true)
                ? random.nextLong()
                : config.getLong("match.seed", 0L);
        WorldType type = worldType(config.getString("match.generator", "AMPLIFIED"));
        matchWorld = WorldCreator.name(worldName)
                .environment(World.Environment.NORMAL)
                .type(type)
                .seed(seed)
                .generateStructures(true)
                .createWorld();
        Objects.requireNonNull(matchWorld, "Match world could not be created");
        configureMatch(matchWorld);
        startPreload(matchWorld);
        return matchWorld;
    }

    public void preloadMatchWorld() {
        prepareMatch();
    }

    public World resetMatch() {
        World previous = matchWorld;
        stopPreload();
        if (previous != null) {
            matchWorld = null;
            String previousName = previous.getName();
            if (!plugin.getServer().unloadWorld(previous, false)) {
                matchWorld = previous;
                plugin.getLogger().warning("Could not unload match world " + previousName + "; reusing it");
                return previous;
            }
            deleteWorldDirectory(previousName);
        }
        return prepareMatch();
    }

    @SuppressWarnings("removal")
    private void configureMatch(World world) {
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setDifficulty(org.bukkit.Difficulty.HARD);
        world.setGameRule(GameRule.PVP, true);
        world.setTime(plugin.getConfig().getLong("match.fixed-time", 6000L));
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnFlags(false, false);
        int hardRadius = plugin.getConfig().getInt("match.hard-radius", 5000);
        world.getWorldBorder().setCenter(0.0, 0.0);
        world.getWorldBorder().setSize(hardRadius * 2.0);
        world.getWorldBorder().setDamageBuffer(0.0);
        world.getWorldBorder().setDamageAmount(
                plugin.getConfig().getDouble("border.damage-per-second", 2.0));
        world.getWorldBorder().setWarningDistance(
                plugin.getConfig().getInt("border.warning-distance", 32));
    }

    public Location lobbyLocation() {
        FileConfiguration config = plugin.getConfig();
        World world = Objects.requireNonNull(lobbyWorld, "Lobby world is not prepared");
        return new Location(
                world,
                config.getDouble("lobby.spawn.x", 0.5),
                config.getDouble("lobby.spawn.y", 65.0),
                config.getDouble("lobby.spawn.z", 0.5),
                (float) config.getDouble("lobby.spawn.yaw", 0.0),
                (float) config.getDouble("lobby.spawn.pitch", 0.0)
        );
    }

    public void setLobby(Location location) {
        lobbyWorld = location.getWorld();
        plugin.getConfig().set("lobby.world", lobbyWorld.getName());
        plugin.getConfig().set("lobby.spawn.x", location.getX());
        plugin.getConfig().set("lobby.spawn.y", location.getY());
        plugin.getConfig().set("lobby.spawn.z", location.getZ());
        plugin.getConfig().set("lobby.spawn.yaw", location.getYaw());
        plugin.getConfig().set("lobby.spawn.pitch", location.getPitch());
        plugin.saveConfig();
        lobbyWorld.setSpawnLocation(location);
    }

    public World lobbyWorld() { return Objects.requireNonNull(lobbyWorld); }
    public World matchWorld() { return Objects.requireNonNull(matchWorld); }

    public int preloadedChunks() {
        return preloadedChunks;
    }

    public int totalPreloadChunks() {
        return totalPreloadChunks;
    }

    private WorldType worldType(String configured) {
        try {
            return WorldType.valueOf(Objects.requireNonNullElse(configured, "AMPLIFIED")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown match.generator '" + configured + "'; using AMPLIFIED");
            return WorldType.AMPLIFIED;
        }
    }

    private void cleanupStaleMatchWorlds(String prefix) {
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix + "_"))
                    .filter(path -> plugin.getServer().getWorld(path.getFileName().toString()) == null)
                    .forEach(path -> deleteDirectory(path, root));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not enumerate stale match worlds", exception);
        }
    }

    private void startPreload(World world) {
        stopPreload();
        if (!plugin.getConfig().getBoolean("match.preload.enabled", true)) {
            return;
        }
        int radius = Math.max(0, plugin.getConfig().getInt("match.preload.radius", 512));
        int chunksPerTick = Math.max(1, Math.min(32,
            plugin.getConfig().getInt("match.preload.chunks-per-tick", 2)));
        Queue<ChunkCoordinate> queue = preloadQueue(radius);
        preloadedChunks = 0;
        totalPreloadChunks = queue.size();
        if (queue.isEmpty()) {
            return;
        }
        preloadTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (queue.isEmpty() || !world.equals(matchWorld)) {
                stopPreload();
                return;
            }
            for (int index = 0; index < chunksPerTick && !queue.isEmpty(); index++) {
                ChunkCoordinate coordinate = queue.remove();
                world.getChunkAtAsync(coordinate.x(), coordinate.z(), true)
                    .thenRun(() -> preloadedChunks++);
            }
        }, 1L, 1L);
    }

    private void stopPreload() {
        if (preloadTask != null) {
            preloadTask.cancel();
            preloadTask = null;
        }
    }

    private static Queue<ChunkCoordinate> preloadQueue(int blockRadius) {
        int chunkRadius = (int) Math.ceil(blockRadius / 16.0D);
        ArrayDeque<ChunkCoordinate> queue = new ArrayDeque<>();
        for (int radius = 0; radius <= chunkRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                addPreloadCoordinate(queue, x, -radius, chunkRadius);
                addPreloadCoordinate(queue, x, radius, chunkRadius);
            }
            for (int z = -radius + 1; z <= radius - 1; z++) {
                addPreloadCoordinate(queue, -radius, z, chunkRadius);
                addPreloadCoordinate(queue, radius, z, chunkRadius);
            }
        }
        return queue;
    }

    private static void addPreloadCoordinate(
        Queue<ChunkCoordinate> queue,
        int x,
        int z,
        int chunkRadius
    ) {
        if (x * x + z * z <= chunkRadius * chunkRadius) {
            queue.add(new ChunkCoordinate(x, z));
        }
    }

    private void deleteWorldDirectory(String worldName) {
        Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        deleteDirectory(root.resolve(worldName).normalize(), root);
    }

    private void deleteDirectory(Path directory, Path root) {
        if (!directory.startsWith(root) || directory.equals(root) || Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not delete stale match world " + directory, exception);
        }
    }

    private record ChunkCoordinate(int x, int z) { }
}
