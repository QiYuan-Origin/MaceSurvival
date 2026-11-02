package org.qiyuan.macesurvival.arena;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;

import java.util.*;

/**
 * Manages floating text holograms above chests using TextDisplay entities.
 */
public final class HologramManager {

    private final MaceSurvival plugin;
    private final Map<Location, TextDisplay> holograms = new HashMap<>();

    public HologramManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    /**
     * Create a hologram displaying chest tier
     */
    public void create(Location location, ChestTier tier) {
        var world = location.getWorld();
        if (world == null) return;

        // Get hologram text from messages
        var text = plugin.messages().get("chest." + tier.name().toLowerCase().replace('_', '-'));

        // Spawn TextDisplay entity
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.VERTICAL);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(org.bukkit.Color.fromARGB(70, 12, 15, 20));
            entity.setShadowed(true);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(140);
            entity.setPersistent(false);  // Don't save to disk
            entity.setViewRange(0.65f);
            entity.setSeeThrough(false);
        });

        holograms.put(location, display);
    }

    /**
     * Remove hologram at specific location
     */
    public void remove(Location location) {
        TextDisplay display = holograms.remove(location);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /**
     * Remove all holograms
     */
    public void removeAll() {
        for (TextDisplay display : holograms.values()) {
            if (display.isValid()) {
                display.remove();
            }
        }
        holograms.clear();
    }

    public int count() {
        return holograms.size();
    }
}
