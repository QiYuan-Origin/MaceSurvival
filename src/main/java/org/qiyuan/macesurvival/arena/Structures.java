package org.qiyuan.macesurvival.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * 建筑铺设。刻意做成几种简单但有掩体价值的形状：
 * 重锤玩法需要高低差来砸人，所以每种建筑都有可以跳下来的落差。
 */
final class Structures {

    private Structures() {
    }

    private enum Kind {
        TOWER, RUIN, BUNKER, PILLARS, PLATFORM
    }

    static void place(World world, Location centre, Random random) {
        Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
        Palette palette = Palette.pick(random);
        switch (kind) {
            case TOWER -> tower(world, centre, random, palette);
            case RUIN -> ruin(world, centre, random, palette);
            case BUNKER -> bunker(world, centre, palette);
            case PILLARS -> pillars(world, centre, random, palette);
            case PLATFORM -> platform(world, centre, random, palette);
        }
    }

    /** 材质组合。同一栋建筑用同一套材质，看起来才像人造的。 */
    private record Palette(Material wall, Material accent, Material floor) {

        static Palette pick(Random random) {
            return switch (random.nextInt(5)) {
                case 0 -> new Palette(Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS, Material.SMOOTH_STONE);
                case 1 -> new Palette(Material.DEEPSLATE_BRICKS, Material.POLISHED_DEEPSLATE, Material.DEEPSLATE_TILES);
                case 2 -> new Palette(Material.SANDSTONE, Material.CHISELED_SANDSTONE, Material.SMOOTH_SANDSTONE);
                case 3 -> new Palette(Material.BRICKS, Material.MUD_BRICKS, Material.POLISHED_ANDESITE);
                default -> new Palette(Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.COBBLESTONE);
            };
        }
    }

    /** 塔。高层放高星箱子，爬上去就暴露在所有人视线里。 */
    private static void tower(World world, Location centre, Random random, Palette palette) {
        int size = 3 + random.nextInt(2);
        int height = 12 + random.nextInt(9);
        int baseX = centre.getBlockX();
        int baseY = centre.getBlockY();
        int baseZ = centre.getBlockZ();

        for (int y = 0; y < height; y++) {
            for (int x = -size; x <= size; x++) {
                for (int z = -size; z <= size; z++) {
                    boolean edge = Math.abs(x) == size || Math.abs(z) == size;
                    if (!edge) {
                        if (y % 5 == 0) {
                            set(world, baseX + x, baseY + y, baseZ + z, palette.floor());
                        }
                        continue;
                    }
                    // 每 5 层开一圈窗，让塔里也能打出去
                    boolean window = y % 5 == 2 && (x == 0 || z == 0);
                    set(world, baseX + x, baseY + y, baseZ + z,
                            window ? Material.AIR : palette.wall());
                }
            }
        }
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                set(world, baseX + x, baseY + height, baseZ + z,
                        Math.abs(x) == size || Math.abs(z) == size ? palette.accent() : palette.floor());
            }
        }
    }

    /** 废墟。断墙加碎石，适合近战绕柱。 */
    private static void ruin(World world, Location centre, Random random, Palette palette) {
        int radius = 5 + random.nextInt(4);
        int baseX = centre.getBlockX();
        int baseY = centre.getBlockY();
        int baseZ = centre.getBlockZ();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                set(world, baseX + x, baseY - 1, baseZ + z, palette.floor());
                boolean edge = Math.abs(x) == radius || Math.abs(z) == radius;
                if (!edge) {
                    continue;
                }
                int height = 1 + random.nextInt(4);
                for (int y = 0; y < height; y++) {
                    set(world, baseX + x, baseY + y, baseZ + z,
                            random.nextInt(6) == 0 ? palette.accent() : palette.wall());
                }
            }
        }
        for (int i = 0; i < radius; i++) {
            int x = baseX + random.nextInt(radius * 2 + 1) - radius;
            int z = baseZ + random.nextInt(radius * 2 + 1) - radius;
            set(world, x, baseY, z, Material.COBBLESTONE);
        }
    }

    /** 地堡。下沉一层，进去要跳下来，出来要爬楼梯，是个高风险高回报的点。 */
    private static void bunker(World world, Location centre, Palette palette) {
        int size = 5;
        int baseX = centre.getBlockX();
        int baseY = centre.getBlockY();
        int baseZ = centre.getBlockZ();

        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                for (int y = -4; y <= 0; y++) {
                    boolean shell = Math.abs(x) == size || Math.abs(z) == size || y == -4;
                    set(world, baseX + x, baseY + y, baseZ + z,
                            shell ? palette.wall() : Material.AIR);
                }
            }
        }
        for (int x = -size + 1; x <= size - 1; x++) {
            for (int z = -size + 1; z <= size - 1; z++) {
                set(world, baseX + x, baseY - 3, baseZ + z, palette.floor());
            }
        }
        // 顶盖留一个天井当入口
        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                boolean hole = Math.abs(x) <= 1 && Math.abs(z) <= 1;
                set(world, baseX + x, baseY + 1, baseZ + z, hole ? Material.AIR : palette.accent());
            }
        }
    }

    /** 石柱群。给重锤玩法准备的落差场地。 */
    private static void pillars(World world, Location centre, Random random, Palette palette) {
        int count = 5 + random.nextInt(5);
        int baseX = centre.getBlockX();
        int baseY = centre.getBlockY();
        int baseZ = centre.getBlockZ();

        for (int i = 0; i < count; i++) {
            int ox = random.nextInt(19) - 9;
            int oz = random.nextInt(19) - 9;
            int height = 4 + random.nextInt(11);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x <= 1; x++) {
                    for (int z = 0; z <= 1; z++) {
                        set(world, baseX + ox + x, baseY + y, baseZ + oz + z,
                                y == height - 1 ? palette.accent() : palette.wall());
                    }
                }
            }
        }
    }

    /** 悬空平台。用柱子撑起来，是最好的高星箱子摆放点。 */
    private static void platform(World world, Location centre, Random random, Palette palette) {
        int size = 4 + random.nextInt(3);
        int lift = 7 + random.nextInt(6);
        int baseX = centre.getBlockX();
        int baseY = centre.getBlockY();
        int baseZ = centre.getBlockZ();

        for (int x = -size; x <= size; x++) {
            for (int z = -size; z <= size; z++) {
                set(world, baseX + x, baseY + lift, baseZ + z,
                        Math.abs(x) == size || Math.abs(z) == size ? palette.accent() : palette.floor());
            }
        }
        for (int corner = 0; corner < 4; corner++) {
            int cx = (corner % 2 == 0 ? -1 : 1) * (size - 1);
            int cz = (corner < 2 ? -1 : 1) * (size - 1);
            for (int y = 0; y < lift; y++) {
                set(world, baseX + cx, baseY + y, baseZ + cz, palette.wall());
            }
        }
    }

    private static void set(World world, int x, int y, int z, Material material) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        if (material == Material.AIR && block.getType().isAir()) {
            return;
        }
        block.setType(material, false);
    }
}
