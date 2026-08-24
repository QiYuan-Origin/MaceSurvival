package org.qiyuan.macesurvival.menu;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.game.GamePlayer;
import org.qiyuan.macesurvival.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 旁观菜单。存活玩家的头像铺在中间两行，点一下就传送过去。
 * 翻页按钮只有真的有上一页或下一页时才放出来。
 */
public final class SpectateMenu extends Menu {

    private int page;

    public SpectateMenu(MaceSurvival plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String configName() {
        return "spectate";
    }

    @Override
    protected void render() {
        List<GamePlayer> targets = targets();
        List<Integer> slots = slotList("target-slots");
        int perPage = Math.max(1, slots.size());
        int pages = Math.max(1, (targets.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        place("header", MessageManager.vars(
                "alive", String.valueOf(plugin.game().aliveCount()),
                "squads", String.valueOf(plugin.squads().aliveSquadIds().size()),
                "time", clock(plugin.game().remaining())));

        if (targets.isEmpty()) {
            place("empty");
        } else {
            for (int i = 0; i < perPage; i++) {
                int index = page * perPage + i;
                if (index >= targets.size()) {
                    break;
                }
                put(slots.get(i), head(targets.get(index)));
            }
        }

        var pageVars = MessageManager.vars(
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages));
        if (page > 0) {
            place("previous-page", pageVars);
        }
        if (page < pages - 1) {
            place("next-page", pageVars);
        }

        place("free-camera");
        place("back");
    }

    /** 只列还活着且在线的人，掉线的头像点了也没用。 */
    private List<GamePlayer> targets() {
        List<GamePlayer> out = new ArrayList<>();
        for (GamePlayer gp : plugin.game().playing()) {
            if (gp.isOnline()) {
                out.add(gp);
            }
        }
        out.sort((a, b) -> b.kills() - a.kills());
        return out;
    }

    private ItemStack head(GamePlayer gp) {
        var section = items().getConfigurationSection("target");
        if (section == null) {
            return null;
        }
        Player online = gp.online();
        double health = online == null ? 0D : online.getHealth();
        var vars = MessageManager.vars(
                "colour", plugin.squads().colorTag(gp.squadId()),
                "player", gp.name(),
                "squad", plugin.squads().displayName(gp.squadId()),
                "kills", String.valueOf(gp.kills()),
                "health", String.format("%.1f", health));
        return ItemBuilder.fromMaterialString(section.getString("material", "PLAYER_HEAD"), gp.name())
                .skullOwner(gp.name())
                .name(section.getString("name", " "), vars)
                .loreWith(section.getStringList("lore"), vars)
                .build();
    }

    private String clock(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format("%02d:%02d", safe / 60, safe % 60);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();

        if (slot == slotOf("previous-page")) {
            click();
            page = Math.max(0, page - 1);
            refresh();
            return;
        }
        if (slot == slotOf("next-page")) {
            click();
            page++;
            refresh();
            return;
        }
        if (slot == slotOf("free-camera")) {
            click();
            viewer.closeInventory();
            viewer.setSpectatorTarget(null);
            plugin.messages().send(viewer, "spectate.free-camera");
            return;
        }
        if (slot == slotOf("back")) {
            click();
            MainMenu.openMain(plugin, viewer);
            return;
        }

        List<Integer> slots = slotList("target-slots");
        int index = slots.indexOf(slot);
        if (index < 0) {
            return;
        }
        List<GamePlayer> targets = targets();
        int absolute = page * Math.max(1, slots.size()) + index;
        if (absolute >= targets.size()) {
            return;
        }
        Player target = targets.get(absolute).online();
        if (target == null) {
            deny();
            plugin.messages().send(viewer, "spectate.target-offline");
            return;
        }
        click();
        viewer.closeInventory();
        watch(target);
    }

    /** 先确保自己是旁观模式，再把镜头挂到目标身上。 */
    private void watch(Player target) {
        if (viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.setGameMode(GameMode.SPECTATOR);
        }
        viewer.teleport(target.getLocation());
        viewer.setSpectatorTarget(target);
        plugin.messages().send(viewer, "spectate.now-watching",
                MessageManager.vars("player", target.getName()));
    }
}
