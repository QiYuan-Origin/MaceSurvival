package org.qiyuan.macesurvival.listener;

import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;

/**
 * All loot actions are limited to living round participants. Menu previews and
 * cancelled actions cannot grant items. Cursor transactions use cloned stacks,
 * cancel vanilla movement first, then commit only the exact cursor and target.
 */
public final class LootListener implements Listener {
    private final MaceSurvival plugin;

    public LootListener(MaceSurvival plugin) { this.plugin = plugin; }

    private boolean playing(Player player) { return plugin.game().active() && plugin.game().participant(player) && !player.isDead()
        && player.getGameMode() == GameMode.SURVIVAL && player.getWorld() == plugin.arenas().getArenaWorld() && !plugin.drops().isDropping(player); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !playing(player)) return;
        var location = event.getInventory().getLocation();
        if (location != null && plugin.arenas().tierAt(location) != null) plugin.arenas().consumeChest(location);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !playing(player)) return;
        ItemStack source = event.getItem().getItemStack();
        if (!plugin.enchants().isAutoApply(source)) return;
        event.setCancelled(true);
        if (!plugin.enchants().applyFromBook(player, source)) {
            event.setCancelled(false);
            return;
        }
        // Decrement the entity itself, never a detached ItemStack copy only.
        if (source.getAmount() <= 1) event.getItem().remove();
        else {
            ItemStack remainder = source.clone();
            remainder.setAmount(remainder.getAmount() - 1);
            event.getItem().setItemStack(remainder);
            event.getItem().setPickupDelay(5);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryRightClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !playing(player)
            || event.getClick() != ClickType.RIGHT || event.getClickedInventory() == null
            || !event.getClickedInventory().equals(player.getInventory())
            || plugin.menus().isMenu(event.getView().getTopInventory())) return;
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        if (cursor.getType().isAir() || target == null || target.getType().isAir()) return;
        boolean merge = plugin.loot().hasCustomDurability(cursor) && plugin.loot().hasCustomDurability(target)
            && cursor.getType() == target.getType();
        boolean book = cursor.getType() == Material.ENCHANTED_BOOK;
        if (!merge && !book) return;
        event.setCancelled(true);
        ItemStack sourceCopy = cursor.clone();
        ItemStack targetCopy = target.clone();
        boolean applied;
        if (merge) applied = plugin.loot().merge(player, sourceCopy, targetCopy);
        else {
            applied = plugin.enchants().applyByHand(player, sourceCopy, targetCopy);
            if (applied) sourceCopy.setAmount(sourceCopy.getAmount() - 1);
            else player.sendMessage(plugin.messages().prefixed("enchant.incompatible"));
        }
        if (!applied) return;
        event.setCurrentItem(targetCopy);
        player.setItemOnCursor(sourceCopy.getAmount() <= 0 ? null : sourceCopy);
    }

    /**
     * One main-hand event owns both hands. The paired off-hand event is ignored,
     * preventing a book stack from being consumed twice by one right-click.
     * Empty-air interaction can arrive pre-cancelled by vanilla because books
     * have no native action, so this handler deliberately handles that case.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !playing(event.getPlayer())
            || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        boolean mainBook = main.getType() == Material.ENCHANTED_BOOK;
        ItemStack source = mainBook ? main : offhand;
        ItemStack target = mainBook ? offhand : main;
        if (source.getType() != Material.ENCHANTED_BOOK) return;
        event.setCancelled(true);
        if (!plugin.enchants().applyByHand(player, source, target)) {
            player.sendMessage(plugin.messages().prefixed("enchant.incompatible"));
            return;
        }
        source.setAmount(source.getAmount() - 1);
        if (mainBook) {
            player.getInventory().setItemInMainHand(source.getAmount() <= 0 ? null : source);
            player.getInventory().setItemInOffHand(target);
        } else {
            player.getInventory().setItemInOffHand(source.getAmount() <= 0 ? null : source);
            player.getInventory().setItemInMainHand(target);
        }
    }

    /** Native use covers mace hits, spear Lunge, shield blocks and elytra flight. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDurabilityDamage(PlayerItemDamageEvent event) {
        if (!playing(event.getPlayer()) || !plugin.loot().hasCustomDurability(event.getItem())) return;
        event.setCancelled(true);
        // Native Unbreaking has already reduced the actual damage in this event.
        plugin.loot().damage(event.getPlayer(), event.getItem(), event.getDamage());
    }

    /** This mode's Mending reward is confirmed kills, rather than loose XP orbs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        if (playing(event.getPlayer()) && plugin.loot().hasCustomDurability(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && playing(player)) {
            event.setAmount(event.getAmount() * plugin.enchants().healingMultiplier(player));
        }
    }
}
