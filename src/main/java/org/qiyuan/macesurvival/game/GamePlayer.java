package org.qiyuan.macesurvival.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 一局内的玩家会话。只存本局数据，跨局统计交给 StatsManager。
 * 刻意不持有 Player 引用，避免玩家退出后内存泄漏。
 */
public final class GamePlayer {

    private final UUID uuid;
    private final String name;

    private PlayerRole role = PlayerRole.LOBBY;
    private String squadId;

    private int kills;
    private int chestsOpened;
    private int booksApplied;
    private long joinedAt = System.currentTimeMillis();
    private long diedAt;
    private String lastDamager;

    /** 跳伞阶段用：是否已经开伞，避免重复给伞和重复取消坠落伤害。 */
    private boolean parachuteOpen;
    /** 落地判定用，落地后不再接管移动。 */
    private boolean landed;

    public GamePlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /** 可能为 null，玩家离线时如此。调用方必须判空。 */
    public Player online() {
        return Bukkit.getPlayer(uuid);
    }

    public boolean isOnline() {
        Player player = online();
        return player != null && player.isOnline();
    }

    public PlayerRole role() {
        return role;
    }

    public void role(PlayerRole role) {
        this.role = role;
        if (role == PlayerRole.ELIMINATED && diedAt == 0L) {
            diedAt = System.currentTimeMillis();
        }
    }

    public String squadId() {
        return squadId;
    }

    public void squadId(String squadId) {
        this.squadId = squadId;
    }

    public boolean hasSquad() {
        return squadId != null;
    }

    public int kills() {
        return kills;
    }

    public void addKill() {
        kills++;
    }

    public int chestsOpened() {
        return chestsOpened;
    }

    public void addChest() {
        chestsOpened++;
    }

    public int booksApplied() {
        return booksApplied;
    }

    public void addBook() {
        booksApplied++;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public void joinedAt(long joinedAt) {
        this.joinedAt = joinedAt;
    }

    public long diedAt() {
        return diedAt;
    }

    /** 记录最近一次伤害来源，死亡消息用它来判定凶手。 */
    public String lastDamager() {
        return lastDamager;
    }

    public void lastDamager(String lastDamager) {
        this.lastDamager = lastDamager;
    }

    public boolean parachuteOpen() {
        return parachuteOpen;
    }

    public void parachuteOpen(boolean parachuteOpen) {
        this.parachuteOpen = parachuteOpen;
    }

    public boolean landed() {
        return landed;
    }

    public void landed(boolean landed) {
        this.landed = landed;
    }

    /** 新一局开始时复用同一个会话对象。 */
    public void resetForRound() {
        role = PlayerRole.LOBBY;
        kills = 0;
        chestsOpened = 0;
        booksApplied = 0;
        diedAt = 0L;
        lastDamager = null;
        parachuteOpen = false;
        landed = false;
        joinedAt = System.currentTimeMillis();
    }
}
