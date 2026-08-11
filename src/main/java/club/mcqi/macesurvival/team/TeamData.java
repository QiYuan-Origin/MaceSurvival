package club.mcqi.macesurvival.team;

import org.bukkit.Color;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TeamData {
    private final UUID id;
    private UUID leader;
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    private final Color color;

    public TeamData(UUID id, UUID leader, Color color) {
        this.id = id;
        this.leader = leader;
        this.color = color;
        members.add(leader);
    }

    public UUID id() { return id; }
    public UUID leader() { return leader; }
    public void setLeader(UUID leader) { this.leader = leader; }
    public Set<UUID> members() { return Set.copyOf(members); }
    public List<UUID> membersInJoinOrder() { return List.copyOf(members); }
    public boolean addMember(UUID playerId) { return members.size() < 4 && members.add(playerId); }
    public boolean removeMember(UUID playerId) { return members.remove(playerId); }
    public boolean contains(UUID playerId) { return members.contains(playerId); }
    public int size() { return members.size(); }
    public Color color() { return color; }
}
