package org.qiyuan.macesurvival.arena;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Slab;

import java.util.Queue;
import java.util.Random;
import java.util.EnumMap;
import java.util.Map;

/** Small, walkable field landmarks planned once and applied with a per-tick block budget. */
final class Structures {
    private static final Map<Material, BlockData> BLOCK_DATA = new EnumMap<>(Material.class);
    private Structures() { }

    static Location plan(World world, int x, int y, int z, boolean landmark, int style,
                         Queue<Change> changes, Random random) {
        int coreX = landmark ? style == 3 ? 4 : 3 : 1;
        int coreZ = landmark ? style == 2 ? 6 : style == 4 ? 4 : style == 3 ? 4 : 3 : 1;
        int reach = Math.max(coreX, coreZ) + 4;
        if (world.getGenerator() instanceof Terrain terrain) terrain.clearTrees(world, x, z, Math.max(coreX, coreZ) + 1, changes);
        // Only the building footprint is level. Its grassy shoulders blend back into the surveyed slope.
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double edge = Math.hypot(Math.max(0, Math.abs(dx) - coreX), Math.max(0, Math.abs(dz) - coreZ));
                double feather = 3.2 + Math.sin((x + dx) * 0.43 + (z + dz) * 0.31) * 0.6;
                if (edge >= feather) continue;
                int existing = naturalGround(world, x + dx, z + dz);
                double blend = Math.clamp(1 - edge / feather, 0, 1);
                blend = blend * blend * (3 - 2 * blend);
                int target = (int) Math.round(existing + (y - existing) * blend);
                for (int cy = Math.min(existing, target) - 1; cy < target; cy++)
                    put(changes, x + dx, cy, z + dz, Material.DIRT);
                boolean entrance = Math.abs(dx) <= 1 && dz < -1;
                put(changes, x + dx, target, z + dz, entrance ? Material.DIRT_PATH : Material.GRASS_BLOCK);
                int clearHeight = edge == 0 ? style == 2 ? 8 : 6 : 3;
                for (int cy = target + 1; cy <= Math.max(existing + 1, target + clearHeight); cy++)
                    put(changes, x + dx, cy, z + dz, Material.AIR);
            }
        }
        approach(world, changes, x, y, z, coreZ);
        int floor = y + 1;
        if (!landmark) {
            put(changes, x - 1, floor, z, Material.BARREL);
            put(changes, x + 1, floor, z + 1, Material.MOSSY_COBBLESTONE);
            put(changes, x + 1, floor, z, Material.OAK_SLAB);
        } else {
            switch (style) {
                case 0 -> ruin(changes, x, floor, z, random);
                case 1 -> canopy(changes, x, floor, z);
                case 2 -> lookout(changes, x, floor, z);
                case 3 -> courtyard(changes, x, floor, z);
                default -> camp(changes, x, floor, z);
            }
        }
        // Every chest has a clear two-block approach, including the lookout's ground-floor stash.
        put(changes, x, floor + 1, z, Material.AIR);
        put(changes, x, floor + 2, z, Material.AIR);
        put(changes, x, floor, z - 1, Material.AIR);
        put(changes, x, floor + 1, z - 1, Material.AIR);
        BlockData chest = Material.CHEST.createBlockData();
        ((Directional) chest).setFacing(BlockFace.NORTH);
        changes.add(new Change(x, floor, z, chest));
        return new Location(world, x, floor, z);
    }

    private static int naturalGround(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        while (y > world.getMinHeight() + 1) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (material.isSolid() && !Tag.LOGS.isTagged(material) && !Tag.LEAVES.isTagged(material)) break;
            y--;
        }
        return y;
    }

    /** A real stepped approach, with three clear columns, joins every level floor to the surrounding slope. */
    private static void approach(World world, Queue<Change> changes, int x, int y, int z, int coreZ) {
        int previous = y;
        // Keep every surveyed column within the site's preloaded neighbouring chunks.
        for (int step = 1; step <= Math.min(12, 15 - coreZ); step++) {
            int atZ = z - coreZ - step;
            int sampled = naturalGround(world, x, atZ);
            int target = Math.clamp(sampled, previous - 1, previous + 1);
            for (int dx = -1; dx <= 1; dx++) {
                int ground = naturalGround(world, x + dx, atZ);
                for (int cy = Math.min(ground, target) - 1; cy < target; cy++) put(changes, x + dx, cy, atZ, Material.DIRT);
                put(changes, x + dx, target, atZ, Material.DIRT_PATH);
                for (int cy = target + 1; cy <= Math.max(ground + 2, target + 3); cy++) put(changes, x + dx, cy, atZ, Material.AIR);
                if (target != previous) {
                    BlockData stair = Material.MOSSY_STONE_BRICK_STAIRS.createBlockData();
                    ((Directional) stair).setFacing(target > previous ? BlockFace.NORTH : BlockFace.SOUTH);
                    int stairZ = target > previous ? atZ + 1 : atZ;
                    changes.add(new Change(x + dx, Math.min(previous, target) + 1, stairZ, stair));
                }
            }
            if (step > 3 && target == sampled && previous == target) break;
            previous = target;
        }
    }

    private static void ruin(Queue<Change> changes, int x, int y, int z, Random random) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                // Surviving floor remains near the intact rear wall, with grass reclaiming its collapsed entrance.
                if (dz > 0 || dx < -1) put(changes, x + dx, y - 1, z + dz,
                    dz >= 2 && dx < 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                if (dx == -3 || dz == 3 || (dx == 3 && dz >= 1)) {
                    int height = dx == -3 ? 4 - Math.max(0, -dz) : dz == 3 ? 3 : 1;
                    if (Math.abs(dx + dz) == 2) height--;
                    for (int dy = 0; dy < height; dy++) put(changes, x + dx, y + dy, z + dz,
                        dy == 0 ? Material.MOSSY_STONE_BRICKS : dy == height - 1 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                }
            }
        }
        put(changes, x - 2, y, z + 1, Material.CHISELED_STONE_BRICKS);
        put(changes, x - 2, y + 1, z + 1, Material.LANTERN);
        put(changes, x + 3, y, z - 1, Material.MOSSY_STONE_BRICK_SLAB);
        put(changes, x + 2, y, z - 2, Material.CRACKED_STONE_BRICKS);
    }

    private static void canopy(Queue<Change> changes, int x, int y, int z) {
        for (int dx : new int[]{-3, 3}) for (int dz : new int[]{-3, 3}) {
            for (int dy = 0; dy < 4; dy++) put(changes, x + dx, y + dy, z + dz, Material.STRIPPED_SPRUCE_LOG);
        }
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            int roofY = y + 7 - Math.abs(dx);
            if (dx == 0) put(changes, x, roofY, z + dz, Material.DARK_OAK_SLAB);
            else {
                BlockData roof = (Math.abs(dz) == 4 ? Material.SPRUCE_STAIRS : Material.DARK_OAK_STAIRS).createBlockData();
                ((Directional) roof).setFacing(dx < 0 ? BlockFace.EAST : BlockFace.WEST);
                changes.add(new Change(x + dx, roofY, z + dz, roof));
            }
        }
        for (int dx = -3; dx <= 3; dx++) for (int dz : new int[]{-3, 3})
            put(changes, x + dx, y + 3, z + dz, Material.STRIPPED_SPRUCE_LOG);
        for (int dx = -2; dx <= 2; dx++) put(changes, x + dx, y, z + 2, Material.SPRUCE_SLAB);
        put(changes, x - 2, y, z, Material.BARREL);
        put(changes, x + 2, y, z, Material.CRAFTING_TABLE);
    }

    private static void lookout(Queue<Change> changes, int x, int y, int z) {
        for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-2, 2})
            for (int dy = 0; dy < 4; dy++) put(changes, x + dx, y + dy, z + dz, Material.STRIPPED_OAK_LOG);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            put(changes, x + dx, y + 3, z + dz, Material.OAK_PLANKS);
            if (Math.abs(dx) == 3 || Math.abs(dz) == 3) put(changes, x + dx, y + 4, z + dz, Material.OAK_FENCE);
        }
        // Four ordinary steps form a true walking route; no jump, wind charge or block required.
        for (int step = 0; step < 4; step++) {
            int atZ = z - 6 + step;
            BlockData stair = Material.OAK_STAIRS.createBlockData();
            ((Directional) stair).setFacing(BlockFace.SOUTH);
            changes.add(new Change(x, y + step, atZ, stair));
            for (int below = y - 1; below < y + step; below++) put(changes, x, below, atZ, Material.OAK_PLANKS);
            put(changes, x, y + step + 1, atZ, Material.AIR);
            put(changes, x, y + step + 2, atZ, Material.AIR);
        }
        put(changes, x, y + 4, z - 3, Material.AIR);
        put(changes, x + 1, y + 4, z + 1, Material.LANTERN);
    }

    private static void courtyard(Queue<Change> changes, int x, int y, int z) {
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            if (Math.abs(dx) == 4 || Math.abs(dz) == 4) {
                if ((Math.abs(dx) <= 1 && Math.abs(dz) == 4) || (Math.abs(dz) <= 1 && Math.abs(dx) == 4)) continue;
                put(changes, x + dx, y, z + dz, Material.COBBLESTONE_WALL);
            }
            if ((Math.abs(dx) == 3 && Math.abs(dz) == 3)) {
                put(changes, x + dx, y, z + dz, Material.STONE_BRICKS);
                put(changes, x + dx, y + 1, z + dz, Material.STONE_BRICK_WALL);
                put(changes, x + dx, y + 2, z + dz, Material.LANTERN);
            }
        }
    }

    private static void camp(Queue<Change> changes, int x, int y, int z) {
        for (int dx = -3; dx <= 3; dx++) {
            int roof = 3 - Math.abs(dx);
            for (int dz = 1; dz <= 4; dz++) put(changes, x + dx, y + roof, z + dz, Material.GREEN_TERRACOTTA);
        }
        for (int dx = -2; dx <= 2; dx++) {
            Slab bench = (Slab) Material.SPRUCE_SLAB.createBlockData();
            changes.add(new Change(x + dx, y, z - 3, bench));
        }
        put(changes, x + 3, y, z - 1, Material.HAY_BLOCK);
        put(changes, x + 3, y, z, Material.BARREL);
        put(changes, x - 3, y, z, Material.HAY_BLOCK);
    }

    private static void put(Queue<Change> changes, int x, int y, int z, Material material) {
        changes.add(new Change(x, y, z, BLOCK_DATA.computeIfAbsent(material, Material::createBlockData)));
    }

    record Change(int x, int y, int z, BlockData data) { }
}
