package org.qiyuan.macesurvival.game;

import org.bukkit.entity.Player;
import org.qiyuan.macesurvival.MaceSurvival;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 小队。单人模式下每人一支队，这样胜负判定只有一套逻辑。
 * 队伍颜色会同步到玩家名字，队友之间一眼能认出来。
 */
public final class SquadManager {

    /** 队伍配色。对抗类玩法必须用队伍色区分敌我。 */
    private static final List<Squad.Palette> PALETTES = List.of(
            new Squad.Palette("#E8523F", "Crimson"),
            new Squad.Palette("#4C8CE8", "Azure"),
            new Squad.Palette("#5FBF6A", "Verdant"),
            new Squad.Palette("#E0B44A", "Amber"),
            new Squad.Palette("#A96FD8", "Violet"),
            new Squad.Palette("#49C7C2", "Cyan"),
            new Squad.Palette("#E88BB5", "Rose"),
            new Squad.Palette("#8C9BA5", "Ashen"),
            new Squad.Palette("#D3763C", "Ember"),
            new Squad.Palette("#7BE05A", "Lime"));

    private final MaceSurvival plugin;
    private final Map<String, Squad> squads = new LinkedHashMap<>();
    /** 大厅里玩家自己邀请形成的预组队，开局时优先保留。 */
    private final Map<UUID, Set<UUID>> preTeams = new LinkedHashMap<>();
    /** 待处理的邀请：被邀请者 -> 邀请他的人。 */
    private final Map<UUID, Set<UUID>> invites = new LinkedHashMap<>();

    public SquadManager(MaceSurvival plugin) {
        this.plugin = plugin;
    }

    public int teamSize() {
        return Math.max(1, plugin.configs().main().getInt("game.squad-size", 4));
    }

    public boolean soloMode() {
        return teamSize() <= 1;
    }

    /** 开局分队。先落实预组队，剩下的人按顺序填补空位。 */
    public void assignSquads(Collection<GamePlayer> participants) {
        squads.clear();
        int size = teamSize();
        List<GamePlayer> pending = new ArrayList<>(participants);

        if (size > 1) {
            for (Map.Entry<UUID, Set<UUID>> entry : preTeams.entrySet()) {
                List<GamePlayer> members = new ArrayList<>();
                GamePlayer leader = find(pending, entry.getKey());
                if (leader == null) {
                    continue;
                }
                members.add(leader);
                for (UUID member : entry.getValue()) {
                    GamePlayer gp = find(pending, member);
                    if (gp != null && members.size() < size) {
                        members.add(gp);
                    }
                }
                if (members.size() < 2) {
                    continue;
                }
                Squad squad = create();
                members.forEach(gp -> add(squad, gp));
                pending.removeAll(members);
            }
        }

        Squad current = null;
        for (GamePlayer gp : pending) {
            if (current == null || current.members().size() >= size) {
                current = create();
            }
            add(current, gp);
        }
        preTeams.clear();
    }

    private GamePlayer find(List<GamePlayer> pool, UUID uuid) {
        for (GamePlayer gp : pool) {
            if (gp.uuid().equals(uuid)) {
                return gp;
            }
        }
        return null;
    }

    private Squad create() {
        String id = "squad-" + (squads.size() + 1);
        Squad.Palette palette = PALETTES.get(squads.size() % PALETTES.size());
        Squad squad = new Squad(id, squads.size() + 1, palette);
        squads.put(id, squad);
        return squad;
    }

    private void add(Squad squad, GamePlayer gp) {
        squad.members().add(gp);
        gp.squadId(squad.id());
        Player online = gp.online();
        if (online != null) {
            plugin.nametags().apply(online, squad);
        }
    }

    // ─────────────── 预组队 ───────────────

    /** 发出邀请。对方同意之前不进预组队，免得被人硬拉进队。 */
    public void offer(Player leader, Player target) {
        invites.computeIfAbsent(target.getUniqueId(), id -> new LinkedHashSet<>())
                .add(leader.getUniqueId());
    }

