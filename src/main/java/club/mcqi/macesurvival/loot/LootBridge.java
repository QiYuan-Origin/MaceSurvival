package club.mcqi.macesurvival.loot;

import org.bukkit.World;

public interface LootBridge {
    LootBridge NO_OP = new LootBridge() { };

    default void spawnInitial(World world, int alivePlayers) { }
    default void spawnNear(World world, double centerX, double centerZ, double radius, int count, double matchProgress) { }
    default void refresh(World world, int alivePlayers) { }
    default void refresh(World world, int alivePlayers, double centerX, double centerZ, double radius) {
        refresh(world, alivePlayers);
    }
    default void clear() { }
    default void restoreWorld(World world) { }
}
