package club.mcqi.macesurvival.presentation;

import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import club.mcqi.macesurvival.game.GameFacade;
import club.mcqi.macesurvival.game.GameState;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Keeps team-colored player names synchronized across tab and per-viewer scoreboards. */
public final class PlayerPresentationService implements AutoCloseable {
    private static final String SCOREBOARD_TEAM_PREFIX = "msn";
    private static final String UNASSIGNED_TEAM_NAME = SCOREBOARD_TEAM_PREFIX + "_unassigned";
    private static final String DEFAULT_TAB_PLAYER =
        "<font:minecraft:uniform><dark_gray>[</dark_gray><{color}>{team_label}</{color}>"
            + "<dark_gray>]</dark_gray> <white>{player}</white></font>";
    private static final String DEFAULT_TAB_HEADER =
        "<font:minecraft:uniform><white><shadow:#404040:1>MACE.VIP</shadow></white></font>\n"
            + "<font:minecraft:uniform><dark_gray>{server}</dark_gray> <gray>•</gray> <color:#ff5555>{phase}</color></font>";
    private static final String DEFAULT_TAB_FOOTER =
        "<font:minecraft:uniform><gray>{online}</gray><dark_gray>/</dark_gray><white>{max}</white> "
            + "<dark_gray>online</dark_gray> <dark_gray>|</dark_gray> "
            + "<gray>Alive</gray> <white>{alive}</white> <dark_gray>|</dark_gray> "
            + "<gray>Party</gray> <{color}>{team_size}/4</{color}></font>";
    private static final List<NamedTextColor> VANILLA_COLORS = List.of(
        NamedTextColor.BLACK,
        NamedTextColor.DARK_BLUE,
        NamedTextColor.DARK_GREEN,
        NamedTextColor.DARK_AQUA,
        NamedTextColor.DARK_RED,
        NamedTextColor.DARK_PURPLE,
        NamedTextColor.GOLD,
        NamedTextColor.GRAY,
        NamedTextColor.DARK_GRAY,
        NamedTextColor.BLUE,
        NamedTextColor.GREEN,
        NamedTextColor.AQUA,
        NamedTextColor.RED,
        NamedTextColor.LIGHT_PURPLE,
        NamedTextColor.YELLOW,
        NamedTextColor.WHITE
    );

    private final JavaPlugin plugin;
    private final TeamManager teams;
    private final TextService text;
    private final Supplier<GameFacade> game;
    private final Map<UUID, Scoreboard> knownScoreboards = new HashMap<>();
    private BukkitTask scoreboardPollTask;
    private BukkitTask pendingRefresh;

