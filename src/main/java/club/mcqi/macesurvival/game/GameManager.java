package club.mcqi.macesurvival.game;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.world.WorldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GameManager implements GameFacade, BorderController.Listener, DeploymentController.Listener {
    private final MaceSurvivalPlugin plugin;
    private final WorldManager worldManager;
    private final TeamManager teamManager;
    private final CombatManager combat;
    private final MatchEvents events;
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Map<UUID, DisconnectSnapshot> disconnectSnapshots = new HashMap<>();
    private final BorderController borderController;
    private final DeploymentController deploymentController;
    private GameState state = GameState.BOOTSTRAPPING;
    private BukkitTask heartbeat;
    private int countdownRemaining;
    private boolean forcedCountdown;
    private int initialTeamCount;
    private Instant matchStartedAt;

    public GameManager(
            MaceSurvivalPlugin plugin,
            WorldManager worldManager,
            TeamManager teamManager,
            LoadoutLayoutManager loadoutLayouts,
            CombatManager combat,
            MatchEvents events
    ) {
        this.plugin = plugin;
        this.worldManager = worldManager;
        this.teamManager = teamManager;
        this.combat = combat;
        this.events = events;
        this.borderController = new BorderController(plugin, this);
        this.deploymentController = new DeploymentController(
                plugin, worldManager, teamManager, loadoutLayouts, combat, this);
    }

    public void enable() {
        worldManager.prepareLobby();
        worldManager.prepareMatch();
        changeState(GameState.WAITING);
        heartbeat = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
        for (Player player : plugin.getServer().getOnlinePlayers()) handleJoin(player);
    }

    public void disable() {
        if (heartbeat != null) heartbeat.cancel();
        heartbeat = null;
        borderController.stop();
        deploymentController.stop();
    }

    private void tickSecond() {
        expireDisconnectedPlayers();
        switch (state) {
            case WAITING -> {
                int minimum = plugin.getConfig().getInt("match.min-players", 100);
                if (eligibleOnlinePlayers().size() >= minimum) {
                    beginCountdown(plugin.getConfig().getInt("match.waiting-countdown-seconds", 120), false);
                }
            }
            case COUNTDOWN -> tickCountdown();
            case ACTIVE -> {
                if (initialTeamCount == 1
                        && borderController.elapsedSeconds() >= plugin.getConfig().getInt("match.duration-seconds", 1500)) {
                    checkForWinner();
                }
            }
            default -> { }
        }
    }

    private void tickCountdown() {
        int minimum = plugin.getConfig().getInt("match.min-players", 100);
        if (!forcedCountdown && eligibleOnlinePlayers().size() < minimum) {
            forcedCountdown = false;
            countdownRemaining = 0;
            changeState(GameState.WAITING);
            return;
        }
        countdownRemaining--;
        events.countdownChanged(countdownRemaining);
        if (countdownRemaining <= 0) beginMatch();
    }

    private List<Player> eligibleOnlinePlayers() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        players.removeIf(player -> !player.getWorld().equals(worldManager.lobbyWorld())
                || player.getGameMode() == GameMode.SPECTATOR);
        return List.copyOf(players);
    }

    private void beginCountdown(int seconds, boolean forced) {
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) return;
        forcedCountdown = forced;
        countdownRemaining = Math.max(1, seconds);
        changeState(GameState.COUNTDOWN);
        events.countdownChanged(countdownRemaining);
    }

    private void beginMatch() {
        List<Player> players = eligibleOnlinePlayers();
        if (players.isEmpty()) {
            forcedCountdown = false;
            changeState(GameState.WAITING);
            return;
        }
        participants.clear();
        disconnectSnapshots.clear();
        for (Player player : players) {
            teamManager.ensureSoloTeam(player.getUniqueId());
            participants.put(player.getUniqueId(), new Participant(player.getUniqueId()));
        }
        initialTeamCount = (int) participants.keySet().stream()
                .map(playerId -> teamManager.teamOf(playerId).orElseThrow().id())
                .distinct()
                .count();
        teamManager.setManuallyLocked(true);
        events.matchPrepared(participants());
        changeState(GameState.BLACKOUT);
        for (Player player : players) {
            player.closeInventory();
            player.setInvulnerable(true);
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 1, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false, false));
            player.showTitle(Title.title(
                    Component.empty(),
                    Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ZERO)
            ));
        }
        int blackoutTicks = plugin.getConfig().getInt("deployment.blackout-ticks", 40);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::beginDeployment, blackoutTicks);
    }

    private void beginDeployment() {
        if (state != GameState.BLACKOUT) return;
        changeState(GameState.DEPLOYMENT);
        deploymentController.prepareAndDeploy(participants()).exceptionally(exception -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not prepare deployment", exception);
            plugin.getServer().getScheduler().runTask(plugin, () -> stopMatch(true));
            return null;
        });
    }

    @Override
    public void onDeploymentFinished() {
        if (state != GameState.DEPLOYMENT) return;
        matchStartedAt = Instant.now();
        changeState(GameState.ACTIVE);
        borderController.start(worldManager.matchWorld());
        for (Participant participant : participants.values()) {
            Player player = plugin.getServer().getPlayer(participant.playerId());
            if (player != null && deploymentController.hasLanded(player.getUniqueId())) {
                player.setInvulnerable(false);
            }
        }
        events.matchActive(worldManager.matchWorld(), participants());
        checkForWinner();
    }

    @Override
    public void onPlayerLanded(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 1.0f);
    }

    @Override
    public void onStageFinished(int stageIndex) {
        events.borderStageFinished(worldManager.matchWorld(), stageIndex, participants());
    }

    @Override
    public void onBoundaryMoved(org.bukkit.WorldBorder border) {
        events.boundaryMoved(border);
    }

    @Override
    public boolean forceStart(int countdownSeconds) {
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) return false;
        beginCountdown(Math.max(1, countdownSeconds), true);
        return true;
    }

    @Override
    public void stopMatch(boolean announce) {
        if (state == GameState.WAITING || state == GameState.BOOTSTRAPPING) return;
        if (announce) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.text().sendPrefixed(player, "game.stopped", Map.of());
            }
        }
        resetToLobby();
    }

    @Override
    public void handleJoin(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null && participant.alive() && participant.disconnectedAt() != null) {
            long elapsed = Duration.between(participant.disconnectedAt(), Instant.now()).toSeconds();
            int grace = plugin.getConfig().getInt("match.disconnect-grace-seconds", 240);
            if (elapsed <= grace && canRestoreDisconnectedParticipant()) {
                participant.setDisconnectedAt(null);
                DisconnectSnapshot snapshot = disconnectSnapshots.remove(player.getUniqueId());
                player.setGameMode(GameMode.SURVIVAL);
                if (snapshot != null) snapshot.restore(player);
                if (state == GameState.BLACKOUT) {
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setInvulnerable(true);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false, false));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
                } else if (state == GameState.DEPLOYMENT
                        || (state == GameState.ACTIVE
                        && !deploymentController.hasLanded(player.getUniqueId()))) {
                    player.setInvulnerable(true);
                    deploymentController.restoreDisconnected(player);
                } else {
                    player.setInvulnerable(false);
                }
                plugin.text().sendPrefixed(player, "game.reconnect-restored", Map.of());
                return;
            }
        }
        if (state == GameState.WAITING || state == GameState.COUNTDOWN) {
            setupLobbyPlayer(player);
        } else {
            setupSpectator(player);
        }
    }

    private boolean canRestoreDisconnectedParticipant() {
        return state == GameState.BLACKOUT || state == GameState.DEPLOYMENT || state == GameState.ACTIVE;
    }

    private void setupLobbyPlayer(Player player) {
        player.closeInventory();
        player.teleport(worldManager.lobbyLocation());
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(false);
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        events.setupLobby(player);
    }

    private void setupSpectator(Player player) {
        player.closeInventory();
        player.setGameMode(GameMode.SPECTATOR);
        player.setInvulnerable(true);
        World world = worldManager.matchWorld();
        Location location = world.getWorldBorder().getCenter();
        location.setY(world.getHighestBlockYAt(location) + 20.0);
        player.teleport(location);
    }

    @Override
    public void handleQuit(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || !participant.alive()
                || (state != GameState.BLACKOUT && state != GameState.ACTIVE
                && state != GameState.DEPLOYMENT)) return;
        participant.setDisconnectedAt(Instant.now());
        disconnectSnapshots.put(player.getUniqueId(), DisconnectSnapshot.capture(player));
    }

    private void expireDisconnectedPlayers() {
        if (state != GameState.BLACKOUT && state != GameState.ACTIVE
                && state != GameState.DEPLOYMENT) return;
        Instant now = Instant.now();
        int grace = plugin.getConfig().getInt("match.disconnect-grace-seconds", 240);
        for (Participant participant : List.copyOf(participants.values())) {
            Instant disconnected = participant.disconnectedAt();
            if (!participant.alive() || disconnected == null) continue;
            if (Duration.between(disconnected, now).toSeconds() < grace) continue;
            DisconnectSnapshot snapshot = disconnectSnapshots.remove(participant.playerId());
            if (snapshot != null) snapshot.dropContents();
            participant.setAlive(false);
            participant.setDisconnectedAt(null);
        }
        checkForWinner();
    }

    @Override
    public void eliminate(Player victim, Player killer) {
        Participant participant = participants.get(victim.getUniqueId());
        if (participant == null || !participant.alive()) return;
        participant.setAlive(false);
        participant.setDisconnectedAt(null);
        disconnectSnapshots.remove(victim.getUniqueId());
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            Participant killerParticipant = participants.get(killer.getUniqueId());
            if (killerParticipant != null && killerParticipant.alive()) {
                killerParticipant.addKill();
                combat.recordKill(killer);
            }
        }
        events.playerEliminated(victim, killer, participant);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (victim.isOnline()) setupSpectator(victim);
            checkForWinner();
        });
    }

    public void recordAttack(Player attacker, Player victim) {
        if (state != GameState.ACTIVE || attacker.getUniqueId().equals(victim.getUniqueId())
                || sameTeam(attacker.getUniqueId(), victim.getUniqueId())) {
            return;
        }
        Participant target = participants.get(victim.getUniqueId());
        Participant source = participants.get(attacker.getUniqueId());
        if (target != null && target.alive() && source != null && source.alive()) {
            target.recordAttacker(attacker.getUniqueId(), Instant.now());
        }
    }

    public Player resolveKiller(Player victim) {
        Player direct = victim.getKiller();
        if (direct != null && isAlive(direct.getUniqueId())
                && !sameTeam(direct.getUniqueId(), victim.getUniqueId())) {
            return direct;
        }
        Participant participant = participants.get(victim.getUniqueId());
        if (participant == null || participant.lastAttacker() == null || participant.lastAttackedAt() == null) {
            return null;
        }
        int window = Math.max(1, plugin.getConfig().getInt("combat.kill-credit-seconds", 30));
        if (Duration.between(participant.lastAttackedAt(), Instant.now()).toSeconds() > window) {
            return null;
        }
        Player credited = plugin.getServer().getPlayer(participant.lastAttacker());
        return credited != null && isAlive(credited.getUniqueId()) ? credited : null;
    }

    private void checkForWinner() {
        if (state != GameState.ACTIVE) return;
        Map<UUID, Set<UUID>> aliveTeams = new LinkedHashMap<>();
        for (Participant participant : participants.values()) {
            if (!participant.alive()) continue;
            TeamData team = teamManager.teamOf(participant.playerId())
                    .orElseGet(() -> teamManager.ensureSoloTeam(participant.playerId()));
            aliveTeams.computeIfAbsent(team.id(), ignored -> new LinkedHashSet<>()).add(participant.playerId());
        }
        if (aliveTeams.size() > 1) return;
        if (initialTeamCount == 1
                && borderController.elapsedSeconds() < plugin.getConfig().getInt("match.duration-seconds", 1500)) {
            return;
        }
        Set<UUID> winners = aliveTeams.values().stream().findFirst().orElseGet(Set::of);
        endMatch(winners);
    }

    private void endMatch(Set<UUID> winningTeam) {
        if (state == GameState.ENDING) return;
        changeState(GameState.ENDING);
        borderController.stop();
        deploymentController.stop();
        events.matchEnded(Set.copyOf(winningTeam), participants());
        int delay = plugin.getConfig().getInt("server.shutdown-delay-seconds", 8);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getConfig().getBoolean("server.shutdown-after-match", true)) {
                plugin.getServer().shutdown();
            } else {
                resetToLobby();
            }
        }, Math.max(1, delay) * 20L);
    }

    private void resetToLobby() {
        borderController.stop();
        deploymentController.stop();
        events.clearMatch();
        participants.clear();
        disconnectSnapshots.clear();
        teamManager.clear();
        forcedCountdown = false;
        countdownRemaining = 0;
        initialTeamCount = 0;
        matchStartedAt = null;
        changeState(GameState.WAITING);
        for (Player player : plugin.getServer().getOnlinePlayers()) setupLobbyPlayer(player);
        worldManager.resetMatch();
    }

    private void changeState(GameState next) {
        state = next;
        events.stateChanged(next);
    }

    public boolean handleDismount(Player player, org.bukkit.entity.Entity vehicle) {
        return deploymentController.handleDismount(player, vehicle);
    }

    public boolean isDeploymentFallProtected(UUID playerId) {
        return deploymentController.isDropping(playerId);
    }

    @Override public GameState state() { return state; }
    @Override public boolean isAlive(UUID playerId) {
        Participant participant = participants.get(playerId);
        return participant != null && participant.alive();
    }
    @Override public boolean isParticipant(UUID playerId) { return participants.containsKey(playerId); }
    @Override public boolean sameTeam(UUID first, UUID second) { return teamManager.areTeamMates(first, second); }
    @Override public Optional<Participant> participant(UUID playerId) {
        return Optional.ofNullable(participants.get(playerId));
    }
    @Override public Collection<Participant> participants() { return List.copyOf(participants.values()); }
    @Override public Optional<Location> lobbyLocation() {
        return state == GameState.BOOTSTRAPPING ? Optional.empty() : Optional.of(worldManager.lobbyLocation());
    }
    @Override public long elapsedSeconds() {
        return matchStartedAt == null ? 0L : Duration.between(matchStartedAt, Instant.now()).toSeconds();
    }
    @Override public double currentBorderRadius() { return borderController.currentRadius(); }
    @Override public int countdownRemainingSeconds() {
        return state == GameState.COUNTDOWN ? Math.max(0, countdownRemaining) : 0;
    }

    private record DisconnectSnapshot(
            Location location,
            double health,
            int food,
            float saturation,
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offHand
    ) {
        private static DisconnectSnapshot capture(Player player) {
            return new DisconnectSnapshot(
                    player.getLocation().clone(),
                    player.getHealth(),
                    player.getFoodLevel(),
                    player.getSaturation(),
                    cloneItems(player.getInventory().getStorageContents()),
                    cloneItems(player.getInventory().getArmorContents()),
                    player.getInventory().getItemInOffHand().clone()
            );
        }

        private void restore(Player player) {
            player.teleport(location);
            player.setHealth(Math.min(health,
                    player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
            player.setFoodLevel(food);
            player.setSaturation(saturation);
            player.getInventory().setStorageContents(cloneItems(storage));
            player.getInventory().setArmorContents(cloneItems(armor));
            player.getInventory().setItemInOffHand(offHand.clone());
        }

        private void dropContents() {
            World world = location.getWorld();
            if (world == null) return;
            for (ItemStack item : storage) if (item != null && !item.isEmpty()) world.dropItemNaturally(location, item);
            for (ItemStack item : armor) if (item != null && !item.isEmpty()) world.dropItemNaturally(location, item);
            if (!offHand.isEmpty()) world.dropItemNaturally(location, offHand);
        }

        private static ItemStack[] cloneItems(ItemStack[] source) {
            ItemStack[] copy = new ItemStack[source.length];
            for (int index = 0; index < source.length; index++) {
                copy[index] = source[index] == null ? null : source[index].clone();
            }
            return copy;
        }
    }
}
