package club.mcqi.macesurvival.combat;

import org.bukkit.inventory.ItemStack;

@FunctionalInterface
public interface ArmorReturnSink {
    void returnArmor(ItemStack armor);
}
