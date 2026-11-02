package org.qiyuan.macesurvival.game;

import org.bukkit.entity.Player;

import java.util.*;

/**
 * Represents a squad of players.
 */
public final class Squad {

    private final int id;
    private UUID leader;
    private final Set<UUID> members;
    private final String name;
    private final String color;

    public Squad(int id, UUID leader, String name, String color) {
        this.id = id;
        this.leader = leader;
        this.members = new LinkedHashSet<>();
        this.members.add(leader);
        this.name = name;
        this.color = color;
    }

    public int id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public void addMember(UUID uuid) {
        members.add(uuid);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        if (uuid.equals(leader) && !members.isEmpty()) leader = members.iterator().next();
    }

    public boolean isMember(UUID uuid) {
        return members.contains(uuid);
    }

    public int size() {
        return members.size();
    }

    public String name() {
        return name;
    }

    public String color() {
        return color;
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public String getColoredName() {
        return name;
    }

    public List<Player> getAliveMembers() {
        List<Player> alive = new ArrayList<>();
        for (UUID uuid : members) {
            Player p = org.bukkit.Bukkit.getPlayer(uuid);
            var plugin = org.qiyuan.macesurvival.MaceSurvival.getPlugin(org.qiyuan.macesurvival.MaceSurvival.class);
            if (p != null && p.isOnline() && plugin.game().getPlayer(uuid).isAlive()) {
                alive.add(p);
            }
        }
        return alive;
    }
}
