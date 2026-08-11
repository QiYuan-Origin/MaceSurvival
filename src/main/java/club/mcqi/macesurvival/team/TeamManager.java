package club.mcqi.macesurvival.team;

import org.bukkit.Color;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TeamManager {
    public static final int MAX_TEAM_SIZE = 4;
    public static final Duration INVITATION_LIFETIME = Duration.ofSeconds(30);

    private final Map<UUID, TeamData> teams = new LinkedHashMap<>();
    private final Map<UUID, UUID> teamByPlayer = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, Invitation>> invitationsByTarget = new LinkedHashMap<>();
    private final BooleanSupplier externallyLocked;
    private final Clock clock;
    private boolean manuallyLocked;
    private Runnable changeListener = () -> { };

    public TeamManager(BooleanSupplier externallyLocked) {
        this(externallyLocked, Clock.systemUTC());
    }

    TeamManager(BooleanSupplier externallyLocked, Clock clock) {
        this.externallyLocked = Objects.requireNonNull(externallyLocked, "externallyLocked");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized boolean isLocked() {
        return manuallyLocked || externallyLocked.getAsBoolean();
    }

    public synchronized void setManuallyLocked(boolean locked) {
        manuallyLocked = locked;
        if (locked) {
            invitationsByTarget.clear();
        }
    }

    public synchronized void setChangeListener(Runnable listener) {
        changeListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized Optional<TeamData> teamOf(UUID playerId) {
        UUID teamId = teamByPlayer.get(playerId);
        return Optional.ofNullable(teamId == null ? null : teams.get(teamId));
    }

    public synchronized TeamData ensureSoloTeam(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        TeamData existing = teamOf(playerId).orElse(null);
        if (existing != null) {
            return existing;
        }

        UUID teamId = UUID.randomUUID();
        TeamData created = new TeamData(teamId, playerId, colorFor(teamId));
        teams.put(teamId, created);
        teamByPlayer.put(playerId, teamId);
        notifyChanged();
        return created;
    }

    public synchronized Collection<TeamData> teams() {
        return List.copyOf(teams.values());
    }

    public synchronized ActionResult invite(UUID inviterId, UUID targetId) {
        Objects.requireNonNull(inviterId, "inviterId");
        Objects.requireNonNull(targetId, "targetId");
        purgeExpiredInvitations();
        if (isLocked()) {
            return ActionResult.LOCKED;
        }
        if (inviterId.equals(targetId)) {
            return ActionResult.CANNOT_TARGET_SELF;
        }

        TeamData inviterTeam = ensureSoloTeam(inviterId);
        if (!inviterTeam.leader().equals(inviterId)) {
            return ActionResult.NOT_LEADER;
        }
        if (inviterTeam.contains(targetId)) {
            return ActionResult.ALREADY_MEMBER;
        }
        if (inviterTeam.size() >= MAX_TEAM_SIZE) {
            return ActionResult.TEAM_FULL;
        }

        TeamData targetTeam = teamOf(targetId).orElse(null);
        if (targetTeam != null && targetTeam.size() > 1) {
            return ActionResult.TARGET_ALREADY_IN_TEAM;
        }

        invitationsByTarget.computeIfAbsent(targetId, ignored -> new LinkedHashMap<>())
            .put(inviterId, new Invitation(inviterId, inviterTeam.id(), clock.instant().plus(INVITATION_LIFETIME)));
        return ActionResult.SUCCESS;
    }

    public synchronized ActionResult accept(UUID targetId, UUID inviterId) {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(inviterId, "inviterId");
        purgeExpiredInvitations();
        if (isLocked()) {
            return ActionResult.LOCKED;
        }

        Map<UUID, Invitation> invitations = invitationsByTarget.get(targetId);
        Invitation invitation = invitations == null ? null : invitations.get(inviterId);
        if (invitation == null) {
            return ActionResult.INVITATION_NOT_FOUND;
        }

        TeamData destination = teams.get(invitation.teamId());
        if (destination == null || !destination.leader().equals(inviterId)) {
            removeInvitation(targetId, inviterId);
            return ActionResult.INVITATION_NOT_FOUND;
        }
        if (destination.size() >= MAX_TEAM_SIZE) {
            removeInvitation(targetId, inviterId);
            return ActionResult.TEAM_FULL;
        }

        TeamData current = teamOf(targetId).orElse(null);
        if (current != null && current.size() > 1) {
            return ActionResult.TARGET_ALREADY_IN_TEAM;
        }
        if (current != null) {
            deleteTeam(current);
        }

        if (!destination.addMember(targetId)) {
            return ActionResult.TEAM_FULL;
        }
        teamByPlayer.put(targetId, destination.id());
        invitationsByTarget.remove(targetId);
        notifyChanged();
        return ActionResult.SUCCESS;
    }

    public synchronized ActionResult decline(UUID targetId, UUID inviterId) {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(inviterId, "inviterId");
        purgeExpiredInvitations();
        return removeInvitation(targetId, inviterId)
            ? ActionResult.SUCCESS
            : ActionResult.INVITATION_NOT_FOUND;
    }

    public synchronized List<Invitation> invitationsFor(UUID targetId) {
        purgeExpiredInvitations();
        Map<UUID, Invitation> invitations = invitationsByTarget.get(targetId);
        return invitations == null ? List.of() : List.copyOf(invitations.values());
    }

    public synchronized ActionResult kick(UUID leaderId, UUID targetId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        if (isLocked()) {
            return ActionResult.LOCKED;
        }
        TeamData team = teamOf(leaderId).orElse(null);
        if (team == null || !team.leader().equals(leaderId)) {
            return ActionResult.NOT_LEADER;
        }
        if (leaderId.equals(targetId)) {
            return ActionResult.CANNOT_TARGET_SELF;
        }
        if (!team.contains(targetId)) {
            return ActionResult.NOT_A_MEMBER;
        }
        team.removeMember(targetId);
        teamByPlayer.remove(targetId);
        invitationsByTarget.remove(targetId);
        notifyChanged();
        return ActionResult.SUCCESS;
    }

    public synchronized ActionResult transferLeadership(UUID leaderId, UUID targetId) {
        Objects.requireNonNull(leaderId, "leaderId");
        Objects.requireNonNull(targetId, "targetId");
        if (isLocked()) {
            return ActionResult.LOCKED;
        }
        TeamData team = teamOf(leaderId).orElse(null);
        if (team == null || !team.leader().equals(leaderId)) {
            return ActionResult.NOT_LEADER;
        }
        if (leaderId.equals(targetId)) {
            return ActionResult.CANNOT_TARGET_SELF;
        }
        if (!team.contains(targetId)) {
            return ActionResult.NOT_A_MEMBER;
        }
        team.setLeader(targetId);
        removeInvitationsForTeam(team.id());
        notifyChanged();
        return ActionResult.SUCCESS;
    }

    public synchronized ActionResult leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (isLocked()) {
            return ActionResult.LOCKED;
        }
        TeamData team = teamOf(playerId).orElse(null);
        if (team == null) {
            return ActionResult.NOT_IN_TEAM;
        }

        team.removeMember(playerId);
        teamByPlayer.remove(playerId);
        invitationsByTarget.remove(playerId);
        if (team.size() == 0) {
            teams.remove(team.id());
            removeInvitationsForTeam(team.id());
            notifyChanged();
            return ActionResult.SUCCESS;
        }
        if (team.leader().equals(playerId)) {
            team.setLeader(team.membersInJoinOrder().getFirst());
            removeInvitationsForTeam(team.id());
        }
        notifyChanged();
        return ActionResult.SUCCESS;
    }

    public synchronized boolean areTeamMates(UUID first, UUID second) {
        UUID firstTeam = teamByPlayer.get(first);
        return firstTeam != null && firstTeam.equals(teamByPlayer.get(second));
    }

    public synchronized void clear() {
        teams.clear();
        teamByPlayer.clear();
        invitationsByTarget.clear();
        manuallyLocked = false;
        notifyChanged();
    }

    private void notifyChanged() {
        changeListener.run();
    }

    private void deleteTeam(TeamData team) {
        teams.remove(team.id());
        for (UUID member : team.members()) {
            teamByPlayer.remove(member);
        }
        removeInvitationsForTeam(team.id());
    }

    private boolean removeInvitation(UUID targetId, UUID inviterId) {
        Map<UUID, Invitation> invitations = invitationsByTarget.get(targetId);
        if (invitations == null || invitations.remove(inviterId) == null) {
            return false;
        }
        if (invitations.isEmpty()) {
            invitationsByTarget.remove(targetId);
        }
        return true;
    }

    private void removeInvitationsForTeam(UUID teamId) {
        List<UUID> emptyTargets = new ArrayList<>();
        for (Map.Entry<UUID, LinkedHashMap<UUID, Invitation>> entry : invitationsByTarget.entrySet()) {
            entry.getValue().values().removeIf(invitation -> invitation.teamId().equals(teamId));
            if (entry.getValue().isEmpty()) {
                emptyTargets.add(entry.getKey());
            }
        }
        emptyTargets.forEach(invitationsByTarget::remove);
    }

    private void purgeExpiredInvitations() {
        Instant now = clock.instant();
        List<UUID> emptyTargets = new ArrayList<>();
        for (Map.Entry<UUID, LinkedHashMap<UUID, Invitation>> entry : invitationsByTarget.entrySet()) {
            entry.getValue().values().removeIf(invitation -> !invitation.expiresAt().isAfter(now));
            if (entry.getValue().isEmpty()) {
                emptyTargets.add(entry.getKey());
            }
        }
        emptyTargets.forEach(invitationsByTarget::remove);
    }

    private static Color colorFor(UUID teamId) {
        float hue = Math.floorMod(teamId.hashCode(), 360) / 360.0F;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.72F, 0.96F) & 0xFFFFFF;
        return Color.fromRGB(rgb);
    }

    public enum ActionResult {
        SUCCESS,
        LOCKED,
        NOT_LEADER,
        NOT_IN_TEAM,
        NOT_A_MEMBER,
        TEAM_FULL,
        TARGET_ALREADY_IN_TEAM,
        ALREADY_MEMBER,
        CANNOT_TARGET_SELF,
        INVITATION_NOT_FOUND
    }

    public record Invitation(UUID inviterId, UUID teamId, Instant expiresAt) {
    }
}
