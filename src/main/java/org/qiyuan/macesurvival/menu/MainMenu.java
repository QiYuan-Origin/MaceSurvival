package org.qiyuan.macesurvival.menu;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.bukkit.entity.Player;
import org.qiyuan.macesurvival.config.MessageManager;

import java.util.Map;

/**
 * 主菜单。所有分页从这里进，所以这一页只放导航按钮。
 * 最后一格按配置在"关闭"和"返回服务器主菜单"之间切换，
 * 两个按钮永远不会同时出现。
 */
public final class MainMenu extends Menu {

    public MainMenu(MaceSurvival plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String configName() {
        return "main";
    }

    @Override
    protected void render() {
        var game = plugin.game();
        var record = plugin.stats().of(viewer);

        place("brief", MessageManager.vars(
                "state", plugin.messages().raw("state." + game.state().name().toLowerCase()),
                "players", String.valueOf(game.lobbyCount()),
                "max", String.valueOf(plugin.configs().main().getInt("game.max-players", 40))));

        place("squad", MessageManager.vars(
                "squad", String.valueOf(plugin.squads().preTeamOf(viewer.getUniqueId()).size() + 1)));

        place("layout");

        place("stats", MessageManager.vars(
                "wins", String.valueOf(record.wins()),
                "kills", String.valueOf(record.kills())));

        place("spectate", MessageManager.vars(
                "alive", String.valueOf(game.aliveCount())));

        place("balance", MessageManager.vars(
                "balance", plugin.economy().format(plugin.economy().balance(viewer)),
                "currency", plugin.economy().currencyName()));

        put(slotOf("close"), closeButton());
    }

    /** 关闭键。close-as-back 打开时整颗按钮换成返回样式。 */
    private ItemStack closeButton() {
        var section = items().getConfigurationSection("close");
        if (section == null) {
            return null;
        }
        if (!backEnabled()) {
            return button("close");
        }
        return org.qiyuan.macesurvival.util.ItemBuilder
                .fromMaterialString(section.getString("back-material", "ARROW"), viewer.getName())
                .name(section.getString("back-name", " "))
                .lore(section.getStringList("back-lore"))
                .build();
    }

    private boolean backEnabled() {
        return plugin.configs().main().getBoolean("menu.close-as-back", false);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == slotOf("brief") || slot == slotOf("balance")) {
            click();
            refresh();
            return;
        }
        if (slot == slotOf("squad")) {
            click();
            new TeamMenu(plugin, viewer).open();
            return;
        }
        if (slot == slotOf("layout")) {
            click();
            new LayoutMenu(plugin, viewer).open();
            return;
        }
        if (slot == slotOf("stats")) {
            click();
            new StatsMenu(plugin, viewer).open();
            return;
        }
        if (slot == slotOf("spectate")) {
            click();
            new SpectateMenu(plugin, viewer).open();
            return;
        }
        if (slot == slotOf("close")) {
            click();
            viewer.closeInventory();
            if (backEnabled()) {
                runBackCommand();
            }
        }
    }

    /** 返回服务器主菜单。命令由服主配，这里只负责以玩家身份派发。 */
    private void runBackCommand() {
        String command = plugin.configs().main().getString("menu.back-command", "menu");
        if (command == null || command.isBlank()) {
            return;
        }
        String cleaned = command.startsWith("/") ? command.substring(1) : command;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> viewer.performCommand(cleaned));
    }

    /** 让子菜单统一走这里回主菜单，省得每个类各写一遍。 */
    public static void openMain(MaceSurvival plugin, Player viewer) {
        new MainMenu(plugin, viewer).open();
    }

    static Map<String, String> noVars() {
        return Map.of();
    }
}
