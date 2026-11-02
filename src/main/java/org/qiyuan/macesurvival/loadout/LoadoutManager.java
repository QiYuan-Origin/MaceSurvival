package org.qiyuan.macesurvival.loadout;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;

/** Starting gear and lobby access items share the configured layout and text. */
public final class LoadoutManager {
    private final MaceSurvival plugin;
    private final NamespacedKey lobbyAction;
    public LoadoutManager(MaceSurvival plugin) {
        this.plugin = plugin;
        lobbyAction = new NamespacedKey(plugin, "lobby_action");
    }
    public String lobbyAction(ItemStack stack) {
        return stack == null || !stack.hasItemMeta() ? null : stack.getItemMeta().getPersistentDataContainer().get(lobbyAction, PersistentDataType.STRING);
    }
    public void giveStartingKit(Player player) { applyStartingKit(player); }
    public void applyStartingKit(Player player) {
        var inventory = player.getInventory();
        inventory.clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setExp(0);
        player.setLevel(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
        var order = plugin.layouts().playerSlots(player.getUniqueId());
        for (int slot = 0; slot < order.size(); slot++) {
            if (!order.get(slot).isEmpty() && startingAmount(order.get(slot)) > 0) inventory.setItem(slot, preview(order.get(slot)));
        }
        inventory.setHeldItemSlot(Math.max(0, order.indexOf("sword")));
    }
    public void applyLoadout(Player player, String loadoutName) { applyStartingKit(player); }
    public void giveLobbyItems(Player player) {
        var config = plugin.configs().main();
        giveLobbyItem(player, "stats", config.getInt("loadout.lobby-stats-slot", 0));
        giveLobbyItem(player, "menu", config.getInt("loadout.lobby-menu-slot", 4));
        giveLobbyItem(player, "team", config.getInt("loadout.lobby-team-slot", 8));
        int held = config.getInt("loadout.lobby-menu-slot", 4);
        if (held >= 0 && held < 9) player.getInventory().setHeldItemSlot(held);
    }
    private void giveLobbyItem(Player player, String action, int slot) {
        if (slot < 0 || slot > 8) return;
        ItemStack stack = plugin.menus().lobbyItem(action);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(lobbyAction, PersistentDataType.STRING, action);
        stack.setItemMeta(meta);
        player.getInventory().setItem(slot, stack);
    }
    /** Real kit item without menu metadata; menu previews tag their own clones. */
    public ItemStack preview(String key) {
        var config = plugin.configs().main();
        Material material = materialFor(key);
        int amount = startingAmount(key);
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        var meta = item.getItemMeta();
        meta.displayName(Text.parse(plugin.menus().loadoutName(key)));
        meta.setUnbreakable(key.equals("sword") || key.equals("axe"));
        item.setItemMeta(meta);
        if (key.equals("mace_1") || key.equals("mace_2")) plugin.loot().initializeDurability(item, config.getInt("loadout.mace-durability", 40));
        return item;
    }
    private Material materialFor(String key) {
        var config = plugin.configs().main();
        return switch (key) {
            case "sword" -> Material.NETHERITE_SWORD;
            case "axe" -> Material.NETHERITE_AXE;
            case "mace_1", "mace_2" -> Material.MACE;
            case "food" -> material(config.getString("loadout.food-material", "COOKED_BEEF"), Material.COOKED_BEEF);
            case "blocks" -> material(config.getString("loadout.block-material", "OAK_PLANKS"), Material.OAK_PLANKS);
            case "wind_charge" -> Material.WIND_CHARGE;
            default -> Material.BARRIER;
        };
    }
    public int startingAmount(String key) {
        var config = plugin.configs().main();
        return Math.max(0, Math.min(materialFor(key).getMaxStackSize(), switch (key) {
            case "food" -> config.getInt("loadout.food-amount", 16);
            case "blocks" -> config.getInt("loadout.block-amount", 32);
            case "wind_charge" -> config.getInt("loadout.wind-charge-amount", 8);
            default -> 1;
        }));
    }
    private static Material material(String raw, Material fallback) {
        Material material = raw == null ? null : Material.matchMaterial(raw);
        return material == null || !material.isItem() || material.isAir() ? fallback : material;
    }
}
