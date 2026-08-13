package club.mcqi.macesurvival.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

final class ReloadableLootTable {
    private static final Definition FALLBACK = new Definition(
        "fallback-food",
        List.of(1, 1, 1),
        "COOKED_BEEF",
        null,
        null,
        null,
        4,
        8,
        0,
        0,
        false
    );

    private List<Definition> definitions = List.of(FALLBACK);

    ReloadResult reload(Map<String, ?> configuration, DefinitionValidator validator) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(validator, "validator");
        List<Definition> loaded = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Map<String, ?> entries = map(configuration.get("entries"));
        if (entries != null) {
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                String id = entry.getKey();
                Map<String, ?> section = map(entry.getValue());
                if (section == null) {
                    problems.add(new Problem(id, "Loot entry must be a configuration section"));
                    continue;
                }
                try {
                    Definition definition = readDefinition(configuration, id, section);
                    validator.validate(definition);
                    loaded.add(definition);
                } catch (IllegalArgumentException exception) {
                    problems.add(new Problem(id, exception.getMessage()));
                }
            }
        }
        boolean fallbackUsed = loaded.isEmpty();
        definitions = fallbackUsed ? List.of(FALLBACK) : List.copyOf(loaded);
        return new ReloadResult(loaded.size(), fallbackUsed, problems);
    }

    Definition select(LootTier tier, RandomGenerator random) {
        return select(tier, random, Set.of());
    }

    Definition select(LootTier tier, RandomGenerator random, Set<String> excludedFamilies) {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(excludedFamilies, "excludedFamilies");
        long totalWeight = totalWeight(tier, excludedFamilies);
        if (totalWeight <= 0 && !excludedFamilies.isEmpty()) {
            totalWeight = totalWeight(tier);
            excludedFamilies = Set.of();
        }
        if (totalWeight <= 0) {
            throw new IllegalStateException("The configured loot table has no positive weight for " + tier);
        }
        long roll = random.nextLong(totalWeight);
        for (Definition definition : definitions) {
            if (excludedFamilies.contains(definition.familyKey())) {
                continue;
            }
            roll -= definition.weight(tier);
            if (roll < 0) {
                return definition;
            }
        }
        throw new IllegalStateException("Weighted loot selection did not produce an entry");
    }

    long totalWeight(LootTier tier) {
        return definitions.stream().mapToLong(definition -> definition.weight(tier)).sum();
    }

    long totalWeight(LootTier tier, Set<String> excludedFamilies) {
        Objects.requireNonNull(excludedFamilies, "excludedFamilies");
        return definitions.stream()
            .filter(definition -> !excludedFamilies.contains(definition.familyKey()))
            .mapToLong(definition -> definition.weight(tier))
            .sum();
    }

    List<Definition> definitions() {
        return definitions;
    }

    private static Definition readDefinition(
            Map<String, ?> root,
            String id,
            Map<String, ?> section
    ) {
        List<Integer> weights = readWeights(root, section);
        IntRange amount = integerRange(section, "amount", 1, 1);
        IntRange uses = integerRange(section, "limited-durability", 0, 0);
        String material = normalized(string(section, "material"));
        String materialGroup = normalized(string(section, "material-group"));
        String itemType = normalized(string(section, "item-type"));
        String enchantment = normalized(string(section, "enchantment"));
        if (material == null && materialGroup == null && itemType == null) {
            throw new IllegalArgumentException("No item factory is configured");
        }
        return new Definition(
            id,
            weights,
            material,
            materialGroup,
            itemType,
            enchantment,
            amount.minimum(),
            amount.maximum(),
            uses.minimum(),
            uses.maximum(),
            booleanValue(section, "random-durability", false)
        );
    }

    private static List<Integer> readWeights(Map<String, ?> root, Map<String, ?> section) {
        List<Integer> direct = integerList(section.get("tier-weights"));
        if (direct.size() >= 3) {
            return positiveWeights(direct);
        }
        int base = Math.max(0, integerValue(section, "weight", 1));
        String category = Objects.requireNonNullElse(string(section, "category"), "equipment");
        Map<String, ?> categories = map(root.get("categories"));
        Map<String, ?> categorySection = categories == null ? null : map(categories.get(category));
        List<Integer> categoryWeights = categorySection == null
            ? List.of()
            : integerList(categorySection.get("tier-weights"));
        if (categoryWeights.size() < 3) {
            categoryWeights = List.of(1, 1, 1);
        }
        List<Integer> weighted = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            long value = (long) base * Math.max(0, categoryWeights.get(index));
            weighted.add((int) Math.min(Integer.MAX_VALUE, value));
        }
        return List.copyOf(weighted);
    }

    private static List<Integer> positiveWeights(List<Integer> values) {
        return List.of(
            Math.max(0, values.get(0)),
            Math.max(0, values.get(1)),
            Math.max(0, values.get(2))
        );
    }

    private static IntRange integerRange(
            Map<String, ?> section,
            String path,
            int fallbackMinimum,
            int fallbackMaximum
    ) {
        List<Integer> values = integerList(section.get(path));
        if (values.size() < 2) {
            return new IntRange(fallbackMinimum, fallbackMaximum);
        }
        int minimum = Math.max(0, values.get(0));
        int maximum = Math.max(minimum, values.get(1));
        return new IntRange(minimum, maximum);
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object element : values) {
            if (element instanceof Number number) {
                result.add(number.intValue());
            }
        }
        return List.copyOf(result);
    }

    private static int integerValue(Map<String, ?> section, String path, int fallback) {
        Object value = section.get(path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean booleanValue(Map<String, ?> section, String path, boolean fallback) {
        Object value = section.get(path);
        return value instanceof Boolean configured ? configured : fallback;
    }

    private static String string(Map<String, ?> section, String path) {
        Object value = section.get(path);
        return value instanceof String configured ? configured : null;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.toUpperCase(Locale.ROOT);
    }

    private static Map<String, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    @FunctionalInterface
    interface DefinitionValidator {
        void validate(Definition definition);
    }

    record ReloadResult(int loadedEntries, boolean fallbackUsed, List<Problem> problems) {
        ReloadResult {
            problems = List.copyOf(problems);
        }
    }

    record Problem(String entryId, String message) {
    }

    record Definition(
        String id,
        List<Integer> tierWeights,
        String material,
        String materialGroup,
        String itemType,
        String enchantment,
        int minimumAmount,
        int maximumAmount,
        int minimumUses,
        int maximumUses,
        boolean randomDurability
    ) {
        Definition {
            Objects.requireNonNull(id, "id");
            tierWeights = List.copyOf(tierWeights);
            if (tierWeights.size() != 3) {
                throw new IllegalArgumentException("Exactly three tier weights are required for " + id);
            }
            if (minimumAmount < 1 || maximumAmount < minimumAmount
                || minimumUses < 0 || maximumUses < minimumUses) {
                throw new IllegalArgumentException("Invalid amount or durability range for " + id);
            }
        }

        int weight(LootTier tier) {
            return tierWeights.get(tier.stars() - 1);
        }

        String familyKey() {
            if (material != null) {
                if (material.endsWith("_SPEAR")) {
                    return "material:spear";
                }
                return "material:" + material;
            }
            if (materialGroup != null) {
                return "group:" + materialGroup;
            }
            if (itemType != null) {
                if (itemType.equals("WEAPON_ENCHANT")) {
                    return "type:" + itemType + ":" + Objects.requireNonNullElse(enchantment, "ANY");
                }
                return "type:" + itemType;
            }
            return "entry:" + id;
        }
    }

    private record IntRange(int minimum, int maximum) {
    }
}
