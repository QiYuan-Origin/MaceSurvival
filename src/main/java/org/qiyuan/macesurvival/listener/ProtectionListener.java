package org.qiyuan.macesurvival.listener;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.qiyuan.macesurvival.MaceSurvival;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Protects only lobby, nonparticipants and session menus; real battle inventories remain usable. */
public final class ProtectionListener implements Listener {
    private final MaceSurvival plugin;
    public ProtectionListener(MaceSurvival plugin) { this.plugin = plugin; }
    private boolean playing(Player p) { return plugin.game().active() && plugin.game().participant(p) && !p.isDead()
        && p.getGameMode() == GameMode.SURVIVAL && p.getWorld() == plugin.arenas().getArenaWorld() && !plugin.drops().isDropping(p); }
    private boolean lobby(World world) { return world.equals(plugin.lobby().spawn().getWorld()); }
    @EventHandler(ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void placeBlock(BlockPlaceEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && !playing(p)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void hunger(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && !playing(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST) public void interact(PlayerInteractEvent e) {
        if (!playing(e.getPlayer())) {
            // Lobby hotbar menus handle the cancelled event themselves. Denying
            // vanilla use also protects doors, farmland, armor and consumables.
            e.setUseInteractedBlock(Event.Result.DENY); e.setUseItemInHand(Event.Result.DENY);
        }
    }
    @EventHandler(ignoreCancelled = true) public void interactEntity(PlayerInteractEntityEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void armorStand(PlayerArmorStandManipulateEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void bucketFill(PlayerBucketFillEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void bucketEmpty(PlayerBucketEmptyEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void consume(PlayerItemConsumeEvent e) { if (!playing(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void projectile(ProjectileLaunchEvent e) { if (e.getEntity().getShooter() instanceof Player p && !playing(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) public void open(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && !playing(p) && !plugin.menus().isMenu(e.getInventory())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) public void inventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && !playing(p) && !plugin.menus().isMenu(e.getView().getTopInventory())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) public void drag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && !playing(p) && !plugin.menus().isMenu(e.getView().getTopInventory())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        if (plugin.game().lobbyOpen() && e.getTo().getY() < plugin.lobby().spawn().getY() - 8) e.getPlayer().teleport(plugin.lobby().spawn());
    }
    @EventHandler(ignoreCancelled = true) public void explode(EntityExplodeEvent e) {
        if (lobby(e.getLocation().getWorld())) { e.blockList().clear(); e.setCancelled(true); }
    }
    @EventHandler(ignoreCancelled = true) public void blockExplode(BlockExplodeEvent e) { if (lobby(e.getBlock().getWorld())) { e.blockList().clear(); e.setCancelled(true); } }
    @EventHandler(ignoreCancelled = true) public void burn(BlockBurnEvent e) { if (lobby(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void ignite(BlockIgniteEvent e) { if (lobby(e.getBlock().getWorld())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void combust(EntityCombustEvent e) { if (e.getEntity() instanceof Player p && plugin.game().protectedPlayer(p)) e.setCancelled(true); }
    private boolean harmful(PotionEffect effect) { return effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL; }
    private boolean blocksEffect(Player target, Player source, boolean harmful) {
        if (!playing(target)) return true;
        if (!harmful) return false;
        if (plugin.game().protectedPlayer(target) || source != null && plugin.game().protectedPlayer(source)) return true;
        return source != null && !source.equals(target) && plugin.configs().main().getBoolean("game.friendly-fire-disabled", true)
            && plugin.game().getPlayer(source).squad() != null && plugin.game().getPlayer(source).squad() == plugin.game().getPlayer(target).squad();
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void splash(PotionSplashEvent e) {
        Player source = e.getEntity().getShooter() instanceof Player player ? player : null;
        boolean harmful = e.getEntity().getEffects().stream().anyMatch(this::harmful);
        for (var target : java.util.List.copyOf(e.getAffectedEntities())) if (target instanceof Player player && blocksEffect(player, source, harmful)) e.setIntensity(target, 0);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void cloud(AreaEffectCloudApplyEvent e) {
        var cloud = e.getEntity();
        Player source = cloud.getSource() instanceof Player player ? player : null;
        boolean harmful = cloud.getCustomEffects().stream().anyMatch(this::harmful)
            || cloud.getBasePotionType() != null && cloud.getBasePotionType().getPotionEffects().stream().anyMatch(this::harmful);
        e.getAffectedEntities().removeIf(target -> target instanceof Player player && blocksEffect(player, source, harmful));
    }
    @EventHandler(ignoreCancelled = true) public void portal(PlayerPortalEvent e) { e.setCancelled(true); }
    @EventHandler public void scatter(ItemSpawnEvent e) {
        if (plugin.game().active() && e.getLocation().getWorld().equals(plugin.arenas().getArenaWorld())) {
            var r = java.util.concurrent.ThreadLocalRandom.current(); e.getEntity().setVelocity(new org.bukkit.util.Vector(r.nextDouble(-0.2, 0.2), 0.3, r.nextDouble(-0.2, 0.2)));
        }
    }
}
