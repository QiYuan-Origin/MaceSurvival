package club.mcqi.macesurvival.game;

import java.time.Instant;
import java.util.UUID;

public final class Participant {
    private final UUID playerId;
    private boolean alive = true;
    private int kills;
    private UUID lastAttacker;
    private Instant lastAttackedAt;
    private Instant disconnectedAt;
    private double damageMultiplier = 1.0;
    private double healingMultiplier = 1.0;
    private int nextKillBoosts;

    public Participant(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() { return playerId; }
    public boolean alive() { return alive; }
    public void setAlive(boolean alive) { this.alive = alive; }
    public int kills() { return kills; }
    public void addKill() { kills++; }
    public UUID lastAttacker() { return lastAttacker; }
    public Instant lastAttackedAt() { return lastAttackedAt; }
    public void recordAttacker(UUID attacker, Instant attackedAt) {
        lastAttacker = attacker;
        lastAttackedAt = attackedAt;
    }
    public Instant disconnectedAt() { return disconnectedAt; }
    public void setDisconnectedAt(Instant disconnectedAt) { this.disconnectedAt = disconnectedAt; }
    public double damageMultiplier() { return damageMultiplier; }
    public void addDamageMultiplier(double amount) { damageMultiplier = Math.min(1.15, damageMultiplier + amount); }
    public double healingMultiplier() { return healingMultiplier; }
    public void addHealingMultiplier(double amount) { healingMultiplier = Math.min(1.25, healingMultiplier + amount); }
    public int nextKillBoosts() { return nextKillBoosts; }
    public void addNextKillBoost() { nextKillBoosts++; }
    public void consumeNextKillBoost() { if (nextKillBoosts > 0) nextKillBoosts--; }
}
