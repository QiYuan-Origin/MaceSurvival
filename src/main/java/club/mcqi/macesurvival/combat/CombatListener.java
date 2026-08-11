package club.mcqi.macesurvival.combat;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;

public final class CombatListener implements Listener {
    private final CombatManager combat;

    public CombatListener(CombatManager combat) {
        this.combat = Objects.requireNonNull(combat, "combat");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event);
        if (attacker == null || !combat.isParticipant(attacker) || event.getEntity().equals(attacker)) {
            return;
        }
        event.setDamage(event.getDamage() * combat.damageMultiplier(attacker));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHealing(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player && combat.isParticipant(player)) {
            event.setAmount(event.getAmount() * combat.healingMultiplier(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        combat.markDeathWeapons(event.getDrops());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !combat.isParticipant(player)) {
            return;
        }
        Item entity = event.getItem();
        ItemStack stack = entity.getItemStack();
        if (combat.isDeathWeapon(stack) && combat.mergeDeathWeapon(player, stack)) {
            event.setCancelled(true);
            entity.remove();
            return;
        }

        LootApplyResult result = combat.applyInstantLoot(player, stack,
            oldArmor -> player.getWorld().dropItemNaturally(player.getLocation(), oldArmor));
        if (result == LootApplyResult.NOT_SPECIAL) {
            if (combat.storeDuplicateEquipment(player, stack)) {
                event.setCancelled(true);
                entity.remove();
            }
            return;
        }
        event.setCancelled(true);
        if (result == LootApplyResult.APPLIED) {
            if (stack.getAmount() <= 1) {
                entity.remove();
            } else {
                stack.setAmount(stack.getAmount() - 1);
                entity.setItemStack(stack);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (combat.isDeathWeapon(stack) || combat.isStarterWeapon(stack)) {
            event.getEntity().setUnlimitedLifetime(true);
            event.getEntity().setWillAge(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !combat.isParticipant(player)) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (event.isRightClick() && combat.isEquipmentBundle(current)
            && combat.addToEquipmentBundle(current, cursor)) {
            event.setCancelled(true);
            event.setCurrentItem(current);
            consumeCursor(event);
            return;
        }
        if (event.isRightClick() && combat.isEquipmentBundle(cursor)
            && combat.addToEquipmentBundle(cursor, current)) {
            event.setCancelled(true);
            event.getView().setCursor(cursor);
            event.setCurrentItem(null);
            return;
        }
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
            if (isEmpty(current) && combat.isArmor(cursor)
                && combat.tryEquipArmor(player, cursor,
                    oldArmor -> player.getWorld().dropItemNaturally(player.getLocation(), oldArmor))) {
                consumeCursor(event);
            }
            return;
        }

        if (event.getClickedInventory() == player.getInventory() && event.isShiftClick()
            && combat.isArmor(current)) {
            event.setCancelled(true);
            if (combat.tryEquipArmor(player, current,
                oldArmor -> player.getWorld().dropItemNaturally(player.getLocation(), oldArmor))) {
                event.setCurrentItem(null);
            }
            return;
        }

        if (event.getClickedInventory() != player.getInventory() || isEmpty(current) || isEmpty(cursor)) {
            return;
        }
        if (event.isRightClick() && combat.isWeaponBook(cursor)) {
            event.setCancelled(true);
            if (combat.applyWeaponBook(player, cursor, current)) {
                consumeCursor(event);
            }
            return;
        }
        if (combat.mergeDamagedItems(player, cursor, current)) {
            event.setCancelled(true);
            event.setCurrentItem(current);
            event.getView().setCursor(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !combat.isParticipant(player)) {
            return;
        }
        boolean touchesArmor = event.getRawSlots().stream()
            .anyMatch(rawSlot -> event.getView().getSlotType(rawSlot) == InventoryType.SlotType.ARMOR);
        if (touchesArmor) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            || !combat.isParticipant(event.getPlayer()) || !combat.isArmor(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        event.setCancelled(true);
        if (combat.tryEquipArmor(player, held,
            oldArmor -> player.getWorld().dropItemNaturally(player.getLocation(), oldArmor))) {
            if (held.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                held.setAmount(held.getAmount() - 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBundleRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
            || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
            || !combat.isParticipant(event.getPlayer()) || !combat.isEquipmentBundle(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        combat.ejectEquipmentBundle(event.getPlayer(), held);
        event.getPlayer().getInventory().setItemInMainHand(held);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExperienceMend(PlayerItemMendEvent event) {
        if (combat.isParticipant(event.getPlayer())
            && event.getItem().containsEnchantment(Enchantment.MENDING)) {
            event.setCancelled(true);
        }
    }

    private Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void consumeCursor(InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (isEmpty(cursor) || cursor.getAmount() <= 1) {
            event.getView().setCursor(null);
            return;
        }
        cursor.setAmount(cursor.getAmount() - 1);
        event.getView().setCursor(cursor);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
