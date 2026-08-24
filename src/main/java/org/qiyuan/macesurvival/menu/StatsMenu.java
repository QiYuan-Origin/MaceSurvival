package org.qiyuan.macesurvival.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜菜单。五个榜单挤在同一颗按钮里循环，左键往后右键往前，
 * lore 会把全部榜单列出来并给当前选中的打勾。
 */
public final class StatsMenu extends Menu {

    private StatsManager.Board board = StatsManager.Board.WINS;

    public StatsMenu(MaceSurvival plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String configName() {
        return "stats";
    }

    @Override
    protected void render() {
        int rank = plugin.stats().rankOf(board, viewer.getUniqueId());

        place("header", MessageManager.vars(
                "board", boardName(board),
                "rank", rank > 0 ? "#" + rank : plugin.messages().raw("leaderboard.unranked")));

        List<Integer> slots = slotList("entry-slots");
        List<StatsManager.Record> top = plugin.stats().top(board, slots.size());
        if (top.isEmpty()) {
            place("empty");
        } else {
            for (int i = 0; i < top.size() && i < slots.size(); i++) {
                put(slots.get(i), entry(i + 1, top.get(i)));
            }
        }

        place("board", MessageManager.vars("boards", boardList()));
        put(slotOf("self"), self());
        place("back");
    }

    private String boardName(StatsManager.Board value) {
        return plugin.messages().raw(value.messageKey());
    }

    /** 全部榜单列成一段 lore，当前那条标绿加勾。 */
    private String boardList() {
        String line = items().getString("board-line", "{marker}");
        String chosen = items().getString("board-selected", "[ {name} ]");
        String idle = items().getString("board-idle", "[ {name} ]");
        List<String> rows = new ArrayList<>();
        for (StatsManager.Board value : StatsManager.Board.values()) {
            String marker = (value == board ? chosen : idle)
                    .replace("{name}", boardName(value));
            rows.add(line.replace("{marker}", marker));
        }
        return String.join("\n", rows);
    }

    private ItemStack entry(int rank, StatsManager.Record record) {
        var section = items().getConfigurationSection("entry");
        if (section == null) {
            return null;
        }
        var vars = MessageManager.vars(
                "rank", String.valueOf(rank),
                "player", record.name(),
                "wins", String.valueOf(record.wins()),
                "kills", String.valueOf(record.kills()),
                "deaths", String.valueOf(record.deaths()),
                "kd", String.format("%.2f", record.kd()),
                "streak", String.valueOf(record.bestStreak()));
        return ItemBuilder.fromMaterialString(section.getString("material", "PLAYER_HEAD"), record.name())
                .skullOwner(record.name())
                .name(section.getString("name", " "), vars)
                .loreWith(section.getStringList("lore"), vars)
                .build();
    }

    private ItemStack self() {
        var section = items().getConfigurationSection("self");
        if (section == null) {
            return null;
        }
        var record = plugin.stats().of(viewer);
        var vars = MessageManager.vars(
                "wins", String.valueOf(record.wins()),
                "kills", String.valueOf(record.kills()),
                "deaths", String.valueOf(record.deaths()),
                "kd", String.format("%.2f", record.kd()),
                "played", String.valueOf(record.games()),
                "streak", String.valueOf(record.bestStreak()));
        return ItemBuilder.fromMaterialString(section.getString("material", "PLAYER_HEAD"), viewer.getName())
                .skullOwner(viewer.getName())
                .name(section.getString("name", " "), vars)
                .loreWith(section.getStringList("lore"), vars)
                .build();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == slotOf("board")) {
            board = event.isRightClick() ? board.previous() : board.next();
            click();
            refresh();
            return;
        }
        if (slot == slotOf("back")) {
            click();
            MainMenu.openMain(plugin, viewer);
        }
    }
}
