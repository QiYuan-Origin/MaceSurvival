package club.mcqi.macesurvival.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentBundleRulesTest {
    @Test
    void onlySingleShieldsElytrasAndSpearsArePackable() {
        assertTrue(EquipmentBundleRules.isPackable("SHIELD", 1));
        assertTrue(EquipmentBundleRules.isPackable("ELYTRA", 1));
        assertTrue(EquipmentBundleRules.isPackable("IRON_SPEAR", 1));
        assertTrue(EquipmentBundleRules.isPackable("netherite_spear", 1));

        assertFalse(EquipmentBundleRules.isPackable("TRIDENT", 1));
        assertFalse(EquipmentBundleRules.isPackable("MACE", 1));
        assertFalse(EquipmentBundleRules.isPackable("SHIELD", 2));
        assertFalse(EquipmentBundleRules.isPackable(null, 1));
    }

    @Test
    void categoryCombinesAllSpearMaterialsButKeepsOtherEquipmentSeparate() {
        assertEquals("spear", EquipmentBundleRules.category("WOODEN_SPEAR"));
        assertEquals("spear", EquipmentBundleRules.category("NETHERITE_SPEAR"));
        assertEquals("shield", EquipmentBundleRules.category("SHIELD"));
        assertEquals("elytra", EquipmentBundleRules.category("ELYTRA"));
    }

    @Test
    void capacityAllowsExactlyTwelveItems() {
        assertTrue(EquipmentBundleRules.canAdd(0, "SHIELD", 1));
        assertTrue(EquipmentBundleRules.canAdd(11, "IRON_SPEAR", 1));
        assertFalse(EquipmentBundleRules.canAdd(12, "IRON_SPEAR", 1));
        assertFalse(EquipmentBundleRules.canAdd(13, "ELYTRA", 1));
        assertFalse(EquipmentBundleRules.canAdd(-1, "ELYTRA", 1));
    }
}
