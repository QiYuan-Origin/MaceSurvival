package org.qiyuan.macesurvival.game;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;

/**
 * 队伍色名字与 Tab 前缀。单人模式不套队伍色，只给存活/淘汰做区分。
 * 用 Scoreboard Team 而不是改 displayName，这样头顶名字也会变色。
 */
public final class NametagManager {

    private final MaceSurvival plugin;

    public NametagManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    private Scoreboard board() {
        return plugin.getServer().getScoreboardManager().getMainScoreboard();
    }

    /** 分队后调用。Tab 里显示 [Azure 2] 这样的队标，头顶只染色不加前缀。 */
    public void apply(Player player, Squad squad) {
        if (squad == null) {
            return;
        }
        Team team = ensure("ms-" + squad.id(), squad);
        team.addEntry(player.getName());
    }

    private Team ensure(String name, Squad squad) {
        Scoreboard board = board();
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        team.color(NamedTextColor.nearestTo(Text.color(squad.palette().hex())));
        if (!plugin.squads().soloMode()) {
            team.prefix(Text.parse("<" + squad.palette().hex() + ">\u2726 "
                    + squad.palette().label() + " " + squad.number() + " <#4A4A4A>| "));
            team.setAllowFriendlyFire(!plugin.configs().main()
                    .getBoolean("game.friendly-fire-disabled", true));
            team.setCanSeeFriendlyInvisibles(true);
        } else {
            team.prefix(Text.parse("<" + squad.palette().hex() + ">"));
        }
        return team;
    }

    /** 淘汰后置灰，观战者一眼能看出谁已经出局。 */
    public void markEliminated(Player player) {
        Scoreboard board = board();
        Team team = board.getTeam("ms-out");
        if (team == null) {
            team = board.registerNewTeam("ms-out");
            team.color(NamedTextColor.DARK_GRAY);
            team.prefix(Text.parse("<#5A5A5A>\u2620 "));
        }
        team.addEntry(player.getName());
    }

    /** 回到大厅时清掉队伍标记，避免上一局的颜色留着。 */
    public void reset(Player player) {
        Scoreboard board = board();
        for (Team team : board.getTeams()) {
            if (team.getName().startsWith("ms-") && team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    /** 插件卸载时清理注册过的队伍，不留垃圾在主计分板上。 */
    public void clearAll() {
        for (Team team : board().getTeams()) {
            if (team.getName().startsWith("ms-")) {
                team.unregister();
            }
        }
    }
}
