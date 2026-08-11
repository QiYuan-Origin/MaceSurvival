package club.mcqi.macesurvival;

import club.mcqi.macesurvival.command.MaceSurvivalCommand;
import club.mcqi.macesurvival.combat.CombatManager;
import club.mcqi.macesurvival.config.ConfigFiles;
import club.mcqi.macesurvival.data.StatsStore;
import club.mcqi.macesurvival.game.GameListener;
import club.mcqi.macesurvival.game.GameManager;
import club.mcqi.macesurvival.game.GameState;
import club.mcqi.macesurvival.game.MatchRuntime;
import club.mcqi.macesurvival.listener.LobbyListener;
import club.mcqi.macesurvival.loot.LootBridge;
import club.mcqi.macesurvival.loot.LootChestManager;
import club.mcqi.macesurvival.menu.MenuListener;
import club.mcqi.macesurvival.menu.MenuManager;
import club.mcqi.macesurvival.placeholder.PlaceholderRegistration;
import club.mcqi.macesurvival.presentation.PlayerPresentationService;
import club.mcqi.macesurvival.scoreboard.ScoreboardManager;
import club.mcqi.macesurvival.team.LoadoutLayoutManager;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import club.mcqi.macesurvival.world.VoidChunkGenerator;
import club.mcqi.macesurvival.world.WorldManager;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class MaceSurvivalPlugin extends JavaPlugin {
    private static final List<String> MENU_RESOURCES = List.of(
        "menus/loadout.yml",
        "menus/team.yml",
        "menus/team-invite.yml",
        "menus/team-invitations.yml"
    );

    private ConfigFiles configFiles;
    private TextService text;
    private StatsStore statistics;
    private ScoreboardManager scoreboards;
    private TeamManager teams;
    private LoadoutLayoutManager loadoutLayouts;
    private MenuManager menus;
    private CombatManager combat;
    private LootChestManager lootChests;
    private MatchRuntime runtime;
    private WorldManager worlds;
    private GameManager game;
    private LobbyListener lobbyListener;
    private PlayerPresentationService presentations;
    private Runnable unregisterPlaceholders = () -> { };

    @Override
    public @NotNull ChunkGenerator getDefaultWorldGenerator(
        @NotNull String worldName,
        @Nullable String id
    ) {
        return new VoidChunkGenerator();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configFiles = new ConfigFiles(this, MENU_RESOURCES);
        configFiles.reload();
        reloadConfig();
        text = new TextService(this, configFiles);
        statistics = new StatsStore(this);
        scoreboards = new ScoreboardManager(text);

        AtomicReference<GameManager> gameReference = new AtomicReference<>();
        teams = new TeamManager(() -> {
            GameManager current = gameReference.get();
            return current != null && current.state() != GameState.WAITING && current.state() != GameState.COUNTDOWN;
        });
        presentations = new PlayerPresentationService(this, teams);
        loadoutLayouts = new LoadoutLayoutManager(this);
        menus = new MenuManager(this, teams, loadoutLayouts, configFiles, text, player -> {
            GameManager current = gameReference.get();
            return current != null
                    && (current.state() == GameState.WAITING || current.state() == GameState.COUNTDOWN)
                    && current.lobbyLocation().map(location -> location.getWorld().equals(player.getWorld())).orElse(false);
        });
        combat = new CombatManager(this, player -> {
            GameManager current = gameReference.get();
            return current != null && current.isAlive(player.getUniqueId());
        });
        runtime = new MatchRuntime(this, text, statistics, scoreboards, teams, combat);
        worlds = new WorldManager(this);
        game = new GameManager(this, worlds, teams, loadoutLayouts, combat, runtime);
        gameReference.set(game);
        lootChests = new LootChestManager(this, combat, player -> game.isAlive(player.getUniqueId()));

        lobbyListener = new LobbyListener(this, lobbyGateway(), teams, menus, text, presentations);
        runtime.bind(game, lootChests, lobbyListener::giveLobbyItems);

        getServer().getPluginManager().registerEvents(new GameListener(this, game, worlds, teams, text), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        getServer().getPluginManager().registerEvents(lobbyListener, this);
        getServer().getPluginManager().registerEvents(combat.listener(), this);
        getServer().getPluginManager().registerEvents(lootChests.listener(), this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin().getName().equals("PlaceholderAPI")) {
                    registerPlaceholders();
                }
            }

            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                if (event.getPlugin().getName().equals("PlaceholderAPI")) {
                    unregisterPlaceholders();
                }
            }
        }, this);
        registerCommand();
        registerPlaceholders();
        getServer().getScheduler().runTask(this, this::finishStartup);
        getLogger().info("MaceSurvival startup hooks registered for Paper 1.21.11.");
    }

    private void finishStartup() {
        try {
            game.enable();
            presentations.start();
            getLogger().info("MaceSurvival worlds and match runtime are ready.");
        } catch (RuntimeException exception) {
            getLogger().log(java.util.logging.Level.SEVERE,
                "Could not finish MaceSurvival startup", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerCommand() {
        MaceSurvivalCommand handler = new MaceSurvivalCommand(
            teams,
            menus,
            statistics,
            new MaceSurvivalCommand.AdministrativeActions() {
            @Override
            public boolean startGame() {
                return game.forceStart(getConfig().getInt("match.forced-countdown-seconds", 5));
            }

            @Override
            public boolean stopGame() {
                if (game.state() == GameState.WAITING || game.state() == GameState.BOOTSTRAPPING) return false;
                game.stopMatch(false);
                return true;
            }

            @Override
            public void reload() {
                reloadEverything();
            }

            @Override
            public void setLobby(Location location) {
                worlds.setLobby(location);
            }
            }
        );
        PluginCommand command = Objects.requireNonNull(getCommand("macesurvival"),
                "macesurvival command is missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    public void reloadEverything() {
        configFiles.reload();
        reloadConfig();
        menus.reload();
        lootChests.reload();
        lobbyListener.refreshVisibility();
        presentations.requestRefresh();
    }

    private void registerPlaceholders() {
        unregisterPlaceholders();
        unregisterPlaceholders = PlaceholderRegistration.registerIfAvailable(this, game, teams, statistics);
    }

    private void unregisterPlaceholders() {
        unregisterPlaceholders.run();
        unregisterPlaceholders = () -> { };
    }

    private LobbyListener.LobbyGateway lobbyGateway() {
        return new LobbyListener.LobbyGateway() {
            @Override
            public boolean gameInProgress() {
                return game.state() != GameState.WAITING && game.state() != GameState.COUNTDOWN;
            }

            @Override
            public boolean isWaitingPlayer(Player player) {
                return !gameInProgress() && player.getWorld().equals(worlds.lobbyWorld());
            }

            @Override
            public void enterWaiting(Player player) {
                game.handleJoin(player);
            }

            @Override
            public void enterSpectator(Player player) {
                game.handleJoin(player);
            }

            @Override
            public Location lobbySpawn() {
                return worlds.lobbyLocation();
            }

            @Override
            public double voidProtectionY() {
                return getConfig().getDouble("lobby.void-return-y", 0.0);
            }
        };
    }

    @Override
    public void onDisable() {
        unregisterPlaceholders();
        if (game != null) game.disable();
        if (runtime != null) runtime.close();
        if (presentations != null) presentations.close();
        if (statistics != null) statistics.close();
        getServer().getScheduler().cancelTasks(this);
    }

    public GameManager game() { return game; }
    public CombatManager combat() { return combat; }
    public TextService text() { return text; }
    public ConfigFiles configFiles() { return configFiles; }
}
