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
import java.util.Comparator;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class WorldManager {
    private final MaceSurvivalPlugin plugin;
    private final Random random = new Random();
    private World lobbyWorld;
    private World matchWorld;

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
        Location spawn = lobbyLocation();
        world.setSpawnLocation(spawn);
        int baseY = spawn.getBlockY() - 1;
        int radius = 12;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double distance = Math.hypot(x, z);
                if (distance > radius + 0.15D) {
                    continue;
                }
                Material material = lobbyPlatformMaterial(x, z, distance, radius);
                if (world.getBlockAt(spawn.getBlockX() + x, baseY, spawn.getBlockZ() + z).isEmpty()) {
                    world.getBlockAt(spawn.getBlockX() + x, baseY, spawn.getBlockZ() + z)
                        .setType(material, false);
                }
            }
        }
        for (int[] offset : new int[][] {{9, 0}, {-9, 0}, {0, 9}, {0, -9}}) {
            if (world.getBlockAt(spawn.getBlockX() + offset[0], baseY + 1, spawn.getBlockZ() + offset[1]).isEmpty()) {
                world.getBlockAt(spawn.getBlockX() + offset[0], baseY + 1, spawn.getBlockZ() + offset[1])
                    .setType(Material.END_ROD, false);
            }
        }
    }

    private static Material lobbyPlatformMaterial(int x, int z, double distance, int radius) {
        if (distance > radius - 1.1D) {
            return Material.POLISHED_BLACKSTONE_BRICKS;
        }
        if (Math.abs(distance - 5.0D) < 0.55D || x == 0 || z == 0) {
            return Material.RED_NETHER_BRICKS;
        }
        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
            return Material.NETHERITE_BLOCK;
        }
        return Material.POLISHED_DEEPSLATE;
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
        return matchWorld;
    }

    public World resetMatch() {
        World previous = matchWorld;
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
        world.setGameRule(GameRule.DO_MOB_SPAWNING,
                plugin.getConfig().getBoolean("match.natural-mob-spawning", false));
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setDifficulty(org.bukkit.Difficulty.HARD);
        world.setGameRule(GameRule.PVP, true);
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
}
