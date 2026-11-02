package org.qiyuan.macesurvival.listener;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.Map;

/** Damage, elimination and loot use the same participant identity as the game state machine. */
public final class CombatListener implements Listener {
    private final MaceSurvival plugin;
    public CombatListener(MaceSurvival plugin) { this.plugin = plugin; }
    private Player attacker(EntityDamageByEntityEvent e) {
        if (e.getDamageSource().getCausingEntity() instanceof Player p) return p;
        if (e.getDamager() instanceof Player p) return p;
        if (e.getDamager() instanceof Projectile p && p.getShooter() instanceof Player shooter) return shooter;
        return null;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (plugin.game().protectedPlayer(victim)) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent by) {
            Player attacker = attacker(by);
            if (attacker == null) return;
            if (plugin.game().protectedPlayer(attacker)) { e.setCancelled(true); return; }
            var a = plugin.game().getPlayer(attacker); var v = plugin.game().getPlayer(victim);
            if (!attacker.equals(victim) && plugin.configs().main().getBoolean("game.friendly-fire-disabled", true) && a.squad() != null && a.squad() == v.squad()) { e.setCancelled(true); return; }
            e.setDamage(e.getDamage() * plugin.enchants().damageMultiplier(attacker));
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordDamage(EntityDamageByEntityEvent e) {
        Player attacker = attacker(e);
        if (attacker != null && e.getEntity() instanceof Player victim && !attacker.equals(victim) && plugin.game().participant(attacker) && plugin.game().participant(victim)) plugin.stats().addDamage(attacker.getUniqueId(), Math.min(victim.getHealth(), e.getFinalDamage()));
    }
    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        if (!plugin.game().active() || !plugin.game().participant(victim)) { e.deathMessage(null); e.getDrops().clear(); e.setDroppedExp(0); return; }
        Player killer = victim.getKiller();
        var gp = plugin.game().getPlayer(victim); gp.addDeath(); plugin.stats().addDeath(victim.getUniqueId());
        e.setKeepInventory(false); e.setKeepLevel(false); e.setDroppedExp(0);
        if (killer != null && !killer.equals(victim) && plugin.game().participant(killer) && (gp.squad() == null || gp.squad() != plugin.game().getPlayer(killer).squad())) {
            var winning = plugin.game().getPlayer(killer); winning.addKill();
            plugin.stats().addKill(killer.getUniqueId(), winning.roundKills());
            plugin.loot().onKill(killer); plugin.enchants().onKill(killer);
            plugin.economy().reward(killer.getUniqueId(), plugin.configs().main().getDouble("economy.rewards.kill", 10));
            e.deathMessage(plugin.messages().prefixed("death.killed", Map.of("victim", victim.getName(), "killer", killer.getName())));
        } else e.deathMessage(plugin.messages().prefixed("death.other", Map.of("victim", victim.getName())));
        // Vanilla creates one real item entity for every equipment drop; add scatter, never duplicate it.
        plugin.game().eliminate(victim); plugin.stats().saveAsync();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (victim.isOnline() && victim.isDead()) victim.spigot().respawn(); }, 2L);
    }
    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        boolean spectating = plugin.game().getPlayer(e.getPlayer()).isSpectator() && !plugin.game().lobbyOpen();
        var center = plugin.arenas().getArenaCenter();
        e.setRespawnLocation(spectating && center != null ? center.clone().add(0, 20, 0) : plugin.game().getLobbySpawn());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!e.getPlayer().isOnline()) return;
            if (spectating && !plugin.game().lobbyOpen()) plugin.game().applySpectator(e.getPlayer());
            else { e.getPlayer().setGameMode(GameMode.ADVENTURE); plugin.loadouts().giveLobbyItems(e.getPlayer()); }
        });
    }
}
