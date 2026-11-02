package org.qiyuan.macesurvival.listener;

import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.Map;

public final class JoinQuitListener implements Listener {
    private final MaceSurvival plugin;
    public JoinQuitListener(MaceSurvival plugin) { this.plugin = plugin; }
    @EventHandler public void join(PlayerJoinEvent e) {
        plugin.game().addPlayer(e.getPlayer());
        e.joinMessage(plugin.messages().prefixed("join.broadcast", Map.of("player", e.getPlayer().getName())));
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        plugin.scoreboard().remove(e.getPlayer()); plugin.game().removePlayer(e.getPlayer().getUniqueId());
        e.quitMessage(plugin.messages().prefixed("quit.broadcast", Map.of("player", e.getPlayer().getName())));
    }
}
