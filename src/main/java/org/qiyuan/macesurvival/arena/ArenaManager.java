package org.qiyuan.macesurvival.arena;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.qiyuan.macesurvival.MaceSurvival;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * 竞技场。地图完全由插件生成，管理员不需要建图也不需要复制世界。
 * 生成分两步：prepare() 造世界与大厅平台，generate() 铺建筑和箱子。
 * 建筑与箱子都放在主线程分批铺，避免一次性放几万个方块把 tick 拖死。
 */
public final class ArenaManager {

    private final MaceSurvival plugin;
    private final Random random = new Random();

    private final Map<Location, ChestTier> chests = new LinkedHashMap<>();
    private final List<Location> structureCentres = new ArrayList<>();

    private World world;
    private Location lobbySpawn;
    private int structureCount;

    public ArenaManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public World world() {
        return world;
    }

    public int structureCount() {
        return structureCount;
    }

    public int chestCount() {
        return chests.size();
    }

    public ChestTier tierAt(Location location) {
        return chests.get(normalise(location));
    }

    public boolean isLootChest(Location location) {
        return chests.containsKey(normalise(location));
    }

    /** 开过的箱子从台账里摘掉，悬浮文本也一起清掉。 */
    public void consumeChest(Location location) {
        Location key = normalise(location);
        if (chests.remove(key) != null) {
            plugin.holograms().remove(key);
        }
    }

    private Location normalise(Location location) {
        return new Location(location.getWorld(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    // ─────────────── 世界 ───────────────

    /**
     * 造世界与等待大厅。世界用空区块生成器，地形由 generate() 自己铺，
     * 这样每局地形都不同，也不依赖任何原版结构。
     */
    public void prepare(Consumer<Boolean> callback) {
        String name = plugin.configs().main().getString("arena.world-name", "mace_arena");
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            world = existing;
            buildLobby();
            callback.accept(true);
            return;
        }

        WorldCreator creator = new WorldCreator(name)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generator(new VoidGenerator());
        if (!"random".equalsIgnoreCase(plugin.configs().main().getString("arena.seed-mode", "random"))) {
            creator.seed(plugin.configs().main().getLong("arena.seed", 20241121L));
        }

        world = creator.createWorld();
        if (world == null) {
            plugin.getLogger().severe("Arena world could not be created, check the server logs.");
            callback.accept(false);
            return;
        }
        applyWorldRules();
        buildLobby();
        callback.accept(true);
    }

    private void applyWorldRules() {
        world.setDifficulty(Difficulty.NORMAL);
        // 1.21.11 把 GameRule 常量搬到了 GameRules，旧常量已标记移除
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.FALL_DAMAGE, true);
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        world.setTime(4000L);
        world.setStorm(false);

        int radius = plugin.configs().main().getInt("arena.radius", 400);
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.5, 0.5);
        border.setSize(radius * 2D);
        border.setWarningDistance(16);
    }

