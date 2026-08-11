package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.team.TeamManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TeamInvitationsMenu extends BaseMenu {
    private final Map<Integer, TeamManager.Invitation> invitationSlots = new LinkedHashMap<>();
    private final int requestedPage;
    private int page;
    private int pageCount;
    private int previousSlot;
    private int nextSlot;
    private int returnSlot;

    TeamInvitationsMenu(MenuManager menuManager, Player viewer, int page) {
        super(menuManager, viewer, "team-invitations");
        requestedPage = Math.max(0, page);
    }

    @Override
    protected void render() {
        clearAndApplyFrame();
        invitationSlots.clear();
        List<TeamManager.Invitation> invitations = menuManager.teams().invitationsFor(viewer.getUniqueId());
        List<Integer> contentSlots = configuredSlots("content-slots");
        int pageSize = Math.max(1, contentSlots.size());
        pageCount = Math.max(1, (invitations.size() + pageSize - 1) / pageSize);
        page = Math.min(requestedPage, pageCount - 1);
        inventory.setItem(configuration.getInt("items.header.slot", 4), configuredItem("items.header",
            Map.of("page", Integer.toString(page + 1), "pages", Integer.toString(pageCount)), Material.PAPER));

        if (invitations.isEmpty()) {
            inventory.setItem(configuration.getInt("items.empty.slot", 22),
                configuredItem("items.empty", Map.of(), Material.GRAY_DYE));
        } else {
            int start = page * pageSize;
            int end = Math.min(invitations.size(), start + pageSize);
            for (int index = start; index < end; index++) {
                TeamManager.Invitation invitation = invitations.get(index);
                int slot = contentSlots.get(index - start);
                invitationSlots.put(slot, invitation);
                inventory.setItem(slot, invitationItem(invitation));
            }
        }

        previousSlot = configuration.getInt("items.previous.slot", 47);
        nextSlot = configuration.getInt("items.next.slot", 51);
        returnSlot = configuration.getInt("items.return.slot", 49);
        if (page > 0) {
            inventory.setItem(previousSlot, configuredItem("items.previous", Map.of(), Material.ARROW));
        }
        if (page + 1 < pageCount) {
            inventory.setItem(nextSlot, configuredItem("items.next", Map.of(), Material.ARROW));
        }
        inventory.setItem(returnSlot, configuredItem("items.return", Map.of(), Material.SPECTRAL_ARROW));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (event.getRawSlot() < 0 || event.getRawSlot() >= inventory.getSize()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == returnSlot) {
            playSound("sounds.return", Sound.ITEM_BOOK_PAGE_TURN);
            menuManager.openTeam(viewer);
            return;
        }
        if (slot == previousSlot && page > 0) {
            menuManager.openInvitations(viewer, page - 1);
            return;
        }
        if (slot == nextSlot && page + 1 < pageCount) {
            menuManager.openInvitations(viewer, page + 1);
            return;
        }

        TeamManager.Invitation invitation = invitationSlots.get(slot);
        if (invitation == null || menuManager.teams().isLocked()) {
            return;
        }
        TeamManager.ActionResult result;
        if (event.getClick() == ClickType.LEFT) {
            result = menuManager.teams().accept(viewer.getUniqueId(), invitation.inviterId());
            if (result == TeamManager.ActionResult.SUCCESS) {
                menuManager.sendConfigured(viewer, "team.joined", Map.of());
                Player inviter = Bukkit.getPlayer(invitation.inviterId());
                if (inviter != null) {
                    menuManager.sendConfigured(inviter, "team.member-joined", Map.of("player", viewer.getName()));
                }
                playSound("sounds.accept", Sound.BLOCK_BEACON_ACTIVATE);
                menuManager.openTeam(viewer);
                return;
            }
        } else if (event.getClick() == ClickType.RIGHT) {
            result = menuManager.teams().decline(viewer.getUniqueId(), invitation.inviterId());
            if (result == TeamManager.ActionResult.SUCCESS) {
                menuManager.sendConfigured(viewer, "team.invite-declined", Map.of());
                playSound("sounds.decline", Sound.ENTITY_ITEM_BREAK);
                render();
                return;
            }
        } else {
            return;
        }
        menuManager.sendConfigured(viewer, TeamResultMessages.path(result), Map.of());
        playSound("sounds.error", Sound.ENTITY_VILLAGER_NO);
        render();
    }

    private ItemStack invitationItem(TeamManager.Invitation invitation) {
        OfflinePlayer inviter = Bukkit.getOfflinePlayer(invitation.inviterId());
        String name = inviter.getName() == null ? invitation.inviterId().toString().substring(0, 8) : inviter.getName();
        ItemStack item = configuredItem("items.invitation", Map.of("player", name), Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(inviter);
            item.setItemMeta(skullMeta);
        }
        return item;
    }
}
