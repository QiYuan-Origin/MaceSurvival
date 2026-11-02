package org.qiyuan.macesurvival.command;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.data.StatsManager;
import org.qiyuan.macesurvival.game.Squad;
import java.util.*;

/** Commands, help and completion use the same per-branch Bukkit/LuckPerms permissions. */
public final class MaceCommand implements CommandExecutor, TabCompleter {
    private final MaceSurvival plugin;
    private static final List<String> PLAYER = List.of("help", "menu", "stats", "team", "spectate", "layout", "leaderboard");
    private static final List<String> ADMIN = List.of("start", "stop", "reload", "map", "loot");
    public MaceCommand(MaceSurvival plugin) { this.plugin = plugin; }
    private boolean allowed(CommandSender s, String branch) { return branch.equals("help") || s.hasPermission("macesurvival." + (ADMIN.contains(branch) ? "admin." : "") + branch); }
    @Override public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, @NotNull String[] args) {
        String branch = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        if (!PLAYER.contains(branch) && !ADMIN.contains(branch)) { plugin.messages().send(s, "command.unknown"); return true; }
        if (!allowed(s, branch)) { plugin.messages().send(s, "command.no-permission"); return true; }
        try {
            switch (branch) {
                case "help" -> help(s, args);
                case "start" -> plugin.messages().send(s, plugin.game().startCountdown() ? "command.start.success" : "command.start.failed");
                case "stop" -> plugin.messages().send(s, plugin.game().stopGame() ? "command.stop.success" : "command.stop.failed");
                case "reload" -> {
                    try { plugin.reloadAll(); plugin.messages().send(s, "command.reload.success"); }
                    catch (IllegalStateException failure) { plugin.messages().send(s, "command.reload.failed"); plugin.getLogger().severe(failure.getMessage()); }
                }
                case "menu", "layout", "spectate" -> { if (s instanceof Player p) plugin.menus().open(p, branch.equals("menu") ? "main" : branch); else plugin.messages().send(s, "command.player-only"); }
                case "stats" -> stats(s, args);
                case "team" -> team(s, args);
                case "leaderboard" -> leaderboard(s, args);
                case "map" -> map(s, args);
                case "loot" -> loot(s, args);
                default -> { }
            }
        } catch (NumberFormatException ex) { plugin.messages().send(s, "command.invalid-number"); }
        return true;
    }
    private void help(CommandSender s, String[] args) {
        int page = 1;
        if (args.length > 1) try { page = Integer.parseInt(args[1]); }
        catch (NumberFormatException exception) { s.sendMessage(plugin.messages().get("command.invalid-number")); return; }
        List<String> entries = new ArrayList<>();
        for (String b : PLAYER) if (!b.equals("help") && allowed(s,b)) entries.add(b);
        for (String b : ADMIN) if (allowed(s,b)) entries.add(b);
        int pages = Math.max(1, (entries.size()+6)/7); page = Math.max(1, Math.min(pages,page));
        s.sendMessage(plugin.messages().get("command.help.header", Map.of("page", Integer.toString(page), "pages", Integer.toString(pages))));
        for (int i=(page-1)*7;i<Math.min(page*7,entries.size());i++) s.sendMessage(plugin.messages().get("command.help." + entries.get(i)));
        if (pages > 1) s.sendMessage(plugin.messages().get("command.help.footer", Map.of("page",Integer.toString(page),"pages",Integer.toString(pages))));
    }
    private void stats(CommandSender s, String[] a) {
        UUID id = s instanceof Player p ? p.getUniqueId() : null;
        if (a.length > 1) {
            var found = plugin.stats().top(StatsManager.Board.GAMES, Integer.MAX_VALUE).stream().filter(r -> r.name().equalsIgnoreCase(a[1])).findFirst();
            if (found.isEmpty()) { plugin.messages().send(s, "command.player-not-found"); return; } id = found.get().uuid();
        }
        if (id == null) { plugin.messages().send(s,"command.player-only"); return; }
        if (a.length == 1 && s instanceof Player p) { plugin.menus().open(p,"stats"); return; }
        var r = plugin.stats().get(id);
        plugin.messages().send(s,"command.stats.summary", Map.of("player",r.name(),"kills",Integer.toString(r.kills()),"deaths",Integer.toString(r.deaths()),"wins",Integer.toString(r.wins()),"games",Integer.toString(r.games())));
    }
    private void team(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.messages().send(s,"command.player-only"); return; }
        if (a.length == 1) { plugin.menus().open(p,"team"); return; }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "create" -> { if (a.length > 2) plugin.game().createTeam(p,String.join(" ",Arrays.copyOfRange(a,2,a.length))); else plugin.game().beginTeamInput(p); }
            case "join" -> {
                if (a.length < 3) { plugin.messages().send(s,"command.team.usage"); break; }
                String selection = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                Squad target = plugin.game().squads().stream().filter(t -> t.name().equalsIgnoreCase(selection) || Integer.toString(t.id()).equals(selection)).findFirst().orElse(null);
                if (target == null) plugin.messages().send(s,"squad.not-found"); else plugin.game().joinTeam(p,target.id());
            }
            case "leave" -> plugin.game().leaveTeam(p);
            case "invite" -> { var target = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null; if (target == null) plugin.messages().send(s,"command.player-not-found"); else plugin.game().invite(p,target); }
            case "accept" -> plugin.game().accept(p);
            case "list" -> {
                if (plugin.game().squads().isEmpty()) plugin.messages().send(s,"squad.empty");
                for (var t : plugin.game().squads()) plugin.messages().send(s,"squad.list-entry",Map.of("name",t.name(),"id",Integer.toString(t.id()),"size",Integer.toString(t.size())));
            }
            default -> plugin.messages().send(s,"command.team.usage");
        }
    }
    private void leaderboard(CommandSender s, String[] a) {
        StatsManager.Board board = StatsManager.Board.WINS;
        if (a.length > 1) try { board = StatsManager.Board.valueOf(a[1].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { plugin.messages().send(s,"command.leaderboard.usage"); return; }
        var list = plugin.stats().top(board,10);
        plugin.messages().send(s,"leaderboard.header",Map.of("board",plugin.messages().plain(board.messageKey())));
        if (list.isEmpty()) plugin.messages().send(s,"leaderboard.empty");
        int rank=0;
        for (var r : list) {
            String value = switch(board) { case WINS -> ""+r.wins(); case KILLS -> ""+r.kills(); case GAMES -> ""+r.games(); case STREAK -> ""+r.bestStreak(); case KD -> String.format(Locale.ROOT,"%.2f",r.kd()); };
            plugin.messages().send(s,"leaderboard.entry",Map.of("rank",""+(++rank),"player",r.name(),"value",value));
        }
    }
    private void map(CommandSender s, String[] a) {
        if (a.length > 1 && a[1].equalsIgnoreCase("regen")) {
            if (!plugin.game().prepareMap(s)) plugin.messages().send(s,"command.map.busy");
            return;
        }
        var world = plugin.arenas().getArenaWorld();
        plugin.messages().send(s,"command.map.info",Map.of("world",world == null ? "未生成" : world.getName(),"chests",""+plugin.arenas().getChestLocations().size(),"state",plugin.game().state().name()));
    }
    private void loot(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.messages().send(s,"command.player-only"); return; }
        if (a.length < 3 || !a[1].equalsIgnoreCase("test")) { plugin.messages().send(s,"command.loot.usage"); return; }
        int tier = Integer.parseInt(a[2]);
        if (tier < 1 || tier > 3) { plugin.messages().send(s,"command.loot.usage"); return; }
        plugin.menus().openLootPreview(p, org.qiyuan.macesurvival.arena.ChestTier.values()[tier-1]);
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender s,@NotNull Command c,@NotNull String label,@NotNull String[] a) {
        List<String> candidates=new ArrayList<>();
        if (a.length==1) { candidates.addAll(PLAYER); candidates.addAll(ADMIN); candidates.removeIf(b -> !allowed(s,b)); }
        else if (!allowed(s,a[0].toLowerCase(Locale.ROOT))) return List.of();
        else if (a.length==2) switch(a[0].toLowerCase(Locale.ROOT)) {
            case "team" -> candidates.addAll(List.of("create","join","leave","invite","accept","list"));
            case "map" -> candidates.addAll(List.of("info","regen"));
            case "loot" -> candidates.add("test");
            case "help" -> candidates.addAll(List.of("1","2"));
            case "leaderboard" -> candidates.addAll(List.of("wins","kills","kd","streak","games"));
            case "stats" -> plugin.stats().top(StatsManager.Board.GAMES,Integer.MAX_VALUE).forEach(r -> candidates.add(r.name()));
            default -> { }
        }
        else if (a.length==3 && a[0].equalsIgnoreCase("team")) {
            if (a[1].equalsIgnoreCase("join")) plugin.game().squads().forEach(t -> candidates.add(""+t.id()));
            if (a[1].equalsIgnoreCase("invite")) Bukkit.getOnlinePlayers().forEach(p -> candidates.add(p.getName()));
        } else if (a.length==3 && a[0].equalsIgnoreCase("loot")) candidates.addAll(List.of("1","2","3"));
        String prefix=a[a.length-1].toLowerCase(Locale.ROOT);
        return candidates.stream().filter(Objects::nonNull).filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
