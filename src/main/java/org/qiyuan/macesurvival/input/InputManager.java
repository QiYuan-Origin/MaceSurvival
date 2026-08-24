package org.qiyuan.macesurvival.input;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天框输入。菜单里需要打字的地方一律走这里：先关菜单，
 * 给一层黑暗并把移速压到 0，再用标题提示玩家输入，
 * 蹲下或者打 cancel 都能中止，超时会自己收摊。
 * 输入内容不进聊天，也不会漏给其他玩家。
 */
public final class InputManager {

    /** 打字期间的等待记录。取消令牌就是这条记录本身。 */
    private record Session(String type, BukkitTask ticker, int seconds) {
    }

    private final MaceSurvival plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public InputManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public boolean waiting(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** 开一次输入。重复调用会顶掉上一次，不会叠两层提示。 */
    public void request(Player player, String type) {
        release(player);
        int timeout = Math.max(5, plugin.configs().main().getInt("menu.input-timeout", 30));

        player.closeInventory();
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,
                timeout * 20 + 40, 0, false, false, false));
        player.setWalkSpeed(0f);
        plugin.messages().sendTitle(player, "input.prompt-title", "input.prompt-subtitle");

        BukkitTask ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            private int left = timeout;

            @Override
            public void run() {
                if (!player.isOnline() || !waiting(player)) {
                    release(player);
                    return;
                }
                if (left <= 0) {
                    release(player);
                    plugin.messages().send(player, "input.timeout");
                    return;
                }
                plugin.messages().actionBar(player, "input.waiting",
                        MessageManager.vars("seconds", String.valueOf(left)));
                left--;
            }
        }, 0L, 20L);

        sessions.put(player.getUniqueId(), new Session(type, ticker, timeout));
    }

    /** 玩家蹲下或者输入 cancel。 */
    public void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.ticker().cancel();
        clear(player);
        plugin.messages().send(player, "input.cancelled");
    }

    /**
     * 收下一行聊天内容。返回 true 表示这行是输入而不是聊天，
     * 调用方必须把聊天事件取消掉。
     */
    public boolean submit(Player player, String raw) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("cancel")) {
            // 主线程里做取消，聊天事件本身是异步的
            plugin.getServer().getScheduler().runTask(plugin, () -> cancel(player));
            return true;
        }
        sessions.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            clear(player);
            plugin.messages().send(player, "input.accepted");
            apply(player, session.type(), text);
        });
        return true;
    }

    /** 输入内容的落点。新的输入类型往这里加一条分支。 */
    private void apply(Player player, String type, String value) {
        switch (type) {
            case "team-invite" -> {
                player.performCommand("mace team invite " + value);
                new org.qiyuan.macesurvival.menu.TeamMenu(plugin, player).open();
            }
            default -> plugin.messages().send(player, "input.cancelled");
        }
    }

    /** 玩家退出或者游戏状态变了，直接丢掉记录，不发任何消息。 */
    public void release(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.ticker().cancel();
        }
        clear(player);
    }

    public void clearAll() {
        for (UUID uuid : Map.copyOf(sessions).keySet()) {
            Session session = sessions.remove(uuid);
            if (session != null) {
                session.ticker().cancel();
            }
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                clear(player);
            }
        }
    }

    /** 撤掉输入期间加的那些限制。调用前记录应该已经摘掉了。 */
    private void clear(Player player) {
        if (!player.isOnline()) {
            return;
        }
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.setWalkSpeed(0.2f);
        player.sendActionBar(net.kyori.adventure.text.Component.empty());
    }

    /** 聊天组件转纯文本，颜色和格式一律不要。 */
    public static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
