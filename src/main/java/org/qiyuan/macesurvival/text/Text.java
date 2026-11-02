package org.qiyuan.macesurvival.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.Map;
import java.util.regex.Pattern;

/** One parser for all player-facing strings: legacy, MiniMessage and JSON components. */
public final class Text {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern HEX = Pattern.compile("(?i)[&§]x(?:[&§][0-9a-f]){6}");
    private static final Pattern SHORT_HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final Pattern CODE = Pattern.compile("(?i)[&§]([0-9a-fk-or])");
    private static final String CLEAR_DECORATIONS = "<bold:false><italic:false><underlined:false><strikethrough:false><obfuscated:false>";
    private static final Map<Character, String> TAGS = Map.ofEntries(
        Map.entry('0',"black"),Map.entry('1',"dark_blue"),Map.entry('2',"dark_green"),Map.entry('3',"dark_aqua"),
        Map.entry('4',"dark_red"),Map.entry('5',"dark_purple"),Map.entry('6',"gold"),Map.entry('7',"gray"),
        Map.entry('8',"dark_gray"),Map.entry('9',"blue"),Map.entry('a',"green"),Map.entry('b',"aqua"),
        Map.entry('c',"red"),Map.entry('d',"light_purple"),Map.entry('e',"yellow"),Map.entry('f',"white"),
        Map.entry('k',"obfuscated"),Map.entry('l',"bold"),Map.entry('m',"strikethrough"),Map.entry('n',"underlined"),Map.entry('o',"italic"),Map.entry('r',"reset"));
    public static Component parse(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try { return jsonText(GsonComponentSerializer.gson().deserialize(trimmed)); } catch (RuntimeException ignored) { /* Treat invalid JSON as configurable plain text. */ }
        }
        return inline(text);
    }
    private static Component inline(String text) {
        text = HEX.matcher(text).replaceAll(m -> CLEAR_DECORATIONS + "<#" + m.group().substring(2).replace("&", "").replace("§", "") + ">");
        text = SHORT_HEX.matcher(text).replaceAll(CLEAR_DECORATIONS + "<#$1>");
        if (!text.contains("<")) return LEGACY.deserialize(CODE.matcher(text).replaceAll("§$1"));
        text = CODE.matcher(text).replaceAll(m -> {
            char code = Character.toLowerCase(m.group(1).charAt(0));
            // Legacy colors/reset clear decorations even inside MiniMessage.
            // Explicit false tags preserve surrounding closing tags and events.
            if (code == 'r') return CLEAR_DECORATIONS + "<white>";
            return (code <= '9' || code >= 'a' && code <= 'f' ? CLEAR_DECORATIONS : "") + "<" + TAGS.get(code) + ">";
        });
        return MINI.deserialize(text);
    }
    private static Component jsonText(Component component) {
        var children = component.children().stream().map(Text::jsonText).toList();
        component = component.children(children);
        if (component instanceof TranslatableComponent translated) {
            component = translated.arguments(translated.arguments().stream().map(argument -> argument.value() instanceof Component text
                ? TranslationArgument.component(jsonText(text)) : argument).toList());
        }
        var hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component text)
            component = component.hoverEvent(HoverEvent.showText(jsonText(text)));
        if (component instanceof TextComponent text && (CODE.matcher(text.content()).find() || text.content().contains("<"))) {
            // JSON owns structural styles, click/hover events and child order;
            // parse only visible leaf text, never command/event payload strings.
            return Component.empty().style(component.style()).append(inline(text.content())).append(children);
        }
        return component;
    }
    public static String escape(String text) { return MINI.escapeTags(text == null ? "" : text); }
    public static String placeholders(String raw, org.bukkit.entity.Player player) {
        if (raw == null || player == null || !raw.contains("%") || !org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return raw;
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, raw);
    }
    public static Component parse(String raw, org.bukkit.entity.Player player) { return parse(placeholders(raw, player)); }
    private Text() { }
}
