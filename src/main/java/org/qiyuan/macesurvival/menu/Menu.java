package org.qiyuan.macesurvival.menu;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;
import org.qiyuan.macesurvival.util.ItemBuilder;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单基类。布局全部来自 menu/*.yml，代码只负责取值和响应点击。
 * 关键约定：MenuListener 会在 handleClick 之前先 setCancelled(true)，
 * 所以子类不需要自己拦，也绝不能反过来把它放开。
 */
public abstract class Menu implements InventoryHolder {

    protected final MaceSurvival plugin;
    protected final Player viewer;
    private Inventory inventory;
    /** 这一轮渲染里被玻璃占住的槽位，点到就只响一声。 */
    private final Set<Integer> framed = new HashSet<>();

    protected Menu(MaceSurvival plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    /** menu/ 下的文件名，不含后缀。 */
    protected abstract String configName();

    /** 往已经铺好边框的容器里放按钮。 */
    protected abstract void render();

    /** 点击回调。事件已经被取消，这里只处理业务。 */
    public abstract void handleClick(InventoryClickEvent event);

    protected FileConfiguration config() {
        return plugin.configs().menu(configName());
    }

    protected ConfigurationSection items() {
        ConfigurationSection section = config().getConfigurationSection("items");
        return section == null ? config().createSection("items") : section;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** 打开菜单。每次都重建容器，避免上一次的残留物品被带过来。 */
    public void open() {
        int rows = Math.max(1, Math.min(6, config().getInt("rows", 6)));
        inventory = Bukkit.createInventory(this, rows * 9,
                Text.parse(config().getString("title", configName())));
        frame(rows);
        render();
        viewer.openInventory(inventory);
        Sounds.play(plugin, viewer, Sounds.parse(config().getStringList("open-sound")));
    }

    /** 就地重画。用于刷新按钮而不闪一次界面。 */
    public void refresh() {
        if (inventory == null) {
            open();
            return;
        }
        inventory.clear();
        frame(inventory.getSize() / 9);
        render();
    }

    /**
     * 围一圈玻璃。四角用本页主色，其余用深色，这样每页都有自己的调子
     * 但整体仍然统一。
     */
    private void frame(int rows) {
        framed.clear();
        ConfigurationSection frame = config().getConfigurationSection("frame");
        if (frame == null) {
            return;
        }
        ItemStack corner = pane(frame, "corner");
        ItemStack filler = pane(frame, "filler");
        int size = rows * 9;
        int last = size - 9;
        for (int slot = 0; slot < size; slot++) {
            boolean edge = slot < 9 || slot >= last || slot % 9 == 0 || slot % 9 == 8;
            if (edge) {
                inventory.setItem(slot, filler);
                framed.add(slot);
            }
        }
        inventory.setItem(0, corner);
        inventory.setItem(8, corner);
        inventory.setItem(last, corner);
        inventory.setItem(size - 1, corner);
    }

    private ItemStack pane(ConfigurationSection frame, String prefix) {
        Material material = Material.matchMaterial(
                frame.getString(prefix + "-material", "BLACK_STAINED_GLASS_PANE").toUpperCase());
        return ItemBuilder.of(material == null ? Material.BLACK_STAINED_GLASS_PANE : material)
                .name(frame.getString(prefix + "-name", " "))
                .lore(frame.getStringList(prefix + "-lore"))
                .build();
    }

    /** 按配置节生成按钮。material 走 ItemBuilder 的 head- 解析。 */
    protected ItemStack button(String key, Map<String, String> vars) {
        ConfigurationSection section = items().getConfigurationSection(key);
        if (section == null) {
            return null;
        }
        return ItemBuilder.fromMaterialString(
                        section.getString("material", "STONE"), viewer.getName())
                .name(section.getString("name", " "), vars)
                .loreWith(section.getStringList("lore"), vars)
                .build();
    }

    protected ItemStack button(String key) {
        return button(key, Map.of());
    }

    /** 把按钮放到它自己配的槽位。槽位越界就不放，绝不抛异常。 */
    protected void place(String key, Map<String, String> vars) {
        ConfigurationSection section = items().getConfigurationSection(key);
        if (section == null) {
            return;
        }
        put(section.getInt("slot", -1), button(key, vars));
    }

    protected void place(String key) {
        place(key, Map.of());
    }

    protected void put(int slot, ItemStack stack) {
        if (stack == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, stack);
        framed.remove(slot);
    }

    /** 给 MenuListener 用：这一格是不是纯装饰。 */
    public boolean isDecoration(int slot) {
        return framed.contains(slot);
    }

    protected int slotOf(String key) {
        ConfigurationSection section = items().getConfigurationSection(key);
        return section == null ? -1 : section.getInt("slot", -1);
    }

    protected List<Integer> slotList(String key) {
        return items().getIntegerList(key);
    }

    protected String action(String key) {
        ConfigurationSection section = items().getConfigurationSection(key);
        return section == null ? "" : section.getString("action", "");
    }

    protected void click() {
        Sounds.play(plugin, viewer, Sounds.click());
    }

    protected void deny() {
        Sounds.play(plugin, viewer, Sounds.deny());
    }
}
