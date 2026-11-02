package org.qiyuan.macesurvival.arena;

import org.bukkit.Axis;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.HashSet;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Continuous woodland valleys. Chunk workers write only their own ChunkData, including tree overlaps. */
final class Terrain extends ChunkGenerator {
    private static final int TREE_GRID = 18;
    private final long seed;
    private final int base;
    private final double phase;
    private final BlockData leaves, log, logX, logZ;
    Terrain(long seed, int base) {
        this.seed = seed;
        this.base = base;
        phase = hash(351, 719) * Math.PI * 2;
        Leaves foliage = (Leaves) Material.OAK_LEAVES.createBlockData();
        foliage.setPersistent(true);
        leaves = foliage;
        log = Material.OAK_LOG.createBlockData();
        Orientable acrossX = (Orientable) Material.OAK_LOG.createBlockData();
        acrossX.setAxis(Axis.X);
        logX = acrossX;
        Orientable acrossZ = (Orientable) Material.OAK_LOG.createBlockData();
        acrossZ.setAxis(Axis.Z);
        logZ = acrossZ;
    }
    /** Winding low routes cross a wooded basin; higher shoulders create cover and changing sight lines. */
    int height(int x, int z) {
        double shoulder = smooth(7, 39, routeDistance(x, z));
        double ridge = 1 - Math.abs(noise(x / 93D + 11, z / 93D - 29));
        double relief = shoulder * (11 + ridge * ridge * 17 + noise(x / 52D - 37, z / 52D + 16) * 6);
        double valley = noise(x / 112D, z / 112D) * 6 + noise(x / 34D + 21, z / 34D - 17) * 2;
        return base + (int) Math.round(valley + relief + noise(x / 13D - 7, z / 13D + 41) * 0.9);
    }
    private double routeDistance(double x, double z) {
        double northSouth = x - Math.sin(z / 67D + phase) * 16 - Math.sin(z / 137D) * 11;
        double eastWest = z - Math.sin(x / 83D - phase * 0.7) * 18 - Math.sin(x / 149D + phase) * 9;
        return Math.min(Math.abs(northSouth), Math.abs(eastWest));
    }
    @Override public void generateNoise(@NotNull WorldInfo world, @NotNull Random random,
                                       int chunkX, int chunkZ, @NotNull ChunkData data) {
        int minimum = world.getMinHeight(), originX = chunkX * 16, originZ = chunkZ * 16;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int wx = originX + x, wz = originZ + z, top = height(wx, wz);
            int slope = Math.max(Math.abs(top - height(wx + 2, wz)), Math.abs(top - height(wx, wz + 2)));
            double forest = noise(wx / 49D + 68, wz / 49D - 36), route = routeDistance(wx, wz);
            data.setBlock(x, minimum, z, Material.BEDROCK);
            data.setRegion(x, minimum + 1, z, x + 1, top - 3, z + 1, Material.STONE);
            if (slope >= 3 && route > 12) {
                Material rock = noise(wx / 9D + 14, wz / 9D - 32) > 0.3 ? Material.ANDESITE : Material.STONE;
                data.setRegion(x, top - 3, z, x + 1, top + 1, z + 1, rock);
                continue;
            }
            data.setRegion(x, top - 3, z, x + 1, top, z + 1, Material.DIRT);
            boolean woodlandFloor = forest > 0.22 && route > 12 && noise(wx / 7D, wz / 7D) > -0.15;
            data.setBlock(x, top, z, woodlandFloor ? Material.PODZOL : Material.GRASS_BLOCK);
            double flora = hash(wx, wz);
            if (route > 5 && forest > 0 && flora > 0.87) data.setBlock(x, top + 1, z, Material.FERN);
            else if (route > 4 && flora > 0.956) data.setBlock(x, top + 1, z, Material.SHORT_GRASS);
            else if (route > 7 && forest < 0.05 && noise(wx / 11D - 9, wz / 11D) > 0.48 && flora > 0.94)
                data.setBlock(x, top + 1, z, Material.AZURE_BLUET);
        }
        BlockSink sink = (x, y, z, material) -> {
            int localX = x - originX, localZ = z - originZ;
            if (localX >= 0 && localX < 16 && localZ >= 0 && localZ < 16 && y >= minimum && y < world.getMaxHeight())
                data.setBlock(localX, y, localZ, material);
        };
        forTrees(originX - 11, originZ - 11, originX + 26, originZ + 26, tree -> tree(tree, sink));
        boulders(originX, originZ, data);
    }
    private void forTrees(int minX, int minZ, int maxX, int maxZ, Consumer<Tree> consumer) {
        for (int cellX = Math.floorDiv(minX, TREE_GRID); cellX <= Math.floorDiv(maxX, TREE_GRID); cellX++)
            for (int cellZ = Math.floorDiv(minZ, TREE_GRID); cellZ <= Math.floorDiv(maxZ, TREE_GRID); cellZ++) {
                int x = cellX * TREE_GRID + 9 + (int) (hash(cellX * 3 + 71, cellZ * 7 - 14) * 11) - 5;
                int z = cellZ * TREE_GRID + 9 + (int) (hash(cellX * 7 - 19, cellZ * 3 + 58) * 11) - 5;
                if (Math.hypot(x, z) < 14 || hash(cellX + 892, cellZ - 377) < 0.20
                    || noise(x / 66D + 37, z / 66D - 21) < -0.40 || routeDistance(x, z) < 11) continue;
                int ground = height(x, z);
                if (Math.abs(ground - height(x + 2, z + 2)) > 3 || Math.abs(ground - height(x - 2, z - 2)) > 3) continue;
                boolean mature = hash(cellX - 88, cellZ + 71) > 0.22;
                int trunk = (mature ? 12 : 8) + (int) (hash(cellX + 631, cellZ - 57) * 5);
                consumer.accept(new Tree(x, ground + 1, z, trunk, mature ? 6 : 4,
                    hash(cellX + 15, cellZ + 311) > 0.5 ? 1 : -1, mature));
            }
    }
    private void tree(Tree tree, BlockSink sink) {
        int x = tree.x, y = tree.y, z = tree.z, h = tree.height, radius = tree.radius;
        crown(sink, x + tree.lean, y + h + 1, z, radius, 4, radius - 1);
        crown(sink, x - radius + 2, y + h - 1, z + tree.lean * 2, radius - 2, 3, radius - 2);
        crown(sink, x + radius - 2, y + h - 2, z - tree.lean * 2, radius - 2, 3, radius - 2);
        for (int dy = 0; dy <= h + 1; dy++) {
            sink.set(x, y + dy, z, log);
            if (tree.mature && dy < h - 3) {
                sink.set(x + 1, y + dy, z, log);
                sink.set(x, y + dy, z + 1, log);
                sink.set(x + 1, y + dy, z + 1, log);
            }
        }
        if (tree.mature) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++)
            for (int atY = height(x + dx, z + dz) + 1; atY < y; atY++) sink.set(x + dx, atY, z + dz, log);
        if (tree.mature) for (int direction = 0; direction < 4; direction++) {
            int dx = direction == 0 ? 1 : direction == 1 ? -1 : 0;
            int dz = direction == 2 ? 1 : direction == 3 ? -1 : 0;
            for (int step = 1; step <= 3; step++) {
                int ground = height(x + dx * step, z + dz * step);
                for (int atY = ground + 1; atY <= Math.max(ground + 1, y + 2 - step); atY++)
                    sink.set(x + dx * step, atY, z + dz * step, dx != 0 ? logX : logZ);
            }
        }
        for (int side : new int[]{-1, 1}) for (int step = 1; step <= radius - 2; step++) {
            int atY = y + h - 5 + step / 2;
            sink.set(x + side * step, atY, z, logX);
            sink.set(x + side * step, atY + 1, z, log);
            if (step > 1) sink.set(x + side * step, atY + 1, z - side * tree.lean, logZ);
        }
    }
    private void crown(BlockSink sink, int x, int y, int z, int rx, int ry, int rz) {
        for (int dx = -rx; dx <= rx; dx++) for (int dz = -rz; dz <= rz; dz++) for (int dy = -ry; dy <= ry; dy++) {
            double shape = dx * dx / (double) (rx * rx) + dy * dy / (double) (ry * ry) + dz * dz / (double) (rz * rz);
            if (shape <= 1 + noise((x + dx) / 3D, (z + dz) / 3D) * 0.16) sink.set(x + dx, y + dy, z + dz, leaves);
        }
    }
    /** Remove the same complete generated geometry, avoiding cut trunks and floating tree canopies at building sites. */
    void clearTrees(World world, int x, int z, int reach, Queue<Structures.Change> changes) {
        BlockData air = Material.AIR.createBlockData();
        Set<TreeBlock> cleared = new HashSet<>();
        forTrees(x - reach - TREE_GRID, z - reach - TREE_GRID, x + reach + TREE_GRID, z + reach + TREE_GRID, tree -> {
            if (Math.abs(tree.x - x) > reach + 1 || Math.abs(tree.z - z) > reach + 1) return;
            tree(tree, (wx, wy, wz, block) -> {
                if (!cleared.add(new TreeBlock(wx, wy, wz))) return;
                Material existing = world.getBlockAt(wx, wy, wz).getType();
                // A nearby site may already have replaced this tree's footprint with its own building.
                if (existing == Material.OAK_LOG || existing == Material.OAK_LEAVES)
                    changes.add(new Structures.Change(wx, wy, wz, air));
            });
        });
    }
    private void boulders(int originX, int originZ, ChunkData data) {
        for (int cellX = Math.floorDiv(originX - 4, 29); cellX <= Math.floorDiv(originX + 19, 29); cellX++)
            for (int cellZ = Math.floorDiv(originZ - 4, 29); cellZ <= Math.floorDiv(originZ + 19, 29); cellZ++) {
                int x = cellX * 29 + 5 + (int) (hash(cellX - 83, cellZ + 45) * 19);
                int z = cellZ * 29 + 5 + (int) (hash(cellX + 37, cellZ - 29) * 19);
                if (hash(cellX + 47, cellZ - 73) < 0.70 || routeDistance(x, z) < 13) continue;
                int y = height(x, z), rx = 2 + (int) (hash(cellX + 92, cellZ) * 2), rz = 2 + (int) (hash(cellX, cellZ + 13) * 2);
                for (int dx = -rx; dx <= rx; dx++) for (int dz = -rz; dz <= rz; dz++) {
                    int localX = x + dx - originX, localZ = z + dz - originZ;
                    if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) continue;
                    double area = dx * dx / (double) (rx * rx) + dz * dz / (double) (rz * rz);
                    if (area > 1) continue;
                    int top = y + (int) Math.round(Math.sqrt(1 - area) * 3);
                    for (int atY = Math.min(y - 2, height(x + dx, z + dz)); atY <= top; atY++)
                        data.setBlock(localX, atY, localZ, atY >= y + 1 ? Material.STONE : Material.ANDESITE);
                }
            }
    }
    private static double smooth(double start, double end, double value) {
        double t = Math.clamp((value - start) / (end - start), 0, 1);
        return t * t * (3 - 2 * t);
    }
    private double noise(double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = hash(ix, iz) * 2 - 1, b = hash(ix + 1, iz) * 2 - 1;
        double c = hash(ix, iz + 1) * 2 - 1, d = hash(ix + 1, iz + 1) * 2 - 1;
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }
    private double hash(int x, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30; value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27; value *= 0x94D049BB133111EBL; value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
    @Override public int getBaseHeight(@NotNull WorldInfo world, @NotNull Random random, int x, int z, @NotNull HeightMap map) { return height(x, z) + 1; }
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo world) {
        return new BiomeProvider() {
            @Override public @NotNull Biome getBiome(@NotNull WorldInfo info, int x, int y, int z) { return Biome.FOREST; }
            @Override public @NotNull List<Biome> getBiomes(@NotNull WorldInfo info) { return List.of(Biome.FOREST); }
        };
    }
    private record Tree(int x, int y, int z, int height, int radius, int lean, boolean mature) { }
    private record TreeBlock(int x, int y, int z) { }
    @FunctionalInterface private interface BlockSink { void set(int x, int y, int z, BlockData material); }
}
