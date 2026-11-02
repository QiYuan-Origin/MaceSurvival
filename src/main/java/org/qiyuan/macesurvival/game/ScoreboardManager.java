package org.qiyuan.macesurvival.game;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Updates existing teams in place, keeping the sidebar stable during combat. */
public final class ScoreboardManager {
    private record Board(Scoreboard scoreboard, Objective objective) { }
    private final MaceSurvival plugin;
    private final Map<UUID, Board> boards = new HashMap<>();
    private FileConfiguration config;
    private BukkitTask updater;

    public ScoreboardManager(MaceSurvival plugin) { this.plugin = plugin; reload(); }
    public void reload() {
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "scoreboard.yml"));
        var resource = plugin.getResource("scoreboard.yml");
        if (resource != null) try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (java.io.IOException exception) {
            plugin.getLogger().severe("Could not read scoreboard defaults: " + exception.getMessage());
        }
    }
    public void startUpdateTask() {
        if (updater != null) updater.cancel();
        updater = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, Math.max(1, config.getInt("update-ticks", 20)));
    }
    public void stop() { if (updater != null) updater.cancel(); removeAll(); }
    public void showLobbyBoard(Player player) { update(player); }
    public void showGameBoard(Player player, GamePlayer gp) { update(player); }
    public String phaseLabel() { return config.getString("phases." + plugin.game().state().name().toLowerCase(Locale.ROOT), "等待中"); }
    public void updateAll() {
        boards.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }
    private void update(Player player) {
        if (!config.getBoolean("enabled", true) || !plugin.configs().main().getBoolean("scoreboard.enabled", true)) { remove(player); return; }
        Board board = boards.computeIfAbsent(player.getUniqueId(), ignored -> createBoard());
        var game = plugin.game();
        var gp = game.getPlayer(player);
        String phase = game.state().name().toLowerCase(Locale.ROOT);
        String section = gp.isSpectator() && game.state() != GameState.WAITING && game.state() != GameState.COUNTDOWN ? "spectator" : phase;
        Map<String, String> values = values(player, gp);
        updateNames(board.scoreboard);
        player.sendPlayerListHeaderAndFooter(Text.parse(resolve(config.getString("tab.header", ""), values), player), Text.parse(resolve(config.getString("tab.footer", ""), values), player));
        board.objective.displayName(Text.parse(resolve(config.getString("title", "<white>重锤生存</white>"), values), player));
        List<String> lines = new ArrayList<>(config.getStringList("lines." + section));
        lines.removeIf(line -> line.matches("\\{team_[1-4]}") && resolve(line, values).isBlank());
        String address = plugin.configs().main().getString("scoreboard.server-ip", config.getString("server-address", ""));
        if (address != null && !address.isBlank()) { lines.add(""); lines.add(address); }
        int count = Math.min(15, lines.size());
        for (int i = 0; i < 15; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            Team team = board.scoreboard.getTeam("line" + i);
            if (team == null) { team = board.scoreboard.registerNewTeam("line" + i); team.addEntry(entry); }
            if (i < count) {
                var rendered = Text.parse(resolve(lines.get(i), values), player);
                if (config.getBoolean("text-shadow", true)) rendered = rendered.shadowColor(ShadowColor.shadowColor(0xFF404040));
                team.prefix(rendered);
                board.objective.getScore(entry).setScore(count - i);
            } else board.scoreboard.resetScores(entry);
        }
        if (player.getScoreboard() != board.scoreboard) player.setScoreboard(board.scoreboard);
    }
    private Board createBoard() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("macesurvival", Criteria.DUMMY, Text.parse(config.getString("title", "重锤生存")));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        return new Board(scoreboard, objective);
    }
    private void updateNames(Scoreboard board) {
        var wanted = new java.util.HashSet<String>();
        Map<String, String> membership = new HashMap<>();
        boolean squadFriendlyFire = !plugin.configs().main().getBoolean("game.friendly-fire-disabled", true);
        for (Player target : Bukkit.getOnlinePlayers()) {
            var gp = plugin.game().getPlayer(target); var squad = gp.squad();
            String name = gp.isSpectator() ? "name_out" : squad == null ? "name_solo" : "name_s" + squad.id();
            membership.put(target.getName(), name);
            wanted.add(name); Team team = board.getTeam(name);
            NamedTextColor color = gp.isSpectator() ? NamedTextColor.DARK_GRAY : squad == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(TextColor.fromHexString(squad.color().replace("<", "").replace(">", "")));
            // A new CraftTeam starts at RESET, which its Adventure color getter cannot represent.
            // Each name_* identity has a fixed color, so initialize once without reading RESET.
            if (team == null) { team = board.registerNewTeam(name); team.color(color); }
            String prefix = gp.isSpectator() ? config.getString("nametag.eliminated-prefix", "☠ ") : squad == null ? "" : config.getString("nametag.squad-prefix", "[{team_id}] ").replace("{team_id}", Integer.toString(squad.id()));
            var component = Text.parse(prefix).colorIfAbsent(color);
            if (!team.prefix().equals(component)) team.prefix(component);
            // Solo/spectator teams share only their presentation; only a real squad is a combat alliance.
            boolean friendlyFire = gp.isSpectator() || squad == null || squadFriendlyFire;
            if (team.allowFriendlyFire() != friendlyFire) team.setAllowFriendlyFire(friendlyFire);
            if (!team.canSeeFriendlyInvisibles()) team.setCanSeeFriendlyInvisibles(true);
            // Bukkit transfers an entry out of its previous team, covering squad and role changes.
            if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
        }
        for (Team team : List.copyOf(board.getTeams())) if (team.getName().startsWith("name_")) {
            if (!wanted.contains(team.getName())) team.unregister();
            else for (String entry : List.copyOf(team.getEntries()))
                if (!team.getName().equals(membership.get(entry))) team.removeEntry(entry);
        }
    }
    private Map<String, String> values(Player player, GamePlayer gp) {
        var game = plugin.game();
        int players = game.totalCount();
        int min = Math.max(2, plugin.configs().main().getInt("game.min-players", 2));
        boolean soloAllowed = plugin.configs().main().getBoolean("squad.allow-solo", true);
        var eligible = game.allPlayers().stream().filter(other -> other.isAlive() && Bukkit.getPlayer(other.uuid()) != null
            && (soloAllowed || other.squad() != null)).toList();
        int needed = Math.max(0, min - eligible.size());
        Map<String, String> values = new HashMap<>();
        values.put("player", player.getName()); values.put("players", String.valueOf(players));
        values.put("minimum", String.valueOf(min)); values.put("needed", String.valueOf(needed));
        values.put("eligible_players", String.valueOf(eligible.size()));
        values.put("squad_size", String.valueOf(Math.max(1, Math.min(16, plugin.configs().main().getInt("squad.size", 4)))));
        long sides = eligible.stream()
            .map(other -> other.squad() == null ? "p:" + other.uuid() : "s:" + other.squad().id()).distinct().count();
        String hint = game.autoStartPaused() ? config.getString("labels.paused", "等待重新开启") : needed > 0 ? config.getString(soloAllowed ? "labels.waiting-players" : "labels.waiting-team-players", "还需 {needed} 名参赛玩家")
            : sides < 2 ? config.getString("labels.waiting-opponent", "等待另一支队伍加入") : config.getString("labels.ready", "即将开始倒计时");
        values.put("waiting_hint", resolve(hint, values));
        values.put("squad_rule", resolve(config.getString(soloAllowed ? "labels.solo-allowed-rule" : "labels.solo-disabled-rule", "每队最多 {squad_size} 人"), values));
        values.put("maximum", String.valueOf(Math.max(2, plugin.configs().main().getInt("game.max-players", Bukkit.getMaxPlayers()))));
        values.put("countdown", String.valueOf(Math.max(0, game.countdown())));
        values.put("phase", config.getString("phases." + game.state().name().toLowerCase(Locale.ROOT), "等待中"));
        values.put("alive", String.valueOf(game.aliveCount())); values.put("alive_squads", String.valueOf(game.aliveSquads()));
        values.put("kills", String.valueOf(gp.roundKills())); values.put("elapsed", time(game.elapsedSeconds()));
        values.put("grace", String.valueOf(Math.max(0, game.graceRemaining()))); values.put("border_time", time(game.borderRemaining()));
        values.put("combat", game.graceRemaining() > 0 ? config.getString("labels.grace", "保护中") : config.getString("labels.pvp", "战斗中"));
        values.put("combat_state", game.graceRemaining() > 0 ? config.getString("labels.grace-remaining", "保护中 · {grace} 秒").replace("{grace}", String.valueOf(game.graceRemaining())) : config.getString("labels.pvp", "战斗中"));
        values.put("border_state", game.borderRemaining() > 0 ? time(game.borderRemaining()) : config.getString("labels.border-active", "收缩中"));
        values.put("squad", gp.squad() == null ? config.getString(soloAllowed ? "labels.solo" : "labels.unteamed", soloAllowed ? "单人生存" : "尚未组队") : net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(gp.squad().name()));
        int teamAlive = (int) game.getAlivePlayers().stream().filter(other -> gp.squad() == null ? other.uuid().equals(gp.uuid()) : other.squad() != null && other.squad().id() == gp.squad().id()).count();
        values.put("team_alive", String.valueOf(teamAlive));
        var teammates = gp.squad() == null ? List.of(gp.uuid()) : List.copyOf(gp.squad().members());
        for (int i = 0; i < 4; i++) {
            String line = "";
            if (i < teammates.size()) {
                UUID id = teammates.get(i); Player member = Bukkit.getPlayer(id);
                var other = game.getPlayer(id);
                String name = member == null ? Bukkit.getOfflinePlayer(id).getName() : member.getName();
                if (name == null) name = "玩家";
                line = member != null && other.isAlive() ? "<white>" + Text.escape(name) + "</white> <#D99587>" + (int) Math.ceil(member.getHealth()) + "❤</#D99587>" : "<dark_gray>" + Text.escape(name) + " · 已淘汰</dark_gray>";
            }
            values.put("team_" + (i + 1), line);
        }
        var arenaWorld = plugin.arenas().getArenaWorld();
        values.put("border", arenaWorld == null ? "—" : String.valueOf(Math.round(arenaWorld.getWorldBorder().getSize())));
        return values;
    }
    private static String time(int seconds) { seconds = Math.max(0, seconds); return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }
    private static String resolve(String raw, Map<String, String> values) {
        if (raw == null) return "";
        for (var entry : values.entrySet()) raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        return raw.replaceAll("\\{[A-Za-z0-9_.-]+}", "—");
    }
    public void remove(Player player) {
        Board removed = boards.remove(player.getUniqueId());
        player.sendPlayerListHeaderAndFooter(net.kyori.adventure.text.Component.empty(), net.kyori.adventure.text.Component.empty());
        if (removed != null && player.getScoreboard() == removed.scoreboard) player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }
    public void removeAll() {
        for (UUID id : List.copyOf(boards.keySet())) { Player player = Bukkit.getPlayer(id); if (player != null) remove(player); }
        boards.clear();
    }
}
