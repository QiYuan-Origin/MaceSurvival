package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import club.mcqi.macesurvival.world.WorldManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

public final class GameListener implements Listener {
    private final MaceSurvivalPlugin plugin;
    private final GameManager game;
    private final WorldManager worlds;
    private final TeamManager teams;
    private final TextService text;
    private final Map<UUID, org.bukkit.Location> deathLocations = new HashMap<>();

    public GameListener(
        MaceSurvivalPlugin plugin,
        GameManager game,
        WorldManager worlds,
        TeamManager teams,
        TextService text
    ) {
        this.plugin = plugin;
        this.game = game;
        this.worlds = worlds;
        this.teams = teams;
        this.text = text;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        game.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isManagedWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isManagedWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!game.isParticipant(player.getUniqueId()) || game.state() == GameState.WAITING
                || game.state() == GameState.COUNTDOWN || game.state() == GameState.BLACKOUT) {
            event.setCancelled(true);
            return;
        }
        if (game.state() == GameState.DEPLOYMENT
                || (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && game.isDeploymentFallProtected(player.getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (game.sameTeam(attacker.getUniqueId(), victim.getUniqueId())) {
            if (!plugin.getConfig().getBoolean("teams.friendly-damage", false)) {
                if (plugin.getConfig().getBoolean("teams.friendly-knockback", true)) {
                    event.setDamage(0.0);
                } else {
                    event.setCancelled(true);
                }
            }
            return;
        }
        game.recordAttack(attacker, victim);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        if (!game.isParticipant(victim.getUniqueId())) return;
        event.deathMessage(null);
        event.setKeepInventory(false);
        event.setKeepLevel(true);
        Player killer = game.resolveKiller(victim);
        deathLocations.put(victim.getUniqueId(), victim.getLocation().clone());
        game.eliminate(victim, killer);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!game.isParticipant(event.getPlayer().getUniqueId())) return;
        org.bukkit.Location deathLocation = deathLocations.remove(event.getPlayer().getUniqueId());
        event.setRespawnLocation(deathLocation == null ? event.getPlayer().getLocation() : deathLocation);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            player.setGameMode(GameMode.SPECTATOR);
            if (deathLocation != null && deathLocation.getWorld() != null) {
                player.teleportAsync(deathLocation);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!game.handleDismount(player, event.getDismounted())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (isManagedWorld(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getWorld().equals(worlds.lobbyWorld())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && player.getWorld().equals(worlds.lobbyWorld())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!isManagedWorld(event.getLocation().getWorld())) {
            return;
        }
        if (event.getEntityType() == EntityType.HAPPY_GHAST
            && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState() && isManagedWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (event.toThunderState() && isManagedWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        if (player.getWorld().equals(worlds.lobbyWorld())
                && player.getY() < plugin.getConfig().getDouble("lobby.void-return-y", 0.0)) {
            player.teleport(worlds.lobbyLocation());
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (!isManagedWorld(event.getPlayer()) && game.state() != GameState.WAITING) {
            game.handleJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.renderer((source, sourceDisplayName, message, viewer) -> text.messageWithComponents(
            source,
            "chat.format",
            Map.of(
                "color", teams.teamOf(source.getUniqueId())
                    .map(team -> TextColor.color(team.color().asRGB()).asHexString())
                    .orElse("#FFFFFF"),
                "player", source.getName()
            ),
            Map.of("message", message)
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("server.motd.enabled", true)) return;
        String lineOne = plugin.getConfig().getString(
            "server.motd.line-1",
            "§7⚔ §4ᴍᴀᴄᴇ ꜱᴜʀᴠɪᴠᴀʟ §7⚔"
        );
        String lineTwo = plugin.getConfig().getString(
            "server.motd.line-2",
            "§81.21.11+ §f● §9§lᴍᴀᴄᴇ§f §7| §9§lꜱᴜʀᴠɪᴠᴀʟ§7 §f● §8mcqi.top"
        );
        event.motd(text.parse(lineOne + "\n" + lineTwo));
    }

    private boolean isManagedWorld(Player player) {
        return isManagedWorld(player.getWorld());
    }

    private boolean isManagedWorld(World world) {
        try {
            if (world.equals(worlds.lobbyWorld())) {
                return true;
            }
            return game.state() != GameState.WAITING
                && game.state() != GameState.BOOTSTRAPPING
                && world.equals(worlds.matchWorld());
        } catch (NullPointerException | IllegalStateException ignored) {
            return false;
        }
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
