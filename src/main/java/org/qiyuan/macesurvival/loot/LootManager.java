package org.qiyuan.macesurvival.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.arena.ChestTier;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.util.ItemBuilder;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 战利品。开箱滚表、背包内合并重锤耐久、耐久耗尽当场炸掉。
 * 表结构全部来自 loot.yml，代码不内置任何一条战利品。
 */
public final class LootManager {

    private final MaceSurvival plugin;
    private final Random random = new Random();

    public LootManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    // ─────────────── 开箱 ───────────────

    /** 滚一箱战利品。空槽由 empty-chance 决定，滚不出东西的箱子也算开过。 */
    public List<ItemStack> roll(ChestTier tier) {
        List<LootEntry> table = tableOf(tier);
        List<ItemStack> out = new ArrayList<>();
        if (table.isEmpty()) {
            return out;
        }
        int rolls = Math.max(1, plugin.configs().loot()
                .getInt("rolls." + tier.configKey(), 3 + tier.stars()));
        int empty = clampPercent(plugin.configs().loot()
                .getInt("empty-chance." + tier.configKey(), 0));

        int total = table.stream().mapToInt(LootEntry::weight).sum();
        for (int i = 0; i < rolls; i++) {
            if (empty > 0 && random.nextInt(100) < empty) {
                continue;
            }
            LootEntry picked = pick(table, total);
            if (picked != null) {
                out.add(build(picked));
            }
        }
        return out;
    }

    /** 管理员 /mace loot test 用的滚表预览。 */
    public List<ItemStack> preview(int stars) {
        return roll(ChestTier.byStars(stars));
    }

    private List<LootEntry> tableOf(ChestTier tier) {
        List<LootEntry> entries = new ArrayList<>();
        for (Map<?, ?> map : plugin.configs().loot().getMapList("tables." + tier.configKey())) {
            LootEntry entry = LootEntry.from(asSection(map));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** 把 yaml 里的 Map 包成 ConfigurationSection，好让 LootEntry 只认一种入参。 */
    private ConfigurationSection asSection(Map<?, ?> map) {
        var config = new org.bukkit.configuration.MemoryConfiguration();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            config.set(String.valueOf(entry.getKey()), entry.getValue());
        }
        return config;
    }

    private LootEntry pick(List<LootEntry> table, int total) {
        int cursor = random.nextInt(Math.max(1, total));
        for (LootEntry entry : table) {
            cursor -= entry.weight();
            if (cursor < 0) {
                return entry;
            }
        }
        return table.isEmpty() ? null : table.get(table.size() - 1);
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** 把一条战利品变成物品。重锤走 LoadoutManager 以保证 PDC 一致。 */
    private ItemStack build(LootEntry entry) {
        int amount = entry.min() >= entry.max()
                ? entry.min()
                : entry.min() + random.nextInt(entry.max() - entry.min() + 1);

        if (entry.isBook()) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(entry.book(), entry.bookLevel(), true);
                book.setItemMeta(meta);
            }
            return book;
        }
        if (entry.isWeapon()) {
            int durability = entry.durability() > 0
                    ? entry.durability()
                    : plugin.configs().main().getInt("loadout.mace-durability", 40);
            ItemStack mace = plugin.loadout().mace(durability);
            if (entry.enchant() != null) {
                mace = ItemBuilder.copyOf(mace)
                        .enchant(entry.enchant(), entry.enchantLevel())
                        .build();
            }
            return mace;
        }
        ItemBuilder builder = ItemBuilder.of(entry.material(), amount);
        if (entry.enchant() != null) {
            builder.enchant(entry.enchant(), entry.enchantLevel());
        }
        return builder.build();
    }

    /** 玩家开箱。箱子一次性，开完从台账摘掉并清掉悬浮文本。 */
    public boolean openChest(Player player, Location location) {
        ChestTier tier = plugin.arena().tierAt(location);
        if (tier == null) {
            return false;
        }
        plugin.arena().consumeChest(location);

        List<ItemStack> loot = roll(tier);
        if (loot.isEmpty()) {
            plugin.messages().send(player, "loot.chest-empty");
            Sounds.play(plugin, player, Sounds.deny());
            return true;
        }
        for (ItemStack stack : loot) {
            give(player, stack);
        }
        plugin.messages().send(player, "loot.chest-opened",
                MessageManager.vars("stars", tier.starBar()));
        Sounds.play(plugin, player, Sounds.chestOpen(tier.stars()));
        return true;
    }

