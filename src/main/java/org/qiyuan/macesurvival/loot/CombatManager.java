package org.qiyuan.macesurvival.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.game.GamePlayer;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 战斗结算。负责重锤命中扣次数、武器炸膛致死的归因、死亡掉落与死亡消息。
 * 状态转移一律交给 GameManager，这里只做战斗层面的判断。
 */
public final class CombatManager {

    private final MaceSurvival plugin;
    /** 正在因为武器炸膛而受伤的玩家，用来把死因归到 by-explosion。 */
    private final Set<UUID> shattering = new HashSet<>();

    public CombatManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /** LootManager 在制造爆炸前调用，爆炸伤害结算完由死亡流程清掉。 */
    public void markShatter(Player player) {
        shattering.add(player.getUniqueId());
        // 爆炸伤害是同 tick 结算的，下一 tick 就该忘掉，免得误判后续死亡
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> shattering.remove(player.getUniqueId()), 2L);
    }

    public boolean isShattering(Player player) {
        return shattering.contains(player.getUniqueId());
    }

    /**
     * 重锤命中之后扣一次耐久。真正的耐久扣减和爆炸都在 LootManager 里，
     * 这里只负责找到主手那把武器。
     */
    public void onMaceHit(Player attacker) {
        PlayerInventory inventory = attacker.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack weapon = inventory.getItem(slot);
        if (weapon == null || weapon.getType() != Material.MACE) {
            return;
        }
        plugin.loot().consume(attacker, slot, weapon);
    }

    /** 猛击落地音效。高空砸下来要有重量感，所以周围的人也听得见。 */
    public void playSmash(Player attacker) {
        Sounds.play(plugin, attacker, Sounds.maceSmash());
        for (Player nearby : attacker.getWorld().getPlayers()) {
            if (!nearby.equals(attacker)
                    && nearby.getLocation().distanceSquared(attacker.getLocation()) <= 400) {
                Sounds.play(plugin, nearby, Sounds.maceSmash());
            }
        }
    }

    // ─────────────── 死亡 ───────────────

    /**
     * 掉落装备。按配置分别控制盔甲和背包，掉在原地让凶手能捡。
     * 重锤连着 PDC 一起掉，捡到的人可以合并耐久。
     */
    public void dropGear(Player victim) {
        var config = plugin.configs().main();
        boolean dropGear = config.getBoolean("death.drop-gear", true);
        boolean dropInventory = config.getBoolean("death.drop-inventory", true);
        PlayerInventory inventory = victim.getInventory();
        Location at = victim.getLocation();

        if (dropGear) {
            for (ItemStack armour : inventory.getArmorContents()) {
                dropAt(at, armour);
            }
            inventory.setArmorContents(null);
            dropAt(at, inventory.getItemInOffHand());
            inventory.setItemInOffHand(null);
        }
        if (dropInventory) {
            for (ItemStack stack : inventory.getStorageContents()) {
                dropAt(at, stack);
            }
            inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
        }
    }

    private void dropAt(Location at, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        at.getWorld().dropItemNaturally(at, stack.clone());
    }

    /**
     * 死亡消息。按死因挑一句，全部保持 "谁 被 谁 干了啥" 的原版句式，
     * 这样和其它模式的死亡消息读起来是一套。
     */
    public void announceDeath(GamePlayer victim, GamePlayer killer, DeathCause cause) {
        Map<String, String> vars = MessageManager.vars(
                "victim", victim.name(),
                "killer", killer == null ? "the arena" : killer.name());
        plugin.game().broadcastAll(cause.messageKey(), vars);
    }

    /** 击杀时的雷。纯视觉效果，不带伤害。 */
    public void lightning(Player victim) {
        if (!plugin.configs().main().getBoolean("death.lightning-on-kill", true)) {
            return;
        }
        victim.getWorld().strikeLightningEffect(victim.getLocation());
    }

    public void clear() {
        shattering.clear();
    }

    /** 死因。决定播哪条死亡消息。 */
    public enum DeathCause {

        MACE("kill.by-mace"),
        SMASH("kill.by-smash"),
        EXPLOSION("kill.by-explosion"),
        FALL("kill.by-fall"),
        VOID("kill.by-void"),
        OTHER("kill.by-other");

        private final String messageKey;

        DeathCause(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }
}