    public boolean hasInvite(UUID target, UUID leader) {
        Set<UUID> pending = invites.get(target);
        return pending != null && pending.contains(leader);
    }

    /** 接受邀请。同一个人只能待在一支预组队里，所以先退掉旧的。 */
    public void accept(Player target, Player leader) {
        Set<UUID> pending = invites.get(target.getUniqueId());
        if (pending != null) {
            pending.remove(leader.getUniqueId());
            if (pending.isEmpty()) {
                invites.remove(target.getUniqueId());
            }
        }
        leavePreTeam(target.getUniqueId());
        invite(leader, target);
    }

    public void invite(Player leader, Player target) {
        preTeams.computeIfAbsent(leader.getUniqueId(), id -> new LinkedHashSet<>())
                .add(target.getUniqueId());
    }

    public void leavePreTeam(UUID uuid) {
        preTeams.remove(uuid);
        preTeams.values().forEach(set -> set.remove(uuid));
        preTeams.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        invites.remove(uuid);
        invites.values().forEach(set -> set.remove(uuid));
        invites.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public Set<UUID> preTeamOf(UUID uuid) {
        Set<UUID> direct = preTeams.get(uuid);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<UUID, Set<UUID>> entry : preTeams.entrySet()) {
            if (entry.getValue().contains(uuid)) {
                Set<UUID> out = new LinkedHashSet<>(entry.getValue());
                out.add(entry.getKey());
                out.remove(uuid);
                return out;
            }
        }
        return Set.of();
    }

    // ─────────────── 查询 ───────────────

    public void onEliminated(GamePlayer gp) {
        // 淘汰状态存在 GamePlayer 上，这里只负责在全队覆灭时提示
        Squad squad = squadOf(gp);
        if (squad == null || soloMode()) {
            return;
        }
        boolean wiped = squad.members().stream().noneMatch(m -> m.role().isPlaying());
        if (wiped) {
            plugin.game().broadcastAll("squad.wiped",
                    org.qiyuan.macesurvival.config.MessageManager.vars(
                            "squad", displayName(squad.id())));
        }
    }

    public Squad squadOf(GamePlayer gp) {
        return gp.squadId() == null ? null : squads.get(gp.squadId());
    }

    public Squad byId(String id) {
        return squads.get(id);
    }

    public Collection<Squad> all() {
        return squads.values();
    }

    public Collection<GamePlayer> membersOf(String squadId) {
        Squad squad = squads.get(squadId);
        return squad == null ? List.of() : squad.members();
    }

    /** 还有存活成员的队伍。判胜只看这个。 */
    public Set<String> aliveSquadIds() {
        Set<String> out = new LinkedHashSet<>();
        for (Squad squad : squads.values()) {
            if (squad.members().stream().anyMatch(m -> m.role().isPlaying())) {
                out.add(squad.id());
            }
        }
        return out;
    }

    /** 超时结算用：存活人数最多的队伍，人数相同时取击杀多的。 */
    public String largestAliveSquad() {
        String best = null;
        int bestAlive = -1;
        int bestKills = -1;
        for (String id : aliveSquadIds()) {
            Squad squad = squads.get(id);
            int alive = (int) squad.members().stream().filter(m -> m.role().isPlaying()).count();
            int kills = squad.members().stream().mapToInt(GamePlayer::kills).sum();
            if (alive > bestAlive || (alive == bestAlive && kills > bestKills)) {
                best = id;
                bestAlive = alive;
                bestKills = kills;
            }
        }
        return best;
    }

    /** 单人模式直接显示玩家名，显示队名反而奇怪。 */
    public String displayName(String squadId) {
        Squad squad = squads.get(squadId);
        if (squad == null) {
            return "-";
        }
        if (soloMode() && !squad.members().isEmpty()) {
            return squad.members().iterator().next().name();
        }
        return squad.palette().label() + " " + squad.number();
    }

    public String colorTag(String squadId) {
        Squad squad = squads.get(squadId);
        return squad == null ? "<#9AA0A6>" : "<" + squad.palette().hex() + ">";
    }

    public void clear() {
        squads.clear();
        preTeams.clear();
    }
}
