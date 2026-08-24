package org.qiyuan.macesurvival.loadout;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.util.ItemBuilder;

import java.util.List;

/**
 * 开局装备。槽位全部由 loadout 配置驱动，服主可以把重锤放到任意快捷栏格子。
 * 重锤带一个自定义耐久计数（PDC），用尽时会炸掉，这套逻辑在 LootManager 里处理，
 * 这里只负责把初始次数写进物品。
 */
public final class LoadoutManager {

    /** 重锤剩余次数。名字固定，改了会让旧局的物品失效。 */
    public static final String DURABILITY_KEY = "durability";
    /** 满耐久值，用来画耐久条。 */
    public static final String MAX_DURABILITY_KEY = "max-durability";

    private final MaceSurvival plugin;

    public LoadoutManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey durabilityKey() {
        return plugin.key(DURABILITY_KEY);
    }

    private NamespacedKey maxDurabilityKey() {
        return plugin.key(MAX_DURABILITY_KEY);
    }

    /** 开局发装备。先清空再发，避免上一局残留。 */
    public void giveStartingKit(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType()));
        player.setHealth(healthOf(player));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExp(0f);
        player.setLevel(0);
        player.setFireTicks(0);

        var config = plugin.configs().main();
        // 槽位来自玩家自己的布局，没改过的人拿到的就是 config.yml 的默认值
        int[] slots = plugin.layouts().slots(player);
        inventory.setItem(slots[0], mace());
        inventory.setItem(slots[1],
                food(config.getInt("loadout.food-amount", 16)));
        inventory.setItem(slots[2],
                blocks(config.getInt("loadout.block-amount", 32)));
        inventory.setItem(slots[3],
                windCharge(config.getInt("loadout.wind-charge-amount", 8)));
        inventory.setHeldItemSlot(slots[0]);
    }

    private double healthOf(Player player) {
        var attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20D : attribute.getValue();
    }

    /** 开局重锤。带自定义耐久计数，不带原版耐久损耗。 */
    public ItemStack mace() {
        return mace(plugin.configs().main().getInt("loadout.mace-durability", 40));
    }

    /** 指定次数的重锤，战利品表用这个产出更耐用的武器。 */
    public ItemStack mace(int durability) {
        int max = Math.max(1, durability);
        return ItemBuilder.of(Material.MACE)
                .name(plugin.messages().raw("loadout.mace-name"))
                .lore(loreOf("loadout.mace-lore", max, max))
                .pdc(durabilityKey(), max)
                .pdc(maxDurabilityKey(), max)
                .durabilityBar(max, max)
                .hideAttributes()
                .build();
    }

    private List<String> loreOf(String key, int remaining, int max) {
        return plugin.configs().messages().getStringList(key).stream()
                .map(line -> line
                        .replace("{remaining}", String.valueOf(remaining))
                        .replace("{max}", String.valueOf(max)))
                .toList();
    }

    public ItemStack food(int amount) {
        Material material = material("loadout.food-material", Material.COOKED_BEEF);
        return ItemBuilder.of(material, amount)
                .name(plugin.messages().raw("loadout.food-name"))
                .lore(plugin.configs().messages().getStringList("loadout.food-lore"))
                .build();
    }

    public ItemStack blocks(int amount) {
        Material material = material("loadout.block-material", Material.OAK_PLANKS);
        return ItemBuilder.of(material, amount)
                .name(plugin.messages().raw("loadout.block-name"))
                .lore(plugin.configs().messages().getStringList("loadout.block-lore"))
                .build();
    }

    public ItemStack windCharge(int amount) {
        return ItemBuilder.of(Material.WIND_CHARGE, amount)
                .name(plugin.messages().raw("loadout.wind-charge-name"))
                .lore(plugin.configs().messages().getStringList("loadout.wind-charge-lore"))
                .build();
    }

    // ─────────────── 大厅物品 ───────────────

    /**
     * 大厅手持物。信息、战绩、导航三件套，槽位配 -1 就不发。
     * 大厅是冒险模式，玩家拿不走，但仍然可以点开导航。
     */
    public void giveLobbyItems(Player player) {
        var config = plugin.configs().main();
        PlayerInventory inventory = player.getInventory();

        put(inventory, config.getInt("loadout.lobby-info-slot", 4), lobbyInfo());
        put(inventory, config.getInt("loadout.lobby-stats-slot", 0), lobbyStats(player));
        put(inventory, config.getInt("loadout.lobby-menu-slot", 8), lobbyMenu());
        int held = config.getInt("loadout.lobby-info-slot", 4);
        inventory.setHeldItemSlot(held >= 0 && held <= 8 ? held : 4);
    }

    private void put(PlayerInventory inventory, int slot, ItemStack stack) {
        if (slot < 0 || slot > 8) {
            return;
        }
        inventory.setItem(slot, stack);
    }

    private ItemStack lobbyInfo() {
        var config = plugin.configs().main();
        return ItemBuilder.of(material("loadout.lobby-info-material", Material.PAPER))
                .name(plugin.messages().raw("loadout.lobby-info-name"))
                .lore(lobbyLore("loadout.lobby-info-lore",
                        "players", String.valueOf(plugin.game().lobbyCount()),
                        "max", String.valueOf(config.getInt("game.max-players", 40))))
                .build();
    }

    private ItemStack lobbyStats(Player player) {
        var record = plugin.stats().of(player);
        return ItemBuilder.of(material("loadout.lobby-stats-material", Material.BOOK))
                .name(plugin.messages().raw("loadout.lobby-stats-name"))
                .lore(lobbyLore("loadout.lobby-stats-lore",
                        "wins", String.valueOf(record.wins()),
                        "kills", String.valueOf(record.kills()),
                        "streak", String.valueOf(record.bestStreak())))
                .build();
    }

    private ItemStack lobbyMenu() {
        return ItemBuilder.of(material("loadout.lobby-menu-material", Material.COMPASS))
                .name(plugin.messages().raw("loadout.lobby-menu-name"))
                .lore(plugin.configs().messages().getStringList("loadout.lobby-menu-lore"))
                .build();
    }

    /** lore 变量替换。键值成对传入，漏配的变量会被清空而不是留在界面上。 */
    private List<String> lobbyLore(String key, String... pairs) {
        return plugin.configs().messages().getStringList(key).stream()
                .map(line -> {
                    String out = line;
                    for (int i = 0; i + 1 < pairs.length; i += 2) {
                        out = out.replace("{" + pairs[i] + "}", pairs[i + 1]);
                    }
                    return out;
                })
                .toList();
    }

    private Material material(String path, Material fallback) {
        String raw = plugin.configs().main().getString(path, fallback.name());
        Material parsed = Material.matchMaterial(raw.toUpperCase());
        return parsed == null ? fallback : parsed;
    }

    // ─────────────── 自定义耐久 ───────────────

    public boolean hasCustomDurability(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(durabilityKey(), PersistentDataType.INTEGER);
    }

    public int remaining(ItemStack stack) {
        if (!hasCustomDurability(stack)) {
            return -1;
        }
        Integer value = stack.getItemMeta().getPersistentDataContainer()
                .get(durabilityKey(), PersistentDataType.INTEGER);
        return value == null ? -1 : value;
    }

    public int maxDurability(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return -1;
        }
        Integer value = stack.getItemMeta().getPersistentDataContainer()
                .get(maxDurabilityKey(), PersistentDataType.INTEGER);
        return value == null ? -1 : value;
    }

    /**
     * 写回剩余次数并刷新 lore 与耐久条。
     * 返回写好的物品，调用方负责放回背包；传入 0 以下不特殊处理，由调用方决定是否炸。
     */
    public ItemStack withRemaining(ItemStack stack, int remaining) {
        int max = Math.max(1, maxDurability(stack));
        int clamped = Math.max(0, Math.min(remaining, max));
        return ItemBuilder.copyOf(stack)
                .lore(loreOf("loadout.mace-lore", clamped, max))
                .pdc(durabilityKey(), clamped)
                .pdc(maxDurabilityKey(), max)
                .durabilityBar(clamped, max)
                .build();
    }

    /**
     * 合并耐久用。次数超过原满值时把满值一起顶上去，
     * 否则捡来的次数会被 withRemaining 的上限截掉。
     */
    public ItemStack withCapacity(ItemStack stack, int remaining) {
        int max = Math.max(Math.max(1, maxDurability(stack)), remaining);
        return ItemBuilder.copyOf(stack)
                .lore(loreOf("loadout.mace-lore", remaining, max))
                .pdc(durabilityKey(), remaining)
                .pdc(maxDurabilityKey(), max)
                .durabilityBar(remaining, max)
                .build();
    }

    /** 消息里引用武器名。没有自定义名时退回材质名，避免出现空白。 */
    public String displayName(ItemStack stack) {
        if (stack == null) {
            return "weapon";
        }
        if (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()) {
            return PlainTextComponentSerializer.plainText()
                    .serialize(stack.getItemMeta().displayName());
        }
        return readable(stack.getType());
    }

    private String readable(Material material) {
        String[] words = material.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
