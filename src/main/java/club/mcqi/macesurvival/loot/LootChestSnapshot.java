package club.mcqi.macesurvival.loot;

import org.bukkit.Location;

import java.util.UUID;

public record LootChestSnapshot(UUID id, Location location, LootTier tier, boolean opened, int viewers) {
    public LootChestSnapshot {
        location = location.clone();
    }

    @Override
    public Location location() {
        return location.clone();
    }
}
