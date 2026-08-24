package org.qiyuan.macesurvival.loadout;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.qiyuan.macesurvival.MaceSurvival;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每位玩家自己的快捷栏布局，存在 layouts.yml。
 * config.yml 里的 loadout.*-slot 只当默认值，玩家改的东西绝不写回服主配置。
 */
public final class LayoutStore {

    /** 四件开局物品的 key，顺序就是默认槽位 0-3。 */
    public static final String[] KEYS = { "mace", "food", "block", "wind-charge" };

    private final MaceSurvival plugin;
    private final Map<UUID, int[]> layouts = new ConcurrentHashMap<>();
    private File file;
    private YamlConfiguration data;

    public LayoutStore(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "layouts.yml");
        if (!file.exists()) {
            try {
                if (file.getParentFile().mkdirs() || file.getParentFile().exists()) {
                    file.createNewFile();
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not create layouts.yml: " + ex.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
        layouts.clear();
        if (data.getConfigurationSection("players") == null) {
            return;
        }
        for (String rawId : data.getConfigurationSection("players").getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(rawId);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int[] slots = defaults();
            for (int i = 0; i < KEYS.length; i++) {
                slots[i] = clamp(data.getInt("players." + rawId + "." + KEYS[i], slots[i]), slots[i]);
            }
            layouts.put(uuid, slots);
        }
    }

    /** config.yml 的默认槽位。服主改了默认值，没自定义过的玩家就跟着变。 */
    public int[] defaults() {
        int[] slots = new int[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            slots[i] = clamp(plugin.configs().main().getInt("loadout." + KEYS[i] + "-slot", i), i);
        }
        return slots;
    }

    public int slot(Player player, int index) {
        return slots(player)[index];
    }

    public int[] slots(Player player) {
        return layouts.getOrDefault(player.getUniqueId(), defaults()).clone();
    }

    /**
     * 把某一件挪到指定槽位。越界的值直接忽略，不抛异常。
     * 目标槽位上已经有别的东西时两者交换，否则开局发装备会互相覆盖。
     */
    public void set(Player player, int index, int slot) {
        if (index < 0 || index >= KEYS.length || slot < 0 || slot > 8) {
            return;
        }
        int[] slots = layouts.computeIfAbsent(player.getUniqueId(), id -> defaults());
        int previous = slots[index];
        for (int i = 0; i < slots.length; i++) {
            if (i != index && slots[i] == slot) {
                slots[i] = previous;
            }
        }
        slots[index] = slot;
        write(player.getUniqueId(), slots);
    }

    /** 恢复默认。顺手把记录删掉，这样以后服主调默认值还能跟上。 */
    public void reset(Player player) {
        layouts.remove(player.getUniqueId());
        if (data != null) {
            data.set("players." + player.getUniqueId(), null);
            save();
        }
    }

    private void write(UUID uuid, int[] slots) {
        if (data == null) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            data.set("players." + uuid + "." + KEYS[i], slots[i]);
        }
        save();
    }

    private void save() {
        if (data == null || file == null) {
            return;
        }
        try {
            data.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save layouts.yml: " + ex.getMessage());
        }
    }

    private int clamp(int value, int fallback) {
        return value >= 0 && value <= 8 ? value : fallback;
    }
}