    /**
     * 发一件战利品。重锤优先并进背包里那把，附魔书按配置即时生效，
     * 背包满了就掉在脚下，绝不静默吞掉。
     */
    public void give(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        if (plugin.loadout().hasCustomDurability(stack) && mergeInto(player, stack)) {
            return;
        }
        if (stack.getType() == Material.ENCHANTED_BOOK
                && plugin.configs().main().getBoolean("loot.books-apply-on-pickup", true)
                && plugin.enchants().applyFromBook(player, stack)) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    // ─────────────── 耐久合并 ───────────────

    /**
     * 把捡到的重锤次数并进背包里已有的那把，避免玩家拿着两把半残武器。
     * 合并成功返回 true，调用方就不该再把物品塞进背包。
     */
    public boolean mergeInto(Player player, ItemStack pickup) {
        if (!plugin.configs().main().getBoolean("loot.merge-durability", true)) {
            return false;
        }
        var loadout = plugin.loadout();
        int gain = loadout.remaining(pickup);
        if (gain <= 0) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack existing = contents[slot];
            if (existing == null || existing.getType() != pickup.getType()) {
                continue;
            }
            if (!loadout.hasCustomDurability(existing)) {
                continue;
            }
            int merged = loadout.remaining(existing) + gain;
            ItemStack updated = loadout.withCapacity(existing, merged);
            player.getInventory().setItem(slot, updated);
            plugin.messages().send(player, "loot.durability-merged", MessageManager.vars(
                    "item", loadout.displayName(updated),
                    "durability", String.valueOf(loadout.remaining(updated))));
            Sounds.play(plugin, player, Sounds.repairMerge());
            return true;
        }
        return false;
    }

    // ─────────────── 耐久消耗与爆炸 ───────────────

    /**
     * 用掉一次重锤。归零时按配置在手里炸开，返回 true 表示武器已经没了。
     * 爆炸只伤人不毁地形，除非服主打开 explosion-breaks-blocks。
     */
    public boolean consume(Player player, int slot, ItemStack weapon) {
        var loadout = plugin.loadout();
        if (!loadout.hasCustomDurability(weapon)) {
            return false;
        }
        int left = loadout.remaining(weapon) - 1;
        if (left > 0) {
            player.getInventory().setItem(slot, loadout.withRemaining(weapon, left));
            warnIfLow(player, weapon, left);
            return false;
        }
        player.getInventory().setItem(slot, null);
        shatter(player, weapon);
        return true;
    }

    private void warnIfLow(Player player, ItemStack weapon, int left) {
        int max = Math.max(1, plugin.loadout().maxDurability(weapon));
        // 低于两成或只剩三次时提醒一次，避免每挥一下都刷屏
        boolean low = left <= 3 || left <= max / 5;
        if (!low) {
            return;
        }
        plugin.messages().send(player, "loot.durability-low", MessageManager.vars(
                "item", plugin.loadout().displayName(weapon),
                "durability", String.valueOf(left)));
    }

    /** 武器在手里炸开。 */
    private void shatter(Player player, ItemStack weapon) {
        plugin.messages().send(player, "loot.durability-broke",
                MessageManager.vars("item", plugin.loadout().displayName(weapon)));
        Sounds.play(plugin, player, Sounds.itemShatter());

        if (!plugin.configs().main().getBoolean("loot.durability-explosion", true)) {
            return;
        }
        float power = (float) plugin.configs().main().getDouble("loot.explosion-power", 2.4);
        boolean breakBlocks = plugin.configs().main()
                .getBoolean("loot.explosion-breaks-blocks", false);
        plugin.combat().markShatter(player);
        player.getWorld().createExplosion(player.getLocation(), power, false, breakBlocks, player);
    }
}
