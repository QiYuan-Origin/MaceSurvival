package org.qiyuan.macesurvival.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.qiyuan.macesurvival.MaceSurvival;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家战绩。地图与对局状态会重置，但战绩必须跨局保留，
 * 所以落盘到 stats.yml，异步写，主线程只碰内存。
 */
public final class StatsManager {

    /** 一条战绩。胜场、击杀、场次、最高连杀、总伤害。 */
    public static final class Record {
        private final UUID uuid;
        private String name;
        private int wins;
        private int kills;
        private int deaths;
        private int games;
        private int bestStreak;
        private double damage;

        Record(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public int wins() {
            return wins;
        }

        public int kills() {
            return kills;
        }

        public int deaths() {
            return deaths;
        }

        public int games() {
            return games;
        }

        public int bestStreak() {
            return bestStreak;
        }

        public double damage() {
            return damage;
        }

        /** 一场没打完不算 K/D，直接给 0 比给 NaN 好。 */
        public double kd() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }

        public double winRate() {
            return games == 0 ? 0D : (double) wins / games * 100D;
        }
    }

    private final MaceSurvival plugin;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private File file;
    private long requestedWrite;
    private long completedWrite;
    private long loadGeneration;
    private boolean loaded;
    private record Snapshot(long sequence, long generation, Map<String, Object> values) { }

    public StatsManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public synchronized void load() {
        // Block both future saves and already-queued snapshots before attempting to read.
        // Failed loads retain the previous in-memory records and never authorize an empty overwrite.
        loaded = false;
        loadGeneration++;
        File candidate = new File(plugin.getDataFolder(), "stats.yml");
        YamlConfiguration data = new YamlConfiguration();
        Map<UUID, Record> parsed = new LinkedHashMap<>();
        try {
            Files.createDirectories(candidate.toPath().getParent());
            if (!Files.notExists(candidate.toPath())) data.load(candidate);
            if (data.contains("players") && !data.isConfigurationSection("players"))
                throw new InvalidConfigurationException("players must be a mapping");
            var players = data.getConfigurationSection("players");
            if (players != null) for (String rawId : players.getKeys(false)) {
                UUID uuid;
                try { uuid = UUID.fromString(rawId); }
                catch (IllegalArgumentException exception) {
                    throw new InvalidConfigurationException("Invalid player UUID: " + rawId, exception);
                }
                String path = "players." + rawId + ".";
                if (!data.isConfigurationSection("players." + rawId))
                    throw new InvalidConfigurationException("Player record must be a mapping: " + rawId);
                Record record = new Record(uuid, data.getString(path + "name", "Unknown"));
                record.wins = count(data, path + "wins");
                record.kills = count(data, path + "kills");
                record.deaths = count(data, path + "deaths");
                record.games = count(data, path + "games");
                record.bestStreak = count(data, path + "best-streak");
                Object damage = data.get(path + "damage");
                if (damage != null && (!(damage instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0))
                    throw new InvalidConfigurationException("Invalid nonnegative damage: " + path + "damage");
                record.damage = damage instanceof Number number ? number.doubleValue() : 0D;
                parsed.put(uuid, record);
            }
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot read stats.yml; existing records and file were left untouched", exception);
        }
        file = candidate;
        records.clear();
        records.putAll(parsed);
        loaded = true;
    }

    private static int count(YamlConfiguration data, String path) throws InvalidConfigurationException {
        Object value = data.get(path);
        if (value == null) return 0;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > Integer.MAX_VALUE
                || number.doubleValue() != Math.rint(number.doubleValue()))
            throw new InvalidConfigurationException("Invalid nonnegative integer: " + path);
        return number.intValue();
    }

    public Record of(UUID uuid, String name) {
        Record record = records.computeIfAbsent(uuid, id -> new Record(id, name));
        if (name != null && !name.isBlank()) {
            record.name = name;
        }
        return record;
    }

    /** 只有 UUID 时用。玩家在线就顺手把名字刷新一遍，离线则沿用上次记录的名字。 */
    public Record of(UUID uuid) {
        Player online = plugin.getServer().getPlayer(uuid);
        return of(uuid, online == null ? null : online.getName());
    }

