package org.qiyuan.macesurvival.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.util.Sounds;

/**
 * 菜单点击入口。这里的顺序很要紧：必须先 setCancelled(true)，
 * 再把事件交给菜单。菜单里的物品一件都不许被拿走，
 * 包括 shift 点、拖拽、以及把物品塞进菜单这几种走法。
 */
public final class MenuListener implements Listener {

    private final MaceSurvival plugin;

    public MenuListener(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Menu menu = menuOf(event.getInventory().getHolder());
        if (menu == null) {
            // 也可能是玩家在自己背包里 shift 点，想把物品送进菜单
            menu = menuOf(event.getView().getTopInventory().getHolder());
            if (menu == null) {
                return;
            }
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }

        if (menu.isDecoration(event.getRawSlot())) {
            Sounds.play(plugin, player, Sounds.border());
            return;
        }

        menu.handleClick(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory().getHolder()) == null) {
            return;
        }
        int top = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < top) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private Menu menuOf(InventoryHolder holder) {
        return holder instanceof Menu menu ? menu : null;
    }
}
