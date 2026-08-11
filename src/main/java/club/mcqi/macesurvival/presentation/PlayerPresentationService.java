package club.mcqi.macesurvival.presentation;

import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
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

/** Keeps team-colored player names synchronized across tab and per-viewer scoreboards. */
public final class PlayerPresentationService implements AutoCloseable {
    private static final String SCOREBOARD_TEAM_PREFIX = "msn";
    private static final String UNASSIGNED_TEAM_NAME = SCOREBOARD_TEAM_PREFIX + "_unassigned";
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
    private final Map<UUID, Scoreboard> knownScoreboards = new HashMap<>();
    private BukkitTask scoreboardPollTask;
    private BukkitTask pendingRefresh;

    public PlayerPresentationService(JavaPlugin plugin, TeamManager teams) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teams = Objects.requireNonNull(teams, "teams");
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
            player.playerListName(coloredName);
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
        for (TeamData team : teams.teams()) {
            TextColor exact = TextColor.color(team.color().asRGB());
            PlayerStyle style = new PlayerStyle(scoreboardTeamName(team.id()), exact, nearestVanillaColor(exact));
            for (UUID member : team.members()) {
                styles.put(member, style);
            }
        }
        for (Player player : onlinePlayers) {
            styles.putIfAbsent(player.getUniqueId(), PlayerStyle.unassigned());
        }
        return Map.copyOf(styles);
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
        NamedTextColor vanillaColor
    ) {
        private PlayerStyle {
            Objects.requireNonNull(scoreboardTeamName, "scoreboardTeamName");
            Objects.requireNonNull(exactColor, "exactColor");
            Objects.requireNonNull(vanillaColor, "vanillaColor");
        }

        private static PlayerStyle unassigned() {
            return new PlayerStyle(UNASSIGNED_TEAM_NAME, NamedTextColor.WHITE, NamedTextColor.WHITE);
        }
    }
}
