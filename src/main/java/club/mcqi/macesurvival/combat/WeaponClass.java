package club.mcqi.macesurvival.combat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

public enum WeaponClass {
    SWORD,
    AXE,
    MACE;

    public static Optional<WeaponClass> infer(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        Material material = item.getType();
        if (material == Material.MACE) {
            return Optional.of(MACE);
        }
        String name = material.name().toLowerCase(Locale.ROOT);
        if (name.endsWith("_sword")) {
            return Optional.of(SWORD);
        }
        if (name.endsWith("_axe")) {
            return Optional.of(AXE);
        }
        return Optional.empty();
    }
}
