package org.qiyuan.macesurvival.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.qiyuan.macesurvival.MaceSurvival;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 货币接入。优先级：配置指定的自定义货币 > Vault > 插件内置金币。
 * 内置金币只是没有经济插件时的兜底，落盘在 balances.yml。
 */
public final class EconomyManager implements Listener {

    /** 由配置驱动的自定义货币，靠命令加减，靠 PlaceholderAPI 变量读余额。 */
    private record Custom(String id, String display, String takeCommand,
                          String giveCommand, String balancePlaceholder) {
    }

    private final MaceSurvival plugin;
    private final Map<UUID, Double> internal = new ConcurrentHashMap<>();
    private record Pending(UUID player, String currency) { }
    private record Snapshot(long sequence, Map<UUID, Double> balances, Map<Pending, Double> rewards) { }
    private final Map<Pending, Double> pendingRewards = new ConcurrentHashMap<>();

    private Economy vault;
    private Custom custom;
    private String currencyName;
    private File file;
    private long requestedWrite;
    private long completedWrite;
    private boolean loaded;
    private boolean registered;

    public EconomyManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "balances.yml");
        YamlConfiguration stored = new YamlConfiguration();
        try {
            if (file.exists()) stored.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IllegalStateException("Cannot read balances.yml; the existing wallet was left untouched", exception);
        }
        internal.clear();
        pendingRewards.clear();
        ConfigurationSection section = stored.getConfigurationSection("balances");
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                try {
                    double amount = section.getDouble(rawId);
                    if (Double.isFinite(amount) && amount >= 0) internal.put(UUID.fromString(rawId), amount);
                } catch (IllegalArgumentException ignored) {
                    // 坏 UUID 直接跳过，不要因为一条脏数据整份余额读不出来
                }
            }
        }
        for (Map<?, ?> reward : stored.getMapList("pending-rewards")) {
            try {
                UUID uuid = UUID.fromString(String.valueOf(reward.get("player")));
                String currency = String.valueOf(reward.get("currency"));
                double amount = Double.parseDouble(String.valueOf(reward.get("amount")));
                if (!currency.isBlank() && Double.isFinite(amount) && amount > 0) pendingRewards.merge(new Pending(uuid, currency), amount, Double::sum);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignored an invalid pending reward in balances.yml.");
            }
        }
        loaded = true;
        selectProvider();
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        Bukkit.getOnlinePlayers().forEach(this::deliverPending);
    }

    private void selectProvider() {
        ConfigurationSection economy = plugin.configs().main().getConfigurationSection("economy");
        currencyName = economy == null ? "Coins" : economy.getString("currency-name", "Coins");
        custom = null;
        vault = null;

        ConfigurationSection picked = economy == null ? null : economy.getConfigurationSection("custom");
        if (picked != null && picked.getBoolean("enabled", false)) {
            String display = picked.getString("name", "Coins");
            custom = new Custom(
                    picked.getString("id", display),
                    display,
                    picked.getString("take-command", ""),
                    picked.getString("give-command", ""),
                    picked.getString("balance-placeholder", ""));
            currencyName = display;
            plugin.getLogger().info("Economy: using the custom currency '" + display + "'.");
            return;
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vault") && (economy == null || economy.getBoolean("use-vault", true))) {
            RegisteredServiceProvider<Economy> provider =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) {
                vault = provider.getProvider();
                plugin.getLogger().info("Economy: hooked into Vault (" + vault.getName() + ").");
                return;
            }
        }
        plugin.getLogger().info("Economy: no provider found, using the built-in wallet.");
    }

    public String currencyName() {
        return currencyName;
    }

    public void reload() {
        saveSync();
        load();
    }

    public double balance(Player player) {
        if (custom != null) {
            return parse(applyPlaceholders(player, custom.balancePlaceholder()));
        }
        if (vault != null) {
            return vault.getBalance(player);
        }
        return internal.getOrDefault(player.getUniqueId(), 0D);
    }

    public boolean has(Player player, double amount) {
        return amount <= 0 || balance(player) >= amount;
    }

    /** 扣款。余额不足返回 false，调用方据此决定是否放行购买。 */
    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (!has(player, amount)) {
            return false;
        }
        if (custom != null) {
            return dispatch(player, custom.takeCommand(), amount);
        }
        if (vault != null) {
            return vault.withdrawPlayer(player, amount).transactionSuccess();
        }
        internal.merge(player.getUniqueId(), -amount, Double::sum);
        saveAsync();
        return true;
    }

    public void deposit(Player player, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return;
        }
        if (custom != null) {
            if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(plugin, () -> deposit(player, amount)); return; }
            if (!dispatch(player, custom.giveCommand(), amount)) queueReward(player.getUniqueId(), custom.id(), amount);
            return;
        }
        if (vault != null) {
            vault.depositPlayer(player, amount);
            return;
        }
        internal.merge(player.getUniqueId(), amount, Double::sum);
        saveAsync();
    }

    /**
     * 发奖。结算时玩家可能已经掉线，所以按 UUID 处理：
     * 在线走正常入账；离线自定义货币记待发奖励，下次上线仍按原货币补发。
     */
    public void reward(UUID uuid, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            deposit(online, amount);
            return;
        }
        if (custom != null) {
            queueReward(uuid, custom.id(), amount);
            return;
        }
        if (vault != null) {
            vault.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount);
            return;
        }
        internal.merge(uuid, amount, Double::sum);
        saveAsync();
    }

    private void queueReward(UUID uuid, String currency, double amount) {
        pendingRewards.merge(new Pending(uuid, currency), amount, Double::sum);
        saveAsync();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) { deliverPending(event.getPlayer()); }

    private void deliverPending(Player player) {
        if (custom == null) return;
        Pending key = new Pending(player.getUniqueId(), custom.id());
        Double amount = pendingRewards.get(key);
        if (amount == null || !dispatch(player, custom.giveCommand(), amount)) return;
        pendingRewards.computeIfPresent(key, (ignored, balance) -> balance > amount ? balance - amount : null);
        saveSync();
    }

    /** 内置钱包余额，离线也能读，菜单和排行榜用。 */
    public double balance(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return balance(online);
        }
        if (vault != null && custom == null) {
            return vault.getBalance(Bukkit.getOfflinePlayer(uuid));
        }
        return internal.getOrDefault(uuid, 0D);
    }

    /** 格式化金额，菜单和消息里统一显示形式。 */
    public String format(double amount) {
        return trim(amount);
    }

    private boolean dispatch(Player player, String template, double amount) {
        if (template == null || template.isBlank()) {
            return false;
        }
        // config.yml 里写的是 {player} / {amount}，同时兼容 %player_name% / %金币数% 这类旧写法
        String command = template
                .replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{amount}", trim(amount))
                .replace("%player_name%", player.getName())
                .replace("%player_uuid%", player.getUniqueId().toString())
                .replace("%金币数%", trim(amount))
                .replace("%amount%", trim(amount));
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String finalCommand = command;
        try {
            if (Bukkit.isPrimaryThread()) return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand)).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException | java.util.concurrent.ExecutionException exception) {
            plugin.getLogger().warning("Custom currency command failed: " + exception.getMessage());
            return false;
        }
    }

    private String applyPlaceholders(Player player, String raw) {
        if (raw == null || raw.isBlank()) {
            return "0";
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, raw);
        }
        return raw;
    }

    private double parse(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private String trim(double amount) {
        return amount == Math.floor(amount)
                ? String.valueOf((long) amount)
                : String.valueOf(amount);
    }

    public void saveAsync() {
        if (!loaded) return;
        Snapshot snapshot = snapshot();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot));
    }

    public void saveSync() {
        if (loaded) write(snapshot());
    }

    private synchronized Snapshot snapshot() { return new Snapshot(++requestedWrite, Map.copyOf(internal), Map.copyOf(pendingRewards)); }

    private synchronized void write(Snapshot snapshot) {
        if (snapshot.sequence() <= completedWrite) return;
        YamlConfiguration out = new YamlConfiguration();
        snapshot.balances().forEach((uuid, value) -> out.set("balances." + uuid, value));
        List<Map<String, Object>> rewards = new ArrayList<>();
        snapshot.rewards().forEach((key, value) -> rewards.add(Map.of("player", key.player().toString(), "currency", key.currency(), "amount", value)));
        out.set("pending-rewards", rewards);
        try {
            Files.createDirectories(file.toPath().getParent());
            var temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
            out.save(temporary.toFile());
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            completedWrite = snapshot.sequence();
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save balances.yml: " + ex.getMessage());
        }
    }

    public OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }
}
