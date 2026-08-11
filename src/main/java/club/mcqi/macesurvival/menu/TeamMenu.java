package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TeamMenu extends BaseMenu {
    private final Map<Integer, UUID> memberSlots = new LinkedHashMap<>();
    private int inviteSlot;
    private int invitationsSlot;
    private int leaveSlot;
    private int navigationSlot;

    TeamMenu(MenuManager menuManager, org.bukkit.entity.Player viewer) {
        super(menuManager, viewer, "team");
    }

    @Override
    protected void render() {
        clearAndApplyFrame();
        memberSlots.clear();
        TeamData team = menuManager.teams().ensureSoloTeam(viewer.getUniqueId());
        Map<String, String> teamPlaceholders = Map.of(
            "team_size", Integer.toString(team.size()),
            "max_size", Integer.toString(TeamManager.MAX_TEAM_SIZE)
        );
        inventory.setItem(configuration.getInt("items.header.slot", 4),
            configuredItem("items.header", teamPlaceholders, Material.MACE));

        List<Integer> configuredMemberSlots = configuredSlots("member-slots");
        List<UUID> members = team.membersInJoinOrder();
        for (int index = 0; index < members.size() && index < configuredMemberSlots.size(); index++) {
            int slot = configuredMemberSlots.get(index);
            UUID memberId = members.get(index);
            memberSlots.put(slot, memberId);
            inventory.setItem(slot, memberItem(team, memberId));
        }

        inviteSlot = configuration.getInt("items.invite.slot", 30);
        invitationsSlot = configuration.getInt("items.invitations.slot", 32);
        leaveSlot = configuration.getInt("items.leave.slot", 40);
        navigationSlot = configuration.getInt("items.navigation.slot", 49);

        if (menuManager.teams().isLocked()) {
            int lockedSlot = configuration.getInt("items.locked.slot", 31);
            inventory.setItem(lockedSlot, configuredItem("items.locked", Map.of(), Material.BARRIER));
        } else {
            if (team.leader().equals(viewer.getUniqueId()) && team.size() < TeamManager.MAX_TEAM_SIZE) {
                inventory.setItem(inviteSlot, configuredItem("items.invite", teamPlaceholders, Material.WRITABLE_BOOK));
            }
            int invitationCount = menuManager.teams().invitationsFor(viewer.getUniqueId()).size();
            if (invitationCount > 0) {
                inventory.setItem(invitationsSlot, configuredItem("items.invitations",
                    Map.of("count", Integer.toString(invitationCount)), Material.PAPER));
            }
            if (team.size() > 1) {
                inventory.setItem(leaveSlot, configuredItem("items.leave", Map.of(), Material.RED_DYE));
            }
        }
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
        if (!menuManager.hasLobbyAccess(viewer) || menuManager.teams().isLocked()) {
            return;
        }
        if (slot == inviteSlot && inventory.getItem(slot) != null) {
            playSound("sounds.invite", Sound.ITEM_BOOK_PAGE_TURN);
            menuManager.openTeamInvite(viewer, 0);
            return;
        }
        if (slot == invitationsSlot && inventory.getItem(slot) != null) {
            playSound("sounds.invitations", Sound.BLOCK_ENCHANTMENT_TABLE_USE);
            menuManager.openInvitations(viewer, 0);
            return;
        }
        if (slot == leaveSlot && inventory.getItem(slot) != null) {
            TeamManager.ActionResult result = menuManager.teams().leave(viewer.getUniqueId());
            sendResult(result, "team.left", Map.of());
            playSound("sounds.leave", Sound.ENTITY_ITEM_BREAK);
            render();
            return;
        }

        UUID memberId = memberSlots.get(slot);
        if (memberId == null || memberId.equals(viewer.getUniqueId())) {
            return;
        }
        TeamManager.ActionResult result;
        if (event.getClick() == ClickType.LEFT) {
            result = menuManager.teams().transferLeadership(viewer.getUniqueId(), memberId);
            sendResult(result, "team.leader-transferred", Map.of("player", displayName(memberId)));
        } else if (event.getClick() == ClickType.RIGHT) {
            result = menuManager.teams().kick(viewer.getUniqueId(), memberId);
            sendResult(result, "team.member-kicked", Map.of("player", displayName(memberId)));
        } else {
            return;
        }
        playSound("sounds.member", Sound.UI_BUTTON_CLICK);
        render();
    }

    private ItemStack memberItem(TeamData team, UUID memberId) {
        OfflinePlayer member = Bukkit.getOfflinePlayer(memberId);
        String playerName = displayName(memberId);
        boolean leader = team.leader().equals(memberId);
        boolean self = viewer.getUniqueId().equals(memberId);
        String itemPath = self ? "items.member-self" : leader ? "items.member-leader" : "items.member";
        Map<String, String> placeholders = Map.of(
            "player", playerName,
            "role", configuration.getString(leader ? "roles.leader" : "roles.member", "")
        );
        ItemStack item = configuredItem(itemPath, placeholders, Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(member);
            item.setItemMeta(skullMeta);
        }
        return item;
    }

    private void sendResult(TeamManager.ActionResult result, String successPath, Map<String, ?> placeholders) {
        String path = result == TeamManager.ActionResult.SUCCESS ? successPath : TeamResultMessages.path(result);
        menuManager.sendConfigured(viewer, path, placeholders);
    }

    private static String displayName(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }
}