    public PlayerPresentationService(JavaPlugin plugin, TeamManager teams, TextService text, Supplier<GameFacade> game) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.text = Objects.requireNonNull(text, "text");
        this.game = Objects.requireNonNull(game, "game");
    }

    public void start() {
        if (scoreboardPollTask != null) {
            return;
        }
        scoreboardPollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pollScoreboards, 1L, 20L);
        requestRefresh();
    }

    /** Coalesces team and player state changes into one main-thread refresh. */
    public void requestRefresh() {
        if (!plugin.isEnabled() || pendingRefresh != null) {
            return;
        }
        pendingRefresh = plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingRefresh = null;
            refreshNow();
        });
    }

    public void refreshNow() {
        requirePrimaryThread();
        List<Player> onlinePlayers = List.copyOf(plugin.getServer().getOnlinePlayers());
        Map<UUID, PlayerStyle> styles = playerStyles(onlinePlayers);

        knownScoreboards.clear();
        for (Player player : onlinePlayers) {
            PlayerStyle style = styles.getOrDefault(player.getUniqueId(), PlayerStyle.unassigned());
            Component coloredName = Component.text(player.getName(), style.exactColor())
                .decoration(TextDecoration.ITALIC, false);
            player.displayName(coloredName);
            player.playerListName(tabListName(player, style));
            player.sendPlayerListHeaderAndFooter(tabHeader(player), tabFooter(player, style));
            knownScoreboards.put(player.getUniqueId(), player.getScoreboard());
        }

        Set<Scoreboard> scoreboards = Collections.newSetFromMap(new IdentityHashMap<>());
        onlinePlayers.stream().map(Player::getScoreboard).forEach(scoreboards::add);
        for (Scoreboard scoreboard : scoreboards) {
            synchronizeScoreboard(scoreboard, onlinePlayers, styles);
        }
    }

    private void pollScoreboards() {
        List<Player> onlinePlayers = List.copyOf(plugin.getServer().getOnlinePlayers());
        if (onlinePlayers.size() != knownScoreboards.size()) {
            requestRefresh();
            return;
        }
        for (Player player : onlinePlayers) {
            if (knownScoreboards.get(player.getUniqueId()) != player.getScoreboard()) {
                requestRefresh();
                return;
            }
        }
    }

    private Map<UUID, PlayerStyle> playerStyles(List<Player> onlinePlayers) {
        Map<UUID, PlayerStyle> styles = new LinkedHashMap<>();
        int teamIndex = 1;
        for (TeamData team : teams.teams()) {
            TextColor exact = TextColor.color(team.color().asRGB());
            String label = team.size() <= 1 ? "SOLO" : String.format(java.util.Locale.ROOT, "T%02d", teamIndex++);
            PlayerStyle style = new PlayerStyle(
                scoreboardTeamName(team.id()),
                exact,
                nearestVanillaColor(exact),
                label,
                team.size()
            );
            for (UUID member : team.members()) {
                styles.put(member, style);
            }
        }
        for (Player player : onlinePlayers) {
            styles.putIfAbsent(player.getUniqueId(), PlayerStyle.unassigned());
        }
        return Map.copyOf(styles);
    }

    private Component tabListName(Player player, PlayerStyle style) {
        return text.messageOr(player, "tab.player", DEFAULT_TAB_PLAYER, Map.of(
            "player", player.getName(),
            "color", style.exactColor().asHexString(),
            "team_label", style.teamLabel(),
            "team_size", style.teamSize()
        ));
    }

    private Component tabHeader(Player player) {
        List<Component> configured = text.messageLines(player, "tab.header", tabPlaceholders(player, PlayerStyle.unassigned()));
        if (!configured.isEmpty()) {
            return Component.join(JoinConfiguration.newlines(), configured);
        }
        return text.parse(player, DEFAULT_TAB_HEADER, tabPlaceholders(player, PlayerStyle.unassigned()));
    }

    private Component tabFooter(Player player, PlayerStyle style) {
        List<Component> configured = text.messageLines(player, "tab.footer", tabPlaceholders(player, style));
        if (!configured.isEmpty()) {
            return Component.join(JoinConfiguration.newlines(), configured);
        }
        return text.parse(player, DEFAULT_TAB_FOOTER, tabPlaceholders(player, style));
    }

    private Map<String, Object> tabPlaceholders(Player player, PlayerStyle style) {
        GameFacade current = game.get();
        GameState state = current == null ? GameState.BOOTSTRAPPING : current.state();
        int alive = current == null
            ? 0
            : (int) current.participants().stream().filter(club.mcqi.macesurvival.game.Participant::alive).count();
        return Map.of(
            "online", plugin.getServer().getOnlinePlayers().size(),
            "max", plugin.getServer().getMaxPlayers(),
            "phase", phaseName(state),
            "alive", state == GameState.WAITING || state == GameState.COUNTDOWN ? "-" : Integer.toString(alive),
            "server", plugin.getConfig().getString("scoreboard.server-address", "mcqi.top"),
            "player", player.getName(),
            "color", style.exactColor().asHexString(),
            "team_label", style.teamLabel(),
            "team_size", style.teamSize()
        );
    }

    private static String phaseName(GameState state) {
        return switch (state) {
            case BOOTSTRAPPING -> "BOOTING";
            case WAITING -> "LOBBY";
            case COUNTDOWN -> "STARTING";
            case BLACKOUT -> "BLACKOUT";
            case DEPLOYMENT -> "DROP";
            case ACTIVE -> "LIVE";
            case ENDING -> "ENDING";
        };
    }

    private static void synchronizeScoreboard(
        Scoreboard scoreboard,
        List<Player> onlinePlayers,
        Map<UUID, PlayerStyle> styles
    ) {
        Set<String> desiredTeams = new LinkedHashSet<>();
        styles.values().stream().map(PlayerStyle::scoreboardTeamName).forEach(desiredTeams::add);

        for (Team existing : new ArrayList<>(scoreboard.getTeams())) {
            if (existing.getName().startsWith(SCOREBOARD_TEAM_PREFIX)
                && !desiredTeams.contains(existing.getName())) {
                existing.unregister();
            }
        }

        Map<String, Team> presentationTeams = new HashMap<>();
        for (PlayerStyle style : styles.values()) {
            presentationTeams.computeIfAbsent(style.scoreboardTeamName(), teamName -> {
                Team team = scoreboard.getTeam(teamName);
                if (team == null) {
                    team = scoreboard.registerNewTeam(teamName);
                }
                team.color(style.vanillaColor());
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
                return team;
            });
        }

        for (Player player : onlinePlayers) {
            PlayerStyle style = styles.getOrDefault(player.getUniqueId(), PlayerStyle.unassigned());
            Team target = presentationTeams.get(style.scoreboardTeamName());
            if (target != null && !target.hasEntry(player.getName())) {
                target.addEntry(player.getName());
            }
        }
    }

    private static String scoreboardTeamName(UUID teamId) {
        String compactId = teamId.toString().replace("-", "");
        return SCOREBOARD_TEAM_PREFIX + compactId.substring(0, 13);
    }

    private static NamedTextColor nearestVanillaColor(TextColor exact) {
        NamedTextColor nearest = NamedTextColor.WHITE;
        long shortestDistance = Long.MAX_VALUE;
        for (NamedTextColor candidate : VANILLA_COLORS) {
            int candidateValue = candidate.value();
            int red = ((exact.value() >>> 16) & 0xFF) - ((candidateValue >>> 16) & 0xFF);
            int green = ((exact.value() >>> 8) & 0xFF) - ((candidateValue >>> 8) & 0xFF);
            int blue = (exact.value() & 0xFF) - (candidateValue & 0xFF);
            long distance = (long) red * red + (long) green * green + (long) blue * blue;
            if (distance < shortestDistance) {
                shortestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Player presentation must be refreshed from the server thread");
        }
    }

    @Override
    public void close() {
        if (scoreboardPollTask != null) {
            scoreboardPollTask.cancel();
            scoreboardPollTask = null;
        }
        if (pendingRefresh != null) {
            pendingRefresh.cancel();
            pendingRefresh = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Component plainName = Component.text(player.getName()).decoration(TextDecoration.ITALIC, false);
            player.displayName(plainName);
            player.playerListName(plainName);
            for (Team team : new ArrayList<>(player.getScoreboard().getTeams())) {
                if (team.getName().startsWith(SCOREBOARD_TEAM_PREFIX)) {
                    team.unregister();
                }
            }
        }
        knownScoreboards.clear();
    }

    private record PlayerStyle(
        String scoreboardTeamName,
        TextColor exactColor,
        NamedTextColor vanillaColor,
        String teamLabel,
        int teamSize
    ) {
        private PlayerStyle {
            Objects.requireNonNull(scoreboardTeamName, "scoreboardTeamName");
            Objects.requireNonNull(exactColor, "exactColor");
            Objects.requireNonNull(vanillaColor, "vanillaColor");
            Objects.requireNonNull(teamLabel, "teamLabel");
        }

        private static PlayerStyle unassigned() {
            return new PlayerStyle(UNASSIGNED_TEAM_NAME, NamedTextColor.WHITE, NamedTextColor.WHITE, "SOLO", 1);
        }
    }
}
