package org.qiyuan.macesurvival.loadout;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.qiyuan.macesurvival.MaceSurvival;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores slot identities, never real ItemStacks. Two maces keep separate identities. */
public final class LayoutStore {
    public static final List<String> KEYS = List.of("sword", "axe", "mace_1", "mace_2", "food", "blocks", "wind_charge");
    private static final List<String> SLOT_KEYS = List.of("sword-slot", "axe-slot", "mace-slot-1", "mace-slot-2", "food-slot", "block-slot", "wind-charge-slot");
    private static final List<Integer> FALLBACK = List.of(0, 1, 7, 8, 2, 3, 4);
    private final MaceSurvival plugin;
    private final File file;
    private final Map<UUID, List<String>> layouts = new HashMap<>();
    private List<String> defaults;

    public LayoutStore(MaceSurvival plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "layouts.yml");
        reload();
    }

    public void reload() {
        defaults = emptyLayout();
        for (int i = 0; i < KEYS.size(); i++) {
            int slot = plugin.configs().main().getInt("loadout." + SLOT_KEYS.get(i), FALLBACK.get(i));
            if (slot < 0 || slot > 8 || !defaults.get(slot).isEmpty()) slot = defaults.indexOf("");
            defaults.set(slot, KEYS.get(i));
        }
        layouts.clear();
        var data = YamlConfiguration.loadConfiguration(file);
        var section = data.getConfigurationSection("players");
        if (section == null) return;
        for (String raw : section.getKeys(false)) {
            try {
                List<String> order = data.getStringList("players." + raw);
                if (valid(order)) layouts.put(UUID.fromString(raw), List.copyOf(order));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignored invalid player layout UUID: " + raw);
            }
        }
    }

    public List<String> playerSlots(UUID uuid) { return new ArrayList<>(layouts.getOrDefault(uuid, defaults)); }
    public int slot(UUID uuid, String key, int fallback) {
        int slot = playerSlots(uuid).indexOf(key);
        return slot < 0 ? fallback : slot;
    }
    public boolean save(UUID uuid, List<String> order) {
        if (!valid(order)) return false;
        List<String> previous = layouts.put(uuid, List.copyOf(order));
        var data = new YamlConfiguration();
        layouts.forEach((id, value) -> data.set("players." + id, value));
        try {
            Files.createDirectories(file.toPath().getParent());
            var temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
            data.save(temporary.toFile());
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            if (previous == null) layouts.remove(uuid); else layouts.put(uuid, previous);
            plugin.getLogger().severe("Could not save layout for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }
    public List<String> defaults() { return new ArrayList<>(defaults); }
    public static List<String> emptyLayout() { return new ArrayList<>(List.of("", "", "", "", "", "", "", "", "")); }
    public static boolean valid(List<String> order) {
        if (order == null || order.size() != 9) return false;
        List<String> actual = order.stream().filter(s -> s != null && !s.isEmpty()).toList();
        return actual.size() == KEYS.size() && new HashSet<>(actual).equals(new HashSet<>(KEYS));
    }
    public int getSlot(Material material) { return getSlot(material.name()); }
    public int getSlot(String key) {
        String converted = switch (key.toUpperCase(java.util.Locale.ROOT)) {
            case "NETHERITE_SWORD" -> "sword";
            case "NETHERITE_AXE" -> "axe";
            case "MACE_1" -> "mace_1";
            case "MACE_2" -> "mace_2";
            default -> key.toLowerCase(java.util.Locale.ROOT);
        };
        return defaults.indexOf(converted);
    }
    public Map<String, Integer> getAll() {
        Map<String, Integer> result = new HashMap<>();
        for (String key : KEYS) result.put(key, defaults.indexOf(key));
        return result;
    }
}
