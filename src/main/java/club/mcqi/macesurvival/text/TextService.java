package club.mcqi.macesurvival.text;

import club.mcqi.macesurvival.config.ConfigFiles;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Renders JSON, MiniMessage and legacy color syntax through one Adventure pipeline. */
public final class TextService {
    private static final String DEFAULT_PREFIX =
        "<font:minecraft:uniform><white><shadow:#404040:1>MACE</shadow></white>"
            + "<color:#ff5555><shadow:#401818:1>.VIP</shadow></color> <dark_gray>›</dark_gray> </font>";
    private static final Map<Character, String> LEGACY_TAGS = legacyTags();

    private final JavaPlugin plugin;
    private final ConfigFiles configFiles;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final GsonComponentSerializer json = GsonComponentSerializer.gson();
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();

    public TextService(JavaPlugin plugin, ConfigFiles configFiles) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configFiles = Objects.requireNonNull(configFiles, "configFiles");
    }

    public Component parse(String input) {
        return parse(null, input, Map.of());
    }

    public Component parse(String input, Map<String, ?> placeholders) {
        return parse(null, input, placeholders);
    }

    public Component parse(Player player, String input) {
        return parse(player, input, Map.of());
    }

    /** Replaces {key}, %key% and &lt;key&gt;, applies PAPI, then parses the chosen text format. */
    public Component parse(Player player, String input, Map<String, ?> placeholders) {
        String source = Objects.requireNonNullElse(input, "");
        boolean jsonTemplate = looksLikeJson(source.trim());
        String rendered = replacePlaceholders(source, placeholders, jsonTemplate);
        boolean usePlaceholderApi = placeholderApiEnabled(player);
        if (!jsonTemplate && usePlaceholderApi) {
            rendered = PlaceholderAPI.setPlaceholders(player, rendered);
        }

        String trimmed = rendered.trim();
        if (jsonTemplate && looksLikeJson(trimmed)) {
            try {
                return withoutItalics(expandJsonMarkup(json.deserialize(trimmed), player, usePlaceholderApi));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Invalid JSON text; falling back to MiniMessage", exception);
            }
        }
        return withoutItalics(miniMessage.deserialize(normalizeLegacy(rendered)));
    }

    public Component message(String path) {
        return message(null, path, Map.of());
    }

    public Component message(Player player, String path, Map<String, ?> placeholders) {
        return messageOr(player, path, "", placeholders);
    }

    /** Inserts trusted Adventure components after parsing the configured template. */
    public Component messageWithComponents(
        Player player,
        String path,
        Map<String, ?> placeholders,
        Map<String, Component> componentPlaceholders
    ) {
        return messageOrWithComponents(player, path, "", placeholders, componentPlaceholders);
    }

    public Component messageOr(Player player, String path, String fallback, Map<String, ?> placeholders) {
        String template = messageTemplate(path).orElseGet(() -> {
            reportMissing(path);
            return fallback;
        });
        return parse(player, template, placeholders);
    }

    public Component messageOrWithComponents(
        Player player,
        String path,
        String fallback,
        Map<String, ?> placeholders,
        Map<String, Component> componentPlaceholders
    ) {
        String template = messageTemplate(path).orElseGet(() -> {
            reportMissing(path);
            return fallback;
        });
        return parseWithComponents(player, template, placeholders, componentPlaceholders);
    }

    public List<Component> messageLines(Player player, String path, Map<String, ?> placeholders) {
        FileConfiguration messages = messages();
        if (messages == null) {
            reportMissing(path);
            return List.of();
        }

        if (messages.isList(path)) {
            List<Component> output = new ArrayList<>();
            for (String line : messages.getStringList(path)) {
                output.add(parse(player, line, placeholders));
            }
            return List.copyOf(output);
        }

        String scalar = messages.getString(path);
        if (scalar == null) {
            reportMissing(path);
            return List.of();
        }
        return List.of(parse(player, scalar, placeholders));
    }

    /** Sends a config message without a prefix, intended for help and native death messages. */
    public void sendRaw(Audience audience, String path, Map<String, ?> placeholders) {
        Player player = audience instanceof Player target ? target : null;
        audience.sendMessage(message(player, path, placeholders));
    }

    /** Sends one message with the configurable mode prefix. */
    public void sendPrefixed(Audience audience, String path, Map<String, ?> placeholders) {
        Player player = audience instanceof Player target ? target : null;
        Component prefix = prefix(player, placeholders);
        Component body = message(player, path, placeholders);
        audience.sendMessage(prefix.append(body));
    }

    public Component prefix(Player player) {
        return prefix(player, Map.of());
    }

    private Component prefix(Player player, Map<String, ?> placeholders) {
        String configured = configFiles.find("config.yml")
            .map(configuration -> configuration.getString("text.prefix"))
            .orElse(null);
        if (configured != null) {
            return parse(player, configured, placeholders);
        }
        return messageOr(player, "prefix", DEFAULT_PREFIX, placeholders);
    }

    private java.util.Optional<String> messageTemplate(String path) {
        FileConfiguration messages = messages();
        return messages == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(messages.getString(path));
    }

    private FileConfiguration messages() {
        return configFiles.find("messages.yml").orElse(null);
    }

    private void reportMissing(String path) {
        if (reportedMissingKeys.add(path)) {
            plugin.getLogger().warning("Missing messages.yml key: " + path);
        }
    }

    private Component parseWithComponents(
        Player player,
        String template,
        Map<String, ?> placeholders,
        Map<String, Component> componentPlaceholders
    ) {
        Objects.requireNonNull(componentPlaceholders, "componentPlaceholders");
        if (componentPlaceholders.isEmpty()) {
            return parse(player, template, placeholders);
        }

        Map<String, Object> renderedPlaceholders = new LinkedHashMap<>(placeholders);
        Map<String, Component> replacements = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Component> entry : componentPlaceholders.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "component placeholder key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Component placeholder keys cannot be blank");
            }
            String marker = "\uF000macesurvival:" + index++ + "\uF001";
            renderedPlaceholders.put(key, marker);
            replacements.put(marker, Objects.requireNonNull(entry.getValue(), "component placeholder value"));
        }

        Component output = parse(player, template, renderedPlaceholders);
        for (Map.Entry<String, Component> replacement : replacements.entrySet()) {
            output = output.replaceText(TextReplacementConfig.builder()
                .matchLiteral(replacement.getKey())
                .replacement(replacement.getValue())
                .build());
        }
        return withoutItalics(output);
    }

    private Component expandJsonMarkup(Component component, Player player, boolean usePlaceholderApi) {
        List<Component> originalChildren = component.children();
        Component body = component.children(List.of());
        if (body instanceof TextComponent text && !text.content().isEmpty()) {
            String content = usePlaceholderApi ? PlaceholderAPI.setPlaceholders(player, text.content()) : text.content();
            Component parsedContent = miniMessage.deserialize(normalizeLegacy(content));
            body = Component.empty().style(text.style()).append(parsedContent);
        }
        List<Component> expandedChildren = originalChildren.stream()
            .map(child -> expandJsonMarkup(child, player, usePlaceholderApi))
            .toList();
        List<Component> combinedChildren = new ArrayList<>(body.children());
        combinedChildren.addAll(expandedChildren);
        return body.children(combinedChildren);
    }

    private Component withoutItalics(Component component) {
        List<Component> children = component.children().stream().map(this::withoutItalics).toList();
        return component.children(children).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private boolean placeholderApiEnabled(Player player) {
        return player != null && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    private static String replacePlaceholders(
        String source,
        Map<String, ?> placeholders,
        boolean escapeForJson
    ) {
        Objects.requireNonNull(placeholders, "placeholders");
        if (placeholders.isEmpty()) {
            return source;
        }

        List<String> orderedKeys = placeholders.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted((left, right) -> Integer.compare(right.length(), left.length()))
            .toList();
        String result = source;
        for (String key : orderedKeys) {
            String value = Objects.toString(placeholders.get(key), "");
            if (escapeForJson) {
                value = escapeJsonText(value);
            }
            result = result.replace("{" + key + "}", value)
                .replace("%" + key + "%", value)
                .replace("<" + key + ">", value);
        }
        return result;
    }

    private static String escapeJsonText(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u")
                            .append(Character.forDigit((character >>> 12) & 0xF, 16))
                            .append(Character.forDigit((character >>> 8) & 0xF, 16))
                            .append(Character.forDigit((character >>> 4) & 0xF, 16))
                            .append(Character.forDigit(character & 0xF, 16));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static boolean looksLikeJson(String input) {
        return (input.startsWith("{") && input.endsWith("}"))
            || (input.startsWith("[") && input.endsWith("]"));
    }

    /** Converts every supported legacy code into equivalent MiniMessage before parsing. */
    static String normalizeLegacy(String input) {
        StringBuilder output = new StringBuilder(input.length() + 24);
        for (int index = 0; index < input.length(); index++) {
            char marker = input.charAt(index);
            if ((marker != '&' && marker != '\u00a7') || index + 1 >= input.length()) {
                output.append(marker);
                continue;
            }

            char code = Character.toLowerCase(input.charAt(index + 1));
            if (code == '#' && index + 7 < input.length()) {
                String hex = input.substring(index + 2, index + 8);
                if (isHex(hex)) {
                    output.append("<reset><#").append(hex).append('>');
                    index += 7;
                    continue;
                }
            }

            if (code == 'x') {
                String hex = readLegacyHex(input, index + 2);
                if (hex != null) {
                    output.append("<reset><#").append(hex).append('>');
                    index += 13;
                    continue;
                }
            }

            String tag = LEGACY_TAGS.get(code);
            if (tag != null) {
                output.append(tag);
                index++;
            } else if (code == 'o') {
                index++;
            } else {
                output.append(marker);
            }
        }
        return output.toString();
    }

    private static String readLegacyHex(String input, int start) {
        if (start + 11 >= input.length()) {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int offset = 0; offset < 12; offset += 2) {
            char marker = input.charAt(start + offset);
            char digit = input.charAt(start + offset + 1);
            if ((marker != '&' && marker != '\u00a7') || Character.digit(digit, 16) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }

    private static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static Map<Character, String> legacyTags() {
        Map<Character, String> tags = new LinkedHashMap<>();
        tags.put('0', "<reset><black>");
        tags.put('1', "<reset><dark_blue>");
        tags.put('2', "<reset><dark_green>");
        tags.put('3', "<reset><dark_aqua>");
        tags.put('4', "<reset><dark_red>");
        tags.put('5', "<reset><dark_purple>");
        tags.put('6', "<reset><gold>");
        tags.put('7', "<reset><gray>");
        tags.put('8', "<reset><dark_gray>");
        tags.put('9', "<reset><blue>");
        tags.put('a', "<reset><green>");
        tags.put('b', "<reset><aqua>");
        tags.put('c', "<reset><red>");
        tags.put('d', "<reset><light_purple>");
        tags.put('e', "<reset><yellow>");
        tags.put('f', "<reset><white>");
        tags.put('k', "<obfuscated>");
        tags.put('l', "<bold>");
        tags.put('m', "<strikethrough>");
        tags.put('n', "<underlined>");
        tags.put('r', "<reset>");
        return Map.copyOf(tags);
    }
}
