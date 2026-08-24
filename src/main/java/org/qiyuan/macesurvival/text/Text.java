package org.qiyuan.macesurvival.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 万能文本解析：同一个字符串里可以混用 &a、§a、&x&F&F&0&0&0&0、<gradient>、<shadow>、以及整段 JSON。
 * 解析顺序很关键：先把 legacy 的 & / § 折成 MiniMessage 标签，再交给 MiniMessage 统一渲染，
 * 否则 <gradient> 内部的 &a 会把渐变截断。
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&').hexCharacter('x').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character('\u00a7').hexCharacter('x').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** &x&F&F&0&0&0&0 / §x§F§F§0§0§0§0 这种 BungeeCord 展开式十六进制。 */
    private static final Pattern UNUSUAL_HEX =
            Pattern.compile("[&\u00a7]x((?:[&\u00a7][0-9A-Fa-f]){6})");

    private static final Pattern SIMPLE_HEX = Pattern.compile("[&\u00a7]#([0-9A-Fa-f]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&\u00a7]([0-9A-Fa-fK-Ok-oRr])");

    private Text() {
    }

    /** "#E8523F" 或 "E8523F" 转成颜色，非法值退回中性灰。 */
    public static TextColor color(String hex) {
        if (hex == null || hex.isBlank()) {
            return NamedTextColor.GRAY;
        }
        String value = hex.startsWith("#") ? hex : "#" + hex;
        TextColor parsed = TextColor.fromHexString(value);
        return parsed == null ? NamedTextColor.GRAY : parsed;
    }

    /** 单行解析。null 会得到空组件，避免菜单里出现 "消息未找到"。 */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String trimmed = raw.trim();
        if (isJson(trimmed)) {
            try {
                return GsonComponentSerializer.gson().deserialize(trimmed);
            } catch (RuntimeException ignored) {
                // 不是合法 JSON 就继续按普通文本走，不要把原始 JSON 抛给玩家
            }
        }
        Component component = MINI.deserialize(toMiniMessage(raw));
        // 物品名和 lore 默认不斜体
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /** 带变量替换的解析，占位符写作 {key}，找不到的 key 会被清空而不是原样显示。 */
    public static Component parse(String raw, Map<String, String> placeholders) {
        return parse(applyPlaceholders(raw, placeholders));
    }

    public static List<Component> parseList(List<String> raw) {
        List<Component> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw) {
            out.add(parse(line));
        }
        return out;
    }

    public static List<Component> parseList(List<String> raw, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw) {
            out.add(parse(line, placeholders));
        }
        return out;
    }

    /**
     * 变量替换。刻意做成"未知变量清空"而不是保留 {value}，
     * 因为残留的 {value} 会直接显示在菜单和聊天框里。
     */
    public static String applyPlaceholders(String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '{') {
                int end = raw.indexOf('}', i + 1);
                if (end > i) {
                    String key = raw.substring(i + 1, end);
                    if (isPlaceholderKey(key)) {
                        String value = placeholders == null ? null : placeholders.get(key);
                        sb.append(value == null ? "" : value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isPlaceholderKey(String key) {
        if (key.isEmpty() || key.length() > 48) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    /** 交给 PlaceholderAPI 之前用的纯文本形式。 */
    public static String plain(String raw) {
        return PlainTextComponentSerializer.plainText().serialize(parse(raw));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** 需要塞进只吃 legacy 字符串的旧 API 时用（例如某些计分板兼容层）。 */
    public static String toLegacySection(String raw) {
        return LEGACY_SECTION.serialize(parse(raw));
    }

    public static void send(Player player, String raw) {
        player.sendMessage(parse(raw));
    }

    private static boolean isJson(String trimmed) {
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * 把 legacy 颜色码折成 MiniMessage 标签。
     * 这样一行里既可以写 &a 又可以写 <shadow>，而且不会互相破坏。
     */
    private static String toMiniMessage(String raw) {
        String work = raw;

        Matcher unusual = UNUSUAL_HEX.matcher(work);
        StringBuilder sb = new StringBuilder();
        while (unusual.find()) {
            String digits = unusual.group(1).replaceAll("[&\u00a7]", "");
            unusual.appendReplacement(sb, Matcher.quoteReplacement("<#" + digits + ">"));
        }
        unusual.appendTail(sb);
        work = sb.toString();

        work = SIMPLE_HEX.matcher(work).replaceAll(result -> "<#" + result.group(1) + ">");

        Matcher legacy = LEGACY_CODE.matcher(work);
        StringBuilder out = new StringBuilder();
        while (legacy.find()) {
            String tag = legacyTag(Character.toLowerCase(legacy.group(1).charAt(0)));
            legacy.appendReplacement(out, Matcher.quoteReplacement(tag));
        }
        legacy.appendTail(out);
        return out.toString();
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }

    /** 只有确实需要 & 语义的旧字符串才走这里。 */
    public static Component legacyAmp(String raw) {
        return LEGACY_AMP.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }
}
