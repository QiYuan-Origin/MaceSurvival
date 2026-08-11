package club.mcqi.macesurvival.game;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface GameFacade {
    GameState state();
    boolean forceStart(int countdownSeconds);
    void stopMatch(boolean announce);
    void handleJoin(Player player);
    void handleQuit(Player player);
    void eliminate(Player victim, Player killer);
    boolean isAlive(UUID playerId);
    boolean isParticipant(UUID playerId);
    boolean sameTeam(UUID first, UUID second);
    Optional<Participant> participant(UUID playerId);
    Collection<Participant> participants();
    Optional<Location> lobbyLocation();
    long elapsedSeconds();
    double currentBorderRadius();
}
