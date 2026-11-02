package org.qiyuan.macesurvival.game;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Tracks per-player game data.
 */
public final class GamePlayer {

    private final UUID uuid;
    private PlayerRole role;
    private int kills;
    private int deaths;
    private int wins;
    private Squad squad;
    private int roundKills;

    public GamePlayer(UUID uuid) {
        this.uuid = uuid;
        this.role = PlayerRole.PLAYER;
        this.kills = 0;
        this.deaths = 0;
        this.wins = 0;
    }

    public UUID uuid() {
        return uuid;
    }

    public PlayerRole role() {
        return role;
    }

    public void setRole(PlayerRole role) {
        this.role = role;
    }

    public int kills() {
        return kills;
    }

    public void addKill() {
        this.kills++;
        this.roundKills++;
    }

    public int deaths() {
        return deaths;
    }

    public void addDeath() {
        this.deaths++;
    }

    public int wins() {
        return wins;
    }

    public void addWin() {
        this.wins++;
    }

    public Squad squad() {
        return squad;
    }

    public void setSquad(Squad squad) {
        this.squad = squad;
    }

    public boolean isAlive() {
        return role == PlayerRole.PLAYER;
    }

    public boolean isSpectator() {
        return role == PlayerRole.SPECTATOR;
    }

    public Squad getSquad() {
        return squad;
    }

    public int getKills() {
        return kills;
    }

    public int roundKills() { return roundKills; }

    public void resetRound() { roundKills = 0; }

    public void loadTotals(int kills, int deaths, int wins) {
        this.kills = kills;
        this.deaths = deaths;
        this.wins = wins;
    }
}
