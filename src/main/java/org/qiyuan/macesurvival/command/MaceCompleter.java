package org.qiyuan.macesurvival.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /mace 的补全。管理员分支只对有权限的人补出来，
 * 普通玩家按 tab 看不到 start/stop/reload 这些。
 */
public final class MaceCompleter implements TabCompleter {

    private static final List<String> PLAYER = List.of("help", "menu", "stats", "team", "spectate");
    private static final List<String> ADMIN = List.of("start", "stop", "reload", "map", "loot");
    private static final List<String> TEAM = List.of("create", "invite", "accept", "leave");
    private static final List<String> MAP = List.of("regen", "info");
    private static final List<String> STARS = List.of("1", "2", "3", "4", "5");

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER);
            if (sender.hasPermission("macesurvival.admin")) {
                options.addAll(ADMIN);
            }
            return filter(options, args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "stats" -> options.addAll(names());
                case "team" -> options.addAll(TEAM);
                case "map" -> {
                    if (sender.hasPermission("macesurvival.admin.map")) {
                        options.addAll(MAP);
                    }
                }
                case "loot" -> {
                    if (sender.hasPermission("macesurvival.admin.loot")) {
                        options.add("test");
                    }
                }
                default -> {
                    // 其它分支没有第二个参数
                }
            }
            return filter(options, args[1]);
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("team")
                    && (args[1].equalsIgnoreCase("invite") || args[1].equalsIgnoreCase("accept"))) {
                options.addAll(names());
            }
            if (args[0].equalsIgnoreCase("loot") && args[1].equalsIgnoreCase("test")
                    && sender.hasPermission("macesurvival.admin.loot")) {
                options.addAll(STARS);
            }
            return filter(options, args[2]);
        }
        return List.of();
    }

    private List<String> names() {
        List<String> out = new ArrayList<>();
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            out.add(online.getName());
        }
        return out;
    }

    private List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
