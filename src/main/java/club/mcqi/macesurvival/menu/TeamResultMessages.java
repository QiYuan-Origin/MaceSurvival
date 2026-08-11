package club.mcqi.macesurvival.menu;

import club.mcqi.macesurvival.team.TeamManager;

final class TeamResultMessages {
    private TeamResultMessages() {
    }

    static String path(TeamManager.ActionResult result) {
        return switch (result) {
            case SUCCESS -> "team.done";
            case LOCKED -> "team.result.locked";
            case NOT_LEADER -> "team.result.not-leader";
            case NOT_IN_TEAM -> "team.result.not-in-team";
            case NOT_A_MEMBER -> "team.result.not-a-member";
            case TEAM_FULL -> "team.result.team-full";
            case TARGET_ALREADY_IN_TEAM -> "team.result.target-already-in-team";
            case ALREADY_MEMBER -> "team.result.already-member";
            case CANNOT_TARGET_SELF -> "team.result.cannot-target-self";
            case INVITATION_NOT_FOUND -> "team.result.invitation-not-found";
        };
    }
}
