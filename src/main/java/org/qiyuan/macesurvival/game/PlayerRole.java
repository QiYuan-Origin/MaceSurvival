package org.qiyuan.macesurvival.game;

/** 玩家在本局里的身份。 */
public enum PlayerRole {

    /** 等待大厅里的玩家。 */
    LOBBY,
    /** 正在跳伞下落。 */
    DIVING,
    /** 已落地，参与对局。 */
    ALIVE,
    /** 已淘汰，转旁观。 */
    ELIMINATED,
    /** 游戏中进服的旁观者，从未参与本局。 */
    SPECTATOR;

    public boolean isPlaying() {
        return this == DIVING || this == ALIVE;
    }

    public boolean isWatching() {
        return this == ELIMINATED || this == SPECTATOR;
    }
}
