package org.qiyuan.macesurvival;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;
import org.qiyuan.macesurvival.arena.ArenaManager;
import org.qiyuan.macesurvival.arena.HologramManager;
import org.qiyuan.macesurvival.config.ConfigManager;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.drop.DropManager;
import org.qiyuan.macesurvival.economy.EconomyManager;
import org.qiyuan.macesurvival.game.GameManager;
import org.qiyuan.macesurvival.game.NametagManager;
import org.qiyuan.macesurvival.game.SquadManager;
import org.qiyuan.macesurvival.command.MaceCommand;
import org.qiyuan.macesurvival.command.MaceCompleter;
import org.qiyuan.macesurvival.enchant.EnchantManager;
import org.qiyuan.macesurvival.input.InputListener;
import org.qiyuan.macesurvival.input.InputManager;
import org.qiyuan.macesurvival.listener.CombatListener;
import org.qiyuan.macesurvival.listener.LootListener;
import org.qiyuan.macesurvival.menu.MenuListener;
import org.qiyuan.macesurvival.loadout.LayoutStore;
import org.qiyuan.macesurvival.loadout.LoadoutManager;
import org.qiyuan.macesurvival.loot.CombatManager;
import org.qiyuan.macesurvival.loot.LootManager;

/**
 * 单端重锤大逃杀。进服即进等待大厅，游戏中进服转旁观。
 * 地图由插件随机生成，不依赖管理员预先建图。
 */
public final class MaceSurvival extends JavaPlugin {

    private static MaceSurvival instance;

    private ConfigManager configs;
    private MessageManager messages;
    private StatsManager stats;
    private EconomyManager economy;
    private SquadManager squads;
    private NametagManager nametags;
    private HologramManager holograms;
    private ArenaManager arena;
    private DropManager drop;
    private LoadoutManager loadout;
    private LayoutStore layouts;
    private LootManager loot;
    private EnchantManager enchants;
    private CombatManager combat;
    private InputManager input;
    private GameManager game;

    public static MaceSurvival get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        configs = new ConfigManager(this);
        configs.reload();
        messages = new MessageManager(configs);

        stats = new StatsManager(this);
        stats.load();
        economy = new EconomyManager(this);
        economy.load();

        squads = new SquadManager(this);
        nametags = new NametagManager(this);
        holograms = new HologramManager(this);
        arena = new ArenaManager(this);
        drop = new DropManager(this);
        loadout = new LoadoutManager(this);
        layouts = new LayoutStore(this);
        layouts.load();
        enchants = new EnchantManager(this);
        combat = new CombatManager(this);
        loot = new LootManager(this);
        input = new InputManager(this);
        game = new GameManager(this);
        game.boot();

        getServer().getPluginManager().registerEvents(new LootListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new InputListener(this), this);

        var command = getCommand("macesurvival");
        if (command != null) {
            command.setExecutor(new MaceCommand(this));
            command.setTabCompleter(new MaceCompleter());
        }

        getLogger().info("MaceSurvival enabled.");
    }

    @Override
    public void onDisable() {
        // 关服时异步任务不会再执行，落盘必须同步
        if (game != null) {
            game.shutdown();
        }
        if (holograms != null) {
            holograms.clearAll();
        }
        if (nametags != null) {
            nametags.clearAll();
        }
        if (drop != null) {
            drop.clear();
        }
        if (combat != null) {
            combat.clear();
        }
        if (input != null) {
            input.clearAll();
        }
        if (stats != null) {
            stats.saveSync();
        }
        if (economy != null) {
            economy.saveSync();
        }
        getLogger().info("MaceSurvival disabled.");
        instance = null;
    }

    public ConfigManager configs() {
        return configs;
    }

    public MessageManager messages() {
        return messages;
    }

    public StatsManager stats() {
        return stats;
    }

    public EconomyManager economy() {
        return economy;
    }

    public SquadManager squads() {
        return squads;
    }

    public NametagManager nametags() {
        return nametags;
    }

    public HologramManager holograms() {
        return holograms;
    }

    public ArenaManager arena() {
        return arena;
    }

    public DropManager drop() {
        return drop;
    }

    public LoadoutManager loadout() {
        return loadout;
    }

    public LayoutStore layouts() {
        return layouts;
    }

    public LootManager loot() {
        return loot;
    }

    public EnchantManager enchants() {
        return enchants;
    }

    public CombatManager combat() {
        return combat;
    }

    public InputManager input() {
        return input;
    }

    public GameManager game() {
        return game;
    }

    public NamespacedKey key(String value) {
        return new NamespacedKey(this, value);
    }
}
