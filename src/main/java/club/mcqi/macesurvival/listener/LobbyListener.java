package club.mcqi.macesurvival.listener;

import club.mcqi.macesurvival.menu.MenuManager;
import club.mcqi.macesurvival.presentation.PlayerPresentationService;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LobbyListener implements Listener {
    private static final int DEFAULT_TEAM_ITEM_SLOT = 0;
    private static final int DEFAULT_LOADOUT_ITEM_SLOT = 4;
    private static final int DEFAULT_VISIBILITY_ITEM_SLOT = 8;

    private final JavaPlugin plugin;
    private final LobbyGateway gateway;
    private final TeamManager teamManager;
    private final MenuManager menuManager;
    private final TextService text;
    private final PlayerPresentationService presentation;
    private final NamespacedKey lobbyControlKey;
    private final Set<UUID> showAllPlayers = ConcurrentHashMap.newKeySet();
    private BukkitTask visibilityRefreshTask;

    public LobbyListener(
        JavaPlugin plugin,
        LobbyGateway gateway,
        TeamManager teamManager,
        MenuManager menuManager,
        TextService text,
        PlayerPresentationService presentation
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.teamManager = Objects.requireNonNull(teamManager, "teamManager");
        this.menuManager = Objects.requireNonNull(menuManager, "menuManager");
        this.text = Objects.requireNonNull(text, "text");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        lobbyControlKey = new NamespacedKey(plugin, "lobby_control");
        teamManager.setChangeListener(() -> {
            requestVisibilityRefresh();
            presentation.requestRefresh();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> admit(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !gateway.isWaitingPlayer(event.getPlayer())) {
            return;
        }
        String control = controlId(event.getItem());
        if (control == null) {
            return;
        }
        event.setCancelled(true);
        if (control.equals("team")) {
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 1.0F);
            menuManager.openTeam(event.getPlayer());
        } else if (control.equals("loadout")) {
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.8F, 1.0F);
            menuManager.openLoadout(event.getPlayer());
        } else if (control.equals("visibility")) {
            UUID playerId = event.getPlayer().getUniqueId();
            if (!showAllPlayers.remove(playerId)) {
                showAllPlayers.add(playerId);
            }
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_TRIAL_SPAWNER_DETECT_PLAYER,
                0.75F, 1.0F);
            refreshVisibility();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        showAllPlayers.remove(event.getPlayer().getUniqueId());
        requestVisibilityRefresh();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !gateway.isWaitingPlayer(player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = event.getHotbarButton() >= 0
            ? player.getInventory().getItem(event.getHotbarButton())
            : null;
        if (isLobbyControl(current) || isLobbyControl(cursor) || isLobbyControl(hotbar)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && gateway.isWaitingPlayer(player)
            && isLobbyControl(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (gateway.isWaitingPlayer(event.getPlayer()) && isLobbyControl(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (gateway.isWaitingPlayer(event.getPlayer())
            && (isLobbyControl(event.getMainHandItem()) || isLobbyControl(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && gateway.isWaitingPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && gateway.isWaitingPlayer(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (gateway.isWaitingPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (gateway.isWaitingPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidFall(org.bukkit.event.player.PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!gateway.isWaitingPlayer(player) || event.getTo().getY() >= gateway.voidProtectionY()) {
            return;
        }
        Location lobby = gateway.lobbySpawn();
        if (lobby != null) {
            player.teleportAsync(lobby, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    public void giveLobbyItems(Player player) {
        teamManager.ensureSoloTeam(player.getUniqueId());
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.setItemOnCursor(new ItemStack(Material.AIR));
        int teamSlot = lobbySlot("team-slot", DEFAULT_TEAM_ITEM_SLOT);
        int loadoutSlot = lobbySlot("loadout-slot", DEFAULT_LOADOUT_ITEM_SLOT);
        int visibilitySlot = lobbySlot("visibility-slot", DEFAULT_VISIBILITY_ITEM_SLOT);
        player.getInventory().setItem(teamSlot, lobbyItem(
            Material.PLAYER_HEAD,
            "team",
            text.message(player, "lobby.hotbar.team.name", Map.of()),
            text.message(player, "lobby.hotbar.team.lore", Map.of())
        ));
        player.getInventory().setItem(loadoutSlot, lobbyItem(
            Material.RECOVERY_COMPASS,
            "loadout",
            text.message(player, "lobby.hotbar.loadout.name", Map.of()),
            text.message(player, "lobby.hotbar.loadout.lore", Map.of())
        ));
        player.getInventory().setItem(visibilitySlot, visibilityItem(player));
        player.getInventory().setHeldItemSlot(teamSlot);
        requestVisibilityRefresh();
    }

    private void admit(Player player) {
        if (!player.isOnline()) {
            return;
        }
        if (gateway.gameInProgress()) {
            gateway.enterSpectator(player);
            presentation.requestRefresh();
            return;
        }
        teamManager.ensureSoloTeam(player.getUniqueId());
        gateway.enterWaiting(player);
        requestVisibilityRefresh();
        presentation.requestRefresh();
    }

    private void requestVisibilityRefresh() {
        if (!plugin.isEnabled() || visibilityRefreshTask != null) {
            return;
        }
        visibilityRefreshTask = plugin.getServer().getScheduler().runTask(plugin, () -> {
            visibilityRefreshTask = null;
            refreshVisibility();
        });
    }

    public void refreshVisibility() {
        var waiting = plugin.getServer().getOnlinePlayers().stream()
            .filter(gateway::isWaitingPlayer)
            .toList();
        int threshold = Math.max(1, plugin.getConfig().getInt("lobby.hide-players-over", 20));
        boolean crowded = waiting.size() >= threshold;
        for (Player viewer : waiting) {
            boolean showEveryone = !crowded || showAllPlayers.contains(viewer.getUniqueId());
            for (Player target : waiting) {
                if (viewer.equals(target) || showEveryone
                    || teamManager.areTeamMates(viewer.getUniqueId(), target.getUniqueId())) {
                    viewer.showPlayer(plugin, target);
                } else {
                    viewer.hidePlayer(plugin, target);
                }
            }
            int visibilitySlot = lobbySlot("visibility-slot", DEFAULT_VISIBILITY_ITEM_SLOT);
            ItemStack existing = viewer.getInventory().getItem(visibilitySlot);
            if ("visibility".equals(controlId(existing))) {
                viewer.getInventory().setItem(visibilitySlot, visibilityItem(viewer));
            }
        }
    }

    private ItemStack visibilityItem(Player player) {
        int threshold = Math.max(1, plugin.getConfig().getInt("lobby.hide-players-over", 20));
        long waitingPlayers = plugin.getServer().getOnlinePlayers().stream().filter(gateway::isWaitingPlayer).count();
        boolean showing = waitingPlayers < threshold || showAllPlayers.contains(player.getUniqueId());
        return lobbyItem(
            showing ? Material.LIME_DYE : Material.GRAY_DYE,
            "visibility",
            text.message(player, showing
                ? "lobby.hotbar.visibility.shown-name"
                : "lobby.hotbar.visibility.hidden-name", Map.of()),
            text.message(player, showing
                ? "lobby.hotbar.visibility.shown-lore"
                : "lobby.hotbar.visibility.hidden-lore", Map.of())
        );
    }

    private int lobbySlot(String key, int fallback) {
        int slot = plugin.getConfig().getInt("lobby.hotbar." + key, fallback);
        return slot < 0 || slot > 8 ? fallback : slot;
    }

    private ItemStack lobbyItem(Material material, String id, Component name, Component lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(lore.decoration(TextDecoration.ITALIC, false)));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(lobbyControlKey, PersistentDataType.STRING, id);
        });
        return item;
    }

    private boolean isLobbyControl(ItemStack item) {
        return controlId(item) != null;
    }

    private String controlId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(lobbyControlKey, PersistentDataType.STRING);
    }

    public interface LobbyGateway {
        boolean gameInProgress();

        boolean isWaitingPlayer(Player player);

        void enterWaiting(Player player);

        void enterSpectator(Player player);

        Location lobbySpawn();

        default double voidProtectionY() {
            return -32.0D;
        }
    }
}
