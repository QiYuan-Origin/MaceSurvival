package org.qiyuan.macesurvival.menu;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.util.ItemBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 小队菜单。预组队名单来自 SquadManager，成员头像按 member-slots 从左到右排。 */
public final class TeamMenu extends Menu {

    public TeamMenu(MaceSurvival plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String configName() {
        return "team";
    }

    @Override
    protected void render() {
        List<UUID> members = members();
        int max = plugin.squads().teamSize();

        place("header", MessageManager.vars(
                "squad", members.size() > 1
                        ? plugin.messages().raw("team.label-formed")
                        : plugin.messages().raw("team.label-solo"),
                "current", String.valueOf(members.size()),
                "max", String.valueOf(max)));

        List<Integer> slots = slotList("member-slots");
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            if (i < members.size() && i < max) {
                put(slot, memberHead(members.get(i)));
            } else if (i < max) {
                put(slot, button("empty-seat"));
            }
        }

        place("create");
        place("invite");
        place("leave");
        place("back");
    }

    /** 自己永远排第一个，后面跟预组队里的人。 */
    private List<UUID> members() {
        List<UUID> out = new ArrayList<>();
        out.add(viewer.getUniqueId());
        for (UUID mate : plugin.squads().preTeamOf(viewer.getUniqueId())) {
            if (!out.contains(mate)) {
                out.add(mate);
            }
        }
        return out;
    }

    private ItemStack memberHead(UUID uuid) {
        var section = items().getConfigurationSection("member");
        if (section == null) {
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName() == null ? uuid.toString().substring(0, 8) : offline.getName();
        var record = plugin.stats().of(uuid);
        var vars = MessageManager.vars(
                "player", name,
                "wins", String.valueOf(record.wins()),
                "kills", String.valueOf(record.kills()),
                "role", plugin.messages().raw(uuid.equals(viewer.getUniqueId())
                        ? "team.role-you" : "team.role-mate"));
        return ItemBuilder.fromMaterialString(section.getString("material", "PLAYER_HEAD"), name)
                .skullOwner(name)
                .name(section.getString("name", " "), vars)
                .loreWith(section.getStringList("lore"), vars)
                .build();
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == slotOf("create")) {
            click();
            dispatch(action("create"));
            refresh();
            return;
        }
        if (slot == slotOf("invite")) {
            click();
            viewer.closeInventory();
            plugin.input().request(viewer, "team-invite");
            return;
        }
        if (slot == slotOf("leave")) {
            click();
            dispatch(action("leave"));
            refresh();
            return;
        }
        if (slot == slotOf("back")) {
            click();
            MainMenu.openMain(plugin, viewer);
        }
    }

    /** 处理 YAML 里的 command: 动作，让按钮和命令走同一条逻辑。 */
    private void dispatch(String raw) {
        if (raw == null || !raw.startsWith("command:")) {
            return;
        }
        String command = raw.substring("command:".length()).trim();
        if (!command.isEmpty()) {
            viewer.performCommand(command);
        }
    }
}
