package club.mcqi.macesurvival.team;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class LoadoutLayoutManager {
    private final NamespacedKey layoutKey;

    public LoadoutLayoutManager(Plugin plugin) {
        layoutKey = new NamespacedKey(Objects.requireNonNull(plugin, "plugin"), "loadout_layout");
    }

    public LoadoutLayout layout(Player player) {
        String stored = player.getPersistentDataContainer().get(layoutKey, PersistentDataType.STRING);
        if (stored == null) {
            return LoadoutLayout.defaults();
        }

        String[] parts = stored.split(",", -1);
        if (parts.length != LoadoutItem.values().length) {
            return LoadoutLayout.defaults();
        }
        try {
            return new LoadoutLayout(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])
            );
        } catch (IllegalArgumentException ignored) {
            return LoadoutLayout.defaults();
        }
    }

    public AssignmentResult assign(Player player, LoadoutItem item, int slot) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(item, "item");
        if (slot < 0 || slot > 8) {
            return AssignmentResult.INVALID_SLOT;
        }

        LoadoutLayout current = layout(player);
        boolean occupied = current.asMap().entrySet().stream()
            .anyMatch(entry -> entry.getKey() != item && entry.getValue() == slot);
        if (occupied) {
            return AssignmentResult.DUPLICATE_SLOT;
        }
        save(player, current.withSlot(item, slot));
        return AssignmentResult.SUCCESS;
    }

    public void reset(Player player) {
        save(player, LoadoutLayout.defaults());
    }

    public void save(Player player, LoadoutLayout layout) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(layout, "layout");
        String serialized = String.join(",",
            Integer.toString(layout.swordSlot()),
            Integer.toString(layout.axeSlot()),
            Integer.toString(layout.firstMaceSlot()),
            Integer.toString(layout.secondMaceSlot())
        );
        player.getPersistentDataContainer().set(layoutKey, PersistentDataType.STRING, serialized);
    }

    public enum AssignmentResult {
        SUCCESS,
        DUPLICATE_SLOT,
        INVALID_SLOT
    }
}
