package org.qiyuan.macesurvival.arena;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.qiyuan.macesurvival.MaceSurvival;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns one disposable arena world; the lobby is never used as a build surface. */
public final class ArenaManager {
    private final MaceSurvival plugin;
    private final HologramManager holograms;
    private final List<Location> chestLocations = new ArrayList<>();
    private final Map<Location, ChestTier> chestTiers = new HashMap<>();
    private final List<Location> dropSpawns = new ArrayList<>();
    private final Set<BukkitTask> buildTasks = new HashSet<>();
    private World arenaWorld;
    private Terrain terrain;
    private Location arenaCenter;
    private CompletableFuture<Void> preparation;
    private long generation;
    private int radius;
    private boolean ready;

    public ArenaManager(MaceSurvival plugin) {
        this.plugin = plugin;
        holograms = new HologramManager(plugin);
    }

    /** Completes on the server thread after structures, loot and landing chunks are ready. */
    public CompletableFuture<Void> prepareArena(long seed) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<Void> scheduled = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> prepareArena(seed).whenComplete((ok, error) -> {
                if (error == null) scheduled.complete(null);
                else scheduled.completeExceptionally(error);
            }));
            return scheduled;
        }
        if (preparation != null && !preparation.isDone()) return preparation;
        cleanup();
        long token = ++generation;
        preparation = new CompletableFuture<>();
        CompletableFuture<Void> result = preparation;
        try {
            FileConfiguration config = plugin.configs().main();
            radius = Math.max(64, Math.min(800, config.getInt("arena.radius", 400)));
            int baseY = Math.max(48, Math.min(150, config.getInt("arena.base-y", 72)));
            String baseName = config.getString("arena.world-name", config.getString("arena.world", "mace_arena"));
            if (baseName == null || !baseName.matches("[A-Za-z0-9_-]{1,48}")) baseName = "mace_arena";
            String name = baseName + "_round_" + UUID.randomUUID().toString().substring(0, 8);
            terrain = new Terrain(seed, baseY);
            arenaWorld = new WorldCreator(name).environment(World.Environment.NORMAL).seed(seed)
                .generateStructures(false)
                .generator(terrain).createWorld();
            if (arenaWorld == null) throw new IllegalStateException("Cannot create the arena world");
            World world = arenaWorld;
            world.setAutoSave(false);
            world.setTime(6000);
            world.setStorm(false);
            world.setThundering(false);
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
            world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
            world.setGameRule(GameRules.KEEP_INVENTORY, false);
            arenaCenter = new Location(world, 0.5, baseY + 1, 0.5);
            world.setSpawnLocation(arenaCenter);
            world.getWorldBorder().setCenter(0.5, 0.5);
            world.getWorldBorder().setSize(radius * 2D);
            world.getWorldBorder().setDamageBuffer(1D);
            world.getWorldBorder().setDamageAmount(1D);
            world.getWorldBorder().setWarningDistance(12);
            List<Site> sites = planSites(new Random(seed));
            preload(world, chunksFor(sites), token, () -> build(world, sites, seed, token, result), result);
        } catch (Exception error) {
            result.completeExceptionally(error);
            plugin.getLogger().severe("Arena preparation failed: " + error.getMessage());
        }
        return result;
    }

    private List<Site> planSites(Random random) {
        FileConfiguration config = plugin.configs().main();
        int minimumStructures = Math.max(0, Math.min(100, config.getInt("arena.min-structures", 24)));
        int maximumStructures = Math.max(0, Math.min(100, config.getInt("arena.max-structures", 32)));
        int lower = Math.min(minimumStructures, maximumStructures);
        int upper = Math.max(minimumStructures, maximumStructures);
        int structures = config.isInt("arena.structures") ? Math.max(0, Math.min(100, config.getInt("arena.structures")))
            : lower + random.nextInt(upper - lower + 1);
        int[] counts = {count("one-star", "one-star-count", 36),
            count("two-star", "two-star-count", 20), count("three-star", "three-star-count", 10)};
        int total = Math.min(180, counts[0] + counts[1] + counts[2]);
        double minimum = Math.max(18D, Math.min(35D, radius / 13D));
        List<Site> sites = new ArrayList<>();
        // Give each tier a separate pass so a crowded small arena still contains rare loot.
        for (int star = 3; star >= 1; star--) {
            for (int index = 0; index < counts[star - 1] && sites.size() < total; index++) {
                for (int attempt = 0; attempt < 150; attempt++) {
                    double angle = random.nextDouble() * Math.PI * 2D;
                    double distance = Math.sqrt(random.nextDouble()) * (radius - 18);
                    int x = (int) Math.round(Math.cos(angle) * distance);
                    int z = (int) Math.round(Math.sin(angle) * distance);
                    boolean occupied = sites.stream().anyMatch(site -> Math.hypot(site.x - x, site.z - z) < minimum);
                    if (!occupied) {
                        sites.add(new Site(x, z, tier(star), sites.size() < structures, random.nextInt(5)));
                        break;
                    }
                }
            }
        }
        if (sites.isEmpty()) sites.add(new Site(0, 0, ChestTier.ONE_STAR, true, 0));
        for (int i = 0; i < 64; i++) {
            double angle = Math.PI * 2D * i / 64D + random.nextDouble() * 0.06;
            double distance = radius * (0.28 + random.nextDouble() * 0.42);
            dropSpawns.add(new Location(arenaWorld, Math.cos(angle) * distance, 0,
                Math.sin(angle) * distance, (float) Math.toDegrees(angle) + 90F, 0F));
        }
        return sites;
    }

    private int count(String name, String oldName, int fallback) {
        FileConfiguration config = plugin.configs().main();
        return Math.max(0, Math.min(120, config.getInt("arena.chests." + name,
            config.getInt("chest." + oldName, fallback))));
    }

    private ChestTier tier(int stars) {
        return stars >= 3 ? ChestTier.THREE_STAR : stars == 2 ? ChestTier.TWO_STAR : ChestTier.ONE_STAR;
    }

    private Queue<Long> chunksFor(List<Site> sites) {
        Set<Long> chunks = new HashSet<>();
        for (Site site : sites) addArea(chunks, site.x >> 4, site.z >> 4, 1);
        for (Location spawn : dropSpawns) addArea(chunks, spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, 1);
        addArea(chunks, 0, 0, 2);
        return new ArrayDeque<>(chunks);
    }

    private void addArea(Set<Long> chunks, int x, int z, int reach) {
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++)
            chunks.add(((long) (x + dx) << 32) | ((z + dz) & 0xffffffffL));
    }

    /** Keep outstanding chunk generation bounded instead of flooding Paper's worker queue. */
    private void preload(World world, Queue<Long> chunks, long token, Runnable done, CompletableFuture<Void> result) {
        AtomicInteger pending = new AtomicInteger();
        BukkitRunnable loader = new BukkitRunnable() {
            @Override public void run() {
                if (token != generation || result.isDone()) { cancel(); return; }
                while (!chunks.isEmpty() && pending.get() < 8) {
                    long key = chunks.remove();
                    pending.incrementAndGet();
                    world.getChunkAtAsync((int) (key >> 32), (int) key, true).whenComplete((chunk, error) -> {
                        Runnable loaded = () -> {
                            if (error != null) result.completeExceptionally(error);
                            else if (token == generation && !result.isDone()) chunk.addPluginChunkTicket(plugin);
                            pending.decrementAndGet();
                        };
                        if (Bukkit.isPrimaryThread()) loaded.run();
                        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, loaded);
                    });
                }
                if (chunks.isEmpty() && pending.get() == 0) { cancel(); done.run(); }
            }
        };
        buildTasks.add(loader.runTaskTimer(plugin, 1L, 1L));
    }

    private void build(World world, List<Site> sites, long seed, long token, CompletableFuture<Void> result) {
        Queue<Structures.Change> changes = new ArrayDeque<>();
        Random random = new Random(seed ^ 0x6A09E667F3BCC909L);
        Queue<Site> pendingSites = new ArrayDeque<>(sites);
        BukkitRunnable builder = new BukkitRunnable() {
            @Override public void run() {
                if (token != generation || result.isDone()) { cancel(); return; }
                try {
                    long deadline = System.nanoTime() + 3_000_000L;
                    if (changes.isEmpty() && !pendingSites.isEmpty()) {
                        Site site = pendingSites.remove();
                        int ground = terrain.height(site.x, site.z);
                        Location chest = Structures.plan(world, site.x, ground, site.z,
                            site.structure, site.style, changes, random);
                        chestLocations.add(chest);
                        chestTiers.put(chest, site.tier);
                    }
                    int placed = 0;
                    while (!changes.isEmpty() && placed++ < 500 && System.nanoTime() < deadline) {
                        Structures.Change change = changes.remove();
                        world.getBlockAt(change.x(), change.y(), change.z()).setBlockData(change.data(), false);
                    }
                    if (!changes.isEmpty() || !pendingSites.isEmpty()) return;
                    for (Location location : chestLocations) {
                        if (location.getBlock().getState() instanceof Chest chest) {
                            plugin.loot().fillChest(chest.getBlockInventory(), chestTiers.get(location));
                            if (plugin.configs().main().getBoolean("arena.chest-hologram", true))
                                holograms.create(location.clone().add(0.5, hologramHeight(), 0.5), chestTiers.get(location));
                        }
                    }
                    arenaCenter.setY(world.getHighestBlockYAt(0, 0, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
                    world.setSpawnLocation(arenaCenter);
                    ready = true;
                    world.removePluginChunkTickets(plugin);
                    cancel();
                    plugin.getLogger().info("Arena ready: " + chestLocations.size() + " loot sites, seed " + seed);
                    result.complete(null);
                } catch (Exception error) { cancel(); result.completeExceptionally(error); }
            }
        };
        buildTasks.add(builder.runTaskTimer(plugin, 1L, 1L));
    }

    private double hologramHeight() {
        return plugin.configs().main().getDouble("arena.hologram-height", plugin.configs().main().getDouble("chest.hologram-height", 1.4));
    }

    /** Legacy callers remain safe: their supplied world is never built over. */
    public void generateArena(World ignoredWorld, Location ignoredCenter, int ignoredRadius, long seed) { prepareArena(seed); }

    public Location spawnFor(int index, int total) {
        if (!ready || arenaWorld == null || dropSpawns.isEmpty()) throw new IllegalStateException("Arena is not ready");
        int step = Math.max(1, dropSpawns.size() / Math.max(1, total));
        Location spawn = dropSpawns.get(Math.floorMod(index * step, dropSpawns.size())).clone();
        double height = Math.max(arenaCenter.getY() + 80D, plugin.configs().main().getDouble("drop.height", 320D));
        spawn.setY(Math.min(arenaWorld.getMaxHeight() - 4D, height));
        return spawn;
    }

    public void consumeChest(Location location) {
        if (chestTiers.remove(location) != null) holograms.remove(location.clone().add(0.5, hologramHeight(), 0.5));
    }

    public ChestTier tierAt(Location location) { return chestTiers.get(location); }
    public boolean isReady() { return ready; }
    public int getRadius() { return radius; }
    public World getArenaWorld() { return arenaWorld; }
    public Location getArenaCenter() { return arenaCenter == null ? null : arenaCenter.clone(); }
    public List<Location> getChestLocations() { return List.copyOf(chestLocations); }

    public void cleanup() {
        generation++;
        ready = false;
        if (preparation != null && !preparation.isDone()) preparation.completeExceptionally(new CancellationException("Arena reset"));
        for (BukkitTask task : buildTasks) task.cancel();
        buildTasks.clear();
        holograms.removeAll();
        chestLocations.clear();
        chestTiers.clear();
        dropSpawns.clear();
        World oldWorld = arenaWorld;
        arenaWorld = null;
        terrain = null;
        arenaCenter = null;
        if (oldWorld != null) retire(oldWorld, 0);
    }

    private void retire(World world, int attempt) {
        if (!world.getPlayers().isEmpty()) {
            if (plugin.isEnabled() && attempt < 100) Bukkit.getScheduler().runTaskLater(plugin, () -> retire(world, attempt + 1), 20L);
            return;
        }
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (!folder.getParent().equals(container) || !world.getName().contains("_round_")) return;
        world.removePluginChunkTickets(plugin);
        if (!Bukkit.unloadWorld(world, false)) return;
        Runnable deletion = () -> {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException error) {
                plugin.getLogger().warning("Cannot remove retired arena " + world.getName() + ": " + error.getMessage());
            }
        };
        if (plugin.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(plugin, deletion);
        // During shutdown retain the unloaded folder instead of blocking on disk traversal.
    }

    private record Site(int x, int z, ChestTier tier, boolean structure, int style) { }
}
