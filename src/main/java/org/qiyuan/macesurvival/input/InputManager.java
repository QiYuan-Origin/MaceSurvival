package org.qiyuan.macesurvival.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** All menu chat input shares one cancellable session; input is never broadcast as chat. */
public final class InputManager implements Listener {
    private record Session(String type, float speed, PotionEffect darkness, BukkitTask timeout) { }
    private final MaceSurvival plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    public InputManager(MaceSurvival plugin) { this.plugin = plugin; }
    public boolean waiting(Player p) { return sessions.containsKey(p.getUniqueId()); }
    public void request(Player p, String type) {
        if (!plugin.game().lobbyOpen()) return;
        release(p); p.closeInventory();
        int seconds = Math.max(5, plugin.configs().main().getInt("menu.input-timeout", 30));
        float speed = p.getWalkSpeed(); PotionEffect previous = p.getPotionEffect(PotionEffectType.DARKNESS);
        p.setWalkSpeed(0); p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, seconds * 20 + 40, 0, false, false, false));
        BukkitTask timer = Bukkit.getScheduler().runTaskLater(plugin, () -> { if (waiting(p)) { release(p); plugin.messages().send(p, "input.timeout"); reopen(p); } }, seconds * 20L);
        sessions.put(p.getUniqueId(), new Session(type, speed, previous, timer));
        plugin.messages().title(p, "input.prompt-title", "input.prompt-subtitle", Map.of());
        plugin.messages().send(p, "squad.create-prompt");
    }
    public void release(Player p) {
        Session s = sessions.remove(p.getUniqueId()); if (s == null) return; s.timeout().cancel();
        p.setWalkSpeed(s.speed()); p.removePotionEffect(PotionEffectType.DARKNESS);
        if (s.darkness() != null) p.addPotionEffect(s.darkness());
        p.clearTitle();
    }
    public void clearAll() { for (UUID id : List.copyOf(sessions.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) release(p); else { Session s = sessions.remove(id); if (s != null) s.timeout().cancel(); } } }
    private void reopen(Player p) { if (p.isOnline() && plugin.game().lobbyOpen()) plugin.menus().open(p, "team"); }
    private void submit(Player p, String raw, Session expected) {
        if (sessions.get(p.getUniqueId()) != expected || !p.isOnline()) return;
        release(p);
        if (raw.isBlank() || raw.equalsIgnoreCase("cancel")) plugin.messages().send(p, "input.cancelled");
        else if ("team-create".equals(expected.type())) plugin.game().createTeam(p, raw);
        reopen(p);
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent e) {
        var s = sessions.get(e.getPlayer().getUniqueId()); if (s == null) return;
        e.setCancelled(true); String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> submit(e.getPlayer(), text, s));
    }
    @EventHandler public void sneak(PlayerToggleSneakEvent e) { if (e.isSneaking() && waiting(e.getPlayer())) { var s = sessions.get(e.getPlayer().getUniqueId()); submit(e.getPlayer(), "cancel", s); } }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent e) {
        if (!waiting(e.getPlayer()) || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 0) { var at = e.getFrom(); at.setYaw(e.getTo().getYaw()); at.setPitch(e.getTo().getPitch()); e.setTo(at); }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { release(e.getPlayer()); }
}
