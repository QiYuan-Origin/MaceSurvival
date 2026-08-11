package club.mcqi.macesurvival.loot;

import org.bukkit.World;
import org.bukkit.WorldBorder;

public interface LootBridge {
    LootBridge NO_OP = new LootBridge() { };

    default void spawnInitial(World world, int alivePlayers) { }
    default void refresh(World world, int alivePlayers) { }
    default void removeOutside(WorldBorder border) { }
    default void clear() { }
}
