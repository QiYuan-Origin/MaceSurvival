package org.qiyuan.macesurvival.game;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一支小队。成员用有序集合，记分板和结算列表的顺序才稳定。
 */
public final class Squad {

    /**
     * 队伍配色。hex 同时供聊天、名字和 Scoreboard Team 使用，
     * Team 那边由 NamedTextColor.nearestTo 折算成最近的原版颜色。
     */
    public record Palette(String hex, String label) {
    }

    private final String id;
    private final int number;
    private final Palette palette;
    private final Set<GamePlayer> members = new LinkedHashSet<>();

    public Squad(String id, int number, Palette palette) {
        this.id = id;
        this.number = number;
        this.palette = palette;
    }

    public String id() {
        return id;
    }

    public int number() {
        return number;
    }

    public Palette palette() {
        return palette;
    }

    public Set<GamePlayer> members() {
        return members;
    }

    public int aliveCount() {
        return (int) members.stream().filter(m -> m.role().isPlaying()).count();
    }

    public int totalKills() {
        return members.stream().mapToInt(GamePlayer::kills).sum();
    }

    public boolean contains(GamePlayer gp) {
        return members.contains(gp);
    }
}
