package club.mcqi.macesurvival.data;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Thread-safe lifetime statistics with serialized, atomic YAML snapshot writes. */
public final class StatsStore implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(15);

    private final Object lock = new Object();
    private final Path dataFile;
    private final Logger logger;
    private final ExecutorService writer;
    private final Map<UUID, MutableStats> statistics = new HashMap<>();
    private boolean closed;

    public StatsStore(JavaPlugin plugin) {
        this(
            Objects.requireNonNull(plugin, "plugin").getDataFolder().toPath().resolve("stats.yml"),
            plugin.getLogger()
        );
    }

    public StatsStore(Path dataFile, Logger logger) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("MaceSurvival-stats-writer").factory()
        );
        load();
    }

    public PlayerStats stats(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            MutableStats stats = statistics.get(playerId);
            return stats == null ? PlayerStats.EMPTY : stats.snapshot();
        }
    }

    public Map<UUID, PlayerStats> snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    public void addGame(UUID playerId) {
        update(playerId, MutableStats::addGame);
    }

    public void addWin(UUID playerId) {
        update(playerId, MutableStats::addWin);
    }

    public void addKills(UUID playerId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Kill amount cannot be negative");
        }
        update(playerId, stats -> stats.addKills(amount));
    }

    public void addDeath(UUID playerId) {
        update(playerId, MutableStats::addDeath);
    }

    /** Records one completed match for a player in one lock acquisition. */
    public void recordGame(UUID playerId, boolean won, int kills, boolean died) {
        if (kills < 0) {
            throw new IllegalArgumentException("Kill amount cannot be negative");
        }
        update(playerId, stats -> stats.recordGame(won, kills, died));
    }

    /** Records a full match result and schedules one coherent disk snapshot. */
    public CompletableFuture<Void> recordGame(
        Collection<UUID> participants,
        Set<UUID> winners,
        Map<UUID, Integer> kills,
        Set<UUID> deaths
    ) {
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(winners, "winners");
        Objects.requireNonNull(kills, "kills");
        Objects.requireNonNull(deaths, "deaths");
        synchronized (lock) {
            ensureOpen();
            for (UUID playerId : participants) {
                int playerKills = Math.max(0, kills.getOrDefault(playerId, 0));
                statistics.computeIfAbsent(playerId, ignored -> new MutableStats())
                    .recordGame(winners.contains(playerId), playerKills, deaths.contains(playerId));
            }
        }
        return saveAsync();
    }

    public List<Map.Entry<UUID, PlayerStats>> leaderboard(StatField field, int limit) {
        Objects.requireNonNull(field, "field");
        if (limit < 0) {
            throw new IllegalArgumentException("Leaderboard limit cannot be negative");
        }
        Comparator<Map.Entry<UUID, PlayerStats>> comparator = Comparator
            .<Map.Entry<UUID, PlayerStats>>comparingInt(entry -> field.value(entry.getValue()))
            .reversed()
            .thenComparing(entry -> entry.getKey().toString());
        return snapshot().entrySet().stream().sorted(comparator).limit(limit).toList();
    }

    /** Uses competition ranking: equal values share the same rank. */
    public int rank(UUID playerId, StatField field) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(field, "field");
        Map<UUID, PlayerStats> current = snapshot();
        int ownValue = field.value(current.getOrDefault(playerId, PlayerStats.EMPTY));
        return 1 + (int) current.values().stream().mapToInt(field::value).filter(value -> value > ownValue).count();
    }

    /** Captures state before leaving the caller thread, then writes it on one I/O thread. */
    public CompletableFuture<Void> saveAsync() {
        synchronized (lock) {
            ensureOpen();
            Map<UUID, PlayerStats> stableSnapshot = snapshotLocked();
            return CompletableFuture.runAsync(() -> writeSnapshot(stableSnapshot), writer);
        }
    }

    /** Waits until all writes submitted before this call are durable. */
    public void flush() {
        saveAsync().join();
    }

    @Override
    public void close() {
        CompletableFuture<Void> finalWrite;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            Map<UUID, PlayerStats> finalSnapshot = snapshotLocked();
            finalWrite = CompletableFuture.runAsync(() -> writeSnapshot(finalSnapshot), writer);
        }

        try {
            finalWrite.join();
        } catch (CompletionException ignored) {
            // writeSnapshot already recorded the underlying I/O failure.
        } finally {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    logger.warning("Statistics writer did not stop within the shutdown timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.log(Level.WARNING, "Interrupted while stopping the statistics writer", exception);
            }
        }
    }

    private void load() {
        if (Files.notExists(dataFile)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Could not load player statistics without risking data loss", exception);
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        synchronized (lock) {
            for (String rawId : players.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(rawId);
                    String path = "players." + rawId + ".";
                    statistics.put(playerId, new MutableStats(
                        nonNegative(yaml.getInt(path + "games")),
                        nonNegative(yaml.getInt(path + "wins")),
                        nonNegative(yaml.getInt(path + "kills")),
                        nonNegative(yaml.getInt(path + "deaths"))
                    ));
                } catch (IllegalArgumentException exception) {
                    logger.warning("Ignoring invalid player UUID in stats.yml: " + rawId);
                }
            }
        }
    }

    private void writeSnapshot(Map<UUID, PlayerStats> stableSnapshot) {
        Path parent = dataFile.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
                temporary = Files.createTempFile(parent, "stats-", ".yml.tmp");
            } else {
                temporary = Files.createTempFile("stats-", ".yml.tmp");
            }

            YamlConfiguration yaml = new YamlConfiguration();
            stableSnapshot.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writePlayer(yaml, entry.getKey(), entry.getValue()));
            yaml.save(temporary.toFile());
            moveAtomically(temporary, dataFile);
            temporary = null;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save player statistics", exception);
            throw new CompletionException("Could not save player statistics", exception);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Could not encode player statistics", exception);
            throw exception;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    logger.log(Level.WARNING, "Could not remove temporary statistics file", exception);
                }
            }
        }
    }

    private static void writePlayer(YamlConfiguration yaml, UUID playerId, PlayerStats stats) {
        String path = "players." + playerId + ".";
        yaml.set(path + "games", stats.games());
        yaml.set(path + "wins", stats.wins());
        yaml.set(path + "kills", stats.kills());
        yaml.set(path + "deaths", stats.deaths());
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void update(UUID playerId, StatsUpdate update) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            ensureOpen();
            update.apply(statistics.computeIfAbsent(playerId, ignored -> new MutableStats()));
        }
    }

    private Map<UUID, PlayerStats> snapshotLocked() {
        Map<UUID, PlayerStats> snapshot = new LinkedHashMap<>();
        statistics.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().snapshot()));
        return Map.copyOf(snapshot);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("StatsStore is closed");
        }
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    @FunctionalInterface
    private interface StatsUpdate {
        void apply(MutableStats stats);
    }

    private static final class MutableStats {
        private int games;
        private int wins;
        private int kills;
        private int deaths;

        private MutableStats() {
        }

        private MutableStats(int games, int wins, int kills, int deaths) {
            this.games = games;
            this.wins = wins;
            this.kills = kills;
            this.deaths = deaths;
        }

        private void addGame() {
            games++;
        }

        private void addWin() {
            wins++;
        }

        private void addKills(int amount) {
            kills += amount;
        }

        private void addDeath() {
            deaths++;
        }

        private void recordGame(boolean won, int gameKills, boolean died) {
            addGame();
            if (won) {
                addWin();
            }
            addKills(gameKills);
            if (died) {
                addDeath();
            }
        }

        private PlayerStats snapshot() {
            return new PlayerStats(games, wins, kills, deaths);
        }
    }

    public record PlayerStats(int games, int wins, int kills, int deaths) {
        public static final PlayerStats EMPTY = new PlayerStats(0, 0, 0, 0);

        public PlayerStats {
            if (games < 0 || wins < 0 || kills < 0 || deaths < 0) {
                throw new IllegalArgumentException("Statistics cannot be negative");
            }
        }
    }

    public enum StatField {
        GAMES(PlayerStats::games),
        WINS(PlayerStats::wins),
        KILLS(PlayerStats::kills),
        DEATHS(PlayerStats::deaths);

        private final java.util.function.ToIntFunction<PlayerStats> extractor;

        StatField(java.util.function.ToIntFunction<PlayerStats> extractor) {
            this.extractor = extractor;
        }

        private int value(PlayerStats stats) {
            return extractor.applyAsInt(stats);
        }
    }
}
