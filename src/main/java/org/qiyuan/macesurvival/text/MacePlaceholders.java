package org.qiyuan.macesurvival.text;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.qiyuan.macesurvival.MaceSurvival;
import java.util.Locale;

/** Optional PlaceholderAPI bridge reads the same round and persistent records as the menus. */
public final class MacePlaceholders extends PlaceholderExpansion {
    private final MaceSurvival plugin;
    public MacePlaceholders(MaceSurvival plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "macesurvival"; }
    @Override public @NotNull String getAuthor() { return "QiYuan"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, @NotNull String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        switch (key) {
            case "state": return plugin.game().state().name();
            case "phase": return plugin.scoreboard().phaseLabel();
            case "players": return Integer.toString(plugin.game().totalCount());
            case "alive": return Integer.toString(plugin.game().aliveCount());
            case "teams": return Integer.toString(plugin.game().aliveSquads());
            case "countdown": return Integer.toString(plugin.game().countdown());
            case "elapsed": return Integer.toString(plugin.game().elapsedSeconds());
            default: break;
        }
        if (player == null) return "";
        var record = plugin.stats().get(player.getUniqueId());
        switch (key) {
            case "kills": return Integer.toString(record.kills());
            case "deaths": return Integer.toString(record.deaths());
            case "wins": return Integer.toString(record.wins());
            case "games": return Integer.toString(record.games());
            case "kd": return String.format(Locale.ROOT, "%.2f", record.kd());
            default: break;
        }
        var online = player.getPlayer();
        if (online == null) return "";
        var gp = plugin.game().getPlayer(online);
        return switch (key) {
            case "round_kills" -> Integer.toString(gp.roundKills());
            case "squad" -> gp.squad() == null ? "单人生存" : gp.squad().name();
            case "role" -> gp.isSpectator() ? "旁观者" : "参赛者";
            case "balance" -> plugin.economy().format(plugin.economy().balance(online));
            default -> null;
        };
    }
}
