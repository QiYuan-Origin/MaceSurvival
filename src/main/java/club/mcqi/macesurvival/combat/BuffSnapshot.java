package club.mcqi.macesurvival.combat;

public record BuffSnapshot(int damageStacks, int healingStacks, int pendingKillBoosts) {
    public double damageMultiplier() {
        return 1.0 + damageStacks * 0.03;
    }

    public double healingMultiplier() {
        return 1.0 + healingStacks * 0.05;
    }
}
