package org.qiyuan.macesurvival.enchant;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.Map;

/**
 * 附魔书。捡起来即时生效，也支持手持武器右键书本附加。
 * 这套流程刻意不走铁砧：大逃杀里没人有空去找铁砧。
 */
public final class EnchantManager {

    private final MaceSurvival plugin;

    public EnchantManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /**
     * 拾取即生效。找得到能吃这本书的武器就打上去并返回 true，
     * 返回 false 时调用方应该把书正常放进背包。
     */
    public boolean applyFromBook(Player player, ItemStack book) {
        Map<Enchantment, Integer> stored = storedOf(book);
        if (stored.isEmpty()) {
            return false;
        }
        int slot = findWeaponSlot(player, stored);
        if (slot < 0) {
            return false;
        }
        ItemStack weapon = player.getInventory().getItem(slot);
        boolean applied = false;
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            if (apply(player, slot, entry.getKey(), entry.getValue(), false)) {
                weapon = player.getInventory().getItem(slot);
                applied = true;
            }
        }
        if (applied) {
            Sounds.play(plugin, player, Sounds.buffGained());
        }
        return applied && weapon != null;
    }

    /**
     * 手持武器右键书本。与拾取生效相比，这里会把失败原因告诉玩家，
     * 因为是玩家主动操作，静默失败会让人以为插件坏了。
     */
    public boolean applyByHand(Player player, ItemStack book) {
        if (!plugin.configs().main().getBoolean("loot.books-apply-on-right-click", true)) {
            return false;
        }
        Map<Enchantment, Integer> stored = storedOf(book);
        if (stored.isEmpty()) {
            return false;
        }
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack weapon = player.getInventory().getItem(slot);
        if (weapon == null || weapon.getType() != Material.MACE) {
            plugin.messages().send(player, "loot.book-no-weapon");
            Sounds.play(plugin, player, Sounds.deny());
            return false;
        }
        boolean applied = false;
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            if (apply(player, slot, entry.getKey(), entry.getValue(), true)) {
                applied = true;
            }
        }
        if (applied) {
            Sounds.play(plugin, player, Sounds.enchantApplied());
        }
        return applied;
    }

    /**
     * 把一条附魔打到指定槽位的武器上。verbose 决定失败时是否提示玩家。
     * 同名附魔取更高等级，同级则视为满级并拒绝。
     */
    private boolean apply(Player player, int slot, Enchantment enchant, int level, boolean verbose) {
        ItemStack weapon = player.getInventory().getItem(slot);
        if (weapon == null || weapon.getType().isAir()) {
            return false;
        }
        if (!enchant.canEnchantItem(weapon) && !weapon.containsEnchantment(enchant)) {
            if (verbose) {
                plugin.messages().send(player, "loot.book-incompatible");
                Sounds.play(plugin, player, Sounds.deny());
            }
            return false;
        }
        int cap = ceilingOf(enchant);
        int current = weapon.getEnchantmentLevel(enchant);
        int target = Math.min(cap, Math.max(current, level));
        if (target <= current) {
            if (verbose) {
                plugin.messages().send(player, "loot.book-max-level", MessageManager.vars(
                        "enchant", nameOf(enchant)));
                Sounds.play(plugin, player, Sounds.deny());
            }
            return false;
        }
        ItemStack updated = weapon.clone();
        updated.addUnsafeEnchantment(enchant, target);
        player.getInventory().setItem(slot, updated);
        plugin.messages().send(player, "loot.book-applied", MessageManager.vars(
                "enchant", nameOf(enchant),
                "level", roman(target),
                "item", plugin.loadout().displayName(updated)));
        return true;
    }

    /** respect-max-level 打开时按原版上限，否则用 absolute-max-level 兜住。 */
    private int ceilingOf(Enchantment enchant) {
        if (plugin.configs().main().getBoolean("loot.respect-max-level", false)) {
            return enchant.getMaxLevel();
        }
        return Math.max(1, plugin.configs().main().getInt("loot.absolute-max-level", 10));
    }

    /** 优先主手，其次背包里第一把还吃得下这条附魔的武器。 */
    private int findWeaponSlot(Player player, Map<Enchantment, Integer> stored) {
        int held = player.getInventory().getHeldItemSlot();
        if (accepts(player.getInventory().getItem(held), stored)) {
            return held;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (accepts(contents[slot], stored)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean accepts(ItemStack stack, Map<Enchantment, Integer> stored) {
        if (stack == null || stack.getType() != Material.MACE) {
            return false;
        }
        for (Map.Entry<Enchantment, Integer> entry : stored.entrySet()) {
            int cap = ceilingOf(entry.getKey());
            int current = stack.getEnchantmentLevel(entry.getKey());
            if (Math.min(cap, entry.getValue()) > current) {
                return true;
            }
        }
        return false;
    }

    public Map<Enchantment, Integer> storedOf(ItemStack book) {
        if (book == null || book.getType() != Material.ENCHANTED_BOOK) {
            return Map.of();
        }
        if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            return meta.getStoredEnchants();
        }
        return Map.of();
    }

    /** 附魔展示名。走 Adventure 的翻译组件，玩家看到的是本地化名字。 */
    public String nameOf(Enchantment enchant) {
        return PlainTextComponentSerializer.plainText().serialize(enchant.displayName(1))
                .replaceAll("\\s+[IVXLC]+$", "");
    }

    /** 等级罗马数字。超过 10 级直接用阿拉伯数字，罗马数字太长反而看不清。 */
    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }
}
