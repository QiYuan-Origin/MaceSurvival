package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.team.LoadoutItem;
import club.mcqi.macesurvival.team.LoadoutLayout;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

final class LoadoutMenu extends BaseMenu {
    private final Map<Integer, LoadoutItem> weaponSlots = new java.util.LinkedHashMap<>();
    private int resetSlot;
    private int navigationSlot;

    LoadoutMenu(MenuManager menuManager, org.bukkit.entity.Player viewer) {
        super(menuManager, viewer, "loadout");
    }

    @Override
    protected void render() {
        clearAndApplyFrame();
        weaponSlots.clear();
        LoadoutLayout layout = menuManager.loadouts().layout(viewer);
        for (LoadoutItem item : LoadoutItem.values()) {
            String key = item.name().toLowerCase(Locale.ROOT).replace('_', '-');
            String path = "items." + key;
            int menuSlot = configuration.getInt(path + ".slot", defaultMenuSlot(item));
            weaponSlots.put(menuSlot, item);
            inventory.setItem(menuSlot, configuredItem(path,
                Map.of("slot", Integer.toString(layout.slot(item) + 1)), item.material()));
        }
        resetSlot = configuration.getInt("items.reset.slot", 31);
        navigationSlot = configuration.getInt("items.navigation.slot", 40);
        inventory.setItem(resetSlot, configuredItem("items.reset", Map.of(), Material.RECOVERY_COMPASS));
        inventory.setItem(navigationSlot, configuredItem("items.navigation", Map.of(), Material.BARRIER));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.getRawSlot() < 0 || event.getRawSlot() >= inventory.getSize()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == navigationSlot) {
            playSound("sounds.close", Sound.BLOCK_IRON_DOOR_CLOSE);
            navigateRoot();
            return;
        }
        if (!menuManager.hasLobbyAccess(viewer)) {
            return;
        }
        if (slot == resetSlot) {
            menuManager.loadouts().reset(viewer);
            menuManager.sendConfigured(viewer, "lobby.loadout-reset", Map.of());
            playSound("sounds.reset", Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN);
            render();
            return;
        }

        LoadoutItem item = weaponSlots.get(slot);
        if (item == null) {
            return;
        }
        int direction = event.getClick() == ClickType.RIGHT ? -1 : event.getClick() == ClickType.LEFT ? 1 : 0;
        if (direction == 0) {
            return;
        }
        if (cycle(item, direction)) {
            playSound("sounds.change", Sound.UI_BUTTON_CLICK);
            render();
        } else {
            menuManager.sendConfigured(viewer, "lobby.loadout-no-slot", Map.of());
            playSound("sounds.error", Sound.ENTITY_VILLAGER_NO);
        }
    }

    private boolean cycle(LoadoutItem item, int direction) {
        LoadoutLayout current = menuManager.loadouts().layout(viewer);
        int candidate = current.slot(item);
        for (int attempts = 0; attempts < 9; attempts++) {
            candidate = Math.floorMod(candidate + direction, 9);
            if (menuManager.loadouts().assign(viewer, item, candidate) == LoadoutLayoutManager.AssignmentResult.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private static int defaultMenuSlot(LoadoutItem item) {
        return switch (item) {
            case SWORD -> 20;
            case AXE -> 21;
            case MACE_ONE -> 23;
            case MACE_TWO -> 24;
        };
    }
}
