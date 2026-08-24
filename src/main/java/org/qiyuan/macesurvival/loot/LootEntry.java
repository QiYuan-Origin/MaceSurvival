package org.qiyuan.macesurvival.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.NamespacedKey;

/**
 * loot.yml 里的一条战利品。durability 只对重锤有意义，
 * enchant 直接打在物品上，book 则产出一本附魔书。
 */
public record LootEntry(Material material,
                        int weight,
                        int min,
                        int max,
                        int durability,
                        Enchantment enchant,
                        int enchantLevel,
                        Enchantment book,
                        int bookLevel) {

    /** 解析一条配置。material 配歪了返回 null，由调用方跳过而不是让整张表炸掉。 */
    public static LootEntry from(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String raw = section.getString("material", "");
        Material material = Material.matchMaterial(raw.toUpperCase());
        if (material == null || material.isAir()) {
            return null;
        }
        int min = Math.max(1, section.getInt("min", 1));
        int max = Math.max(min, section.getInt("max", min));

        String[] enchantPair = split(section.getString("enchant"));
        String[] bookPair = split(section.getString("book"));

        return new LootEntry(material,
                Math.max(1, section.getInt("weight", 1)),
                min,
                max,
                section.getInt("durability", 0),
                resolve(enchantPair[0]),
                parseLevel(enchantPair[1]),
                resolve(bookPair[0]),
                parseLevel(bookPair[1]));
    }

    private static String[] split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{null, null};
        }
        int colon = raw.lastIndexOf(':');
        if (colon < 0) {
            return new String[]{raw.trim(), "1"};
        }
        return new String[]{raw.substring(0, colon).trim(), raw.substring(colon + 1).trim()};
    }

    /** 附魔按注册表查，这样 1.21 新加的 DENSITY / BREACH / WIND_BURST 都能配。 */
    private static Enchantment resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase().replace(' ', '_'));
        if (key == null) {
            return null;
        }
        return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(key);
    }

    private static int parseLevel(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    public boolean isBook() {
        return book != null;
    }

    public boolean isWeapon() {
        return material == Material.MACE;
    }
}
