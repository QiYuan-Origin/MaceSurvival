package org.qiyuan.macesurvival.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts the v1 menu model once; the caller preserves the complete source as a backup. */
final class MenuConfigMigration {
    private MenuConfigMigration() { }

    static boolean isLegacy(YamlConfiguration source) {
        return source.getInt("config_version", 0) < 2 && (source.contains("rows") || source.contains("frame") || source.contains("open-sound"));
    }

    static YamlConfiguration migrate(String id, YamlConfiguration source, YamlConfiguration defaults, YamlConfiguration legacy) throws InvalidConfigurationException {
        YamlConfiguration result = new YamlConfiguration();
        result.loadFromString(defaults.saveToString());
        // Keep compatible owner edits, while replacing the previous release's English defaults.
        copyChanged(id, source, legacy, result, "title", "title");
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && source.contains(key) && !legacy.contains(key)
                    && !key.equals("config_version") && !key.equals("size")) result.set(key, translate(id, source.get(key)));
        }
        int rows = source.getInt("rows", 0);
        int size = source.getInt("size", defaults.getInt("size"));
        if (rows != legacy.getInt("rows") && rows >= 1 && rows <= 6) size = rows * 9;
        result.set("size", Math.max(defaults.getInt("size"), Math.min(54, size - size % 9)));

        Map<String, String> sections = new LinkedHashMap<>();
        switch (id) {
            case "main" -> {
                sections.put("items.brief", "items.info"); sections.put("items.squad", "items.team");
                for (String key : List.of("layout", "stats", "spectate", "close")) sections.put("items." + key, "items." + key);
                copyChanged(id, source, legacy, result, "items.close.back-material", "root-back.material");
                copyChanged(id, source, legacy, result, "items.close.back-name", "root-back.display_name");
                copyChanged(id, source, legacy, result, "items.close.back-lore", "root-back.lore");
            }
            case "team" -> {
                sections.put("items.header", "items.info");
                for (String key : List.of("create", "leave", "back")) sections.put("items." + key, "items." + key);
            }
            case "layout" -> {
                sections.put("items.header", "items.info"); sections.put("items.back", "items.back");
            }
            case "stats" -> {
                sections.put("items.header", "record-header"); sections.put("items.board", "selector");
                sections.put("items.entry", "entry"); sections.put("items.empty", "empty");
                sections.put("items.self", "items.self"); sections.put("items.back", "items.back");
                copyChanged(id, source, legacy, result, "items.entry-slots", "content-slots");
            }
            case "spectate" -> {
                sections.put("items.header", "items.header"); sections.put("items.target", "entry");
                sections.put("items.empty", "empty"); sections.put("items.back", "items.back");
                sections.put("items.previous-page", "previous"); sections.put("items.next-page", "next");
                copyChanged(id, source, legacy, result, "items.target-slots", "content-slots");
            }
            default -> throw new IllegalArgumentException("Unknown legacy menu: " + id);
        }
        sections.forEach((from, to) -> {
            for (String field : List.of("material", "slot", "lore", "name", "display_name")) {
                copyChanged(id, source, legacy, result, from + "." + field, to + "." + (field.equals("name") ? "display_name" : field));
            }
        });
        copyChanged(id, source, legacy, result, "frame.filler-material", "items.border.material");
        copyChanged(id, source, legacy, result, "frame.filler-name", "items.border.display_name");
        repairSlots(result, defaults, id);
        result.set("config_version", defaults.getInt("config_version", 2));
        return result;
    }

    private static void copyChanged(String id, YamlConfiguration source, YamlConfiguration legacy, YamlConfiguration target, String from, String to) {
        if (source.contains(from) && target.contains(to) && !Objects.equals(source.get(from), legacy.get(from))) target.set(to, translate(id, source.get(from)));
    }

    private static Object translate(String id, Object value) {
        if (value instanceof String text) {
            text = text.replace("{state}", "{phase}").replace("{played}", "{games}").replace("{current}", "{team_members}");
            text = text.replace("{max}", id.equals("team") ? "{squad_size}" : "{maximum}").replace("{squads}", "{alive_squads}");
            if (id.equals("spectate")) text = text.replace("{player}", "{target}").replace("{squad}", "{target_team}").replace("{kills}", "{target_kills}").replace("{colour}", "");
            return text;
        }
        if (value instanceof List<?> list) return list.stream().map(entry -> translate(id, entry)).toList();
        return value;
    }

    private static void repairSlots(YamlConfiguration result, YamlConfiguration defaults, String id) {
        int size = result.getInt("size");
        Set<Integer> occupied = new HashSet<>();
        if (id.equals("layout")) {
            List<Integer> edit = result.getIntegerList("edit-slots");
            if (edit.size() != 9 || edit.stream().distinct().count() != 9 || edit.stream().anyMatch(slot -> slot < 0 || slot >= size)) result.set("edit-slots", defaults.getIntegerList("edit-slots"));
            occupied.addAll(result.getIntegerList("edit-slots"));
            occupied.add(result.getInt("ready.slot", 31));
        }
        List<String> controls = new ArrayList<>();
        ConfigurationSection items = result.getConfigurationSection("items");
        if (items != null) for (String key : items.getKeys(false)) if (!key.equals("border") && items.isConfigurationSection(key)) controls.add("items." + key);
        for (String key : List.of("selector", "record-header", "previous", "next")) if (result.isConfigurationSection(key)) controls.add(key);
        for (String control : controls) {
            int slot = result.getInt(control + ".slot", -1);
            if (slot < 0 || slot >= size || occupied.contains(slot)) {
                slot = defaults.getInt(control + ".slot", -1);
                if (slot < 0 || slot >= size || occupied.contains(slot)) {
                    slot = 0;
                    while (slot < size && occupied.contains(slot)) slot++;
                }
                result.set(control + ".slot", slot);
            }
            occupied.add(slot);
        }
        if (result.contains("content-slots")) {
            List<Integer> content = result.getIntegerList("content-slots").stream().filter(slot -> slot >= 0 && slot < size && !occupied.contains(slot)).distinct().toList();
            if (content.isEmpty()) content = defaults.getIntegerList("content-slots").stream().filter(slot -> slot >= 0 && slot < size && !occupied.contains(slot)).distinct().toList();
            if (content.isEmpty()) {
                List<Integer> fallback = new ArrayList<>();
                for (int slot = 9; slot < size - 9; slot++) if (!occupied.contains(slot)) fallback.add(slot);
                content = List.copyOf(fallback);
            }
            result.set("content-slots", content);
            if (result.isConfigurationSection("empty") && !content.isEmpty()) {
                int empty = result.getInt("empty.slot", -1);
                if (empty < 0 || empty >= size || occupied.contains(empty)) result.set("empty.slot", content.getFirst());
            }
        }
    }
}