    public Record get(UUID uuid) {
        return of(uuid);
    }

    public Record of(Player player) {
        return of(player.getUniqueId(), player.getName());
    }

    public Record peek(UUID uuid) {
        return records.get(uuid);
    }

    // ─────────────── 累计 ───────────────

    public void addKill(UUID uuid) {
        of(uuid).kills++;
    }

    /** currentStreak 是本局到目前为止的击杀数，用来刷新历史最高连杀。 */
    public void addKill(UUID uuid, int currentStreak) {
        Record record = of(uuid);
        record.kills++;
        if (currentStreak > record.bestStreak) {
            record.bestStreak = currentStreak;
        }
    }

    public void addDeath(UUID uuid) {
        of(uuid).deaths++;
    }

    public void addDamage(UUID uuid, double amount) {
        of(uuid).damage += amount;
    }

    /** 参与一局。淘汰、胜利、超时都算，旁观不算。 */
    public void addPlayed(UUID uuid) {
        of(uuid).games++;
    }

    public void addWin(UUID uuid) {
        of(uuid).wins++;
    }

    // ─────────────── 排行榜 ───────────────

    public enum Board {
        WINS("leaderboard.board.wins"),
        KILLS("leaderboard.board.kills"),
        KD("leaderboard.board.kd"),
        STREAK("leaderboard.board.streak"),
        GAMES("leaderboard.board.games");

        private final String messageKey;

        Board(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }

        public Board next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Board previous() {
            return values()[(ordinal() - 1 + values().length) % values().length];
        }
    }

    public List<Record> top(Board board, int limit) {
        Comparator<Record> comparator = switch (board) {
            case WINS -> Comparator.comparingInt(Record::wins);
            case KILLS -> Comparator.comparingInt(Record::kills);
            case KD -> Comparator.comparingDouble(Record::kd);
            case STREAK -> Comparator.comparingInt(Record::bestStreak);
            case GAMES -> Comparator.comparingInt(Record::games);
        };
        List<Record> sorted = new ArrayList<>(records.values());
        sorted.sort(comparator.reversed().thenComparing(Record::name));
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    public int rankOf(Board board, UUID uuid) {
        List<Record> sorted = top(board, Integer.MAX_VALUE);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }
        return 0;
    }

    // ─────────────── 落盘 ───────────────

    /** 结算时调用，提交当前战绩快照。 */
    public void saveAll() {
        saveAsync();
    }

    /** 异步保存。玩家退出和整局结束时各存一次，防止服务端重启丢数据。 */
    public void saveAsync() {
        Snapshot snapshot = snapshot();
        if (snapshot != null) plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeSnapshot(snapshot));
    }

    /** onDisable 时必须同步写，异步任务在关服时不会被执行。 */
    public void saveSync() {
        Snapshot snapshot = snapshot();
        if (snapshot != null) writeSnapshot(snapshot);
    }

    private synchronized Snapshot snapshot() {
        if (!loaded) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Record record : records.values()) {
            String path = "players." + record.uuid() + ".";
            out.put(path + "name", record.name());
            out.put(path + "wins", record.wins());
            out.put(path + "kills", record.kills());
            out.put(path + "deaths", record.deaths());
            out.put(path + "games", record.games());
            out.put(path + "best-streak", record.bestStreak());
            out.put(path + "damage", record.damage());
        }
        return new Snapshot(++requestedWrite, loadGeneration, out);
    }

    private synchronized void writeSnapshot(Snapshot snapshot) {
        if (!loaded || snapshot.generation() != loadGeneration || snapshot.sequence() <= completedWrite) return;
        YamlConfiguration out = new YamlConfiguration();
        snapshot.values().forEach(out::set);
        try {
            var temp = file.toPath().resolveSibling(file.getName() + ".tmp");
            out.save(temp.toFile());
            try {
                java.nio.file.Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                java.nio.file.Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            completedWrite = snapshot.sequence();
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
        }
    }
}
