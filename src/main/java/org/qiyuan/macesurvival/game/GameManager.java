package org.qiyuan.macesurvival.game;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.util.Sounds;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单端一局的总控。等待大厅 -> 倒计时 -> 跳伞 -> 对局 -> 结算 -> 重启。
 * 所有状态转移都必须经过这里，监听器只上报事件，不自己改状态。
 */
public final class GameManager {

    private final MaceSurvival plugin;
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();

    private GameState state = GameState.BOOTING;
    private BukkitTask ticker;
    private int countdown;
    private int elapsed;
    private boolean graceActive;

    public GameManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public GameState state() {
        return state;
    }

    public int countdown() {
        return countdown;
    }

    public int elapsed() {
        return elapsed;
    }

    public boolean graceActive() {
        return graceActive;
    }

    /** 对局剩余秒数，用于记分板。 */
    public int remaining() {
        return Math.max(0, cfg("game.time-limit", 1800) - elapsed);
    }

    // ─────────────── 生命周期 ───────────────

    /** 插件启用时调用。地图准备好之后进入等待大厅。 */
    public void boot() {
        state = GameState.BOOTING;
        plugin.arena().prepare(success -> {
            state = GameState.WAITING;
            startTicker();
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                join(online);
            }
        });
    }

    public void shutdown() {
        stopTicker();
        players.clear();
        state = GameState.BOOTING;
    }

    private void startTicker() {
        stopTicker();
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    /** 每秒一跳。倒计时、宽容期、时限都在这里推进。 */
    private void tick() {
        switch (state) {
            case WAITING -> tickWaiting();
            case COUNTDOWN -> tickCountdown();
            case RUNNING -> tickRunning();
            default -> {
                // BOOTING / DROPPING / ENDING 由各自的流程驱动，这里不介入
            }
        }
    }

    private void tickWaiting() {
        int lobby = lobbyCount();
        int min = cfg("game.min-players", 2);
        if (lobby >= min) {
            countdown = lobby >= cfg("game.max-players", 40)
                    ? cfg("game.full-lobby-countdown", 15)
                    : cfg("game.lobby-countdown", 60);
            state = GameState.COUNTDOWN;
            return;
        }
        // 只在人数变化时提示，避免每秒刷屏
    }

    private void tickCountdown() {
        int lobby = lobbyCount();
        if (lobby < cfg("game.min-players", 2)) {
            state = GameState.WAITING;
            countdown = 0;
            broadcastLobby("lobby.countdown-cancelled");
            return;
        }
        if (countdown <= 0) {
            beginDrop();
            return;
        }
        List<Integer> announce = plugin.configs().main().getIntegerList("game.countdown-announce-at");
        if (announce.contains(countdown)) {
            broadcastLobby("lobby.countdown", MessageManager.vars("seconds", String.valueOf(countdown)));
            for (GamePlayer gp : lobbyPlayers()) {
                Player online = gp.online();
                if (online != null) {
                    Sounds.play(plugin, online, countdown <= 3 ? Sounds.confirm() : Sounds.click());
                }
            }
        }
        countdown--;
    }

    private void tickRunning() {
        elapsed++;
        if (graceActive && elapsed >= cfg("game.grace-period", 20)) {
            graceActive = false;
            broadcastAll("game.grace-over");
        }
        if (elapsed >= cfg("game.time-limit", 1800)) {
            endByTimeout();
            return;
        }
        checkWinCondition();
    }

    // ─────────────── 进出 ───────────────

    /** 进服。游戏中进来的直接转旁观，绝不打断正在进行的对局。 */
    public void join(Player player) {
        GamePlayer gp = players.computeIfAbsent(player.getUniqueId(), id -> new GamePlayer(player));
        if (state.isInProgress()) {
            gp.role(PlayerRole.SPECTATOR);
            plugin.messages().send(player, "lobby.already-running");
            applySpectator(player);
            return;
        }
        if (lobbyCount() >= cfg("game.max-players", 40)) {
            gp.role(PlayerRole.SPECTATOR);
            plugin.messages().send(player, "lobby.full");
            applySpectator(player);
            return;
        }
        gp.resetForRound();
        applyLobby(player);
        broadcastLobby("lobby.join", MessageManager.vars(
                "player", player.getName(),
                "current", String.valueOf(lobbyCount()),
                "max", String.valueOf(cfg("game.max-players", 40))));
        int needed = cfg("game.min-players", 2) - lobbyCount();
        if (needed > 0) {
            plugin.messages().send(player, "lobby.waiting",
                    MessageManager.vars("needed", String.valueOf(needed)));
        }
    }

    /** 退服。对局中退出算淘汰，避免小队人数永远算不清。 */
    public void quit(Player player) {
        GamePlayer gp = players.get(player.getUniqueId());
        if (gp == null) {
            return;
        }
        if (state.acceptsPlayers()) {
            players.remove(player.getUniqueId());
            broadcastLobby("lobby.quit", MessageManager.vars(
                    "player", player.getName(),
                    "current", String.valueOf(lobbyCount()),
                    "max", String.valueOf(cfg("game.max-players", 40))));
            return;
        }
        if (gp.role().isPlaying()) {
            gp.role(PlayerRole.ELIMINATED);
            plugin.squads().onEliminated(gp);
            checkWinCondition();
        }
    }

    /** 大厅状态：清空背包、冒险模式、满血、无 buff。 */
    public void applyLobby(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0f);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        heal(player);
        clearEffects(player);
        player.setAllowFlight(false);
        player.setFlying(false);
        plugin.arena().teleportToLobby(player);
        plugin.loadout().giveLobbyItems(player);
    }

    /** 旁观：真旁观模式，可穿墙看，不参与碰撞。 */
    public void applySpectator(Player player) {
        player.getInventory().clear();
        clearEffects(player);
        player.setGameMode(GameMode.SPECTATOR);
        if (plugin.configs().main().getBoolean("game.spectator-fly", true)) {
            player.setAllowFlight(true);
        }
        plugin.arena().teleportToSpectate(player);
    }

    private void heal(Player player) {
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20.0 : max.getValue());
    }

    private void clearEffects(Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    // ─────────────── 开局 ───────────────

    /** 管理员强制开始。 */
    public void forceStart(String by) {
        if (state.isInProgress()) {
            return;
        }
        broadcastLobby("lobby.force-start", MessageManager.vars("player", by));
        beginDrop();
    }

    /** 管理员强制结束，直接走结算。 */
    public void forceStop(String by) {
        if (!state.isInProgress()) {
            return;
        }
        broadcastAll("lobby.force-stop", MessageManager.vars("player", by));
        endRound(null);
    }

    /**
     * 进入跳伞。黑屏遮住地图生成过程，生成完再传送到高空。
     * 这里不直接 setState(RUNNING)，落地由 DropManager 回调。
     */
    private void beginDrop() {
        state = GameState.DROPPING;
        countdown = 0;
        elapsed = 0;
        graceActive = true;

        Set<GamePlayer> participants = new LinkedHashSet<>(lobbyPlayers());
        if (participants.isEmpty()) {
            state = GameState.WAITING;
            return;
        }
        plugin.squads().assignSquads(participants);

        for (GamePlayer gp : participants) {
            gp.role(PlayerRole.DIVING);
            Player online = gp.online();
            if (online != null) {
                plugin.drop().blackout(online);
            }
        }
        broadcastAll("arena.generating");

        plugin.arena().generate(result -> {
            if (!result) {
                broadcastAll("arena.generate-failed", MessageManager.vars("error", "see console"));
                state = GameState.WAITING;
                return;
            }
            broadcastAll("arena.generated", MessageManager.vars(
                    "structures", String.valueOf(plugin.arena().structureCount()),
                    "chests", String.valueOf(plugin.arena().chestCount())));
            for (GamePlayer gp : participants) {
                Player online = gp.online();
                if (online == null) {
                    gp.role(PlayerRole.ELIMINATED);
                    continue;
                }
                plugin.loadout().giveStartingKit(online);
                plugin.drop().beginDive(online, gp);
            }
        });
    }

    /** DropManager 在最后一名玩家落地或超时后回调。 */
    public void onAllLanded() {
        if (state != GameState.DROPPING) {
            return;
        }
        state = GameState.RUNNING;
        elapsed = 0;
        graceActive = cfg("game.grace-period", 20) > 0;
        for (GamePlayer gp : playing()) {
            Player online = gp.online();
            if (online != null) {
                plugin.messages().sendTitle(online, "game.start-title", "game.start-subtitle");
            }
        }
    }

    /** 单个玩家落地。 */
    public void onLanded(GamePlayer gp) {
        gp.landed(true);
        gp.role(PlayerRole.ALIVE);
        Player online = gp.online();
        if (online != null) {
            Sounds.play(plugin, online, Sounds.landed());
            plugin.messages().send(online, "drop.landed");
        }
        if (playing().stream().allMatch(GamePlayer::landed)) {
            onAllLanded();
        }
    }

    // ─────────────── 淘汰与结算 ───────────────

    /** 玩家死亡，由 CombatListener 调用。装备掉落在监听器里处理。 */
    public void onDeath(GamePlayer victim, GamePlayer killer) {
        victim.role(PlayerRole.ELIMINATED);
        plugin.stats().addDeath(victim.uuid());

        if (killer != null && !killer.uuid().equals(victim.uuid())) {
            killer.addKill();
            plugin.stats().addKill(killer.uuid());
            Player online = killer.online();
            if (online != null) {
                Sounds.play(plugin, online, Sounds.kill());
                plugin.messages().send(online, "kill.killer-feedback", MessageManager.vars(
                        "victim", victim.name(),
                        "kills", String.valueOf(killer.kills())));
            }
            plugin.economy().reward(killer.uuid(), cfg("economy.reward-kill", 40));
        }

        Player victimOnline = victim.online();
        if (victimOnline != null) {
            plugin.messages().send(victimOnline, "kill.victim-feedback",
                    MessageManager.vars("killer", killer == null ? "the arena" : killer.name()));
            if (plugin.configs().main().getBoolean("death.spectate-on-death", true)) {
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> {
                            if (victimOnline.isOnline()) {
                                applySpectator(victimOnline);
                            }
                        }, 20L);
            }
        }
        plugin.squads().onEliminated(victim);
        checkWinCondition();
    }

    /** 剩一支小队就结束。小队模式和单人模式共用这套判定。 */
    private void checkWinCondition() {
        if (state != GameState.RUNNING) {
            return;
        }
        Set<String> aliveSquads = plugin.squads().aliveSquadIds();
        if (aliveSquads.size() > 1) {
            return;
        }
        if (aliveSquads.isEmpty()) {
            endRound(null);
            return;
        }
        endRound(aliveSquads.iterator().next());
    }

    private void endByTimeout() {
        broadcastAll("end.broadcast-timeout");
        String largest = plugin.squads().largestAliveSquad();
        endRound(largest);
    }

    /** winnerSquad 为 null 表示无人获胜。 */
    private void endRound(String winnerSquad) {
        if (state == GameState.ENDING) {
            return;
        }
        state = GameState.ENDING;

        Collection<GamePlayer> winners = winnerSquad == null
                ? List.of()
                : plugin.squads().membersOf(winnerSquad);

        if (winnerSquad != null && !winners.isEmpty()) {
            int totalKills = winners.stream().mapToInt(GamePlayer::kills).sum();
            broadcastAll("end.broadcast-winner", MessageManager.vars(
                    "winner", plugin.squads().displayName(winnerSquad),
                    "kills", String.valueOf(totalKills)));
        }

        for (GamePlayer gp : players.values()) {
            Player online = gp.online();
            if (online == null) {
                continue;
            }
            boolean won = winners.contains(gp);
            if (won) {
                plugin.messages().sendTitle(online, "end.win-title", "end.win-subtitle");
                Sounds.play(plugin, online, Sounds.victory());
                plugin.stats().addWin(gp.uuid());
                plugin.economy().reward(gp.uuid(), cfg("economy.reward-win", 250));
            } else if (winnerSquad == null) {
                plugin.messages().sendTitle(online, "end.draw-title", "end.draw-subtitle");
                Sounds.play(plugin, online, Sounds.defeat());
            } else {
                plugin.messages().sendTitle(online, "end.lose-title", "end.lose-subtitle");
                Sounds.play(plugin, online, Sounds.defeat());
            }
            if (gp.role() != PlayerRole.SPECTATOR) {
                plugin.stats().addPlayed(gp.uuid());
                plugin.economy().reward(gp.uuid(), cfg("economy.reward-participation", 10));
            }
            applySpectator(online);
        }
        plugin.stats().saveAll();
        scheduleRestart();
    }

    /** 单端模式靠重启重置地图和数据，不做原地清理。 */
    private void scheduleRestart() {
        int seconds = cfg("game.end-screen", 12);
        broadcastAll("end.restarting", MessageManager.vars("seconds", String.valueOf(seconds)));
        if (!plugin.configs().main().getBoolean("game.restart-on-end", true)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                players.values().forEach(GamePlayer::resetForRound);
                plugin.squads().clear();
                plugin.arena().prepare(ok -> {
                    state = GameState.WAITING;
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        join(online);
                    }
                });
            }, seconds * 20L);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getServer().restart(), seconds * 20L);
    }

    // ─────────────── 查询 ───────────────

    public GamePlayer of(Player player) {
        return players.get(player.getUniqueId());
    }

    public GamePlayer of(UUID uuid) {
        return players.get(uuid);
    }

    public Collection<GamePlayer> all() {
        return players.values();
    }

    public Set<GamePlayer> lobbyPlayers() {
        Set<GamePlayer> out = new LinkedHashSet<>();
        for (GamePlayer gp : players.values()) {
            if (gp.role() == PlayerRole.LOBBY && gp.isOnline()) {
                out.add(gp);
            }
        }
        return out;
    }

    public Set<GamePlayer> playing() {
        Set<GamePlayer> out = new LinkedHashSet<>();
        for (GamePlayer gp : players.values()) {
            if (gp.role().isPlaying()) {
                out.add(gp);
            }
        }
        return out;
    }

    public Set<GamePlayer> watching() {
        Set<GamePlayer> out = new LinkedHashSet<>();
        for (GamePlayer gp : players.values()) {
            if (gp.role().isWatching()) {
                out.add(gp);
            }
        }
        return out;
    }

    public int lobbyCount() {
        return lobbyPlayers().size();
    }

    public int aliveCount() {
        return playing().size();
    }

    // ─────────────── 广播 ───────────────

    private void broadcastLobby(String key) {
        broadcastLobby(key, Map.of());
    }

    private void broadcastLobby(String key, Map<String, String> vars) {
        var message = plugin.messages().get(key, vars);
        for (GamePlayer gp : lobbyPlayers()) {
            Player online = gp.online();
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    /** 全服广播，包含旁观者，这样观战的人也能跟上战况。 */
    public void broadcastAll(String key) {
        broadcastAll(key, Map.of());
    }

    public void broadcastAll(String key, Map<String, String> vars) {
        var message = plugin.messages().get(key, vars);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    private int cfg(String path, int fallback) {
        return plugin.configs().main().getInt(path, fallback);
    }




}
