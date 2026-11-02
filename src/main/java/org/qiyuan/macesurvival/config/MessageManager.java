package org.qiyuan.macesurvival.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Resource defaults fill missing keys without replacing a server owner's customized messages. */
public final class MessageManager {
    private final MaceSurvival plugin;
    private YamlConfiguration config;
    private String prefix;
    private final Set<String> warned = new HashSet<>();
    public MessageManager(MaceSurvival plugin) { this.plugin = plugin; reload(); }
    public void reload() {
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        try (var stream = plugin.getResource("messages.yml")) {
            if (stream != null) config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (IOException e) { plugin.getLogger().severe("Cannot load bundled messages: " + e.getMessage()); }
        prefix = config.getString("prefix", ""); warned.clear();
    }
    public Component get(String key) { return get(key, Map.of()); }
    public Component get(String key, Map<String, String> values) {
        return get(key, values, null);
    }
    private Component get(String key, Map<String, String> values, Player player) {
        String raw = config.getString(key);
        if (raw == null) {
            if (warned.add(key)) plugin.getLogger().severe("Missing bundled message key: " + key);
            return Component.empty();
        }
        if (values != null) for (var value : values.entrySet()) raw = raw.replace("{" + value.getKey() + "}", value.getValue());
        return Text.parse(raw, player);
    }
    /** Chat output is intentionally prefix-free; menu titles and scoreboard own their presentation. */
    public Component prefixed(String key) { return get(key); }
    public Component prefixed(String key, Map<String, String> values) { return get(key, values); }
    public String getPrefix() { return prefix; }
    public String plain(String key) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(get(key)); }
    public void send(CommandSender target, String key) { send(target, key, Map.of()); }
    public void send(CommandSender target, String key, Map<String, String> values) {
        Player player = target instanceof Player p ? p : null;
        Component body = get(key, values, player);
        target.sendMessage(body);
    }
    public void title(Player player, String title, String subtitle, Map<String, String> values) {
        player.showTitle(Title.title(get(title, values, player), get(subtitle, values, player), Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
    }
    public void sendTitle(Player p, String title, String subtitle) { title(p, title, subtitle, Map.of()); }
    public void actionBar(Player p, String key, Map<String, String> vars) { p.sendActionBar(get(key, vars, p)); }
    public static Map<String, String> vars(String... values) { Map<String, String> map = new HashMap<>(); for (int i=0;i+1<values.length;i+=2) map.put(values[i],values[i+1]); return map; }
}
