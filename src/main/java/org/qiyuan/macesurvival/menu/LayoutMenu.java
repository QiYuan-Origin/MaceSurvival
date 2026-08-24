package org.qiyuan.macesurvival.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷栏布局菜单。四件开局物品各自占一颗按钮，左键往后挪一格，
 * 右键往前挪一格，到头会绕回来。改动只写进玩家自己的 layouts.yml，
 * config.yml 的 loadout 段永远只当默认值。
 */
public final class LayoutMenu extends Menu {

    /** 按钮 key，顺序跟 LayoutStore.KEYS 一致，索引就是存档下标。 */
    private static final String[] BUTTONS = { "mace", "food", "blocks", "wind-charge" };

    public LayoutMenu(MaceSurvival plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String configName() {
        return "layout";
    }

    @Override
    protected void render() {
        place("header");
        int[] slots = plugin.layouts().slots(viewer);
        for (int i = 0; i < BUTTONS.length; i++) {
            place(BUTTONS[i], MessageManager.vars("slots", slotList(slots[i])));
        }
        place("reset");
        place("back");
    }

    /** 九个槽位全列出来，选中的那个标绿加勾。 */
    private String slotList(int selected) {
        String line = items().getString("slot-list-line", "{marker}");
        String chosen = items().getString("slot-list-selected", "[ Slot {slot} ]");
        String idle = items().getString("slot-list-idle", "[ Slot {slot} ]");
        List<String> rows = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            String marker = (slot == selected ? chosen : idle)
                    .replace("{slot}", String.valueOf(slot + 1));
            rows.add(line.replace("{marker}", marker));
        }
        return String.join("\n", rows);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();

        if (slot == slotOf("reset")) {
            click();
            plugin.layouts().reset(viewer);
            refresh();
            return;
        }
        if (slot == slotOf("back")) {
            click();
            MainMenu.openMain(plugin, viewer);
            return;
        }

        for (int i = 0; i < BUTTONS.length; i++) {
            if (slot != slotOf(BUTTONS[i])) {
                continue;
            }
            int step = event.isRightClick() ? -1 : 1;
            int next = (plugin.layouts().slot(viewer, i) + step + 9) % 9;
            plugin.layouts().set(viewer, i, next);
            click();
            refresh();
            return;
        }
    }
}
