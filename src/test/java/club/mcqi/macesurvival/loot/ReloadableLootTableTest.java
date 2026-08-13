package club.mcqi.macesurvival.loot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadableLootTableTest {
    @Test
    void reloadReplacesOldDefinitionsInsteadOfAppending() {
        ReloadableLootTable table = new ReloadableLootTable();

        ReloadableLootTable.ReloadResult first = table.reload(
            configuration(Map.of("old-food", materialEntry("BREAD", List.of(8, 5, 2)))),
            definition -> { }
        );
        ReloadableLootTable.ReloadResult second = table.reload(
            configuration(Map.of("new-food", materialEntry("COOKED_BEEF", List.of(1, 3, 9)))),
            definition -> { }
        );

        assertEquals(1, first.loadedEntries());
        assertEquals(1, second.loadedEntries());
        assertFalse(second.fallbackUsed());
        assertEquals(List.of("new-food"), table.definitions().stream()
            .map(ReloadableLootTable.Definition::id)
            .toList());
        assertEquals(9, table.totalWeight(LootTier.THREE));
    }

    @Test
    void reloadKeepsValidEntriesAndReportsMalformedOrSemanticallyInvalidEntries() {
        ReloadableLootTable table = new ReloadableLootTable();
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("valid", materialEntry("BREAD", List.of(1, 1, 1)));
        entries.put("not-a-section", "BREAD");
        entries.put("missing-source", Map.of("tier-weights", List.of(1, 1, 1)));
        entries.put("rejected-material", materialEntry("NOT_ALLOWED", List.of(1, 1, 1)));

        ReloadableLootTable.ReloadResult result = table.reload(configuration(entries), definition -> {
            if ("NOT_ALLOWED".equals(definition.material())) {
                throw new IllegalArgumentException("Test validator rejected material");
            }
        });

        assertEquals(1, result.loadedEntries());
        assertFalse(result.fallbackUsed());
        assertEquals(3, result.problems().size());
        assertEquals(List.of("not-a-section", "missing-source", "rejected-material"),
            result.problems().stream().map(ReloadableLootTable.Problem::entryId).toList());
        assertEquals("valid", table.definitions().getFirst().id());
    }

    @Test
    void reloadFallsBackOnlyWhenNoValidEntryRemains() {
        ReloadableLootTable table = new ReloadableLootTable();

        ReloadableLootTable.ReloadResult result = table.reload(
            configuration(Map.of("broken", Map.of("amount", List.of(1, 2)))),
            definition -> { }
        );

        assertEquals(0, result.loadedEntries());
        assertTrue(result.fallbackUsed());
        ReloadableLootTable.Definition fallback = table.definitions().getFirst();
        assertEquals("fallback-food", fallback.id());
        assertEquals("COOKED_BEEF", fallback.material());
        assertEquals(4, fallback.minimumAmount());
        assertEquals(8, fallback.maximumAmount());
        assertEquals(1, table.totalWeight(LootTier.ONE));
    }

    @Test
    void categoryWeightsAndBaseWeightAreCombinedForEveryTier() {
        ReloadableLootTable table = new ReloadableLootTable();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("categories", Map.of(
            "rare", Map.of("tier-weights", List.of(2, 3, 4))
        ));
        root.put("entries", Map.of(
            "scaled", Map.of(
                "material", "ELYTRA",
                "category", "rare",
                "weight", 5
            )
        ));

        table.reload(root, definition -> { });

        ReloadableLootTable.Definition definition = table.definitions().getFirst();
        assertEquals(List.of(10, 15, 20), definition.tierWeights());
        assertEquals(10, table.totalWeight(LootTier.ONE));
        assertEquals(20, table.totalWeight(LootTier.THREE));
    }

    @Test
    void configuredRangesAreClampedWithoutCreatingInvalidIntervals() {
        ReloadableLootTable table = new ReloadableLootTable();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("material", "SHIELD");
        entry.put("tier-weights", List.of(-4, 3, 7));
        entry.put("amount", List.of(8, 4));
        entry.put("limited-durability", List.of(-2, 16));

        table.reload(configuration(Map.of("shield", entry)), definition -> { });

        ReloadableLootTable.Definition definition = table.definitions().getFirst();
        assertEquals(List.of(0, 3, 7), definition.tierWeights());
        assertEquals(8, definition.minimumAmount());
        assertEquals(8, definition.maximumAmount());
        assertEquals(0, definition.minimumUses());
        assertEquals(16, definition.maximumUses());
    }

    @Test
    void selectionRejectsATierWhoseEntriesAllHaveZeroWeight() {
        ReloadableLootTable table = new ReloadableLootTable();
        table.reload(
            configuration(Map.of("late-only", materialEntry("ELYTRA", List.of(0, 2, 4)))),
            definition -> { }
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> table.select(LootTier.ONE, new Random(42L)));

        assertTrue(exception.getMessage().contains("ONE"));
        assertEquals("late-only", table.select(LootTier.TWO, new Random(42L)).id());
    }

    @Test
    void totalWeightDoesNotOverflowWhenSeveralEntriesUseMaximumWeights() {
        ReloadableLootTable table = new ReloadableLootTable();
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("first", materialEntry("BREAD",
            List.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)));
        entries.put("second", materialEntry("COOKED_BEEF",
            List.of(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)));

        table.reload(configuration(entries), definition -> { });

        assertEquals(2L * Integer.MAX_VALUE, table.totalWeight(LootTier.ONE));
        assertTrue(List.of("first", "second").contains(
            table.select(LootTier.ONE, new Random(7L)).id()));
    }

    @Test
    void validatorCanIsolateWeaponEnchantWithoutAnEnchantmentName() {
        ReloadableLootTable table = new ReloadableLootTable();
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("valid", materialEntry("BREAD", List.of(1, 1, 1)));
        entries.put("missing-enchantment", Map.of(
            "item-type", "WEAPON_ENCHANT",
            "tier-weights", List.of(1, 1, 1)
        ));

        ReloadableLootTable.ReloadResult result = table.reload(configuration(entries), definition -> {
            if ("WEAPON_ENCHANT".equals(definition.itemType()) && definition.enchantment() == null) {
                throw new IllegalArgumentException("WEAPON_ENCHANT requires enchantment");
            }
        });

        assertEquals(1, result.loadedEntries());
        assertEquals("missing-enchantment", result.problems().getFirst().entryId());
        assertEquals(List.of("valid"), table.definitions().stream()
            .map(ReloadableLootTable.Definition::id)
            .toList());
    }

    @Test
    void bundledLootConfigurationLoadsEveryEntryWithPositiveWeights() throws IOException {
        Map<String, Object> configuration;
        try (InputStream input = ReloadableLootTableTest.class.getResourceAsStream("/loot.yml")) {
            if (input == null) {
                throw new IOException("Bundled loot.yml was not found");
            }
            configuration = stringMap(new Yaml().load(input));
        }
        ReloadableLootTable table = new ReloadableLootTable();

        ReloadableLootTable.ReloadResult result = table.reload(configuration, definition -> { });

        assertFalse(result.fallbackUsed());
        assertTrue(result.problems().isEmpty(), () -> "Invalid loot entries: " + result.problems());
        assertTrue(result.loadedEntries() >= 35, "Default loot table was unexpectedly truncated");
        assertEquals(List.of("NETHERITE_SPEAR"), table.definitions().stream()
            .map(ReloadableLootTable.Definition::material)
            .filter(material -> material != null && material.endsWith("_SPEAR"))
            .distinct()
            .toList());
        for (ReloadableLootTable.Definition definition : table.definitions()) {
            assertTrue(definition.weight(LootTier.ONE) > 0,
                () -> definition.id() + " has no one-star weight");
            assertTrue(definition.weight(LootTier.TWO) > 0,
                () -> definition.id() + " has no two-star weight");
            assertTrue(definition.weight(LootTier.THREE) > 0,
                () -> definition.id() + " has no three-star weight");
        }
    }

    private static Map<String, Object> configuration(Map<String, ?> entries) {
        return Map.of("entries", entries);
    }

    private static Map<String, Object> materialEntry(String material, List<Integer> weights) {
        return Map.of(
            "material", material,
            "amount", List.of(1, 1),
            "tier-weights", weights
        );
    }

    private static Map<String, Object> stringMap(Object source) {
        if (!(source instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Expected a YAML mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() instanceof String key) {
                Object value = entry.getValue();
                result.put(key, value instanceof Map<?, ?> ? stringMap(value) : value);
            }
        }
        return result;
    }
}
