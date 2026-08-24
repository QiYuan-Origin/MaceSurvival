package org.qiyuan.macesurvival.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GamePlayer;

/** 战利品交互：开箱、附魔书右键、拾取合并。 */
public final class LootListener implements Listener {

    private final MaceSurvival plugin;

    public LootListener(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /** 开箱。战利品箱一次性，开完方块也一起收掉，避免玩家对着空箱子发呆。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        GamePlayer gp = plugin.game().of(player);
        if (gp == null || !gp.role().isPlaying()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            handleBookInHand(event, player, gp);
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !plugin.arena().isLootChest(block.getLocation())) {
            handleBookInHand(event, player, gp);
            return;
        }
        event.setCancelled(true);
        if (plugin.loot().openChest(player, block.getLocation())) {
            gp.addChest();
            block.setType(Material.AIR, false);
        }
    }

    /** 手持附魔书右键空气或方块，把附魔打到主手武器上。 */
    private void handleBookInHand(PlayerInteractEvent event, Player player, GamePlayer gp) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack book = event.getItem();
        if (book == null || book.getType() != Material.ENCHANTED_BOOK) {
            return;
        }
        event.setCancelled(true);
        if (plugin.enchants().applyByHand(player, book)) {
            gp.addBook();
            consumeOne(player, book);
        }
    }

    /** 用掉一本书。整叠只减一本，剩下的留在手上继续用。 */
    private void consumeOne(Player player, ItemStack book) {
        if (book.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        book.setAmount(book.getAmount() - 1);
    }

    /**
     * 地上捡东西也要走战利品逻辑：重锤合并耐久、附魔书按配置即时生效。
     * 只在对局中接管，大厅和旁观不动。
     */
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GamePlayer gp = plugin.game().of(player);
        if (gp == null || !gp.role().isPlaying()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (plugin.loadout().hasCustomDurability(stack)) {
            if (plugin.loot().mergeInto(player, stack)) {
                event.setCancelled(true);
                event.getItem().remove();
            }
            return;
        }
        if (stack.getType() != Material.ENCHANTED_BOOK
                || !plugin.configs().main().getBoolean("loot.books-apply-on-pickup", true)) {
            return;
        }
        if (plugin.enchants().applyFromBook(player, stack)) {
            gp.addBook();
            event.setCancelled(true);
            if (stack.getAmount() <= 1) {
                event.getItem().remove();
            } else {
                stack.setAmount(stack.getAmount() - 1);
                event.getItem().setItemStack(stack);
            }
        }
    }
}
