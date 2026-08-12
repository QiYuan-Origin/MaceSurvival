package club.mcqi.macesurvival.game;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface MatchEvents {
    default void setupLobby(Player player) { }
    default void stateChanged(GameState state) { }
    default void countdownChanged(int remainingSeconds) { }
    default void matchPrepared(Collection<Participant> participants) { }
    default void matchStarting(org.bukkit.World world, Collection<Participant> participants) { }
    default void matchActive(World world, Collection<Participant> participants, Collection<org.bukkit.Location> deploymentLocations) { }
    default void borderStageFinished(World world, int stageIndex, Collection<Participant> participants) { }
    default void boundaryMoved(World world, org.bukkit.Location center, double radius) { }
    default void playerEliminated(Player victim, Player killer, Participant participant) { }
    default void matchEnded(Set<UUID> winningTeam, Collection<Participant> participants) { }
    default void clearMatch() { }
}
