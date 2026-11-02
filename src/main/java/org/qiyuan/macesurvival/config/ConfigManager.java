package org.qiyuan.macesurvival.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.qiyuan.macesurvival.MaceSurvival;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates the whole configuration before replacing files or the active configuration. */
public final class ConfigManager {
    private static final String[] FILES = {"config.yml", "messages.yml", "loot.yml", "scoreboard.yml", "menu/main.yml", "menu/team.yml", "menu/layout.yml", "menu/stats.yml", "menu/spectate.yml"};
    private final MaceSurvival plugin;
    private FileConfiguration config, loot;
    private record Prepared(YamlConfiguration current, YamlConfiguration defaults, String before, boolean exists) { }
    public ConfigManager(MaceSurvival plugin) { this.plugin = plugin; reload(); }
    public void reload() {
        Map<String, Prepared> prepared = new LinkedHashMap<>();
        String reading = "config.yml";
        try {
            // Parse everything first: one malformed menu must not partially upgrade the other files.
            for (String path : FILES) {
                reading = path;
                File file = new File(plugin.getDataFolder(), path);
                YamlConfiguration defaults = resource(path);
                YamlConfiguration current = new YamlConfiguration();
                if (file.exists()) current.load(file);
                prepared.put(path, new Prepared(current, defaults, current.saveToString(), file.exists()));
            }
            YamlConfiguration main = prepared.get("config.yml").current();
            migrateMain(main);
            YamlConfiguration messages = prepared.get("messages.yml").current();
            replaceLegacyDefaults(messages, prepared.get("messages.yml").defaults(), resource("migrations/v1/messages.yml"));
            YamlConfiguration oldMain = resource("migrations/v1/config.yml");
            if (!messages.contains("prefix") && main.contains("prefix")
                    && !java.util.Objects.equals(main.get("prefix"), oldMain.get("prefix"))) {
                messages.set("prefix", main.get("prefix"));
            }
            YamlConfiguration scoreboard = prepared.get("scoreboard.yml").current();
            for (String key : new String[]{"enabled", "update-ticks", "title", "server-ip"}) {
                String target = key.equals("server-ip") ? "server-address" : key;
                String from = "scoreboard." + key;
                if (!scoreboard.contains(target) && main.contains(from) && !java.util.Objects.equals(main.get(from), oldMain.get(from))) scoreboard.set(target, main.get(from));
            }
            for (var entry : prepared.entrySet()) {
                reading = entry.getKey();
                Prepared value = entry.getValue();
                if (reading.startsWith("menu/") && MenuConfigMigration.isLegacy(value.current())) {
                    String id = reading.substring(5, reading.length() - 4);
                    YamlConfiguration migrated = MenuConfigMigration.migrate(id, value.current(), value.defaults(), resource("migrations/v1/" + reading));
                    value = new Prepared(migrated, value.defaults(), value.before(), value.exists());
                    entry.setValue(value);
                }
                if (reading.equals("menu/main.yml") || reading.equals("menu/team.yml") || reading.equals("scoreboard.yml")) {
                    replaceLegacyDefaults(value.current(), value.defaults(), resource("migrations/v2/" + reading));
                }
                mergeMissing(value.current(), value.defaults());
            }
            for (var entry : prepared.entrySet()) {
                reading = entry.getKey();
                Prepared value = entry.getValue();
                if (value.exists() && value.before().equals(value.current().saveToString())) continue;
                File file = new File(plugin.getDataFolder(), reading);
                Files.createDirectories(file.toPath().getParent());
                if (value.exists() && reading.startsWith("menu/") && MenuConfigMigration.isLegacy(parse(value.before()))) {
                    var backup = file.toPath().resolveSibling(file.getName() + ".v1.bak");
                    if (!Files.exists(backup)) Files.copy(file.toPath(), backup);
                }
                saveAtomic(value.current(), file);
            }
            config = prepared.get("config.yml").current();
            loot = prepared.get("loot.yml").current();
            plugin.reloadConfig();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read or restore configuration " + reading + ": " + exception.getMessage(), exception);
        }
    }

    private static YamlConfiguration parse(String yaml) throws org.bukkit.configuration.InvalidConfigurationException {
        YamlConfiguration result = new YamlConfiguration(); result.loadFromString(yaml); return result;
    }

    private YamlConfiguration resource(String path) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        try (var stream = plugin.getResource(path)) {
            if (stream == null) throw new IOException("Bundled resource missing: " + path);
            YamlConfiguration result = new YamlConfiguration();
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return result;
        }
    }

    private static void migrateMain(YamlConfiguration current) {
        String[][] aliases = {
                {"squad.size", "game.squad-size"}, {"arena.world", "arena.world-name"},
                {"arena.min-structures", "arena.structures"}, {"arena.max-structures", "arena.structures"},
                {"chest.one-star-count", "arena.chests.one-star"}, {"chest.two-star-count", "arena.chests.two-star"},
                {"chest.three-star-count", "arena.chests.three-star"}, {"chest.four-star-count", "arena.chests.four-star"},
                {"chest.hologram-height", "arena.hologram-height"}, {"loadout.mace-slot-1", "loadout.mace-slot"},
                {"economy.rewards.win", "economy.reward-win"}, {"economy.rewards.kill", "economy.reward-kill"},
                {"economy.rewards.participation", "economy.reward-participation"}, {"economy.custom.id", "economy.custom.name"}
        };
        for (String[] pair : aliases) if (!current.contains(pair[0]) && current.contains(pair[1])) current.set(pair[0], current.get(pair[1]));
    }

    private static void mergeMissing(YamlConfiguration current, YamlConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !current.contains(key)) current.set(key, defaults.get(key));
        }
        if (defaults.getInt("config_version", 0) > current.getInt("config_version", 0)) current.set("config_version", defaults.getInt("config_version"));
    }

    private static void replaceLegacyDefaults(YamlConfiguration current, YamlConfiguration defaults, YamlConfiguration legacy) {
        if (current.getInt("config_version", 0) >= defaults.getInt("config_version", 0)) return;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && legacy.contains(key) && current.contains(key)
                    && java.util.Objects.equals(current.get(key), legacy.get(key))) current.set(key, defaults.get(key));
        }
    }

    private static void saveAtomic(YamlConfiguration config, File file) throws IOException {
        var temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
        config.save(temporary.toFile());
        try {
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public FileConfiguration main() { return config; }
    public FileConfiguration loot() { return loot; }
}
