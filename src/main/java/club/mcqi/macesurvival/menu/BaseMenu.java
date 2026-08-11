package club.mcqi.macesurvival.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public abstract class BaseMenu implements InventoryHolder {
    protected final MenuManager menuManager;
    protected final Player viewer;
    protected final FileConfiguration configuration;
    protected Inventory inventory;
    private final int inventorySize;
    private final Component inventoryTitle;
    private int lastClickTick = Integer.MIN_VALUE;
    private int lastClickSlot = Integer.MIN_VALUE;
    private ClickType lastClickType;

    protected BaseMenu(MenuManager menuManager, Player viewer, String menuName) {
        this.menuManager = Objects.requireNonNull(menuManager, "menuManager");
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        configuration = menuManager.configurations().configuration("menus/" + menuName + ".yml");
        inventorySize = validSize(configuration.getInt("size", 54));
        inventoryTitle = menuManager.text().parse(viewer, configuration.getString("title", ""));
    }

    public final void open() {
        inventory = menuManager.plugin().getServer().createInventory(this, inventorySize, inventoryTitle);
        render();
        viewer.openInventory(inventory);
        playSound("open-sound", Sound.BLOCK_AMETHYST_BLOCK_CHIME);
    }

    protected abstract void render();

    public abstract void handleClick(InventoryClickEvent event);

    final boolean acceptClick(InventoryClickEvent event) {
        int tick = viewer.getTicksLived();
        int slot = event.getRawSlot();
        ClickType click = event.getClick();
        if (tick == lastClickTick && slot == lastClickSlot && click == lastClickType) {
            return false;
        }
        lastClickTick = tick;
        lastClickSlot = slot;
        lastClickType = click;
        return true;
    }

    @Override
    public final @NotNull Inventory getInventory() {
        return Objects.requireNonNull(inventory, "Menu inventory has not been opened yet");
    }

    protected final void clearAndApplyFrame() {
        inventory.clear();
        applyConfiguredSlots("items.edge");
        applyConfiguredSlots("items.corner");
        applyConfiguredSlots("items.header");
    }

    protected final void applyConfiguredSlots(String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        ItemStack item = configuredItem(section, Map.of(), Material.BLACK_STAINED_GLASS_PANE);
        for (int slot : configuredSlots(section)) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, item);
            }
        }
    }

    protected final ItemStack configuredItem(String path, Map<String, String> placeholders, Material fallback) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) {
            return new ItemStack(fallback);
        }
        return configuredItem(section, placeholders, fallback);
    }

    protected final ItemStack configuredItem(
        ConfigurationSection section,
        Map<String, String> placeholders,
        Material fallback
    ) {
        Material material = Material.matchMaterial(section.getString("material", fallback.name()));
        ItemStack item = new ItemStack(material == null ? fallback : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(menuManager.text().parse(viewer, section.getString("name", ""), placeholders));
        List<Component> lore = section.getStringList("lore").stream()
            .map(line -> menuManager.text().parse(viewer, line, placeholders))
            .toList();
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    protected final void playSound(String path, Sound fallback) {
        String configured = configuration.getString(path);
        NamespacedKey key = configured == null ? null : NamespacedKey.fromString(configured);
        Sound sound = key == null ? null : Registry.SOUND_EVENT.get(key);
        viewer.playSound(viewer.getLocation(), sound == null ? fallback : sound, 0.8F, 1.0F);
    }

    protected final void navigateRoot() {
        if (configuration.getBoolean("navigation.close-as-return", false)) {
            String command = configuration.getString("navigation.return-command", "menu").strip();
            viewer.closeInventory();
            if (!command.isEmpty()) {
                String commandLine = command.startsWith("/") ? command.substring(1) : command;
                menuManager.plugin().getServer().dispatchCommand(viewer, commandLine);
            }
            return;
        }
        viewer.closeInventory();
    }

    protected final List<Integer> configuredSlots(String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        return section == null ? List.of() : configuredSlots(section);
    }

    private static List<Integer> configuredSlots(ConfigurationSection section) {
        List<Integer> slots = new ArrayList<>();
        for (Object value : section.getList("slots", List.of())) {
            if (value instanceof Number number) {
                slots.add(number.intValue());
                continue;
            }
            String text = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
            int separator = text.indexOf('-');
            if (separator < 0) {
                try {
                    slots.add(Integer.parseInt(text));
                } catch (NumberFormatException ignored) {
                    // Invalid administrator-provided slots are skipped during rendering.
                }
                continue;
            }
            try {
                int start = Integer.parseInt(text.substring(0, separator));
                int end = Integer.parseInt(text.substring(separator + 1));
                for (int slot = Math.min(start, end); slot <= Math.max(start, end); slot++) {
                    slots.add(slot);
                }
            } catch (NumberFormatException ignored) {
                // Invalid administrator-provided ranges are skipped during rendering.
            }
        }
        return List.copyOf(slots);
    }

    private static int validSize(int configuredSize) {
        if (configuredSize < 9 || configuredSize > 54 || configuredSize % 9 != 0) {
            return 54;
        }
        return configuredSize;
    }
}
