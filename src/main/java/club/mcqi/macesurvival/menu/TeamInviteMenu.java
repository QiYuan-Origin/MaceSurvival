package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TeamInviteMenu extends BaseMenu {
    private final Map<Integer, UUID> playerSlots = new LinkedHashMap<>();
    private final int requestedPage;
    private int page;
    private int pageCount;
    private int previousSlot;
    private int nextSlot;
    private int returnSlot;

    TeamInviteMenu(MenuManager menuManager, Player viewer, int page) {
        super(menuManager, viewer, "team-invite");
        requestedPage = Math.max(0, page);
    }

    @Override
    protected void render() {
        clearAndApplyFrame();
        playerSlots.clear();
        List<Integer> contentSlots = configuredSlots("content-slots");
        List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
            .filter(player -> !player.getUniqueId().equals(viewer.getUniqueId()))
            .filter(menuManager::hasLobbyAccess)
            .filter(player -> menuManager.teams().teamOf(player.getUniqueId())
                .map(team -> team.size() <= 1)
                .orElse(true))
            .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        int pageSize = Math.max(1, contentSlots.size());
        pageCount = Math.max(1, (candidates.size() + pageSize - 1) / pageSize);
        page = Math.min(requestedPage, pageCount - 1);

        inventory.setItem(configuration.getInt("items.header.slot", 4), configuredItem("items.header",
            Map.of("page", Integer.toString(page + 1), "pages", Integer.toString(pageCount)), Material.WRITABLE_BOOK));
        if (candidates.isEmpty()) {
            inventory.setItem(configuration.getInt("items.empty.slot", 22),
                configuredItem("items.empty", Map.of(), Material.GRAY_DYE));
        } else {
            int start = page * pageSize;
            int end = Math.min(candidates.size(), start + pageSize);
            for (int index = start; index < end; index++) {
                Player candidate = candidates.get(index);
                int slot = contentSlots.get(index - start);
                playerSlots.put(slot, candidate.getUniqueId());
                inventory.setItem(slot, playerItem(candidate));
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
            playSound("sounds.page", Sound.ITEM_BOOK_PAGE_TURN);
            menuManager.openTeamInvite(viewer, page - 1);
            return;
        }
        if (slot == nextSlot && page + 1 < pageCount) {
            playSound("sounds.page", Sound.ITEM_BOOK_PAGE_TURN);
            menuManager.openTeamInvite(viewer, page + 1);
            return;
        }
        UUID targetId = playerSlots.get(slot);
        if (targetId == null || menuManager.teams().isLocked()) {
            return;
        }

        TeamManager.ActionResult result = menuManager.teams().invite(viewer.getUniqueId(), targetId);
        Player target = Bukkit.getPlayer(targetId);
        if (result == TeamManager.ActionResult.SUCCESS) {
            String cachedName = Bukkit.getOfflinePlayer(targetId).getName();
            String targetName = target != null
                ? target.getName()
                : cachedName == null ? targetId.toString().substring(0, 8) : cachedName;
            menuManager.sendConfigured(viewer, "team.invite-sent", Map.of("player", targetName));
            if (target != null) {
                menuManager.sendConfigured(target, "team.invite-received", Map.of("player", viewer.getName()));
            }
            playerSlots.remove(slot);
            inventory.clear(slot);
            playSound("sounds.invite", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            return;
        }
        menuManager.sendConfigured(viewer, TeamResultMessages.path(result), Map.of());
        playSound("sounds.error", Sound.ENTITY_VILLAGER_NO);
    }

    private ItemStack playerItem(Player player) {
        TeamData team = menuManager.teams().teamOf(player.getUniqueId()).orElse(null);
        ItemStack item = configuredItem("items.player", Map.of(
            "player", player.getName(),
            "team_size", Integer.toString(team == null ? 1 : team.size())
        ), Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(player);
            item.setItemMeta(skullMeta);
        }
        return item;
    }
}
