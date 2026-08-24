package org.qiyuan.macesurvival.game;

/** 单端一局的生命周期。任何时刻只有一个状态，转移由 GameManager 统一控制。 */
public enum GameState {

    /** 插件刚启用或上一局清理完，等地图生成。 */
    BOOTING,
    /** 等待大厅，人数够了开始倒计时。 */
    WAITING,
    /** 倒计时中，人数掉下去会回到 WAITING。 */
    COUNTDOWN,
    /** 黑屏 + 地图生成 + 传送到高空。 */
    DROPPING,
    /** 落地后的正式对局。 */
    RUNNING,
    /** 结算画面，随后重启服务器重置地图。 */
    ENDING;

    /** 这些状态下新进服的玩家应当变成旁观者。 */
    public boolean isInProgress() {
        return this == DROPPING || this == RUNNING || this == ENDING;
    }

    /** 能否接受新玩家进入等待大厅。 */
    public boolean acceptsPlayers() {
        return this == WAITING || this == COUNTDOWN;
    }
}
