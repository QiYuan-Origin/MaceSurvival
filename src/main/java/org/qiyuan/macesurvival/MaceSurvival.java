package org.qiyuan.macesurvival;

import org.bukkit.plugin.java.JavaPlugin;
import org.qiyuan.macesurvival.config.ConfigManager;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.game.GameManager;
import org.qiyuan.macesurvival.game.ScoreboardManager;
import org.qiyuan.macesurvival.loadout.LoadoutManager;
import org.qiyuan.macesurvival.loadout.LayoutStore;
import org.qiyuan.macesurvival.loot.LootManager;
import org.qiyuan.macesurvival.loot.EnchantManager;
import org.qiyuan.macesurvival.arena.ArenaManager;
import org.qiyuan.macesurvival.command.MaceCommand;
import org.qiyuan.macesurvival.drop.DropManager;
import org.qiyuan.macesurvival.listener.*;
import org.qiyuan.macesurvival.menu.MenuManager;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.economy.EconomyManager;
import org.qiyuan.macesurvival.input.InputManager;
import org.qiyuan.macesurvival.game.LobbyManager;

/**
 * MaceSurvival - Mace Battle Royale for Paper 1.21.11
 *
 * Single-server battle royale with skydiving, durability-based loot,
 * and procedurally generated arenas.
 */
public final class MaceSurvival extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private GameManager gameManager;
    private ScoreboardManager scoreboardManager;
    private LoadoutManager loadoutManager;
    private LayoutStore layoutStore;
    private LootManager lootManager;
    private EnchantManager enchantManager;
    private ArenaManager arenaManager;
    private DropManager dropManager;
    private MenuManager menuManager;
    private StatsManager statsManager;
    private EconomyManager economyManager;
    private InputManager inputManager;
    private LobbyManager lobbyManager;
    private org.qiyuan.macesurvival.text.MacePlaceholders placeholders;

    @Override
    public void onEnable() {
        getLogger().info("Loading MaceSurvival...");

        // Load configurations
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this);
        statsManager = new StatsManager(this);
        statsManager.load();
        economyManager = new EconomyManager(this);
        economyManager.load();
        lobbyManager = new LobbyManager(this);
        inputManager = new InputManager(this);

        // Initialize managers
        layoutStore = new LayoutStore(this);
        loadoutManager = new LoadoutManager(this);
        lootManager = new LootManager(this);
        enchantManager = new EnchantManager(this);
        arenaManager = new ArenaManager(this);
        dropManager = new DropManager(this);
        menuManager = new MenuManager(this);
        scoreboardManager = new ScoreboardManager(this);
        gameManager = new GameManager(this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new org.qiyuan.macesurvival.text.MacePlaceholders(this);
            placeholders.register();
        }

        // Start scoreboard task
        scoreboardManager.startUpdateTask();

        // Register commands
        var cmd = getCommand("macesurvival");
        if (cmd != null) {
            var executor = new MaceCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // Register listeners
        registerListeners();
        getServer().getOnlinePlayers().forEach(gameManager::addPlayer);

        getLogger().info("MaceSurvival enabled!");
    }

    @Override
    public void onDisable() {
        if (placeholders != null) placeholders.unregister();
        if (gameManager != null) {
            gameManager.shutdown();
        }
        if (scoreboardManager != null) scoreboardManager.stop();
        if (statsManager != null) statsManager.saveSync();
        if (economyManager != null) economyManager.saveSync();
        getLogger().info("MaceSurvival disabled.");
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new JoinQuitListener(this), this);
        pm.registerEvents(new CombatListener(this), this);
        pm.registerEvents(new LootListener(this), this);
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(inputManager, this);
        pm.registerEvents(new ProtectionListener(this), this);
    }

    public ConfigManager configs() {
        return configManager;
    }

    public MessageManager messages() {
        return messageManager;
    }

    public GameManager game() {
        return gameManager;
    }

    public ScoreboardManager scoreboard() {
        return scoreboardManager;
    }

    public LoadoutManager loadouts() {
        return loadoutManager;
    }

    public LayoutStore layouts() {
        return layoutStore;
    }

    public LootManager loot() {
        return lootManager;
    }

    public EnchantManager enchants() {
        return enchantManager;
    }

    public ArenaManager arenas() {
        return arenaManager;
    }

    public DropManager drops() {
        return dropManager;
    }

    public MenuManager menus() {
        return menuManager;
    }

    public StatsManager stats() { return statsManager; }
    public EconomyManager economy() { return economyManager; }
    public InputManager input() { return inputManager; }
    public LobbyManager lobby() { return lobbyManager; }

    public void reloadAll() {
        configs().reload(); messages().reload(); layouts().reload();
        loot().reload(); enchants().reload(); menus().reload(); scoreboard().reload(); scoreboard().startUpdateTask();
        economy().reload();
    }
}
