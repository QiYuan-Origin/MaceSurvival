package org.qiyuan.macesurvival.arena;

import org.bukkit.Material;

/**
 * 箱子星级。星越高战利品越好，但数量越少、位置越暴露。
 * 颜色与符号会同时用在悬浮文本和开箱提示上，保持一条视觉线索。
 */
public enum ChestTier {

    ONE(1, "one-star", "#9AA0A6", Material.CHEST),
    TWO(2, "two-star", "#5FBF6A", Material.CHEST),
    THREE(3, "three-star", "#4C8CE8", Material.TRAPPED_CHEST),
    FOUR(4, "four-star", "#C9A227", Material.TRAPPED_CHEST);

    private final int stars;
    private final String configKey;
    private final String hex;
    private final Material material;

    ChestTier(int stars, String configKey, String hex, Material material) {
        this.stars = stars;
        this.configKey = configKey;
        this.hex = hex;
        this.material = material;
    }

    public int stars() {
        return stars;
    }

    /** arena.chests 下的键名。 */
    public String configKey() {
        return configKey;
    }

    public String hex() {
        return hex;
    }

    public Material material() {
        return material;
    }

    /** ★★☆☆ 这种进度感的星条，空星用暗色，四星满条。 */
    public String starBar() {
        StringBuilder sb = new StringBuilder("<" + hex + ">");
        sb.append("\u2605".repeat(stars));
        if (stars < 4) {
            sb.append("<#4A4A4A>").append("\u2606".repeat(4 - stars));
        }
        return sb.toString();
    }

    public static ChestTier byStars(int stars) {
        for (ChestTier tier : values()) {
            if (tier.stars == stars) {
                return tier;
            }
        }
        return ONE;
    }
}
