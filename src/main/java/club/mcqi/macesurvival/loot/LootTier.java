package club.mcqi.macesurvival.loot;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.random.RandomGenerator;

public enum LootTier {
    ONE(1, NamedTextColor.WHITE),
    TWO(2, NamedTextColor.AQUA),
    THREE(3, NamedTextColor.LIGHT_PURPLE);

    private final int stars;
    private final NamedTextColor color;

    LootTier(int stars, NamedTextColor color) {
        this.stars = stars;
        this.color = color;
    }

    public int stars() {
        return stars;
    }

    public NamedTextColor color() {
        return color;
    }

    public static LootTier roll(RandomGenerator random, double matchProgress) {
        double progress = Math.max(0.0, Math.min(1.0, matchProgress));
        double oneWeight = 0.55 - progress * 0.35;
        double twoWeight = 0.30 + progress * 0.10;
        double roll = random.nextDouble();
        if (roll < oneWeight) {
            return ONE;
        }
        if (roll < oneWeight + twoWeight) {
            return TWO;
        }
        return THREE;
    }

    public static LootTier fromStars(int stars) {
        return switch (stars) {
            case 1 -> ONE;
            case 2 -> TWO;
            case 3 -> THREE;
            default -> throw new IllegalArgumentException("Loot tier must contain 1 to 3 stars");
        };
    }
}
