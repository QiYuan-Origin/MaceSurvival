package org.qiyuan.macesurvival.game;

/**
 * Game states for the battle royale flow.
 */
public enum GameState {
    /** Server is booting, game not ready */
    BOOTING,

    /** Waiting for players in lobby */
    WAITING,

    /** Countdown before game starts */
    COUNTDOWN,

    /** Players are skydiving */
    DROPPING,

    /** Main game in progress */
    RUNNING,

    /** Game finished, showing end screen */
    ENDING
}
