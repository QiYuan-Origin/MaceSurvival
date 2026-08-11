package club.mcqi.macesurvival.placeholder;

import club.mcqi.macesurvival.data.StatsStore;
import club.mcqi.macesurvival.game.GameFacade;
import club.mcqi.macesurvival.team.TeamManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/** Keeps PlaceholderAPI optional at runtime. */
public final class PlaceholderRegistration {
    private static final Runnable NO_OP = () -> { };

    private PlaceholderRegistration() {
    }

    public static Runnable registerIfAvailable(
        JavaPlugin plugin,
        GameFacade game,
        TeamManager teams,
        StatsStore statistics
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(teams, "teams");
        Objects.requireNonNull(statistics, "statistics");
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return NO_OP;
        }

        MaceSurvivalExpansion expansion = new MaceSurvivalExpansion(plugin, game, teams, statistics);
        if (!expansion.register()) {
            plugin.getLogger().warning("Could not register the MaceSurvival PlaceholderAPI expansion");
            return NO_OP;
        }
        return () -> {
            if (expansion.isRegistered()) {
                expansion.unregister();
            }
        };
    }
}
