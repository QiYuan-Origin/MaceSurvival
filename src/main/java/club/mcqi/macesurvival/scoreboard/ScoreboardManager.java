package club.mcqi.macesurvival.scoreboard;

import club.mcqi.macesurvival.text.TextService;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns per-player sidebar scoreboards while the match is visible. */
public final class ScoreboardManager implements AutoCloseable {
    private static final String OBJECTIVE_NAME = "macesurvival";
    private static final String DEFAULT_TITLE =
        "<color:#E70939><shadow:#3A0612:1>ᴍᴀᴄᴇ ꜱᴜʀᴠɪᴠᴀʟ</shadow></color> "
            + "<dark_gray>#</dark_gray><white>{rank}</white>";
    private static final String DEFAULT_TIME_BORDER =
        "<color:#8bdcff>◷</color> <gray>{time}</gray> <dark_gray>•</dark_gray> <gray>Ring</gray> <color:#ffc857>{border}m</color>";
    private static final String DEFAULT_TIME_BORDER_MOVING =
        "<color:#8bdcff>◷</color> <gray>{time}</gray> <dark_gray>•</dark_gray> <gray>Ring</gray> <color:#ff4f68>{border}m</color>";
    private static final String DEFAULT_ALIVE =
        "<color:#a7efff>♥</color> <gray>Alive</gray> <white>{alive}</white>";
    private static final String DEFAULT_KILLS =
        "<color:#ffdf6b>⚔</color> <gray>Kills</gray> <white>{kills}</white> "
            + "<dark_gray>#</dark_gray><color:#ff4f68>{rank}</color>";
    private static final String DEFAULT_TEAM_KILLS =
        "<color:#91ff6d>⚑</color> <gray>Team</gray> <color:#5dff66>{kills}</color> "
            + "<dark_gray>#</dark_gray><white>{rank}</white>";
    private static final String DEFAULT_TEAMMATES =
        "<dark_gray>ᴛᴇᴀᴍ ᴛʀᴀᴄᴋ</dark_gray>";
    private static final String DEFAULT_TEAMMATE =
        "<white>{direction}</white> <{color}>{name}</{color}> <gray>{distance}m</gray> <color:#ff4f68>0%</color>";
    private static final String DEFAULT_TEAMMATE_UNKNOWN =
        "<dark_gray>-</dark_gray> <{color}>{name}</{color}> <gray>--m</gray> <color:#ff4f68>0%</color>";
    private static final String DEFAULT_TEAMMATE_DEAD =
        "<red><strikethrough>{name}</strikethrough></red> <dark_gray>down</dark_gray>";
    private static final String DEFAULT_SERVER =
        "<color:#9aa8ff><shadow:#202448:1>MCQI.TOP</shadow></color>";
    private static final String DEFAULT_LOBBY_STATUS =
        "<color:#8bdcff>◷</color> <gray>Queue</gray> <white>{waiting}</white><dark_gray>/</dark_gray><color:#91ff6d>{minimum}</color>";
    private static final String DEFAULT_LOBBY_NEEDED =
        "<color:#ffdf6b>!</color> <gray>Need:</gray> <color:#ff4f68>{needed}</color>";
    private static final String DEFAULT_LOBBY_COUNTDOWN =
        "<color:#ffdf6b>!</color> <gray>Drop:</gray> <color:#ff4f68>{seconds}s</color>";
    private static final String DEFAULT_LOBBY_TEAM =
        "<color:#91ff6d>⚑</color> <gray>Party:</gray> <{color}>{team_size}</{color}><dark_gray>/</dark_gray><white>{max_size}</white>";
    private static final String[] DIRECTION_ARROWS = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};

    private final TextService text;
    private final Map<UUID, BoardState> boards = new HashMap<>();

    public ScoreboardManager(TextService text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /** Creates or updates a stable sidebar. Call this from the server thread. */
    public void update(Player viewer, BoardSnapshot snapshot) {
        requirePrimaryThread();
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(snapshot, "snapshot");

        BoardState state = boards.computeIfAbsent(viewer.getUniqueId(), ignored -> createBoard(viewer, "--"));
        state.objective().displayName(text.messageOr(viewer, "scoreboard.title", DEFAULT_TITLE, Map.of(
            "rank", snapshot.playerKillRank()
        )));
        render(state, lines(viewer, snapshot));
        if (viewer.getScoreboard() != state.scoreboard()) {
            viewer.setScoreboard(state.scoreboard());
        }
    }

    /** Shows the waiting lobby sidebar instead of leaving players on an empty board. */
    public void updateLobby(Player viewer, LobbySnapshot snapshot) {
        requirePrimaryThread();
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(snapshot, "snapshot");

        BoardState state = boards.computeIfAbsent(viewer.getUniqueId(), ignored -> createBoard(viewer, "--"));
        state.objective().displayName(text.messageOr(viewer, "scoreboard.title", DEFAULT_TITLE, Map.of(
            "rank", "--"
        )));
        render(state, lobbyLines(viewer, snapshot));
        if (viewer.getScoreboard() != state.scoreboard()) {
            viewer.setScoreboard(state.scoreboard());
        }
    }

    /** Restores the scoreboard that was present before MaceSurvival took ownership. */
    public void clear(Player player) {
        requirePrimaryThread();
        Objects.requireNonNull(player, "player");
        BoardState state = boards.remove(player.getUniqueId());
        if (state != null && player.getScoreboard() == state.scoreboard()) {
            player.setScoreboard(state.previous());
        }
    }

    public boolean isShowing(UUID playerId) {
        return boards.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public void clearAll() {
        requirePrimaryThread();
        List<UUID> viewers = List.copyOf(boards.keySet());
        for (UUID playerId : viewers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                clear(player);
            } else {
                boards.remove(playerId);
            }
        }
    }

    @Override
    public void close() {
        clearAll();
    }

    private BoardState createBoard(Player viewer, String rank) {
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) {
            throw new IllegalStateException("Bukkit scoreboard manager is unavailable");
        }
        Scoreboard scoreboard = bukkitManager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            Criteria.DUMMY,
            text.messageOr(viewer, "scoreboard.title", DEFAULT_TITLE, Map.of("rank", rank))
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        return new BoardState(viewer.getScoreboard(), scoreboard, objective, 0);
    }

    private List<Component> lines(Player viewer, BoardSnapshot snapshot) {
        Map<String, Object> timeBorder = Map.of(
            "time", formatTime(snapshot.elapsed()),
            "border", snapshot.borderDistance()
        );
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(text.messageOr(
            viewer,
            snapshot.borderMoving() ? "scoreboard.time-border-moving" : "scoreboard.time-border",
            snapshot.borderMoving() ? DEFAULT_TIME_BORDER_MOVING : DEFAULT_TIME_BORDER,
            timeBorder
        ));
        lines.add(text.messageOr(viewer, "scoreboard.alive", DEFAULT_ALIVE, Map.of("alive", snapshot.alivePlayers())));
        lines.add(Component.empty());
        lines.add(text.messageOr(viewer, "scoreboard.kills", DEFAULT_KILLS, Map.of(
            "kills", snapshot.playerKills(),
            "rank", snapshot.playerKillRank()
        )));
        lines.add(text.messageOr(viewer, "scoreboard.team-kills", DEFAULT_TEAM_KILLS, Map.of(
            "kills", snapshot.teamKills(),
            "rank", snapshot.teamKillRank()
        )));

        List<TeammateView> teammates = snapshot.teammates().stream()
            .filter(teammate -> !teammate.playerId().equals(viewer.getUniqueId()))
            .limit(3)
            .toList();
        if (!teammates.isEmpty()) {
            lines.add(Component.empty());
            lines.add(text.messageOr(viewer, "scoreboard.teammates", DEFAULT_TEAMMATES, Map.of()));
            teammates.forEach(teammate -> lines.add(teammateLine(viewer, teammate)));
        }
        lines.add(Component.empty());
        lines.add(text.messageOr(viewer, "scoreboard.server", DEFAULT_SERVER, Map.of()));
        return List.copyOf(lines);
    }

    private List<Component> lobbyLines(Player viewer, LobbySnapshot snapshot) {
        String color = snapshot.teamColor().asHexString();
        int needed = Math.max(0, snapshot.minimumPlayers() - snapshot.waitingPlayers());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(text.messageOr(viewer, "scoreboard.lobby-status", DEFAULT_LOBBY_STATUS, Map.of(
            "phase", snapshot.phase(),
            "waiting", snapshot.waitingPlayers(),
            "minimum", snapshot.minimumPlayers(),
            "needed", needed
        )));
        lines.add(text.messageOr(viewer, "scoreboard.lobby-team", DEFAULT_LOBBY_TEAM, Map.of(
            "team_size", snapshot.teamSize(),
            "max_size", snapshot.maxTeamSize(),
            "color", color
        )));
        if (snapshot.countdownSeconds() > 0) {
            lines.add(text.messageOr(viewer, "scoreboard.lobby-countdown", DEFAULT_LOBBY_COUNTDOWN, Map.of(
                "seconds", snapshot.countdownSeconds()
            )));
        } else {
            lines.add(text.messageOr(viewer, "scoreboard.lobby-needed", DEFAULT_LOBBY_NEEDED, Map.of(
                "needed", needed
            )));
        }
        lines.add(Component.empty());
        lines.add(text.messageOr(viewer, "scoreboard.server", DEFAULT_SERVER, Map.of()));
        return List.copyOf(lines);
    }

    private Component teammateLine(Player viewer, TeammateView teammate) {
        String color = teammate.color().asHexString();
        if (!teammate.alive()) {
            return text.messageOr(viewer, "scoreboard.teammate-dead", DEFAULT_TEAMMATE_DEAD, Map.of(
                "name", teammate.name(),
                "color", color
            ));
        }

        Position target = teammate.position();
        if (target == null || !target.worldId().equals(viewer.getWorld().getUID())) {
            return text.messageOr(viewer, "scoreboard.teammate-unknown", DEFAULT_TEAMMATE_UNKNOWN, Map.of(
                "name", teammate.name(),
                "color", color
            ));
        }

        double deltaX = target.x() - viewer.getLocation().getX();
        double deltaZ = target.z() - viewer.getLocation().getZ();
        int distance = (int) Math.round(Math.hypot(deltaX, deltaZ));
        return text.messageOr(viewer, "scoreboard.teammate", DEFAULT_TEAMMATE, Map.of(
            "direction", direction(viewer.getLocation().getYaw(), deltaX, deltaZ),
            "distance", distance,
            "name", teammate.name(),
            "color", color
        ));
    }

    private static void render(BoardState state, List<Component> lines) {
        int total = lines.size();
        for (int index = 0; index < total; index++) {
            String entry = entry(index);
            Score score = state.objective().getScore(entry);
            score.customName(lines.get(index));
            score.numberFormat(NumberFormat.blank());
            score.setScore(total - index);
        }
        for (int index = total; index < state.renderedLines(); index++) {
            state.scoreboard().resetScores(entry(index));
        }
        state.renderedLines(total);
    }

    private static String entry(int index) {
        return "ms-line-" + index;
    }

    private static String direction(float viewerYaw, double deltaX, double deltaZ) {
        double targetYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double relative = normalizeDegrees(targetYaw - viewerYaw);
        int sector = Math.floorMod((int) Math.floor((relative + 22.5D) / 45.0D), DIRECTION_ARROWS.length);
        return DIRECTION_ARROWS[sector];
    }

    private static double normalizeDegrees(double angle) {
        double normalized = angle % 360.0D;
        if (normalized >= 180.0D) {
            normalized -= 360.0D;
        } else if (normalized < -180.0D) {
            normalized += 360.0D;
        }
        return normalized;
    }

    private static String formatTime(Duration elapsed) {
        long seconds = Math.max(0L, elapsed.toSeconds());
        long minutes = seconds / 60L;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60L);
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Scoreboards must be updated from the server thread");
        }
    }

    private static final class BoardState {
        private final Scoreboard previous;
        private final Scoreboard scoreboard;
        private final Objective objective;
        private int renderedLines;

        private BoardState(Scoreboard previous, Scoreboard scoreboard, Objective objective, int renderedLines) {
            this.previous = previous;
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.renderedLines = renderedLines;
        }

        private Scoreboard previous() {
            return previous;
        }

        private Scoreboard scoreboard() {
            return scoreboard;
        }

        private Objective objective() {
            return objective;
        }

        private int renderedLines() {
            return renderedLines;
        }

        private void renderedLines(int value) {
            renderedLines = value;
        }
    }

    /** Immutable display data supplied by the live game manager. */
    public record BoardSnapshot(
        Duration elapsed,
        String borderDistance,
        boolean borderMoving,
        int alivePlayers,
        int playerKills,
        int playerKillRank,
        int teamKills,
        int teamKillRank,
        List<TeammateView> teammates
    ) {
        public BoardSnapshot {
            elapsed = Objects.requireNonNull(elapsed, "elapsed");
            borderDistance = Objects.requireNonNull(borderDistance, "borderDistance");
            if (alivePlayers < 0 || playerKills < 0 || teamKills < 0) {
                throw new IllegalArgumentException("Scoreboard values cannot be negative");
            }
            if (playerKillRank < 1 || teamKillRank < 1) {
                throw new IllegalArgumentException("Scoreboard ranks start at one");
            }
            teammates = List.copyOf(Objects.requireNonNull(teammates, "teammates"));
        }
    }

    public record LobbySnapshot(
        String phase,
        int waitingPlayers,
        int minimumPlayers,
        int countdownSeconds,
        int teamSize,
        int maxTeamSize,
        TextColor teamColor
    ) {
        public LobbySnapshot {
            phase = Objects.requireNonNull(phase, "phase");
            if (waitingPlayers < 0 || minimumPlayers < 0 || countdownSeconds < 0
                || teamSize < 0 || maxTeamSize < 1) {
                throw new IllegalArgumentException("Lobby scoreboard values cannot be negative");
            }
            teamColor = Objects.requireNonNullElse(teamColor, NamedTextColor.WHITE);
        }
    }

    public record TeammateView(
        UUID playerId,
        String name,
        boolean alive,
        Position position,
        TextColor color
    ) {
        public TeammateView {
            playerId = Objects.requireNonNull(playerId, "playerId");
            name = Objects.requireNonNull(name, "name");
            color = Objects.requireNonNullElse(color, NamedTextColor.WHITE);
        }

        public static TeammateView alive(Player player, TextColor color) {
            Objects.requireNonNull(player, "player");
            return new TeammateView(
                player.getUniqueId(),
                player.getName(),
                true,
                Position.from(player.getLocation()),
                color
            );
        }

        public static TeammateView dead(UUID playerId, String name, TextColor color) {
            return new TeammateView(playerId, name, false, null, color);
        }
    }

    public record Position(UUID worldId, double x, double y, double z) {
        public Position {
            worldId = Objects.requireNonNull(worldId, "worldId");
        }

        public static Position from(Location location) {
            Objects.requireNonNull(location, "location");
            World world = Objects.requireNonNull(location.getWorld(), "location.world");
            return new Position(world.getUID(), location.getX(), location.getY(), location.getZ());
        }
    }
}
