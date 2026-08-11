package club.mcqi.macesurvival.combat;

import java.util.HashSet;

public record StarterLayout(int swordSlot, int axeSlot, int firstMaceSlot, int secondMaceSlot) {
    public static final StarterLayout DEFAULT = new StarterLayout(0, 1, 7, 8);

    public StarterLayout {
        HashSet<Integer> slots = new HashSet<>();
        slots.add(swordSlot);
        slots.add(axeSlot);
        slots.add(firstMaceSlot);
        slots.add(secondMaceSlot);
        if (slots.size() != 4 || slots.stream().anyMatch(slot -> slot < 0 || slot > 8)) {
            throw new IllegalArgumentException("Starter slots must be four unique hotbar slots from 0 to 8");
        }
    }
}
