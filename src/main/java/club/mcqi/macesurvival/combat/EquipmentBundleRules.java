package club.mcqi.macesurvival.combat;

import java.util.Locale;

final class EquipmentBundleRules {
    static final int CAPACITY = 12;

    private EquipmentBundleRules() {
    }

    static boolean isPackable(String materialName, int amount) {
        if (materialName == null || amount != 1) {
            return false;
        }
        String normalized = materialName.toUpperCase(Locale.ROOT);
        return normalized.equals("SHIELD") || normalized.equals("ELYTRA")
            || normalized.endsWith("_SPEAR");
    }

    static boolean canAdd(int currentSize, String materialName, int amount) {
        return currentSize >= 0 && currentSize < CAPACITY && isPackable(materialName, amount);
    }

    static String category(String materialName) {
        String normalized = materialName.toUpperCase(Locale.ROOT);
        if (normalized.endsWith("_SPEAR")) {
            return "spear";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
