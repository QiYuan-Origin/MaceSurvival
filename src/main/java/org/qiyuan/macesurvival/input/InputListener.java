package org.qiyuan.macesurvival.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.qiyuan.macesurvival.MaceSurvival;

/**
 * 输入流程的事件入口。等待输入时的聊天会被吃掉，不会广播出去；
 * 蹲下当作确认取消；掉线直接把限制清掉，免得下次进来还走不动。
 */
public final class InputListener implements Listener {

    private final MaceSurvival plugin;

    public InputListener(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.input().waiting(player)) {
            return;
        }
        if (plugin.input().submit(player, InputManager.plain(event.message()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        if (plugin.input().waiting(event.getPlayer())) {
            plugin.input().cancel(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.input().release(event.getPlayer());
    }
}
