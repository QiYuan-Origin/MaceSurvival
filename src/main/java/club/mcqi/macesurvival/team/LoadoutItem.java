package club.mcqi.macesurvival.team;

import org.bukkit.Material;

public enum LoadoutItem {
    SWORD(Material.NETHERITE_SWORD, 0),
    AXE(Material.NETHERITE_AXE, 1),
    MACE_ONE(Material.MACE, 7),
    MACE_TWO(Material.MACE, 8);

    private final Material material;
    private final int defaultSlot;

    LoadoutItem(Material material, int defaultSlot) {
        this.material = material;
        this.defaultSlot = defaultSlot;
    }

    public Material material() {
        return material;
    }

    public int defaultSlot() {
        return defaultSlot;
    }
}
