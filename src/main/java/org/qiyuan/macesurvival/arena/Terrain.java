package org.qiyuan.macesurvival.arena;

import org.bukkit.Material;

/**
 * 地形高度函数。用叠加的值噪声而不是原版生成器，
 * 这样同一个种子出来的地形可复现，而且不需要等区块生成。
 */
final class Terrain {

    /** 水面高度。低于这条线的地方灌水，形成湖和河谷。 */
    static final int WATER_LEVEL = 62;

    private static final int BASE = 66;

    private final long seed;

    Terrain(long seed) {
        this.seed = seed;
    }

    /** 三层噪声叠加：大陆起伏 + 丘陵 + 细节。 */
    int heightAt(int x, int z) {
        double continent = noise(x / 220.0, z / 220.0, 1) * 26.0;
        double hills = noise(x / 70.0, z / 70.0, 2) * 11.0;
        double detail = noise(x / 22.0, z / 22.0, 3) * 3.5;
        int height = (int) Math.round(BASE + continent + hills + detail);
        return Math.max(50, Math.min(height, 140));
    }

    /** 地表方块。按高度分带，高处露岩石，水边是沙子。 */
    Material surfaceAt(int x, int z, int height) {
        if (height <= WATER_LEVEL + 1) {
            return Material.SAND;
        }
        if (height >= 120) {
            return Material.STONE;
        }
        if (height >= 106) {
            return Material.COARSE_DIRT;
        }
        double patch = noise(x / 13.0, z / 13.0, 7);
        if (patch > 0.34) {
            return Material.PODZOL;
        }
        if (patch < -0.38) {
            return Material.GRAVEL;
        }
        return Material.GRASS_BLOCK;
    }

    /** 双线性插值的值噪声，返回 -1..1。 */
    private double noise(double x, double z, int octave) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);

        double n00 = raw(x0, z0, octave);
        double n10 = raw(x0 + 1, z0, octave);
        double n01 = raw(x0, z0 + 1, octave);
        double n11 = raw(x0 + 1, z0 + 1, octave);

        double top = n00 + (n10 - n00) * fx;
        double bottom = n01 + (n11 - n01) * fx;
        return top + (bottom - top) * fz;
    }

    private double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 由坐标和种子直接散列出 -1..1 的伪随机值，不需要预生成表。 */
    private double raw(int x, int z, int octave) {
        long h = seed * 6364136223846793005L
                + x * 341873128712L
                + z * 132897987541L
                + octave * 2971215073L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }
}
