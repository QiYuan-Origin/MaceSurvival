package org.qiyuan.macesurvival.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GameState;

/** Holder identity survives page changes; cancellation always precedes menu logic. */
public final class MenuListener implements Listener {
    private final MaceSurvival plugin;
    public MenuListener(MaceSurvival plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLobbyItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String action = plugin.loadouts().lobbyAction(event.getItem());
        if (action == null) return;
        event.setCancelled(true);
        if (plugin.game().state() != GameState.WAITING && plugin.game().state() != GameState.COUNTDOWN) return;
        Player player = event.getPlayer();
        plugin.menus().open(player, action.equals("menu") ? "main" : action);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (plugin.menus().isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) plugin.menus().handleClick(player, event);
            return;
        }
        if (plugin.loadouts().lobbyAction(event.getCurrentItem()) != null || plugin.loadouts().lobbyAction(event.getCursor()) != null
            || plugin.menus().isPreview(event.getCurrentItem()) || plugin.menus().isPreview(event.getCursor())) event.setCancelled(true);
        if (event.getHotbarButton() >= 0 && plugin.loadouts().lobbyAction(event.getWhoClicked().getInventory().getItem(event.getHotbarButton())) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (plugin.menus().isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) plugin.menus().handleDrag(player, event);
        } else if (plugin.loadouts().lobbyAction(event.getOldCursor()) != null || plugin.menus().isPreview(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) plugin.menus().onClose(player, event.getInventory());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onDrop(PlayerDropItemEvent event) {
        if (plugin.loadouts().lobbyAction(event.getItemDrop().getItemStack()) != null || plugin.menus().isPreview(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onSwap(PlayerSwapHandItemsEvent event) {
        if (plugin.loadouts().lobbyAction(event.getMainHandItem()) != null || plugin.loadouts().lobbyAction(event.getOffHandItem()) != null
            || plugin.menus().isPreview(event.getMainHandItem()) || plugin.menus().isPreview(event.getOffHandItem())) event.setCancelled(true);
    }
}
