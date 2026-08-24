package org.qiyuan.macesurvival.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有默认配置都打包在 resources 里，运行时只做"缺失即补齐"，不做凭空生成。
 * reload 会检查每个受管文件是否还在，被管理员误删的会原样补回来。
 */
public final class ConfigManager {

    /** 所有受管文件，reload 时逐个检查。菜单全部集中在 menu/ 下面。 */
    private static final List<String> MANAGED = List.of(
            "config.yml",
            "messages.yml",
            "loot.yml",
            "menu/main.yml",
            "menu/team.yml",
            "menu/layout.yml",
            "menu/stats.yml",
            "menu/spectate.yml");

    private final Plugin plugin;
    private final Map<String, FileConfiguration> loaded = new LinkedHashMap<>();
    private final List<String> lastRestored = new ArrayList<>();

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 首次加载与 reload 都走这里。返回被补回来的文件名，便于给管理员反馈。 */
    public List<String> reload() {
        lastRestored.clear();
        loaded.clear();
        for (String path : MANAGED) {
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) {
                plugin.saveResource(path, false);
                lastRestored.add(path);
            }
            loaded.put(path, read(file));
        }
        upgradeVersions();
        return List.copyOf(lastRestored);
    }

    private FileConfiguration read(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        // 把 jar 内默认值作为 fallback，这样新增配置项不需要玩家删文件重生成
        InputStream defaults = plugin.getResource(relative(file));
        if (defaults != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            config.options().copyDefaults(true);
        }
        return config;
    }

    private String relative(File file) {
        String base = plugin.getDataFolder().getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(base)) {
            path = path.substring(base.length());
        }
        return path.replace('\\', '/').replaceFirst("^/", "");
    }

    /**
     * config_version 比 jar 内低时，把新增键补进玩家文件并落盘，
     * 已有的键一律保留，不会丢玩家改过的值和数据。
     */
    private void upgradeVersions() {
        for (Map.Entry<String, FileConfiguration> entry : loaded.entrySet()) {
            FileConfiguration config = entry.getValue();
            InputStream stream = plugin.getResource(entry.getKey());
            if (stream == null) {
                continue;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            int current = config.getInt("config_version", 0);
            int target = bundled.getInt("config_version", 0);
            if (current >= target) {
                continue;
            }
            boolean changed = false;
            for (String key : bundled.getKeys(true)) {
                if (!config.contains(key)) {
                    config.set(key, bundled.get(key));
                    changed = true;
                }
            }
            config.set("config_version", target);
            if (changed || current != target) {
                save(entry.getKey(), config);
            }
        }
    }

    private void save(String path, FileConfiguration config) {
        try {
            config.save(new File(plugin.getDataFolder(), path));
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save " + path + ": " + ex.getMessage());
        }
    }

    public FileConfiguration main() {
        return get("config.yml");
    }

    public FileConfiguration messages() {
        return get("messages.yml");
    }

    public FileConfiguration loot() {
        return get("loot.yml");
    }

    public FileConfiguration menu(String name) {
        return get("menu/" + name + ".yml");
    }

    public FileConfiguration get(String path) {
        FileConfiguration config = loaded.get(path);
        if (config == null) {
            // 走到这里说明文件在运行期被删了，就地补回来，避免抛异常打断玩家操作
            File file = new File(plugin.getDataFolder(), path);
            if (!file.exists()) {
                plugin.saveResource(path, false);
            }
            config = read(file);
            loaded.put(path, config);
        }
        return config;
    }

    public List<String> managedFiles() {
        return MANAGED;
    }
}
