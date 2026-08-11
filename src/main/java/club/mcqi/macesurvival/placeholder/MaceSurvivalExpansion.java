package club.mcqi.macesurvival.placeholder;

import club.mcqi.macesurvival.data.StatsStore;
import club.mcqi.macesurvival.game.GameFacade;
import club.mcqi.macesurvival.game.Participant;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Supplies live match and lifetime statistics to PlaceholderAPI. */
final class MaceSurvivalExpansion extends PlaceholderExpansion {
    private static final String IDENTIFIER = "macesurvival";

    private final JavaPlugin plugin;
    private final GameFacade game;
    private final TeamManager teams;
    private final StatsStore statistics;

    MaceSurvivalExpansion(
        JavaPlugin plugin,
        GameFacade game,
        TeamManager teams,
        StatsStore statistics
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.game = Objects.requireNonNull(game, "game");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        List<String> authors = plugin.getPluginMeta().getAuthors();
        return authors.isEmpty() ? "QiYuan-Origin" : String.join(", ", authors);
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String parameters) {
        String key = parameters.toLowerCase(Locale.ROOT);
        UUID playerId = player == null ? null : player.getUniqueId();
        return switch (key) {
            case "state" -> game.state().name().toLowerCase(Locale.ROOT);
            case "alive" -> Integer.toString(alivePlayers());
            case "is_alive" -> Boolean.toString(playerId != null && game.isAlive(playerId));
            case "kills" -> Integer.toString(currentKills(playerId));
            case "kill_rank" -> Integer.toString(playerKillRank(playerId));
            case "team_kills" -> Integer.toString(teamValue(playerId).kills());
            case "team_rank" -> Integer.toString(teamValue(playerId).rank());
            case "border" -> formatBorder(game.currentBorderRadius());
            case "time" -> formatTime(game.elapsedSeconds());
            case "team_size" -> Integer.toString(teamSize(playerId));
            case "stats_games" -> Integer.toString(stats(playerId).games());
            case "stats_wins" -> Integer.toString(stats(playerId).wins());
            case "stats_kills" -> Integer.toString(stats(playerId).kills());
            case "stats_deaths" -> Integer.toString(stats(playerId).deaths());
            case "stats_win_rate" -> formatWinRate(stats(playerId));
            case "stats_kd" -> formatKillDeathRatio(stats(playerId));
            default -> null;
        };
    }

    private int alivePlayers() {
        return (int) game.participants().stream().filter(Participant::alive).count();
    }

    private int currentKills(@Nullable UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        return game.participant(playerId).map(Participant::kills).orElse(0);
    }

    private int playerKillRank(@Nullable UUID playerId) {
        if (playerId == null || game.participant(playerId).isEmpty()) {
            return 0;
        }
        int ownKills = currentKills(playerId);
        return 1 + (int) game.participants().stream()
            .mapToInt(Participant::kills)
            .filter(kills -> kills > ownKills)
            .count();
    }

    private TeamValue teamValue(@Nullable UUID playerId) {
        if (playerId == null) {
            return TeamValue.NONE;
        }
        UUID ownTeam = teamId(playerId);
        Map<UUID, Integer> scores = teamScores(game.participants());
        if (!scores.containsKey(ownTeam)) {
            return TeamValue.NONE;
        }
        int ownKills = scores.get(ownTeam);
        int rank = 1 + (int) scores.values().stream().filter(kills -> kills > ownKills).count();
        return new TeamValue(ownKills, rank);
    }

    private Map<UUID, Integer> teamScores(Collection<Participant> participants) {
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        for (Participant participant : participants) {
            UUID teamId = teamId(participant.playerId());
            scores.putIfAbsent(teamId, 0);
            if (participant.alive()) {
                scores.merge(teamId, participant.kills(), Integer::sum);
            }
        }
        return scores;
    }

    private UUID teamId(UUID playerId) {
        return teams.teamOf(playerId).map(TeamData::id).orElse(playerId);
    }

    private int teamSize(@Nullable UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        return teams.teamOf(playerId).map(TeamData::size).orElse(1);
    }

    private StatsStore.PlayerStats stats(@Nullable UUID playerId) {
        return playerId == null ? StatsStore.PlayerStats.EMPTY : statistics.stats(playerId);
    }

    private static String formatBorder(double radius) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, radius));
    }

    private static String formatTime(long elapsedSeconds) {
        long seconds = Math.max(0L, elapsedSeconds);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String formatWinRate(StatsStore.PlayerStats value) {
        double percentage = value.games() == 0 ? 0.0D : value.wins() * 100.0D / value.games();
        return String.format(Locale.ROOT, "%.1f", percentage);
    }

    private static String formatKillDeathRatio(StatsStore.PlayerStats value) {
        double ratio = value.deaths() == 0 ? value.kills() : value.kills() / (double) value.deaths();
        return String.format(Locale.ROOT, "%.2f", ratio);
    }

    private record TeamValue(int kills, int rank) {
        private static final TeamValue NONE = new TeamValue(0, 0);
    }
}
