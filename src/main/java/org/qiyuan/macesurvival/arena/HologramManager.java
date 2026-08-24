package org.qiyuan.macesurvival.arena;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 箱子上方的星级悬浮文本。用 TextDisplay 而不是盔甲架，
 * 因为 TextDisplay 支持面向玩家和背景透明，实体数量也少一半。
 */
public final class HologramManager {

    private final MaceSurvival plugin;
    private final Map<Location, TextDisplay> displays = new LinkedHashMap<>();

    public HologramManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /** 在箱子上方生成星级文本。必须在主线程调用。 */
    public void spawn(Location chest, double height, ChestTier tier) {
        if (chest.getWorld() == null) {
            return;
        }
        Location at = chest.clone().add(0.5, height, 0.5);
        TextDisplay display = chest.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.text(label(tier));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(org.bukkit.Color.fromARGB(0));
            entity.setViewRange(0.45f);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            Transformation transformation = entity.getTransformation();
            transformation.getScale().set(0.9f, 0.9f, 0.9f);
            entity.setTransformation(transformation);
        });
        displays.put(chest, display);
    }

    private Component label(ChestTier tier) {
        String raw = plugin.messages().raw("chest.hologram", MessageManager.vars(
                "stars", tier.starBar(),
                "tier", plugin.messages().raw("chest.tier." + tier.configKey())));
        return Text.parse(raw);
    }

    /** 箱子被开走后清掉对应文本。 */
    public void remove(Location chest) {
        TextDisplay display = displays.remove(chest);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public void clearAll() {
        displays.values().forEach(display -> {
            if (display.isValid()) {
                display.remove();
            }
        });
        displays.clear();
    }
}
