package club.mcqi.macesurvival.team;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record LoadoutLayout(int swordSlot, int axeSlot, int firstMaceSlot, int secondMaceSlot) {
    public LoadoutLayout {
        Set<Integer> slots = new HashSet<>();
        for (int slot : new int[] {swordSlot, axeSlot, firstMaceSlot, secondMaceSlot}) {
            if (slot < 0 || slot > 8) {
                throw new IllegalArgumentException("A loadout slot must be between 0 and 8");
            }
            if (!slots.add(slot)) {
                throw new IllegalArgumentException("Loadout slots must be unique");
            }
        }
    }

    public static LoadoutLayout defaults() {
        return new LoadoutLayout(0, 1, 7, 8);
    }

    public int slot(LoadoutItem item) {
        return switch (item) {
            case SWORD -> swordSlot;
            case AXE -> axeSlot;
            case MACE_ONE -> firstMaceSlot;
            case MACE_TWO -> secondMaceSlot;
        };
    }

    public LoadoutLayout withSlot(LoadoutItem item, int slot) {
        return switch (item) {
            case SWORD -> new LoadoutLayout(slot, axeSlot, firstMaceSlot, secondMaceSlot);
            case AXE -> new LoadoutLayout(swordSlot, slot, firstMaceSlot, secondMaceSlot);
            case MACE_ONE -> new LoadoutLayout(swordSlot, axeSlot, slot, secondMaceSlot);
            case MACE_TWO -> new LoadoutLayout(swordSlot, axeSlot, firstMaceSlot, slot);
        };
    }

    public Map<LoadoutItem, Integer> asMap() {
        EnumMap<LoadoutItem, Integer> slots = new EnumMap<>(LoadoutItem.class);
        for (LoadoutItem item : LoadoutItem.values()) {
            slots.put(item, slot(item));
        }
        return Map.copyOf(slots);
    }
}
