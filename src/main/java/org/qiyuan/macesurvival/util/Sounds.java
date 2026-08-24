package org.qiyuan.macesurvival.util;

import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 音效组合。支持 "sound,volume,pitch,delayTicks" 的配置串，多层叠加成一个有层次的音效。
 * 刻意不去大幅调音调，调过头会明显降低听感。
 */
public final class Sounds {

    /** 名字到音效的缓存表，第一次解析配置时建好。 */
    private static volatile Map<String, Sound> lookup;

    private Sounds() {
    }

    public record Layer(Sound sound, float volume, float pitch, long delay) {
    }

    /** 打开主菜单：紫水晶 + 翻书，6 tick 后再补一次翻书。 */
    public static List<Layer> menuMain() {
        return List.of(
                new Layer(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.0f, 0L),
                new Layer(Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f, 0L),
                new Layer(Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.1f, 6L));
    }

    /** 组队菜单：末影龙鳞片般的低鸣 + 铁砧轻响。 */
    public static List<Layer> menuTeam() {
        return List.of(
                new Layer(Sound.BLOCK_COPPER_BULB_TURN_ON, 0.8f, 1.0f, 0L),
                new Layer(Sound.BLOCK_VAULT_INSERT_ITEM, 0.5f, 1.0f, 4L));
    }

    /** 物品栏预设菜单：锻造台 + 砂轮。 */
    public static List<Layer> menuLayout() {
        return List.of(
                new Layer(Sound.BLOCK_SMITHING_TABLE_USE, 0.7f, 1.0f, 0L),
                new Layer(Sound.BLOCK_GRINDSTONE_USE, 0.4f, 1.2f, 5L));
    }

    /** 统计菜单：钟摆 + 书页。 */
    public static List<Layer> menuStats() {
        return List.of(
                new Layer(Sound.BLOCK_TRIAL_SPAWNER_AMBIENT, 0.6f, 1.0f, 0L),
                new Layer(Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.0f, 3L));
    }

    /** 边框玻璃点击。 */
    public static List<Layer> border() {
        return List.of(new Layer(Sound.UI_BUTTON_CLICK, 0.35f, 1.0f, 0L));
    }

    /** 普通按钮点击。 */
    public static List<Layer> click() {
        return List.of(new Layer(Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f, 0L));
    }

    public static List<Layer> deny() {
        return List.of(new Layer(Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f, 0L));
    }

    public static List<Layer> confirm() {
        return List.of(
                new Layer(Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f, 0L),
                new Layer(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f, 3L));
    }

    /** 重锤猛击：要有重量感，所以是低音调的重击叠加。 */
    public static List<Layer> maceSmash() {
        return List.of(
                new Layer(Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 1.0f, 0L),
                new Layer(Sound.ENTITY_GENERIC_EXPLODE, 0.45f, 0.6f, 1L),
                new Layer(Sound.BLOCK_ANVIL_LAND, 0.35f, 0.5f, 2L));
    }

    /** 开局跳伞：风声 + 远处的雷。 */
    public static List<Layer> dropStart() {
        return List.of(
                new Layer(Sound.ITEM_ELYTRA_FLYING, 0.8f, 1.0f, 0L),
                new Layer(Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.4f, 4L));
    }

    /** 开伞：布料被风撑开的闷响，用旗帜和皮革甲叠出布面感。 */
    public static List<Layer> parachute() {
        return List.of(
                new Layer(Sound.ITEM_ARMOR_EQUIP_LEATHER, 1.0f, 0.7f, 0L),
                new Layer(Sound.ENTITY_HORSE_ARMOR, 0.6f, 0.8f, 1L),
                new Layer(Sound.BLOCK_WOOL_PLACE, 0.8f, 0.6f, 3L));
    }

    public static List<Layer> landed() {
        return List.of(
                new Layer(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.0f, 0L),
                new Layer(Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f, 2L));
    }

    /** 拿到附魔书并自动生效。 */
    public static List<Layer> buffGained() {
        return List.of(
                new Layer(Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f, 0L),
                new Layer(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.0f, 2L));
    }

