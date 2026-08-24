package org.qiyuan.macesurvival.listener;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.game.GamePlayer;
import org.qiyuan.macesurvival.loot.CombatManager.DeathCause;

/** 战斗与死亡。宽容期拦伤害，重锤命中扣次数，死亡走 GameManager 的淘汰流程。 */
public final class CombatListener implements Listener {

    private final MaceSurvival plugin;

    public CombatListener(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /** 宽容期与非对局状态一律不吃伤害，落地前被人白打是最劝退的体验。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        GamePlayer gp = plugin.game().of(victim);
        if (gp == null) {
            return;
        }
        if (!gp.role().isPlaying()) {
            event.setCancelled(true);
            return;
        }
        if (plugin.game().graceActive() && isPvp(event)) {
            event.setCancelled(true);
        }
    }

    private boolean isPvp(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Player;
    }

    /** 玩家互殴。记下凶手，重锤命中额外扣一次耐久。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        GamePlayer victimGp = plugin.game().of(victim);
        GamePlayer attackerGp = plugin.game().of(attacker);
        if (victimGp == null || attackerGp == null || !attackerGp.role().isPlaying()) {
            return;
        }
        victimGp.lastDamager(attacker.getName());

        if (attacker.getInventory().getItemInMainHand().getType() != Material.MACE) {
            return;
        }
        // 从高处砸下来才算猛击，落地一击的音效和普通挥砍要分开
        if (attacker.getFallDistance() > 1.5f) {
            plugin.combat().playSmash(attacker);
        }
        plugin.combat().onMaceHit(attacker);
    }

    /** 死亡。掉落交给 CombatManager，淘汰判定交给 GameManager。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        GamePlayer gp = plugin.game().of(victim);
        if (gp == null || !gp.role().isPlaying()) {
            return;
        }
        // 原版死亡消息一律吃掉，改用自己那套统一句式
        event.deathMessage(null);
        event.setKeepInventory(false);
        event.getDrops().clear();

        plugin.combat().dropGear(victim);
        plugin.combat().lightning(victim);

        GamePlayer killer = resolveKiller(victim, gp);
        plugin.combat().announceDeath(gp, killer, causeOf(victim, event));
        plugin.game().onDeath(gp, killer);
    }

    private GamePlayer resolveKiller(Player victim, GamePlayer gp) {
        Player direct = victim.getKiller();
        if (direct != null) {
            return plugin.game().of(direct);
        }
        String last = gp.lastDamager();
        if (last == null) {
            return null;
        }
        Player fallback = plugin.getServer().getPlayerExact(last);
        return fallback == null ? null : plugin.game().of(fallback);
    }

    /** 死因归类。武器炸膛优先，因为它本质上还是玩家自己的操作。 */
    private DeathCause causeOf(Player victim, PlayerDeathEvent event) {
        if (plugin.combat().isShattering(victim)) {
            return DeathCause.EXPLOSION;
        }
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last == null) {
            return DeathCause.OTHER;
        }
        return switch (last.getCause()) {
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> victim.getKiller() != null
                    && victim.getKiller().getInventory().getItemInMainHand().getType() == Material.MACE
                    ? DeathCause.MACE
                    : DeathCause.OTHER;
            case FALL, FLY_INTO_WALL -> DeathCause.FALL;
            case VOID -> DeathCause.VOID;
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> DeathCause.EXPLOSION;
            default -> DeathCause.OTHER;
        };
    }
}
