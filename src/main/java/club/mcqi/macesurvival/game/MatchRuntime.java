package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.data.StatsStore;
import club.mcqi.macesurvival.loot.LootBridge;
import club.mcqi.macesurvival.scoreboard.ScoreboardManager;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class MatchRuntime implements MatchEvents, AutoCloseable {
    private final MaceSurvivalPlugin plugin;
    private final TextService text;
    private final StatsStore statistics;
    private final ScoreboardManager scoreboards;
    private final TeamManager teams;
    private final CombatManager combat;
    private GameFacade game;
    private LootBridge loot = LootBridge.NO_OP;
    private Consumer<Player> lobbySetup = player -> { };
    private BukkitTask scoreboardTask;

    public MatchRuntime(
            MaceSurvivalPlugin plugin,
            TextService text,
            StatsStore statistics,
            ScoreboardManager scoreboards,
            TeamManager teams,
            CombatManager combat
    ) {
        this.plugin = plugin;
        this.text = text;
        this.statistics = statistics;
        this.scoreboards = scoreboards;
        this.teams = teams;
        this.combat = combat;
    }

    public void bind(GameFacade game, LootBridge loot, Consumer<Player> lobbySetup) {
        this.game = game;
        this.loot = loot == null ? LootBridge.NO_OP : loot;
        this.lobbySetup = lobbySetup == null ? player -> { } : lobbySetup;
        if (scoreboardTask != null) scoreboardTask.cancel();
        int period = Math.max(1, plugin.getConfig().getInt("scoreboard.update-ticks", 10));
        scoreboardTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateScoreboards, period, period);
    }

    @Override
    public void setupLobby(Player player) {
        lobbySetup.accept(player);
        scoreboards.clear(player);
        text.sendPrefixed(player, "lobby.joined", Map.of());
        updateScoreboards();
    }

    @Override
    public void stateChanged(GameState state) {
        if (state == GameState.BLACKOUT || state == GameState.ENDING) {
            scoreboards.clearAll();
            return;
        }
        updateScoreboards();
    }

    @Override
    public void countdownChanged(int remainingSeconds) {
        if (!(remainingSeconds <= 5 || remainingSeconds == 10 || remainingSeconds == 30
                || remainingSeconds == 60 || remainingSeconds == 120)) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            text.sendPrefixed(player, "game.countdown", Map.of("seconds", remainingSeconds));
        }
    }

    @Override
    public void matchPrepared(Collection<Participant> participants) {
        combat.resetMatch();
        for (Participant participant : participants) combat.resetPlayer(participant.playerId());
    }

    @Override
    public void matchActive(org.bukkit.World world, Collection<Participant> participants) {
        loot.spawnInitial(world, aliveCount(participants));
    }

    @Override
    public void borderStageFinished(
            org.bukkit.World world,
            int stageIndex,
            Collection<Participant> participants
    ) {
        loot.removeOutside(world.getWorldBorder());
        loot.refresh(world, aliveCount(participants));
        for (Player player : world.getPlayers()) {
            text.sendPrefixed(player, "game.border-moving", Map.of("stage", stageIndex));
        }
    }

    @Override
    public void boundaryMoved(org.bukkit.WorldBorder border) {
        loot.removeOutside(border);
    }

    @Override
    public void playerEliminated(Player victim, Player killer, Participant participant) {
        String messageKey = deathMessageKey(killer);
        Map<String, Object> placeholders = new HashMap<>();
        placeholders.put("victim", victim.getName());
        placeholders.put("killer", killer == null ? "" : killer.getName());
        placeholders.put("victim_color", teamColor(victim.getUniqueId()).asHexString());
        placeholders.put("killer_color", killer == null ? "#ffffff" : teamColor(killer.getUniqueId()).asHexString());
        Component message = text.message(victim, messageKey, placeholders);
        plugin.getServer().broadcast(message);
        victim.showTitle(Title.title(
                text.message(victim, "title.defeat", Map.of()),
                text.message(victim, "title.defeat-subtitle", Map.of()),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
        ));
        victim.playSound(victim.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.0f, 0.8f);
    }

    @Override
    public void matchEnded(Set<UUID> winningTeam, Collection<Participant> participants) {
        Map<UUID, Integer> kills = new LinkedHashMap<>();
        Set<UUID> deaths = new LinkedHashSet<>();
        for (Participant participant : participants) {
            kills.put(participant.playerId(), participant.kills());
            if (!participant.alive()) deaths.add(participant.playerId());
        }
        statistics.recordGame(participants.stream().map(Participant::playerId).toList(), winningTeam, kills, deaths);
        List<Component> winnerNames = winningTeam.stream()
                .sorted()
                .<Component>map(playerId -> {
                    String name = Bukkit.getOfflinePlayer(playerId).getName();
                    return Component.text(name == null ? playerId.toString().substring(0, 8) : name,
                        teamColor(playerId));
                })
                .toList();
        Component announcement = winningTeam.isEmpty()
                ? text.message(null, "game.draw", Map.of())
                : text.messageWithComponents(
                    null,
                    "game.winner",
                    Map.of(),
                    Map.of("winner", Component.join(
                        JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)),
                        winnerNames
                    ))
                );
        plugin.getServer().broadcast(announcement);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean winner = winningTeam.contains(player.getUniqueId());
            if (winner) {
                player.showTitle(Title.title(
                        text.message(player, "title.victory", Map.of()),
                        text.message(player, "title.victory-subtitle", Map.of()),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(800))
                ));
            }
            playEndingSound(player);
        }
    }

    private void playEndingSound(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.15f, 0.9f);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 0.85f, 1.0f), 4L);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f), 11L);
    }

    @Override
    public void clearMatch() {
        loot.clear();
        combat.resetMatch();
        scoreboards.clearAll();
    }

    private void updateScoreboards() {
        if (game == null) return;
        if (game.state() == GameState.WAITING || game.state() == GameState.COUNTDOWN) {
            updateLobbyScoreboards();
            return;
        }
        if (game.state() != GameState.ACTIVE && game.state() != GameState.DEPLOYMENT) return;
        Collection<Participant> participants = game.participants();
        Map<UUID, Integer> playerRanks = competitionRanks(participants.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Participant::playerId,
                        Participant::kills,
                        (first, second) -> first,
                        LinkedHashMap::new
                )));
        Map<UUID, Integer> teamScores = teamScores(participants);
        Map<UUID, Integer> teamRanks = competitionRanks(teamScores);
        int alive = aliveCount(participants);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            Participant own = game.participant(viewer.getUniqueId()).orElse(null);
            if (own == null && viewer.getGameMode() != org.bukkit.GameMode.SPECTATOR) continue;
            TeamData team = teams.teamOf(viewer.getUniqueId()).orElse(null);
            UUID teamId = team == null ? viewer.getUniqueId() : team.id();
            int ownKills = own == null ? 0 : own.kills();
            scoreboards.update(viewer, new ScoreboardManager.BoardSnapshot(
                    Duration.ofSeconds(game.elapsedSeconds()),
                    Math.max(0.0, game.currentBorderRadius()),
                    game.state() == GameState.ACTIVE,
                    alive,
                    ownKills,
                    playerRanks.getOrDefault(viewer.getUniqueId(), 1),
                    teamScores.getOrDefault(teamId, 0),
                    teamRanks.getOrDefault(teamId, 1),
                    teammateViews(viewer, team)
            ));
        }
    }

    private void updateLobbyScoreboards() {
        org.bukkit.World lobbyWorld = game.lobbyLocation()
            .map(org.bukkit.Location::getWorld)
            .orElse(null);
        if (lobbyWorld == null) {
            return;
        }
        int waitingPlayers = (int) plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> player.getWorld().equals(lobbyWorld))
            .filter(player -> player.getGameMode() != org.bukkit.GameMode.SPECTATOR)
            .count();
        int minimum = Math.max(1, plugin.getConfig().getInt("match.min-players", 100));
        int countdown = game.state() == GameState.COUNTDOWN ? game.countdownRemainingSeconds() : 0;
        String phase = game.state() == GameState.COUNTDOWN ? "STARTING" : "LOBBY";
        for (Player viewer : lobbyWorld.getPlayers()) {
            if (viewer.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                scoreboards.clear(viewer);
                continue;
            }
            TeamData team = teams.teamOf(viewer.getUniqueId()).orElse(null);
            TextColor color = team == null ? TextColor.color(0xFFFFFF) : TextColor.color(team.color().asRGB());
            scoreboards.updateLobby(viewer, new ScoreboardManager.LobbySnapshot(
                phase,
                waitingPlayers,
                minimum,
                countdown,
                team == null ? 1 : team.size(),
                TeamManager.MAX_TEAM_SIZE,
                color
            ));
        }
    }

    private List<ScoreboardManager.TeammateView> teammateViews(Player viewer, TeamData team) {
        if (team == null) return List.of();
        TextColor color = TextColor.color(team.color().asRGB());
        List<ScoreboardManager.TeammateView> result = new ArrayList<>();
        for (UUID member : team.members()) {
            Player online = plugin.getServer().getPlayer(member);
            if (online != null && game.isAlive(member)) {
                result.add(ScoreboardManager.TeammateView.alive(online, color));
            } else {
                String name = Bukkit.getOfflinePlayer(member).getName();
                result.add(ScoreboardManager.TeammateView.dead(
                    member,
                    name == null ? member.toString().substring(0, 8) : name,
                    color
                ));
            }
        }
        return List.copyOf(result);
    }

    private Map<UUID, Integer> teamScores(Collection<Participant> participants) {
        Map<UUID, Integer> scores = new LinkedHashMap<>();
        for (Participant participant : participants) {
            TeamData team = teams.teamOf(participant.playerId()).orElse(null);
            UUID teamId = team == null ? participant.playerId() : team.id();
            scores.putIfAbsent(teamId, 0);
            if (participant.alive()) scores.merge(teamId, participant.kills(), Integer::sum);
        }
        return scores;
    }

    private static Map<UUID, Integer> competitionRanks(Map<UUID, Integer> scores) {
        Map<UUID, Integer> ranks = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            int rank = 1 + (int) scores.values().stream().filter(value -> value > entry.getValue()).count();
            ranks.put(entry.getKey(), rank);
        }
        return ranks;
    }

    private TextColor teamColor(UUID playerId) {
        return teams.teamOf(playerId)
                .map(team -> TextColor.color(team.color().asRGB()))
                .orElse(TextColor.color(0xFFFFFF));
    }

    private static int aliveCount(Collection<Participant> participants) {
        return (int) participants.stream().filter(Participant::alive).count();
    }

    private static String deathMessageKey(Player killer) {
        if (killer == null) return "death.generic";
        ItemStack item = killer.getInventory().getItemInMainHand();
        Material material = item.getType();
        if (material == Material.MACE) return "death.mace";
        if (material.name().endsWith("_SWORD")) return "death.sword";
        if (material.name().endsWith("_AXE")) return "death.axe";
        if (material.name().endsWith("_SPEAR")) return "death.spear";
        return "death.generic";
    }

    @Override
    public void close() {
        if (scoreboardTask != null) scoreboardTask.cancel();
        scoreboardTask = null;
        scoreboards.close();
    }
}
