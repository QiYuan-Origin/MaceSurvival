package org.qiyuan.macesurvival.game;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.*;

/** One server owns one round. Every asynchronous continuation is guarded by a round token. */
public final class GameManager {
    private final MaceSurvival plugin;
    private final Map<UUID, GamePlayer> players = new LinkedHashMap<>();
    private final Map<Integer, Squad> squads = new LinkedHashMap<>();
    private final Set<UUID> roundPlayers = new LinkedHashSet<>(), landed = new HashSet<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private GameState state = GameState.WAITING;
    private final BukkitTask ticker;
    private int countdown, elapsed, ending, nextSquadId = 1;
    private long generation;
    private boolean closing, borderStarted, autoStartPaused, mapPreparing;

    public GameManager(MaceSurvival plugin) {
        this.plugin = plugin;
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }
    public GameState state() { return state; }
    public GameState getState() { return state; }
    public int countdown() { return countdown; }
    public int elapsedSeconds() { return elapsed; }
    public boolean autoStartPaused() { return autoStartPaused; }
    public int graceRemaining() { return state == GameState.RUNNING ? Math.max(0, plugin.configs().main().getInt("game.grace-period", 20) - elapsed) : 0; }
    public int borderRemaining() { return Math.max(0, plugin.configs().main().getInt("arena.border-shrink-after", 300) - elapsed); }
    public boolean lobbyOpen() { return state == GameState.WAITING || state == GameState.COUNTDOWN; }
    public boolean active() { return state == GameState.DROPPING || state == GameState.RUNNING; }
    public GamePlayer getPlayer(UUID id) { return players.computeIfAbsent(id, GamePlayer::new); }
    public GamePlayer getPlayer(Player player) { return getPlayer(player.getUniqueId()); }
    public Collection<GamePlayer> allPlayers() { return List.copyOf(players.values()); }
    public Collection<Squad> squads() { return List.copyOf(squads.values()); }
    public int totalCount() { return (int) players.values().stream().filter(p -> p.isAlive() && Bukkit.getPlayer(p.uuid()) != null).count(); }
    public List<GamePlayer> getAlivePlayers() { return players.values().stream().filter(p -> roundPlayers.contains(p.uuid()) && p.isAlive() && Bukkit.getPlayer(p.uuid()) != null).toList(); }
    public int aliveCount() { return getAlivePlayers().size(); }
    public int aliveSquads() { return (int) getAlivePlayers().stream().map(this::side).distinct().count(); }
    private String side(GamePlayer p) { return p.squad() == null ? "p:" + p.uuid() : "s:" + p.squad().id(); }
    private long waitingSides() { return players.values().stream().filter(p -> p.isAlive() && Bukkit.getPlayer(p.uuid()) != null).map(this::side).distinct().count(); }
    private boolean eligible(GamePlayer p) { return p.isAlive() && Bukkit.getPlayer(p.uuid()) != null && (plugin.configs().main().getBoolean("squad.allow-solo", true) || p.squad() != null); }
    private boolean enoughPlayers() { return players.values().stream().filter(this::eligible).count() >= Math.max(2, plugin.configs().main().getInt("game.min-players", 2)) && players.values().stream().filter(this::eligible).map(this::side).distinct().count() >= 2; }

