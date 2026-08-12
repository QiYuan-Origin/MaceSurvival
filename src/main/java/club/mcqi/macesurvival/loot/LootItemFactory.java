package club.mcqi.macesurvival.loot;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.combat.BuffType;
import club.mcqi.macesurvival.combat.CombatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.logging.Level;

final class LootItemFactory {
    private static final List<Enchantment> ARMOR_ENCHANTMENTS = List.of(
        Enchantment.PROTECTION,
        Enchantment.FIRE_PROTECTION,
        Enchantment.BLAST_PROTECTION,
        Enchantment.PROJECTILE_PROTECTION,
        Enchantment.FEATHER_FALLING,
        Enchantment.RESPIRATION,
        Enchantment.AQUA_AFFINITY,
        Enchantment.THORNS,
        Enchantment.DEPTH_STRIDER,
        Enchantment.FROST_WALKER,
        Enchantment.UNBREAKING
    );
    private static final Map<String, Enchantment> ENCHANTMENTS = enchantments();

    private final JavaPlugin plugin;
    private final CombatManager combat;
    private final RandomGenerator random = ThreadLocalRandom.current();
    private final ReloadableLootTable table = new ReloadableLootTable();

    LootItemFactory(JavaPlugin plugin, CombatManager combat) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
        reload();
    }

    void reload() {
        FileConfiguration configuration = lootConfiguration();
        ReloadableLootTable.ReloadResult result = table.reload(
            configurationMap(configuration),
            this::validateDefinition
        );
        for (ReloadableLootTable.Problem problem : result.problems()) {
            plugin.getLogger().log(Level.WARNING,
                "Ignoring invalid loot entry {0}: {1}",
                new Object[] {problem.entryId(), problem.message()});
        }
        if (result.fallbackUsed()) {
            plugin.getLogger().warning("loot.yml contains no valid entries; using cooked beef as a fallback");
        }
    }

    ItemStack create(LootTier tier, UUID chestId) {
        ReloadableLootTable.Definition selected = table.select(tier, random);
        ItemStack item = createItem(selected, tier);
        int amount = random.nextInt(selected.minimumAmount(), selected.maximumAmount() + 1);
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        combat.tagLootSource(item, chestId);
        return item;
    }

    private ItemStack createItem(ReloadableLootTable.Definition definition, LootTier tier) {
        ItemStack item;
        if (definition.material() != null) {
            Material material = material(definition.material());
            item = new ItemStack(material);
            if (material.name().endsWith("_SPEAR")) {
                applySpearProperties(item, tier);
            }
        } else if (definition.materialGroup() != null) {
            Material[] pieces = armorSet(definition.materialGroup().replace("_ARMOR", ""));
            item = new ItemStack(pieces[random.nextInt(pieces.length)]);
        } else {
            item = createTypedItem(definition, tier);
        }
        applyDurability(definition, item);
        applyPresentation(definition, tier, item);
        return item;
    }

    private ItemStack createTypedItem(ReloadableLootTable.Definition definition, LootTier tier) {
        String type = Objects.requireNonNull(definition.itemType(),
            () -> "Loot entry " + definition.id() + " has no material, material-group or item-type");
        return switch (type) {
            case "HEALING_POTION" -> potion(
                tier == LootTier.ONE ? PotionType.HEALING : PotionType.STRONG_HEALING, true);
            case "REGENERATION_POTION" -> potion(
                tier == LootTier.ONE ? PotionType.REGENERATION : PotionType.STRONG_REGENERATION, true);
            case "STRENGTH_POTION" -> potion(
                tier == LootTier.ONE ? PotionType.STRENGTH : PotionType.STRONG_STRENGTH, random.nextBoolean());
            case "SWIFTNESS_POTION" -> potion(
                tier == LootTier.ONE ? PotionType.SWIFTNESS : PotionType.STRONG_SWIFTNESS, random.nextBoolean());
            case "INVISIBILITY_POTION" -> potion(
                tier == LootTier.THREE ? PotionType.LONG_INVISIBILITY : PotionType.INVISIBILITY, random.nextBoolean());
            case "FIRE_RESISTANCE_POTION" -> potion(PotionType.FIRE_RESISTANCE, random.nextBoolean());
            case "SLOW_FALLING_POTION" -> potion(PotionType.SLOW_FALLING, random.nextBoolean());
            case "DAMAGE_UPGRADE" -> combat.createBoostToken(BuffType.DAMAGE);
            case "HEALING_UPGRADE" -> combat.createBoostToken(BuffType.HEALING);
            case "NEXT_KILL_UPGRADE" -> combat.createBoostToken(BuffType.NEXT_KILL);
            case "ARMOR_ENCHANT" -> armorBook(tier);
            case "WEAPON_ENCHANT" -> weaponBook(definition.enchantment(), tier);
            case "KILL_MENDING" -> combat.createWeaponBook(
                Enchantment.MENDING, rollLevel(combat.maximumLevel(Enchantment.MENDING), tier));
            default -> throw new IllegalArgumentException("Unknown item-type " + type);
        };
    }

    private ItemStack armorBook(LootTier tier) {
        Enchantment enchantment = ARMOR_ENCHANTMENTS.get(random.nextInt(ARMOR_ENCHANTMENTS.size()));
        int level = enchantment.equals(Enchantment.THORNS)
            ? combat.maximumLevel(enchantment)
            : rollLevel(combat.maximumLevel(enchantment), tier);
        return combat.createArmorBook(enchantment, level);
    }

    private ItemStack weaponBook(String configuredEnchantment, LootTier tier) {
        String key = Objects.requireNonNull(configuredEnchantment, "WEAPON_ENCHANT requires enchantment")
            .toUpperCase(Locale.ROOT);
        Enchantment enchantment = ENCHANTMENTS.get(key);
        if (enchantment == null) {
            throw new IllegalArgumentException("Unknown enchantment " + configuredEnchantment);
        }
        return combat.createWeaponBook(enchantment, rollLevel(combat.maximumLevel(enchantment), tier));
    }

    private void applySpearProperties(ItemStack spear, LootTier tier) {
        int maximumLunge = combat.maximumLevel(Enchantment.LUNGE);
        spear.addUnsafeEnchantment(Enchantment.LUNGE, rollLevel(maximumLunge, tier));
        String tierName = switch (tier) {
            case ONE -> "one-star";
            case TWO -> "two-star";
            case THREE -> "three-star";
        };
        List<Double> range = lootConfiguration().getDoubleList("spear.bonus-damage." + tierName);
        double minimum = range.size() >= 2 ? range.get(0) : 1.0;
        double maximum = range.size() >= 2 ? range.get(1) : tier.stars() * 4.0 + 4.0;
        combat.applySpearDamageBonus(spear, random.nextDouble(minimum, Math.nextUp(maximum)));
    }

    private void applyPresentation(ReloadableLootTable.Definition definition, LootTier tier, ItemStack item) {
        if (item.getType().isAir()) {
            return;
        }
        if (plugin instanceof MaceSurvivalPlugin maceSurvival) {
            String family = presentationFamily(definition, item);
            String tierColor = tierColor(tier);
            item.editMeta(meta -> {
                meta.displayName(maceSurvival.text().message(null, "loot.item-name", Map.of(
                    "stars", stars(tier),
                    "color", tierColor,
                    "name", readableItemName(item)
                )).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                    maceSurvival.text().message(null, "loot.item-lore.tier", Map.of(
                        "stars", stars(tier),
                        "color", tierColor
                    )).decoration(TextDecoration.ITALIC, false),
                    maceSurvival.text().message(null, "loot.item-lore." + family, Map.of())
                        .decoration(TextDecoration.ITALIC, false)
                ));
            });
        }
    }

    private String presentationFamily(ReloadableLootTable.Definition definition, ItemStack item) {
        if (definition.itemType() != null) {
            String type = definition.itemType();
            if (type.endsWith("_POTION")) return "potion";
            if (type.equals("WEAPON_ENCHANT") || type.equals("KILL_MENDING")) return "weapon-book";
            if (type.equals("ARMOR_ENCHANT")) return "armor-book";
            if (type.endsWith("_UPGRADE")) return "boost";
        }
        String materialName = item.getType().name();
        if (materialName.endsWith("_SPEAR")) return "spear";
        if (materialName.equals("ELYTRA") || materialName.equals("SHIELD")) return "gear";
        if (materialName.endsWith("_HELMET") || materialName.endsWith("_CHESTPLATE")
            || materialName.endsWith("_LEGGINGS") || materialName.endsWith("_BOOTS")) return "armor";
        return "supply";
    }

    private static String stars(LootTier tier) {
        return "★".repeat(tier.stars());
    }

    private static String tierColor(LootTier tier) {
        return switch (tier) {
            case ONE -> "#f6f8ff";
            case TWO -> "#58d9ff";
            case THREE -> "#d98cff";
        };
    }

    private static String readableItemName(ItemStack item) {
        String material = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String[] words = material.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private void applyDurability(ReloadableLootTable.Definition definition, ItemStack item) {
        if (definition.maximumUses() > 0) {
            combat.randomizeRemainingUses(item, definition.minimumUses(), definition.maximumUses());
            return;
        }
        if (!definition.randomDurability()) {
            return;
        }
        double minimum = Math.max(0.0, Math.min(1.0,
            plugin.getConfig().getDouble("loot.armor-durability-min", 0.30)));
        double maximum = Math.max(minimum, Math.min(1.0,
            plugin.getConfig().getDouble("loot.armor-durability-max", 0.90)));
        combat.randomizeDurability(item, minimum, maximum);
    }

    private ItemStack potion(PotionType type, boolean splash) {
        ItemStack potion = new ItemStack(splash ? Material.SPLASH_POTION : Material.POTION);
        potion.editMeta(meta -> {
            if (meta instanceof PotionMeta potionMeta) {
                potionMeta.setBasePotionType(type);
            }
        });
        return potion;
    }

    private int rollLevel(int maximum, LootTier tier) {
        int level = 1;
        double continuation = 0.24 + tier.stars() * 0.14;
        while (level < maximum && random.nextDouble() < continuation) {
            level++;
        }
        return level;
    }

    private void validateDefinition(ReloadableLootTable.Definition definition) {
        if (definition.material() != null) {
            material(definition.material());
            return;
        }
        if (definition.materialGroup() != null) {
            armorSet(definition.materialGroup().replace("_ARMOR", ""));
            return;
        }
        String itemType = definition.itemType();
        if (itemType == null) {
            throw new IllegalArgumentException("Loot entry " + definition.id() + " has no item source");
        }
        switch (itemType) {
            case "HEALING_POTION", "REGENERATION_POTION", "STRENGTH_POTION",
                "SWIFTNESS_POTION", "INVISIBILITY_POTION", "FIRE_RESISTANCE_POTION",
                "SLOW_FALLING_POTION", "DAMAGE_UPGRADE", "HEALING_UPGRADE",
                "NEXT_KILL_UPGRADE", "ARMOR_ENCHANT", "KILL_MENDING" -> {
                // These types do not need additional configuration.
            }
            case "WEAPON_ENCHANT" -> {
                String enchantment = definition.enchantment();
                if (enchantment == null) {
                    throw new IllegalArgumentException("WEAPON_ENCHANT requires enchantment");
                }
                if (!ENCHANTMENTS.containsKey(enchantment)) {
                    throw new IllegalArgumentException("Unknown enchantment " + enchantment);
                }
            }
            default -> throw new IllegalArgumentException("Unknown item-type " + itemType);
        }
    }

    private FileConfiguration lootConfiguration() {
        if (plugin instanceof MaceSurvivalPlugin maceSurvival) {
            return maceSurvival.configFiles().configuration("loot.yml");
        }
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "loot.yml"));
    }

    private static Map<String, Object> configurationMap(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            values.put(key, value instanceof ConfigurationSection child
                ? configurationMap(child)
                : value);
        }
        return values;
    }

    private static Material material(String configured) {
        try {
            return Material.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown material " + configured, exception);
        }
    }

    private static Material[] armorSet(String configuredMaterial) {
        String material = configuredMaterial.toUpperCase(Locale.ROOT);
        return new Material[] {
            material(material + "_HELMET"),
            material(material + "_CHESTPLATE"),
            material(material + "_LEGGINGS"),
            material(material + "_BOOTS")
        };
    }

    private static Map<String, Enchantment> enchantments() {
        Map<String, Enchantment> values = new LinkedHashMap<>();
        values.put("SHARPNESS", Enchantment.SHARPNESS);
        values.put("BREACH", Enchantment.BREACH);
        values.put("DENSITY", Enchantment.DENSITY);
        values.put("WIND_BURST", Enchantment.WIND_BURST);
        values.put("UNBREAKING", Enchantment.UNBREAKING);
        values.put("MENDING", Enchantment.MENDING);
        values.put("LUNGE", Enchantment.LUNGE);
        return Map.copyOf(values);
    }

}
