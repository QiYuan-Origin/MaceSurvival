package club.mcqi.macesurvival.command;

import club.mcqi.macesurvival.data.StatsStore;
import club.mcqi.macesurvival.menu.MenuManager;
import club.mcqi.macesurvival.team.TeamData;
import club.mcqi.macesurvival.team.TeamManager;
import club.mcqi.macesurvival.text.TextService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class MaceSurvivalCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION_START = "macesurvival.admin.start";
    public static final String PERMISSION_STOP = "macesurvival.admin.stop";
    public static final String PERMISSION_RELOAD = "macesurvival.admin.reload";
    public static final String PERMISSION_SET_LOBBY = "macesurvival.admin.setlobby";
    public static final String PERMISSION_TEAM = "macesurvival.team";
    public static final String PERMISSION_USE = "macesurvival.use";
    public static final String PERMISSION_STATS = "macesurvival.stats";
    public static final String PERMISSION_STATS_OTHERS = "macesurvival.stats.others";
    public static final String PERMISSION_ADMIN = "macesurvival.admin";

    private final TeamManager teamManager;
    private final MenuManager menuManager;
    private final StatsStore statistics;
    private final TextService text;
    private final AdministrativeActions actions;

    /** Compatibility constructor; inject StatsStore with the four-argument overload to enable /stats. */
    public MaceSurvivalCommand(
        TeamManager teamManager,
        MenuManager menuManager,
        AdministrativeActions actions
    ) {
        this(teamManager, menuManager, null, actions);
    }

    public MaceSurvivalCommand(
        TeamManager teamManager,
        MenuManager menuManager,
        StatsStore statistics,
        AdministrativeActions actions
    ) {
        this.teamManager = Objects.requireNonNull(teamManager, "teamManager");
        this.menuManager = Objects.requireNonNull(menuManager, "menuManager");
        this.statistics = statistics;
        text = menuManager.textService();
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public boolean onCommand(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String label,
        @NotNull String[] args
    ) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "team" -> handleTeam(sender, label, args);
            case "loadout" -> handleLoadout(sender, args);
            case "stats" -> handleStats(sender, label, args);
            case "start" -> handleAdmin(
                sender, PERMISSION_START, actions::startGame, "admin.start-requested", "admin.start-failed"
            );
            case "stop" -> handleAdmin(
                sender, PERMISSION_STOP, actions::stopGame, "admin.stop-complete", "admin.stop-none"
            );
            case "reload" -> handleReload(sender);
            case "setlobby" -> handleSetLobby(sender);
            default -> {
                sendConfigured(sender, "general.unknown-command", Map.of("label", label));
                yield true;
            }
        };
    }

    private boolean handleTeam(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION_TEAM)) {
            sendConfigured(sender, "general.no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sendConfigured(sender, "general.player-only", Map.of());
            return true;
        }
        if (args.length == 1 || args[1].equalsIgnoreCase("menu")) {
            menuManager.openTeam(player);
            return true;
        }

        String operation = args[1].toLowerCase(Locale.ROOT);
        if (operation.equals("leave")) {
            sendTeamResult(player, teamManager.leave(player.getUniqueId()), "team.left", Map.of());
            return true;
        }
        if (operation.equals("list")) {
            sendTeamList(player);
            return true;
        }
        if (args.length < 3) {
            sendConfigured(player, "command.team-usage", Map.of("label", label));
            return true;
        }

        Player onlineTarget = findOnlinePlayer(args[2]);
        UUID targetId = operation.equals("accept") || operation.equals("decline")
            ? findInviter(player, args[2]).orElse(null)
            : onlineTarget == null ? null : onlineTarget.getUniqueId();
        if (targetId == null) {
            sendConfigured(player, "general.player-or-invitation-not-found", Map.of());
            return true;
        }

        TeamManager.ActionResult result = switch (operation) {
            case "invite" -> teamManager.invite(player.getUniqueId(), targetId);
            case "accept" -> teamManager.accept(player.getUniqueId(), targetId);
            case "decline" -> teamManager.decline(player.getUniqueId(), targetId);
            case "kick" -> teamManager.kick(player.getUniqueId(), targetId);
            case "transfer" -> teamManager.transferLeadership(player.getUniqueId(), targetId);
            default -> null;
        };
        if (result == null) {
            sendConfigured(player, "team.unknown-action", Map.of());
            return true;
        }

        String targetName = onlineTarget == null ? args[2] : onlineTarget.getName();
        Map<String, Object> placeholders = Map.of("player", targetName);
        String successPath = switch (operation) {
            case "invite" -> "team.invite-sent";
            case "accept" -> "team.joined";
            case "decline" -> "team.invite-declined";
            case "kick" -> "team.member-kicked";
            case "transfer" -> "team.leader-transferred";
            default -> "team.done";
        };
        sendTeamResult(player, result, successPath, placeholders);
        if (result == TeamManager.ActionResult.SUCCESS) {
            notifyTarget(player, onlineTarget, operation);
            if (operation.equals("accept")) {
                Player inviter = Bukkit.getPlayer(targetId);
                if (inviter != null) {
                    sendConfigured(inviter, "team.member-joined", Map.of("player", player.getName()));
                }
            }
        }
        return true;
    }

    private boolean handleLoadout(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_USE)) {
            sendConfigured(sender, "general.no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sendConfigured(sender, "general.player-only", Map.of());
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            menuManager.loadoutLayouts().reset(player);
            sendConfigured(player, "lobby.loadout-reset", Map.of());
            return true;
        }
        menuManager.openLoadout(player);
        return true;
    }

    private boolean handleStats(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION_STATS)) {
            sendConfigured(sender, "general.no-permission", Map.of());
            return true;
        }
        if (statistics == null) {
            sendConfigured(sender, "stats.unavailable", Map.of());
            return true;
        }

        OfflinePlayer target;
        if (args.length < 2) {
            if (!(sender instanceof Player player)) {
                sendConfigured(sender, "command.stats-usage", Map.of("label", label));
                return true;
            }
            target = player;
        } else {
            target = findStatsPlayer(args[1]);
            if (target == null) {
                sendConfigured(sender, "stats.player-not-found", Map.of("player", args[1]));
                return true;
            }
            if (!(sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId()))
                && !sender.hasPermission(PERMISSION_STATS_OTHERS)) {
                sendConfigured(sender, "general.no-permission", Map.of());
                return true;
            }
        }

        StatsStore.PlayerStats value = statistics.stats(target.getUniqueId());
        String targetName = target.getName() == null ? target.getUniqueId().toString().substring(0, 8) : target.getName();
        double winRate = value.games() == 0 ? 0.0D : value.wins() * 100.0D / value.games();
        double killDeathRatio = value.deaths() == 0 ? value.kills() : value.kills() / (double) value.deaths();
        Map<String, Object> placeholders = Map.of(
            "player", targetName,
            "games", value.games(),
            "wins", value.wins(),
            "kills", value.kills(),
            "deaths", value.deaths(),
            "win_rate", String.format(Locale.ROOT, "%.1f", winRate),
            "kd", String.format(Locale.ROOT, "%.2f", killDeathRatio),
            "rank", statistics.rank(target.getUniqueId(), StatsStore.StatField.KILLS)
        );
        sendConfiguredLines(sender, "stats.view", placeholders);
        return true;
    }

    private boolean handleAdmin(
        CommandSender sender,
        String permission,
        BooleanOperation operation,
        String successPath,
        String failurePath
    ) {
        if (!hasAdminPermission(sender, permission)) {
            sendConfigured(sender, "general.unknown-command-hidden", Map.of());
            return true;
        }
        sendConfigured(sender, operation.run() ? successPath : failurePath, Map.of());
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!hasAdminPermission(sender, PERMISSION_RELOAD)) {
            sendConfigured(sender, "general.unknown-command-hidden", Map.of());
            return true;
        }
        try {
            actions.reload();
            sendConfigured(sender, "general.config-reloaded", Map.of());
        } catch (RuntimeException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not reload MaceSurvival configuration", exception);
            sendConfigured(sender, "general.config-reload-failed", Map.of());
        }
        return true;
    }

    private boolean handleSetLobby(CommandSender sender) {
        if (!hasAdminPermission(sender, PERMISSION_SET_LOBBY)) {
            sendConfigured(sender, "general.unknown-command-hidden", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sendConfigured(sender, "admin.setlobby-player-only", Map.of());
            return true;
        }
        actions.setLobby(player.getLocation());
        sendConfigured(sender, "admin.setlobby-complete", Map.of());
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        Map<String, Object> placeholders = Map.of("label", label);
        sendRawConfigured(sender, "help.header", placeholders);
        if (sender.hasPermission(PERMISSION_TEAM)) {
            sendRawConfigured(sender, "help.team", placeholders);
        }
        if (sender.hasPermission(PERMISSION_USE)) {
            sendRawConfigured(sender, "help.loadout", placeholders);
        }
        if (sender.hasPermission(PERMISSION_STATS)) {
            sendRawConfigured(sender, "help.stats", placeholders);
        }
        sendAdminHelp(sender, PERMISSION_START, "help.admin-start", placeholders);
        sendAdminHelp(sender, PERMISSION_STOP, "help.admin-stop", placeholders);
        sendAdminHelp(sender, PERMISSION_RELOAD, "help.admin-reload", placeholders);
        sendAdminHelp(sender, PERMISSION_SET_LOBBY, "help.admin-setlobby", placeholders);
    }

    private void sendAdminHelp(
        CommandSender sender,
        String permission,
        String messagePath,
        Map<String, ?> placeholders
    ) {
        if (hasAdminPermission(sender, permission)) {
            sendRawConfigured(sender, messagePath, placeholders);
        }
    }

    private void sendTeamList(Player player) {
        TeamData team = teamManager.ensureSoloTeam(player.getUniqueId());
        List<String> names = team.membersInJoinOrder().stream()
            .map(Bukkit::getOfflinePlayer)
            .map(member -> member.getName() == null
                ? member.getUniqueId().toString().substring(0, 8)
                : member.getName())
            .toList();
        sendConfigured(player, "team.list", Map.of(
            "size", team.size(),
            "max", TeamManager.MAX_TEAM_SIZE,
            "members", String.join(", ", names)
        ));
    }

    private void sendTeamResult(
        Player player,
        TeamManager.ActionResult result,
        String successPath,
        Map<String, ?> placeholders
    ) {
        sendConfigured(player, result == TeamManager.ActionResult.SUCCESS ? successPath : teamResultPath(result), placeholders);
    }

    private void notifyTarget(Player actor, @Nullable Player target, String operation) {
        if (target == null) {
            return;
        }
        switch (operation) {
            case "invite" -> sendConfigured(target, "team.invite-received", Map.of("player", actor.getName()));
            case "kick" -> sendConfigured(target, "team.kicked", Map.of());
            case "transfer" -> sendConfigured(target, "team.you-are-leader", Map.of());
            default -> {
                // Accept and decline resolve the inviter separately.
            }
        }
    }

    private Optional<UUID> findInviter(Player player, String name) {
        return teamManager.invitationsFor(player.getUniqueId()).stream()
            .map(TeamManager.Invitation::inviterId)
            .filter(uuid -> {
                String inviterName = Bukkit.getOfflinePlayer(uuid).getName();
                return inviterName != null && inviterName.equalsIgnoreCase(name);
            })
            .findFirst();
    }

    private static Player findOnlinePlayer(String name) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    private OfflinePlayer findStatsPlayer(String name) {
        Player online = findOnlinePlayer(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        return statistics.snapshot().keySet().stream()
            .map(Bukkit::getOfflinePlayer)
            .filter(player -> player.getName() != null && player.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    private static String teamResultPath(TeamManager.ActionResult result) {
        return switch (result) {
            case SUCCESS -> "team.done";
            case LOCKED -> "team.result.locked";
            case NOT_LEADER -> "team.result.not-leader";
            case NOT_IN_TEAM -> "team.result.not-in-team";
            case NOT_A_MEMBER -> "team.result.not-a-member";
            case TEAM_FULL -> "team.result.team-full";
            case TARGET_ALREADY_IN_TEAM -> "team.result.target-already-in-team";
            case ALREADY_MEMBER -> "team.result.already-member";
            case CANNOT_TARGET_SELF -> "team.result.cannot-target-self";
            case INVITATION_NOT_FOUND -> "team.result.invitation-not-found";
        };
    }

    private void sendConfigured(CommandSender sender, String path, Map<String, ?> placeholders) {
        Player player = sender instanceof Player target ? target : null;
        sender.sendMessage(text.prefix(player).append(text.message(player, path, placeholders)));
    }

    private void sendConfiguredLines(CommandSender sender, String path, Map<String, ?> placeholders) {
        Player player = sender instanceof Player target ? target : null;
        Component prefix = text.prefix(player);
        for (Component line : text.messageLines(player, path, placeholders)) {
            sender.sendMessage(prefix.append(line));
        }
    }

    private void sendRawConfigured(CommandSender sender, String path, Map<String, ?> placeholders) {
        Player player = sender instanceof Player target ? target : null;
        sender.sendMessage(text.message(player, path, placeholders));
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull CommandSender sender,
        @NotNull Command command,
        @NotNull String alias,
        @NotNull String[] args
    ) {
        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>(List.of("help"));
            if (sender.hasPermission(PERMISSION_TEAM)) {
                subcommands.add("team");
            }
            if (sender.hasPermission(PERMISSION_USE)) {
                subcommands.add("loadout");
            }
            if (sender.hasPermission(PERMISSION_STATS)) {
                subcommands.add("stats");
            }
            addPermitted(subcommands, sender, PERMISSION_START, "start");
            addPermitted(subcommands, sender, PERMISSION_STOP, "stop");
            addPermitted(subcommands, sender, PERMISSION_RELOAD, "reload");
            addPermitted(subcommands, sender, PERMISSION_SET_LOBBY, "setlobby");
            return filter(subcommands, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            if (statistics == null || !sender.hasPermission(PERMISSION_STATS_OTHERS)) {
                return List.of();
            }
            return filter(statPlayerNames(), args[1]);
        }
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("team") && sender.hasPermission(PERMISSION_TEAM)) {
            return filter(List.of("menu", "invite", "accept", "decline", "leave", "kick", "transfer", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("loadout") && sender.hasPermission(PERMISSION_USE)) {
            return filter(List.of("reset"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("team") && sender.hasPermission(PERMISSION_TEAM)) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "invite" -> filter(invitablePlayerNames(player), args[2]);
                case "accept", "decline" -> filter(inviterNames(player), args[2]);
                case "kick", "transfer" -> filter(teamMemberNames(player), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> statPlayerNames() {
        Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(names::add);
        statistics.snapshot().keySet().stream()
            .map(Bukkit::getOfflinePlayer)
            .map(OfflinePlayer::getName)
            .filter(Objects::nonNull)
            .forEach(names::add);
        return List.copyOf(names);
    }

    private List<String> invitablePlayerNames(Player player) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
            .filter(menuManager::hasLobbyAccess)
            .filter(target -> teamManager.teamOf(target.getUniqueId()).map(team -> team.size() <= 1).orElse(true))
            .map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private List<String> inviterNames(Player player) {
        return teamManager.invitationsFor(player.getUniqueId()).stream()
            .map(TeamManager.Invitation::inviterId)
            .map(Bukkit::getOfflinePlayer)
            .map(OfflinePlayer::getName)
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    private List<String> teamMemberNames(Player player) {
        return teamManager.teamOf(player.getUniqueId()).stream()
            .map(TeamData::members)
            .flatMap(Collection::stream)
            .filter(uuid -> !uuid.equals(player.getUniqueId()))
            .map(Bukkit::getOfflinePlayer)
            .map(OfflinePlayer::getName)
            .filter(Objects::nonNull)
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private static void addPermitted(
        Collection<String> values,
        CommandSender sender,
        String permission,
        String value
    ) {
        if (hasAdminPermission(sender, permission)) {
            values.add(value);
        }
    }

    private static boolean hasAdminPermission(CommandSender sender, String permission) {
        return sender.hasPermission(PERMISSION_ADMIN) || sender.hasPermission(permission);
    }

    private static List<String> filter(Collection<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    @FunctionalInterface
    private interface BooleanOperation {
        boolean run();
    }

    public interface AdministrativeActions {
        boolean startGame();

        boolean stopGame();

        void reload();

        void setLobby(Location location);
    }
}
