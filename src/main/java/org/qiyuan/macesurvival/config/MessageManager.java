package org.qiyuan.macesurvival.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.qiyuan.macesurvival.text.Text;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息全部来自 messages.yml。找不到 key 时给出可读的兜底文本，
 * 绝不把 "消息未找到" 或裸 key 丢给玩家。
 */
public final class MessageManager {

    private final ConfigManager configs;

    public MessageManager(ConfigManager configs) {
        this.configs = configs;
    }

    private String prefixRaw() {
        return configs.main().getString("prefix",
                "<#C9A227><shadow:#404040:1>\u2726 MACE</shadow></#C9A227> <#9AA0A6><shadow:#404040:1>\u00bb</shadow></#9AA0A6> ");
    }

    /** 带前缀的普通消息。 */
    public Component get(String key, Map<String, String> placeholders) {
        return Text.parse(prefixRaw() + raw(key), placeholders);
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    /** 不带前缀，help 分支和标题类文本用这个。 */
    public Component plain(String key, Map<String, String> placeholders) {
        return Text.parse(raw(key), placeholders);
    }

    public Component plain(String key) {
        return plain(key, Map.of());
    }

    public List<Component> plainList(String key, Map<String, String> placeholders) {
        List<String> lines = configs.messages().getStringList(key);
        if (lines.isEmpty()) {
            return List.of(Text.parse("<#9AA0A6>- " + key));
        }
        return Text.parseList(lines, placeholders);
    }

    public List<Component> plainList(String key) {
        return plainList(key, Map.of());
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        target.sendMessage(get(key, placeholders));
    }

    public void send(CommandSender target, String key) {
        send(target, key, Map.of());
    }

    public void sendPlain(CommandSender target, String key, Map<String, String> placeholders) {
        target.sendMessage(plain(key, placeholders));
    }

    public void sendPlain(CommandSender target, String key) {
        sendPlain(target, key, Map.of());
    }

    /** 逐行发送不带前缀的文本块，help 分支就靠它。 */
    public void sendPlainList(CommandSender target, String key, Map<String, String> placeholders) {
        for (Component line : plainList(key, placeholders)) {
            target.sendMessage(line);
        }
    }

    public void sendPlainList(CommandSender target, String key) {
        sendPlainList(target, key, Map.of());
    }

    public void broadcast(String key, Map<String, String> placeholders) {
        Component message = get(key, placeholders);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    public void broadcast(String key) {
        broadcast(key, Map.of());
    }

    /** 标题与副标题都不带前缀，前缀塞进标题会很难看。 */
    public void sendTitle(Player player, String titleKey, String subtitleKey,
                          Map<String, String> placeholders) {
        Component title = plain(titleKey, placeholders);
        Component subtitle = plain(subtitleKey, placeholders);
        player.showTitle(Title.title(title, subtitle,
                Title.Times.times(
                        Duration.ofMillis(300),
                        Duration.ofMillis(2200),
                        Duration.ofMillis(600))));
    }

    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        sendTitle(player, titleKey, subtitleKey, Map.of());
    }

    /** 物品栏上方的提示条，跳伞和输入流程用它做持续提示。 */
    public void actionBar(Player player, String key, Map<String, String> placeholders) {
        player.sendActionBar(plain(key, placeholders));
    }

    public void actionBar(Player player, String key) {
        actionBar(player, key, Map.of());
    }

    /** 原始字符串。缺失时返回一句灰色的可读兜底，方便管理员发现漏配。 */
    public String raw(String key) {
        String value = configs.messages().getString(key);
        if (value == null || value.isEmpty()) {
            return "<#9AA0A6>" + key.replace('.', ' ').replace('_', ' ');
        }
        return value;
    }

    public String raw(String key, Map<String, String> placeholders) {
        return Text.applyPlaceholders(raw(key), placeholders);
    }

    /** 便捷构造变量表。 */
    public static Map<String, String> vars(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
