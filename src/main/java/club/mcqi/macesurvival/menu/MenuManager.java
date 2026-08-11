package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.config.ConfigFiles;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Map;
import java.util.function.Predicate;

public final class MenuManager {
    private final JavaPlugin plugin;
    private final TeamManager teamManager;
    private final LoadoutLayoutManager loadoutLayouts;
    private final ConfigFiles configurations;
    private final TextService text;
    private final Predicate<Player> lobbyAccess;

    public MenuManager(
        JavaPlugin plugin,
        TeamManager teamManager,
        LoadoutLayoutManager loadoutLayouts,
        ConfigFiles configurations,
        TextService text,
        Predicate<Player> lobbyAccess
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teamManager = Objects.requireNonNull(teamManager, "teamManager");
        this.loadoutLayouts = Objects.requireNonNull(loadoutLayouts, "loadoutLayouts");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.text = Objects.requireNonNull(text, "text");
        this.lobbyAccess = Objects.requireNonNull(lobbyAccess, "lobbyAccess");
    }

    public void openTeam(Player player) {
        if (requireLobbyAccess(player)) {
            new TeamMenu(this, player).open();
        }
    }

    public void openTeamInvite(Player player, int page) {
        if (requireLobbyAccess(player)) {
            new TeamInviteMenu(this, player, page).open();
        }
    }

    public void openInvitations(Player player, int page) {
        if (requireLobbyAccess(player)) {
            new TeamInvitationsMenu(this, player, page).open();
        }
    }

    public void openLoadout(Player player) {
        if (requireLobbyAccess(player)) {
            new LoadoutMenu(this, player).open();
        }
    }

    public void reload() {
        configurations.reload();
    }

    public void sendConfigured(Player player, String path, Map<String, ?> placeholders) {
        text.sendPrefixed(player, path, placeholders);
    }

    JavaPlugin plugin() {
        return plugin;
    }

    TeamManager teams() {
        return teamManager;
    }

    LoadoutLayoutManager loadouts() {
        return loadoutLayouts;
    }

    public LoadoutLayoutManager loadoutLayouts() {
        return loadoutLayouts;
    }

    ConfigFiles configurations() {
        return configurations;
    }

    TextService text() {
        return text;
    }

    public TextService textService() {
        return text;
    }

    public boolean hasLobbyAccess(Player player) {
        return lobbyAccess.test(player);
    }

    private boolean requireLobbyAccess(Player player) {
        if (lobbyAccess.test(player)) {
            return true;
        }
        sendConfigured(player, "lobby.settings-locked", Map.of());
        return false;
    }
}