    /** 大厅是一块悬空平台，跟对局地形完全隔开，玩家掉不下去。 */
    private void buildLobby() {
        int y = 200;
        lobbySpawn = new Location(world, 0.5, y + 1, 0.5);
        world.setSpawnLocation(0, y + 1, 0);

        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                boolean edge = Math.abs(x) == 6 || Math.abs(z) == 6;
                world.getBlockAt(x, y, z).setType(edge
                        ? Material.POLISHED_DEEPSLATE
                        : Material.SMOOTH_STONE);
                // 玻璃围栏挡住边缘，站在大厅里不会走出去
                if (edge) {
                    for (int h = 1; h <= 3; h++) {
                        world.getBlockAt(x, y + h, z).setType(Material.TINTED_GLASS);
                    }
                }
            }
        }
        for (int x = -5; x <= 5; x += 5) {
            for (int z = -5; z <= 5; z += 5) {
                world.getBlockAt(x, y + 1, z).setType(Material.SEA_LANTERN);
            }
        }
    }

    public Location lobbySpawn() {
        if (lobbySpawn == null && world != null) {
            buildLobby();
        }
        return lobbySpawn;
    }

    public void teleportToLobby(Player player) {
        Location target = lobbySpawn();
        if (target != null) {
            player.teleport(target);
        }
    }

    /** 旁观点放在竞技场正上方，一进来就能俯瞰全场。 */
    public void teleportToSpectate(Player player) {
        if (world == null) {
            return;
        }
        player.teleport(new Location(world, 0.5, 160, 0.5, 0f, 90f));
    }

    // ─────────────── 空世界生成器 ───────────────

    /** 空区块。地形全部由 generate() 手动铺，保证每局都不一样。 */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
            return false;
        }
    }
    // ─────────────── 地形与建筑 ───────────────

    /**
     * 铺一局的地图。地面用柏林式的高度扰动铺一层石头 + 地表方块，
     * 再撒建筑，最后按配置数量放箱子。整个过程分批进行，回调在全部完成后触发。
     */
    public void generate(Consumer<Boolean> callback) {
        if (world == null) {
            callback.accept(false);
            return;
        }
        chests.clear();
        structureCentres.clear();
        structureCount = 0;
        plugin.holograms().clearAll();

        int radius = plugin.configs().main().getInt("arena.radius", 400);
        long seed = "random".equalsIgnoreCase(
                plugin.configs().main().getString("arena.seed-mode", "random"))
                ? System.nanoTime()
                : plugin.configs().main().getLong("arena.seed", 20241121L);
        random.setSeed(seed);
        Terrain terrain = new Terrain(seed);

        // 每批铺一条 x 带，60 条一 tick，铺完再撒建筑
        List<Integer> columns = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            columns.add(x);
        }
        stripe(terrain, columns, radius, 0, () -> {
            scatterStructures(terrain, radius);
            scatterChests(terrain, radius);
            callback.accept(true);
        });
    }

    private void stripe(Terrain terrain, List<Integer> columns, int radius, int index, Runnable done) {
        int batch = 24;
        int end = Math.min(columns.size(), index + batch);
        for (int i = index; i < end; i++) {
            carveColumn(terrain, columns.get(i), radius);
        }
        if (end >= columns.size()) {
            done.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> stripe(terrain, columns, radius, end, done));
    }

    private void carveColumn(Terrain terrain, int x, int radius) {
        for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) {
                continue;
            }
            int height = terrain.heightAt(x, z);
            Material surface = terrain.surfaceAt(x, z, height);
            world.getBlockAt(x, height, z).setType(surface, false);
            world.getBlockAt(x, height - 1, z).setType(Material.DIRT, false);
            for (int y = height - 2; y >= height - 5; y--) {
                world.getBlockAt(x, y, z).setType(Material.STONE, false);
            }
            world.getBlockAt(x, height - 6, z).setType(Material.DEEPSLATE, false);
            if (height <= Terrain.WATER_LEVEL) {
                for (int y = height + 1; y <= Terrain.WATER_LEVEL; y++) {
                    world.getBlockAt(x, y, z).setType(Material.WATER, false);
                }
            }
        }
    }
    /** 撒建筑。互相之间保持最小距离，避免挤成一团让某个区域物资过剩。 */
    private void scatterStructures(Terrain terrain, int radius) {
        int target = plugin.configs().main().getInt("arena.structures", 26);
        int minDistance = plugin.configs().main().getInt("arena.min-structure-distance", 48);
        int attempts = 0;

        while (structureCentres.size() < target && attempts < target * 60) {
            attempts++;
            int x = random.nextInt(radius * 2) - radius;
            int z = random.nextInt(radius * 2) - radius;
            if (x * x + z * z > (radius - 24) * (radius - 24)) {
                continue;
            }
            int y = terrain.heightAt(x, z);
            if (y <= Terrain.WATER_LEVEL) {
                continue;
            }
            Location centre = new Location(world, x, y + 1, z);
            if (tooClose(centre, minDistance)) {
                continue;
            }
            Structures.place(world, centre, random);
            structureCentres.add(centre);
        }
        structureCount = structureCentres.size();
    }

    private boolean tooClose(Location candidate, int minDistance) {
        double squared = (double) minDistance * minDistance;
        for (Location existing : structureCentres) {
            if (existing.distanceSquared(candidate) < squared) {
                return true;
            }
        }
        return false;
    }

    /**
     * 放箱子。低星散布在建筑里和野外，高星只放在建筑顶层这种显眼位置，
     * 逼玩家为好物资去暴露自己。
     */
    private void scatterChests(Terrain terrain, int radius) {
        for (ChestTier tier : ChestTier.values()) {
            int amount = plugin.configs().main()
                    .getInt("arena.chests." + tier.configKey(), defaultAmount(tier));
            for (int i = 0; i < amount; i++) {
                Location spot = pickChestSpot(terrain, radius, tier);
                if (spot != null) {
                    placeChest(spot, tier);
                }
            }
        }
    }

    private int defaultAmount(ChestTier tier) {
        return switch (tier) {
            case ONE -> 60;
            case TWO -> 34;
            case THREE -> 16;
            case FOUR -> 6;
        };
    }

    private Location pickChestSpot(Terrain terrain, int radius, ChestTier tier) {
        boolean preferStructure = tier.stars() >= 3;
        for (int attempt = 0; attempt < 80; attempt++) {
            Location base;
            if (preferStructure && !structureCentres.isEmpty()) {
                Location centre = structureCentres.get(random.nextInt(structureCentres.size()));
                base = centre.clone().add(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
            } else if (!structureCentres.isEmpty() && random.nextBoolean()) {
                Location centre = structureCentres.get(random.nextInt(structureCentres.size()));
                base = centre.clone().add(random.nextInt(17) - 8, 0, random.nextInt(17) - 8);
            } else {
                int x = random.nextInt(radius * 2) - radius;
                int z = random.nextInt(radius * 2) - radius;
                if (x * x + z * z > (radius - 16) * (radius - 16)) {
                    continue;
                }
                base = new Location(world, x, terrain.heightAt(x, z) + 1, z);
            }
            Location surface = highestFreeSpot(base);
            if (surface != null && !chests.containsKey(normalise(surface))) {
                return surface;
            }
        }
        return null;
    }

    /** 从候选点往上找第一个能站住箱子的位置，站不住就放弃。 */
    private Location highestFreeSpot(Location base) {
        int x = base.getBlockX();
        int z = base.getBlockZ();
        for (int y = Math.min(base.getBlockY() + 12, 250); y > Terrain.WATER_LEVEL; y--) {
            Block block = world.getBlockAt(x, y, z);
            Block below = world.getBlockAt(x, y - 1, z);
            if (block.getType().isAir() && below.getType().isSolid()) {
                return new Location(world, x, y, z);
            }
        }
        return null;
    }

    private void placeChest(Location location, ChestTier tier) {
        Block block = world.getBlockAt(location);
        block.setType(tier.material(), false);
        chests.put(normalise(location), tier);
        if (plugin.configs().main().getBoolean("arena.chest-hologram", true)) {
            double height = plugin.configs().main().getDouble("arena.hologram-height", 1.4);
            plugin.holograms().spawn(normalise(location), height, tier);
        }
    }

    /** 结算或插件卸载时清掉悬浮文本，重启会重置世界所以方块不用管。 */
    public void cleanup() {
        plugin.holograms().clearAll();
        chests.clear();
        structureCentres.clear();
        structureCount = 0;
    }
}