    /** 附魔书打到武器上。 */
    public static List<Layer> enchantApplied() {
        return List.of(
                new Layer(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.9f, 1.0f, 0L),
                new Layer(Sound.BLOCK_ANVIL_USE, 0.5f, 1.3f, 3L),
                new Layer(Sound.ENTITY_ITEM_BREAK, 0.3f, 1.8f, 6L));
    }

    /** 有耐久的物品用尽炸掉。 */
    public static List<Layer> itemShatter() {
        return List.of(
                new Layer(Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f, 0L),
                new Layer(Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.2f, 0L));
    }

    /** 背包内合并耐久。 */
    public static List<Layer> repairMerge() {
        return List.of(
                new Layer(Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1.0f, 0L),
                new Layer(Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.5f, 1.4f, 3L));
    }

    /** 开箱子。星级越高音调越亮。 */
    public static List<Layer> chestOpen(int tier) {
        float pitch = 0.9f + (tier - 1) * 0.15f;
        return List.of(
                new Layer(Sound.BLOCK_VAULT_OPEN_SHUTTER, 0.8f, pitch, 0L),
                new Layer(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, pitch, 3L));
    }

    public static List<Layer> victory() {
        return List.of(
                new Layer(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f, 0L),
                new Layer(Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f, 6L),
                new Layer(Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f, 14L));
    }

    public static List<Layer> defeat() {
        return List.of(
                new Layer(Sound.ENTITY_WITHER_DEATH, 0.5f, 1.4f, 0L),
                new Layer(Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f, 8L));
    }

    /** 击杀提示。 */
    public static List<Layer> kill() {
        return List.of(
                new Layer(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.2f, 0L),
                new Layer(Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.5f, 2L));
    }

    public static void play(Plugin plugin, Player player, List<Layer> layers) {
        if (player == null || layers == null) {
            return;
        }
        for (Layer layer : layers) {
            if (layer.delay() <= 0L) {
                player.playSound(player.getLocation(), layer.sound(), layer.volume(), layer.pitch());
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.playSound(player.getLocation(), layer.sound(), layer.volume(), layer.pitch());
                    }
                }, layer.delay());
            }
        }
    }

    /** 解析 "BLOCK_ANVIL_LAND,0.8,1.0,4" 这种配置串，非法值退回安静的点击音。 */
    public static List<Layer> parse(List<String> raw) {
        List<Layer> layers = new ArrayList<>();
        if (raw == null) {
            return layers;
        }
        for (String line : raw) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",");
            Sound sound = resolve(parts[0].trim());
            if (sound == null) {
                continue;
            }
            float volume = parseFloat(parts, 1, 0.8f);
            float pitch = parseFloat(parts, 2, 1.0f);
            long delay = (long) parseFloat(parts, 3, 0f);
            layers.add(new Layer(sound, volume, pitch, Math.max(0L, delay)));
        }
        return layers;
    }

    /**
     * 配置里两种写法都收：BLOCK_AMETHYST_BLOCK_CHIME 和 block.amethyst_block.chime。
     * 统一折成大写下划线再查表，服主不用记注册表里的点分格式。
     */
    private static Sound resolve(String name) {
        return byName().get(normalize(name));
    }

    private static String normalize(String name) {
        String value = name.trim();
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /** 注册表只遍历一次，之后都走缓存。 */
    private static Map<String, Sound> byName() {
        Map<String, Sound> cached = lookup;
        if (cached != null) {
            return cached;
        }
        Map<String, Sound> built = new HashMap<>();
        for (Sound sound : Registry.SOUND_EVENT) {
            built.put(normalize(Registry.SOUND_EVENT.getKeyOrThrow(sound).getKey()), sound);
        }
        lookup = built;
        return built;
    }

    private static float parseFloat(String[] parts, int index, float fallback) {
        if (parts.length <= index) {
            return fallback;
        }
        try {
            return Float.parseFloat(parts[index].trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
