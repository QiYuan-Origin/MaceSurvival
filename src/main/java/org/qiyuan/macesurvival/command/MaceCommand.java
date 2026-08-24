package org.qiyuan.macesurvival.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.config.MessageManager;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.loadout.LoadoutManager;
import org.qiyuan.macesurvival.menu.MainMenu;
import org.qiyuan.macesurvival.menu.SpectateMenu;
import org.qiyuan.macesurvival.menu.StatsMenu;
import org.qiyuan.macesurvival.menu.TeamMenu;

import java.util.List;

/**
 * /mace 的全部分支。help 分支的消息一律不带前缀，
 * 管理员那一段只发给持有 macesurvival.admin 的人。
 */
public final class MaceCommand implements CommandExecutor {

    private final MaceSurvival plugin;

    public MaceCommand(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (args.length == 0) {
            return open(sender);
        }
        return switch (args[0].toLowerCase()) {
            case "help" -> help(sender);
            case "menu" -> open(sender);
            case "stats" -> stats(sender, args);
            case "team" -> team(sender, args);
            case "spectate" -> spectate(sender);
            case "start" -> start(sender);
            case "stop" -> stop(sender);
            case "reload" -> reload(sender);
            case "map" -> map(sender, args);
            case "loot" -> loot(sender, args);
            default -> unknown(sender);
        };
    }

    // ─────────────── 玩家分支 ───────────────

    private boolean open(CommandSender sender) {
        Player player = player(sender);
        if (player == null || !allowed(player, "macesurvival.menu")) {
            return true;
        }
        MainMenu.openMain(plugin, player);
        return true;
    }