    public void addPlayer(Player player) {
        var gp = getPlayer(player);
        var record = plugin.stats().of(player);
        gp.loadTotals(record.kills(), record.deaths(), record.wins());
        player.closeInventory();
        gp.setRole(PlayerRole.SPECTATOR);
        if (lobbyOpen() && totalCount() < Math.max(2, plugin.configs().main().getInt("game.max-players", 64))) {
            gp.setRole(PlayerRole.PLAYER); applyLobby(player);
            plugin.messages().send(player, "join.welcome");
            if (!autoStartPaused && enoughPlayers()) startCountdown();
        } else {
            gp.setRole(PlayerRole.SPECTATOR); applySpectator(player);
            plugin.messages().send(player, lobbyOpen() ? "join.full" : "join.spectator-mode");
        }
    }
    public void removePlayer(UUID id) {
        var gp = players.get(id); if (gp == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null) { plugin.drops().cancel(player); plugin.input().release(player); plugin.enchants().clear(player); }
        if (active() && roundPlayers.contains(id) && gp.isAlive()) {
            gp.setRole(PlayerRole.SPECTATOR); gp.addDeath(); plugin.stats().addDeath(id);
            if (player != null) {
                for (var stack : player.getInventory().getContents()) if (stack != null && !stack.getType().isAir()) player.getWorld().dropItemNaturally(player.getLocation(), stack.clone());
                player.getInventory().clear();
            }
            tryFinishDrop(); checkWinCondition();
        } else if (lobbyOpen()) {
            detach(gp); players.remove(id);
            if (state == GameState.COUNTDOWN && !enoughPlayers()) cancelCountdown();
        }
        invites.remove(id); invites.values().removeIf(id::equals); plugin.stats().saveAsync();
    }
    public Location getLobbySpawn() { return plugin.lobby().spawn(); }
    private void resetPlayer(Player player) {
        player.setFireTicks(0); player.setFallDistance(0); player.setInvulnerable(false);
        player.setFlying(false); player.setAllowFlight(false); player.setGliding(false); player.setWalkSpeed(0.2f);
        player.setFoodLevel(20); player.setSaturation(20f); player.setExp(0); player.setLevel(0);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        var attr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (!player.isDead()) player.setHealth(attr == null ? 20 : attr.getValue());
    }
    private void applyLobby(Player player) {
        player.setGameMode(GameMode.ADVENTURE); resetPlayer(player); player.getInventory().clear();
        player.teleport(getLobbySpawn()); plugin.loadouts().giveLobbyItems(player); plugin.scoreboard().showLobbyBoard(player);
    }
    public void applySpectator(Player player) {
        player.getInventory().clear(); player.setGameMode(GameMode.SPECTATOR); player.setFallDistance(0);
        // Waiting spectators belong to the lobby, including while the previous arena retires.
        var center = lobbyOpen() ? null : plugin.arenas().getArenaCenter();
        player.teleport(center == null ? getLobbySpawn() : center.clone().add(0, 35, 0));
    }
    public boolean startCountdown() {
        if (state != GameState.WAITING || mapPreparing || !enoughPlayers()) return false;
        autoStartPaused = false;
        state = GameState.COUNTDOWN; countdown = Math.max(3, plugin.configs().main().getInt("game.lobby-countdown", 45));
        broadcast("countdown.remaining", Map.of("seconds", Integer.toString(countdown))); return true;
    }
    private void cancelCountdown() {
        if (state != GameState.COUNTDOWN) return;
        state = GameState.WAITING; countdown = 0; broadcast("countdown.cancelled", Map.of());
    }
    private void tick() {
        if (closing) return;
        switch (state) {
            case WAITING -> { if (!autoStartPaused && enoughPlayers()) startCountdown(); }
            case COUNTDOWN -> {
                if (!enoughPlayers()) { cancelCountdown(); return; }
                if (--countdown <= 0) prepareRound();
                else if (countdown <= 10 || countdown % 15 == 0) {
                    broadcast("countdown.remaining", Map.of("seconds", Integer.toString(countdown)));
                    if (countdown <= 5) for (Player p : Bukkit.getOnlinePlayers()) {
                        plugin.messages().title(p, "countdown.title", "countdown.subtitle", Map.of("seconds", Integer.toString(countdown)));
                        p.playSound(p.getLocation(), "block.note_block.hat", 0.7f, 1f);
                    }
                }
            }
            case RUNNING -> {
                elapsed++;
                if (elapsed == Math.max(1, plugin.configs().main().getInt("game.grace-period", 20))) broadcast("game.pvp-enabled", Map.of());
                if (!borderStarted && borderRemaining() == 0) {
                    borderStarted = true; var world = plugin.arenas().getArenaWorld();
                    if (world != null) world.getWorldBorder().changeSize(Math.max(12, plugin.configs().main().getDouble("arena.border-final-size", 24)), Math.max(5, plugin.configs().main().getInt("arena.border-shrink-duration", 600)));
                    broadcast("game.border-shrinking", Map.of());
                }
                if (elapsed >= Math.max(30, plugin.configs().main().getInt("game.time-limit", 1800))) finishRound(true); else checkWinCondition();
            }
            case ENDING -> { if (--ending <= 0) resetRound(); }
            default -> { }
        }
    }
    private void prepareRound() {
        if (!enoughPlayers()) { cancelCountdown(); return; }
        state = GameState.BOOTING; plugin.menus().closeAll(); plugin.input().clearAll();
        long token = ++generation; broadcast("game.preparing", Map.of()); var cfg = plugin.configs().main();
        long seed = "fixed".equalsIgnoreCase(cfg.getString("arena.seed-mode")) ? cfg.getLong("arena.seed", 20241121) : new Random().nextLong();
        (plugin.arenas().isReady() ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : plugin.arenas().prepareArena(seed)).whenComplete((ignored, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (closing || token != generation || state != GameState.BOOTING) return;
            if (failure != null) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Arena preparation failed", failure);
                broadcast("game.prepare-failed", Map.of()); resetRound(); return;
            }
            roundPlayers.clear();
            for (Player p : Bukkit.getOnlinePlayers()) if (eligible(getPlayer(p)) && roundPlayers.size() < Math.max(2, cfg.getInt("game.max-players", 64))) roundPlayers.add(p.getUniqueId());
            // A changed player limit can exclude the opposing side even though the lobby is ready.
            if (roundPlayers.size() < Math.max(2, cfg.getInt("game.min-players", 2))
                    || roundPlayers.stream().map(this::getPlayer).map(this::side).distinct().count() < 2) { resetRound(); return; }
            plugin.enchants().clearRound(); landed.clear(); elapsed = 0; borderStarted = false; state = GameState.DROPPING;
            // A lobby player can be ineligible (for example when solo entry is disabled).
            // Move every nonparticipant into the same real spectator flow as a mid-round join.
            for (Player p : Bukkit.getOnlinePlayers()) if (!roundPlayers.contains(p.getUniqueId())) {
                getPlayer(p).setRole(PlayerRole.SPECTATOR); applySpectator(p);
            }
            for (UUID id : roundPlayers) {
                getPlayer(id).resetRound(); plugin.stats().addPlayed(id); Player p = Bukkit.getPlayer(id);
                if (p != null) { resetPlayer(p); p.getInventory().clear(); plugin.drops().startDrop(p); }
            }
            plugin.stats().saveAsync();
        }));
    }
    public void onLanded(Player player) {
        if (!active() || !roundPlayers.contains(player.getUniqueId()) || !getPlayer(player).isAlive() || !landed.add(player.getUniqueId())) return;
        player.setGameMode(GameMode.SURVIVAL); plugin.loadouts().applyStartingKit(player);
        tryFinishDrop();
    }
    private void tryFinishDrop() {
        if (state == GameState.DROPPING && getAlivePlayers().stream().allMatch(p -> landed.contains(p.uuid()))) {
            state = GameState.RUNNING; elapsed = 0;
            broadcast("game.started", Map.of("seconds", Integer.toString(plugin.configs().main().getInt("game.grace-period", 20)))); checkWinCondition();
        }
    }
    /** Called only by the current drop session after its guarded teleport/timeout failure. */
    public void onDropFailed(UUID id) {
        if (state != GameState.DROPPING || !roundPlayers.contains(id) || !getPlayer(id).isAlive()) return;
        getPlayer(id).setRole(PlayerRole.SPECTATOR);
        Player p = Bukkit.getPlayer(id);
        if (p != null) { applySpectator(p); plugin.messages().send(p, "game.drop-failed"); }
        tryFinishDrop(); checkWinCondition();
    }
    public boolean participant(Player p) { return roundPlayers.contains(p.getUniqueId()) && getPlayer(p).isAlive(); }
    public boolean protectedPlayer(Player p) { return !active() || !participant(p) || state == GameState.DROPPING || graceRemaining() > 0 || plugin.drops().isDropping(p); }
    public void eliminate(Player player) {
        if (!active() || !participant(player)) return;
        getPlayer(player).setRole(PlayerRole.SPECTATOR); plugin.drops().cancel(player); plugin.enchants().clear(player);
        plugin.messages().title(player, "victory.defeat-title", "victory.defeat-subtitle", Map.of());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && active() && getPlayer(player).isSpectator() && !player.isDead()) applySpectator(player);
            tryFinishDrop(); checkWinCondition();
        });
    }
    public void checkWinCondition() { if (active() && aliveSquads() <= 1) finishRound(false); }
    /** Administrative abort never awards a win, and invalidates pending preparation/drop tasks. */
    public boolean stopGame() {
        if (state == GameState.WAITING) return false;
        autoStartPaused = true; mapPreparing = false;
        generation++; plugin.drops().cancelAll(); broadcast("game.aborted", Map.of()); resetRound(); return true;
    }
    public boolean prepareMap(org.bukkit.command.CommandSender sender) {
        if (state != GameState.WAITING || mapPreparing) return false;
        mapPreparing = true; long token = ++generation;
        plugin.messages().send(sender, "command.map.preparing");
        var cfg = plugin.configs().main();
        long seed = "fixed".equalsIgnoreCase(cfg.getString("arena.seed-mode")) ? cfg.getLong("arena.seed", 20241121) : new Random().nextLong();
        plugin.arenas().prepareArena(seed).whenComplete((unused, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (closing || token != generation) return;
            mapPreparing = false;
            plugin.messages().send(sender, failure == null ? "command.map.ready" : "game.prepare-failed");
        }));
        return true;
    }
    private void finishRound(boolean timedOut) {
        if (!active()) return;
        List<GamePlayer> alive = getAlivePlayers(); boolean won = !timedOut && !alive.isEmpty() && aliveSquads() == 1;
        state = GameState.ENDING; generation++; plugin.drops().cancelAll(); ending = Math.max(3, plugin.configs().main().getInt("game.end-screen", 10));
        for (UUID id : roundPlayers) plugin.economy().reward(id, plugin.configs().main().getDouble("economy.rewards.participation", 0));
        if (won) {
            GamePlayer first = alive.get(0); Set<UUID> winners = new LinkedHashSet<>();
            for (UUID id : roundPlayers) if (side(getPlayer(id)).equals(side(first))) winners.add(id);
            for (UUID id : winners) { getPlayer(id).addWin(); plugin.stats().addWin(id); plugin.economy().reward(id, plugin.configs().main().getDouble("economy.rewards.win", 50)); }
            String name = first.squad() == null ? Optional.ofNullable(Bukkit.getPlayer(first.uuid())).map(Player::getName).orElse("玩家") : first.squad().name();
            broadcast(first.squad() == null ? "game.winner-solo" : "game.winner-squad", Map.of("player", name, "squad", name));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (winners.contains(p.getUniqueId())) { plugin.messages().title(p, "victory.title", "victory.subtitle", Map.of()); p.playSound(p.getLocation(), "ui.toast.challenge_complete", 0.8f, 1f); }
                else plugin.messages().title(p, "game.finished-title", "game.finished-subtitle", Map.of("winner", name));
            }
        } else broadcast(timedOut ? "game.time-limit" : "game.no-winners", Map.of());
        plugin.stats().saveAsync(); plugin.economy().saveAsync();
    }
    private void resetRound() {
        generation++; plugin.drops().cancelAll(); plugin.input().clearAll(); plugin.menus().closeAll(); plugin.enchants().clearRound();
        roundPlayers.clear(); landed.clear(); countdown = elapsed = 0; state = GameState.WAITING;
        int seats = 0, maximum = Math.max(2, plugin.configs().main().getInt("game.max-players", 64));
        for (Player p : Bukkit.getOnlinePlayers()) {
            var gp = getPlayer(p); gp.resetRound();
            if (p.isDead()) p.spigot().respawn();
            if (seats++ < maximum) { gp.setRole(PlayerRole.PLAYER); applyLobby(p); }
            else { gp.setRole(PlayerRole.SPECTATOR); applySpectator(p); }
        }
        for (var gp : List.copyOf(players.values())) if (Bukkit.getPlayer(gp.uuid()) == null) { detach(gp); players.remove(gp.uuid()); }
        plugin.arenas().cleanup();
        // Server lifecycle belongs to its owner: round cleanup does not stop/restart the server.
    }
    public Squad createSquad(UUID leader, String name, String color) {
        Squad s = new Squad(nextSquadId++, leader, name, color); squads.put(s.id(), s); getPlayer(leader).setSquad(s); return s;
    }
    public boolean createTeam(Player p, String raw) {
        if (!canEditTeam(p)) return false;
        if (getPlayer(p).squad() != null) { plugin.messages().send(p, "squad.already-in-team"); return false; }
        String name = raw == null ? "" : raw.trim();
        if (!name.matches("[\\p{L}\\p{N}_ -]{1,20}")) { plugin.messages().send(p, "squad.invalid-name"); return false; }
        if (squads.values().stream().anyMatch(s -> s.name().equalsIgnoreCase(name))) { plugin.messages().send(p, "squad.name-exists"); return false; }
        String[] palette = {"#7FC9D9", "#E9C876", "#B4D8C0", "#D9A0BC", "#AEBEF0", "#E9AE83", "#B7D184", "#C4A2DF"};
        createSquad(p.getUniqueId(), name, palette[Math.floorMod(nextSquadId - 1, palette.length)]); plugin.messages().send(p, "squad.created", Map.of("name", name)); return true;
    }
    public boolean joinTeam(Player p, int id) {
        if (!canEditTeam(p)) return false; var team = squads.get(id);
        if (team == null) { plugin.messages().send(p, "squad.not-found"); return false; }
        if (getPlayer(p).squad() != null) { plugin.messages().send(p, "squad.already-in-team"); return false; }
        if (team.size() >= Math.max(1, Math.min(16, plugin.configs().main().getInt("squad.size", 4)))) { plugin.messages().send(p, "squad.full"); return false; }
        team.addMember(p.getUniqueId()); getPlayer(p).setSquad(team); plugin.messages().send(p, "squad.joined", Map.of("name", team.name())); return true;
    }
    public boolean leaveTeam(Player p) {
        if (!canEditTeam(p)) return false;
        if (getPlayer(p).squad() == null) { plugin.messages().send(p, "squad.not-in-team"); return false; }
        detach(getPlayer(p)); plugin.messages().send(p, "squad.left"); return true;
    }
    private void detach(GamePlayer gp) { var s = gp.squad(); if (s != null) { s.removeMember(gp.uuid()); if (s.isEmpty()) squads.remove(s.id()); gp.setSquad(null); } }
    public boolean canEditTeam(Player p) { if (lobbyOpen()) return true; plugin.messages().send(p, "menu.lobby-only"); return false; }
    public void beginTeamInput(Player p) { if (canEditTeam(p)) plugin.input().request(p, "team-create"); }
    public boolean invite(Player p, Player target) {
        if (!canEditTeam(p)) return false; Squad team = getPlayer(p).squad();
        if (team == null || !team.leader().equals(p.getUniqueId())) { plugin.messages().send(p, "squad.leader-only"); return false; }
        if (target.equals(p) || getPlayer(target).squad() != null) { plugin.messages().send(p, "squad.invite-invalid"); return false; }
        invites.put(target.getUniqueId(), p.getUniqueId());
        plugin.messages().send(target, "squad.invited", Map.of("player", p.getName(), "name", team.name()));
        plugin.messages().send(p, "squad.invite-sent", Map.of("player", target.getName())); return true;
    }
    public boolean accept(Player p) {
        UUID leader = invites.remove(p.getUniqueId()); Squad team = leader == null ? null : getPlayer(leader).squad();
        if (team == null) { plugin.messages().send(p, "squad.no-invite"); return false; } return joinTeam(p, team.id());
    }
    private void broadcast(String key, Map<String, String> vars) { Bukkit.broadcast(plugin.messages().prefixed(key, vars)); }
    public void shutdown() {
        closing = true; generation++; ticker.cancel(); plugin.drops().cancelAll(); plugin.input().clearAll(); plugin.menus().closeAll();
        for (Player p : Bukkit.getOnlinePlayers()) { p.teleport(getLobbySpawn()); p.setGameMode(GameMode.ADVENTURE); }
        plugin.arenas().cleanup();
    }
}
