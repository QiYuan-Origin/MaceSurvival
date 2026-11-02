package org.qiyuan.macesurvival.arena;

/**
 * Chest tier levels for loot quality
 */
public enum ChestTier {
    ONE_STAR(1),
    TWO_STAR(2),
    THREE_STAR(3),
    FOUR_STAR(4);

    private final int stars;

    ChestTier(int stars) {
        this.stars = stars;
    }

    public int getStars() {
        return stars;
    }

    public String getDisplayName() {
        return "★".repeat(stars);
    }
}