    private boolean help(CommandSender sender) {
        // help 不加前缀，管理员段落按权限过滤
        plugin.messages().sendPlainList(sender, "help.header",
                MessageManager.vars("version", plugin.getPluginMeta().getVersion()));
        plugin.messages().sendPlainList(sender, "help.player");
        if (sender.hasPermission("macesurvival.admin")) {
            plugin.messages().sendPlainList(sender, "help.admin");
        }
        plugin.messages().sendPlainList(sender, "help.footer");
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "macesurvival.stats")) {
            return true;
        }
        if (args.length < 2) {
            new StatsMenu(plugin, player).open();
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(player, "general.player-not-found",
                    MessageManager.vars("target", args[1]));
            return true;
        }
        StatsManager.Record record = plugin.stats().of(target);
        plugin.messages().send(player, "stats.header",
                MessageManager.vars("player", record.name()));
        plugin.messages().send(player, "stats.wins",
                MessageManager.vars("wins", String.valueOf(record.wins())));
        plugin.messages().send(player, "stats.kills",
                MessageManager.vars("kills", String.valueOf(record.kills())));
        plugin.messages().send(player, "stats.deaths",
                MessageManager.vars("deaths", String.valueOf(record.deaths())));
        plugin.messages().send(player, "stats.kdr",
                MessageManager.vars("kdr", String.format("%.2f", record.kd())));
        plugin.messages().send(player, "stats.played",
                MessageManager.vars("played", String.valueOf(record.games())));
        return true;
    }

    private boolean team(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "macesurvival.team")) {
            return true;
        }
        if (args.length < 2) {
            new TeamMenu(plugin, player).open();
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "create" -> {
                plugin.squads().leavePreTeam(player.getUniqueId());
                plugin.messages().send(player, "team.created");
            }
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "leave" -> {
                plugin.squads().leavePreTeam(player.getUniqueId());
                plugin.messages().send(player, "team.disbanded");
            }
            default -> plugin.messages().send(player, "general.unknown-command");
        }
        return true;
    }

    private void invite(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "general.unknown-command");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null || target.equals(player)) {
            plugin.messages().send(player, "general.player-not-found",
                    MessageManager.vars("target", args[2]));
            return;
        }
        int max = plugin.squads().teamSize();
        if (plugin.squads().preTeamOf(player.getUniqueId()).size() + 1 >= max) {
            plugin.messages().send(player, "team.full");
            return;
        }
        plugin.squads().offer(player, target);
        plugin.messages().send(player, "team.invited",
                MessageManager.vars("target", target.getName()));
        plugin.messages().send(target, "team.invite-received",
                MessageManager.vars("player", player.getName()));
    }

    private void accept(Player player, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(player, "general.unknown-command");
            return;
        }
        Player leader = plugin.getServer().getPlayerExact(args[2]);
        if (leader == null) {
            plugin.messages().send(player, "general.player-not-found",
                    MessageManager.vars("target", args[2]));
            return;
        }
        if (!plugin.squads().hasInvite(player.getUniqueId(), leader.getUniqueId())) {
            plugin.messages().send(player, "team.no-invite");
            return;
        }
        int max = plugin.squads().teamSize();
        if (plugin.squads().preTeamOf(leader.getUniqueId()).size() + 1 >= max) {
            plugin.messages().send(player, "team.full");
            return;
        }
        plugin.squads().accept(player, leader);
        int current = plugin.squads().preTeamOf(leader.getUniqueId()).size() + 1;
        var vars = MessageManager.vars(
                "player", player.getName(),
                "current", String.valueOf(current),
                "max", String.valueOf(max));
        plugin.messages().send(player, "team.joined", vars);
        plugin.messages().send(leader, "team.joined", vars);
    }

    private boolean spectate(CommandSender sender) {
        Player player = player(sender);
        if (player == null || !allowed(player, "macesurvival.play")) {
            return true;
        }
        if (plugin.game().watching().stream().noneMatch(gp -> gp.uuid().equals(player.getUniqueId()))) {
            plugin.messages().send(player, "spectate.not-spectator");
            return true;
        }
        if (plugin.game().aliveCount() == 0) {
            plugin.messages().send(player, "spectate.nobody-alive");
            return true;
        }
        new SpectateMenu(plugin, player).open();
        return true;
    }

    // ─────────────── 管理员分支 ───────────────

    private boolean start(CommandSender sender) {
        if (!allowed(sender, "macesurvival.admin.start")) {
            return true;
        }
        plugin.game().forceStart(sender.getName());
        return true;
    }

    private boolean stop(CommandSender sender) {
        if (!allowed(sender, "macesurvival.admin.stop")) {
            return true;
        }
        plugin.game().forceStop(sender.getName());
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!allowed(sender, "macesurvival.admin.reload")) {
            return true;
        }
        long started = System.currentTimeMillis();
        try {
            List<String> restored = plugin.configs().reload();
            long ms = System.currentTimeMillis() - started;
            if (restored.isEmpty()) {
                plugin.messages().send(sender, "general.reloaded",
                        MessageManager.vars("ms", String.valueOf(ms)));
            } else {
                plugin.messages().send(sender, "general.reloaded-restored", MessageManager.vars(
                        "count", String.valueOf(restored.size()),
                        "files", String.join(", ", restored)));
            }
        } catch (RuntimeException exception) {
            plugin.messages().send(sender, "general.reload-failed",
                    MessageManager.vars("error", String.valueOf(exception.getMessage())));
        }
        return true;
    }

    private boolean map(CommandSender sender, String[] args) {
        if (!allowed(sender, "macesurvival.admin.map")) {
            return true;
        }
        if (args.length < 2) {
            return unknown(sender);
        }
        switch (args[1].toLowerCase()) {
            case "regen" -> {
                plugin.messages().send(sender, "arena.regenerating");
                plugin.arena().generate(ok -> {
                    if (ok) {
                        plugin.messages().send(sender, "arena.generated", MessageManager.vars(
                                "structures", String.valueOf(plugin.arena().structureCount()),
                                "chests", String.valueOf(plugin.arena().chestCount())));
                    } else {
                        plugin.messages().send(sender, "arena.generate-failed",
                                MessageManager.vars("error", "see console"));
                    }
                });
            }
            case "info" -> plugin.messages().send(sender, "arena.generated", MessageManager.vars(
                    "structures", String.valueOf(plugin.arena().structureCount()),
                    "chests", String.valueOf(plugin.arena().chestCount())));
            default -> unknown(sender);
        }
        return true;
    }

    private boolean loot(CommandSender sender, String[] args) {
        if (!allowed(sender, "macesurvival.admin.loot")) {
            return true;
        }
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("test")) {
            return unknown(sender);
        }
        int stars;
        try {
            stars = Integer.parseInt(args[2]);
        } catch (NumberFormatException exception) {
            plugin.messages().send(sender, "general.invalid-number",
                    MessageManager.vars("input", args[2]));
            return true;
        }
        LoadoutManager loadout = plugin.loadout();
        for (ItemStack stack : plugin.loot().preview(stars)) {
            plugin.messages().send(player, "loot.durability-merged", MessageManager.vars(
                    "item", loadout.displayName(stack),
                    "durability", String.valueOf(stack.getAmount())));
        }
        return true;
    }

    // ─────────────── 公用 ───────────────

    private boolean unknown(CommandSender sender) {
        plugin.messages().send(sender, "general.unknown-command");
        return true;
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "general.player-only");
        return null;
    }

    private boolean allowed(CommandSender sender, String node) {
        if (sender.hasPermission(node)) {
            return true;
        }
        plugin.messages().send(sender, "general.no-permission");
        return false;
    }
}
