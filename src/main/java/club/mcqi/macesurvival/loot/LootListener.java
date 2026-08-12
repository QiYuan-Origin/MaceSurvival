package club.mcqi.macesurvival.loot;

import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.combat.LootApplyResult;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LootListener implements Listener {
    private final LootChestManager chests;
    private final CombatManager combat;

    public LootListener(LootChestManager chests, CombatManager combat) {
        this.chests = Objects.requireNonNull(chests, "chests");
        this.combat = Objects.requireNonNull(combat, "combat");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        LootChestManager.ManagedChest managed = chests.find(event.getInventory());
        if (managed == null) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player) || !chests.isParticipant(player)) {
            event.setCancelled(true);
            return;
        }
        chests.opened(managed, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClose(InventoryCloseEvent event) {
        LootChestManager.ManagedChest managed = chests.find(event.getInventory());
        if (managed != null && event.getPlayer() instanceof Player player) {
            chests.closed(managed, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        LootChestManager.ManagedChest managed = chests.find(event.getView().getTopInventory());
        if (managed == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || !chests.isParticipant(player)) {
            event.setCancelled(true);
            return;
        }
        Inventory top = event.getView().getTopInventory();
        boolean clickedTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
        if (!clickedTop) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }

        InventoryAction action = event.getAction();
        if (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE
            || action == InventoryAction.PLACE_SOME || action == InventoryAction.SWAP_WITH_CURSOR
            || action == InventoryAction.HOTBAR_SWAP || event.getClick().isKeyboardClick()) {
            event.setCancelled(true);
            return;
        }

        ItemStack current = event.getCurrentItem();
        if (isEmpty(current)) {
            return;
        }
        AtomicReference<ItemStack> returnedArmor = new AtomicReference<>();
        LootApplyResult result = combat.applyInstantLoot(player, current, returnedArmor::set);
        if (result == LootApplyResult.NOT_SPECIAL) {
            return;
        }
        event.setCancelled(true);
        if (result == LootApplyResult.REJECTED) {
            return;
        }

        ItemStack returned = returnedArmor.get();
        if (!isEmpty(returned)) {
            combat.tagLootSource(returned, managed.id());
            event.setCurrentItem(returned);
        } else if (current.getAmount() <= 1) {
            event.setCurrentItem(null);
        } else {
            current.setAmount(current.getAmount() - 1);
            event.setCurrentItem(current);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        LootChestManager.ManagedChest managed = chests.find(event.getView().getTopInventory());
        if (managed == null) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (chests.find(event.getSource()) != null || chests.find(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (chests.isManaged(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(chests::isManaged);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(chests::isManaged);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(chests::isManaged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(chests::isManaged)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        chests.restoreChunk(event.getChunk());
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }
}
