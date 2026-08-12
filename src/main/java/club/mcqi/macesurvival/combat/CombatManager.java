package club.mcqi.macesurvival.combat;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import club.mcqi.macesurvival.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

public final class CombatManager {
    private static final String KIND_WEAPON_BOOK = "weapon_book";
    private static final String KIND_ARMOR_BOOK = "armor_book";
    private static final String KIND_BOOST = "boost";
    private static final String UPGRADE_PREFIX = "weapon_upgrade_";
    private static final List<Enchantment> KNOWN_ENCHANTMENTS = List.of(
        Enchantment.PROTECTION,
        Enchantment.FIRE_PROTECTION,
        Enchantment.FEATHER_FALLING,
        Enchantment.BLAST_PROTECTION,
        Enchantment.PROJECTILE_PROTECTION,
        Enchantment.RESPIRATION,
        Enchantment.AQUA_AFFINITY,
        Enchantment.THORNS,
        Enchantment.DEPTH_STRIDER,
        Enchantment.FROST_WALKER,
        Enchantment.SHARPNESS,
        Enchantment.UNBREAKING,
        Enchantment.DENSITY,
        Enchantment.BREACH,
        Enchantment.WIND_BURST,
        Enchantment.MENDING,
        Enchantment.LUNGE
    );

    private final JavaPlugin plugin;
    private final TextService text;
    private final Predicate<Player> participantPredicate;
    private final CombatListener listener;
    private final EquipmentBundleManager equipmentBundles;
    private final RandomGenerator random = ThreadLocalRandom.current();
    private final Map<UUID, MutableBuffState> buffs = new HashMap<>();
    private final Map<UUID, Integer> mergeEpochs = new HashMap<>();

    private final NamespacedKey lootKindKey;
    private final NamespacedKey enchantmentKey;
    private final NamespacedKey enchantmentLevelKey;
    private final NamespacedKey boostTypeKey;
    private final NamespacedKey starterWeaponKey;
    private final NamespacedKey weaponClassKey;
    private final NamespacedKey deathWeaponKey;
    private final NamespacedKey sourceChestKey;
    private final NamespacedKey killMendingKey;
    private final NamespacedKey lastMergeEpochKey;
    private final String namespace;

    public CombatManager(JavaPlugin plugin) {
        this(plugin, player -> true);
    }

    public CombatManager(JavaPlugin plugin, Predicate<Player> participantPredicate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.text = plugin instanceof MaceSurvivalPlugin maceSurvival ? maceSurvival.text() : null;
        this.participantPredicate = Objects.requireNonNull(participantPredicate, "participantPredicate");
        lootKindKey = new NamespacedKey(plugin, "loot_kind");
        enchantmentKey = new NamespacedKey(plugin, "loot_enchantment");
        enchantmentLevelKey = new NamespacedKey(plugin, "loot_enchantment_level");
        boostTypeKey = new NamespacedKey(plugin, "loot_boost");
        starterWeaponKey = new NamespacedKey(plugin, "starter_weapon");
        weaponClassKey = new NamespacedKey(plugin, "weapon_class");
        deathWeaponKey = new NamespacedKey(plugin, "death_weapon");
        sourceChestKey = new NamespacedKey(plugin, "source_chest");
        killMendingKey = new NamespacedKey(plugin, "kill_mending");
        lastMergeEpochKey = new NamespacedKey(plugin, "last_merge_epoch");
        namespace = lootKindKey.getNamespace();
        equipmentBundles = new EquipmentBundleManager(plugin);
        listener = new CombatListener(this);
    }

    public CombatListener listener() {
        return listener;
    }

    public boolean isParticipant(Player player) {
        return participantPredicate.test(player);
    }

    public void equipStarterKit(Player player) {
        equipStarterKit(player, StarterLayout.DEFAULT);
    }

    public void equipStarterKit(Player player, StarterLayout layout) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(layout, "layout");
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(layout.swordSlot(), createStarterWeapon(Material.NETHERITE_SWORD, WeaponClass.SWORD));
        inventory.setItem(layout.axeSlot(), createStarterWeapon(Material.NETHERITE_AXE, WeaponClass.AXE));
        inventory.setItem(layout.firstMaceSlot(), createStarterWeapon(Material.MACE, WeaponClass.MACE));
        inventory.setItem(layout.secondMaceSlot(), createStarterWeapon(Material.MACE, WeaponClass.MACE));
    }

    public ItemStack createStarterWeapon(Material material, WeaponClass weaponClass) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(starterWeaponKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(weaponClassKey, PersistentDataType.STRING,
                weaponClass.name().toLowerCase(Locale.ROOT));
        });
        return item;
    }

    public boolean isStarterWeapon(ItemStack item) {
        return hasByteFlag(item, starterWeaponKey);
    }

    public boolean storeDuplicateEquipment(Player player, ItemStack item) {
        return isParticipant(player) && equipmentBundles.storeDuplicatePickup(player, item);
    }

    public boolean isEquipmentBundle(ItemStack item) {
        return equipmentBundles.isBundle(item);
    }

    public boolean addToEquipmentBundle(ItemStack bundle, ItemStack item) {
        return equipmentBundles.add(bundle, item);
    }

    public ItemStack ejectEquipmentBundle(Player player, ItemStack bundle) {
        return equipmentBundles.eject(player, bundle);
    }

    public ItemStack createLootArmor(Material material) {
        ItemStack item = new ItemStack(material);
        if (!isArmor(item)) {
            throw new IllegalArgumentException(material + " is not player armor");
        }
        randomizeDurability(item, 0.30, 0.90);
        return item;
    }

    public void randomizeDurability(ItemStack item, double minimumRemaining, double maximumRemaining) {
        Objects.requireNonNull(item, "item");
        if (minimumRemaining < 0.0 || maximumRemaining > 1.0 || minimumRemaining > maximumRemaining) {
            throw new IllegalArgumentException("Remaining durability range must be inside 0.0 to 1.0");
        }
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof Damageable damageable) || !damageable.hasMaxDamage()) {
            return;
        }
        int maximumDamage = damageable.getMaxDamage();
        if (maximumDamage <= 1) {
            return;
        }
        double remainingRatio = random.nextDouble(minimumRemaining, Math.nextUp(maximumRemaining));
        int remaining = Math.max(1, Math.min(maximumDamage, (int) Math.round(maximumDamage * remainingRatio)));
        damageable.setDamage(maximumDamage - remaining);
        item.setItemMeta(damageable);
    }

    public void randomizeRemainingUses(ItemStack item, int minimumUses, int maximumUses) {
        Objects.requireNonNull(item, "item");
        if (minimumUses < 1 || maximumUses < minimumUses) {
            throw new IllegalArgumentException("Remaining uses must be a positive range");
        }
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof Damageable damageable) || !damageable.hasMaxDamage()) {
            return;
        }
        int maximumDamage = damageable.getMaxDamage();
        int upper = Math.max(1, Math.min(maximumDamage, maximumUses));
        int lower = Math.max(1, Math.min(upper, minimumUses));
        int remaining = random.nextInt(lower, upper + 1);
        damageable.setDamage(maximumDamage - remaining);
        item.setItemMeta(damageable);
    }

    public void applySpearDamageBonus(ItemStack spear, double bonusDamage) {
        Objects.requireNonNull(spear, "spear");
        if (!spear.getType().name().endsWith("_SPEAR") || !Double.isFinite(bonusDamage)
            || bonusDamage <= 0.0) {
            throw new IllegalArgumentException("Spear bonus damage must be positive and finite");
        }
        NamespacedKey modifierKey = new NamespacedKey(plugin, "spear_bonus_damage");
        spear.editMeta(meta -> {
            Collection<AttributeModifier> existing = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
            if (existing != null) {
                existing.stream()
                    .filter(modifier -> modifier.getKey().equals(modifierKey))
                    .toList()
                    .forEach(modifier -> meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE, modifier));
            }
            meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                modifierKey,
                bonusDamage,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND
            ));
        });
    }

    public ItemStack createWeaponBook(Enchantment enchantment, int level) {
        return createEnchantmentBook(enchantment, level, KIND_WEAPON_BOOK);
    }

    public ItemStack createArmorBook(Enchantment enchantment, int level) {
        return createEnchantmentBook(enchantment, level, KIND_ARMOR_BOOK);
    }

    public ItemStack createBoostToken(BuffType type) {
        Objects.requireNonNull(type, "type");
        String messageType = switch (type) {
            case DAMAGE -> "damage";
            case HEALING -> "healing";
            case NEXT_KILL -> "next-kill";
        };
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        item.editMeta(meta -> {
            meta.displayName(configuredMessage(null, "combat.boost-item." + messageType + ".name", Map.of()));
            meta.lore(List.of(configuredMessage(null, "combat.boost-item." + messageType + ".lore", Map.of())));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(lootKindKey, PersistentDataType.STRING, KIND_BOOST);
            data.set(boostTypeKey, PersistentDataType.STRING, type.name());
        });
        return item;
    }

    public void tagLootSource(ItemStack item, UUID chestId) {
        if (isEmpty(item)) {
            return;
        }
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
            sourceChestKey, PersistentDataType.STRING, chestId.toString()));
    }

    public Optional<UUID> lootSource(ItemStack item) {
        if (isEmpty(item)) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer()
            .get(sourceChestKey, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public LootApplyResult applyInstantLoot(Player player, ItemStack item, ArmorReturnSink returnSink) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(returnSink, "returnSink");
        if (isEmpty(item)) {
            return LootApplyResult.NOT_SPECIAL;
        }
        if (isArmor(item)) {
            return tryEquipArmor(player, item, returnSink)
                ? LootApplyResult.APPLIED
                : LootApplyResult.REJECTED;
        }

        String kind = item.getItemMeta().getPersistentDataContainer()
            .get(lootKindKey, PersistentDataType.STRING);
        if (KIND_ARMOR_BOOK.equals(kind)) {
            return applyArmorBook(player, item) ? LootApplyResult.APPLIED : LootApplyResult.REJECTED;
        }
        if (KIND_BOOST.equals(kind)) {
            return applyBoostToken(player, item) ? LootApplyResult.APPLIED : LootApplyResult.REJECTED;
        }
        return LootApplyResult.NOT_SPECIAL;
    }

    public boolean isWeaponBook(ItemStack item) {
        return KIND_WEAPON_BOOK.equals(lootKind(item));
    }

    public boolean applyWeaponBook(Player player, ItemStack book, ItemStack target) {
        if (!isParticipant(player) || !isWeaponBook(book) || isEmpty(target)) {
            return false;
        }
        Enchantment enchantment = readEnchantment(book).orElse(null);
        int bookLevel = readEnchantmentLevel(book);
        if (enchantment == null || bookLevel <= 0 || !enchantment.canEnchantItem(target)) {
            return false;
        }
        if (!applyEnchantmentUpgrade(target, enchantment, bookLevel)) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE,
            SoundCategory.PLAYERS, 1.0f, 1.0f);
        int appliedLevel = target.getEnchantmentLevel(enchantment);
        player.sendActionBar(configuredMessage(
            player,
            "combat.enchant-applied",
            Map.of("level", appliedLevel),
            Map.of("enchantment", enchantment.description().color(NamedTextColor.AQUA))
        ));
        return true;
    }

    public boolean tryEquipArmor(Player player, ItemStack candidate, ArmorReturnSink returnSink) {
        if (!isParticipant(player) || !isArmor(candidate)) {
            return false;
        }
        EquipmentSlot slot = armorSlot(candidate).orElseThrow();
        ItemStack current = player.getInventory().getItem(slot);
        if (!isEmpty(current) && compareArmor(candidate, current) <= 0) {
            return false;
        }

        ItemStack equipped = candidate.clone();
        equipped.setAmount(1);
        equipped.editMeta(meta -> meta.getPersistentDataContainer().remove(sourceChestKey));
        player.getInventory().setItem(slot, equipped);
        if (!isEmpty(current)) {
            returnSink.returnArmor(current.clone());
        }
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC,
            SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.sendActionBar(configuredMessage(
            player,
            "combat.armor-equipped",
            Map.of(),
            Map.of("item", Component.translatable(equipped.translationKey(), NamedTextColor.AQUA))
        ));
        return true;
    }

    public boolean mergeDamagedItems(Player player, ItemStack donor, ItemStack target) {
        if (!isParticipant(player) || isEmpty(donor) || isEmpty(target)
            || donor.getType() != target.getType() || donor.getAmount() != 1 || target.getAmount() != 1) {
            return false;
        }
        ItemMeta donorRawMeta = donor.getItemMeta();
        ItemMeta targetRawMeta = target.getItemMeta();
        if (!(donorRawMeta instanceof Damageable donorDamageable)
            || !(targetRawMeta instanceof Damageable targetDamageable)
            || donorRawMeta.isUnbreakable() || targetRawMeta.isUnbreakable()) {
            return false;
        }
        int epoch = mergeEpochs.getOrDefault(player.getUniqueId(), 0);
        int donorEpoch = donorRawMeta.getPersistentDataContainer()
            .getOrDefault(lastMergeEpochKey, PersistentDataType.INTEGER, Integer.MIN_VALUE);
        if (donorEpoch == epoch) {
            return false;
        }
        int lastEpoch = targetRawMeta.getPersistentDataContainer()
            .getOrDefault(lastMergeEpochKey, PersistentDataType.INTEGER, Integer.MIN_VALUE);
        if (lastEpoch == epoch) {
            return false;
        }

        if (!targetDamageable.hasMaxDamage() || !donorDamageable.hasMaxDamage()) {
            return false;
        }
        int targetMaximum = targetDamageable.getMaxDamage();
        int donorMaximum = donorDamageable.getMaxDamage();
        if (targetMaximum <= 0 || donorMaximum <= 0 || targetDamageable.getDamage() <= 0) {
            return false;
        }
        int targetRemaining = targetMaximum - targetDamageable.getDamage();
        int donorRemaining = donorMaximum - donorDamageable.getDamage();
        int repairedRemaining = Math.min(targetMaximum, targetRemaining + Math.max(1, donorRemaining / 2));
        if (repairedRemaining <= targetRemaining) {
            return false;
        }
        targetDamageable.setDamage(targetMaximum - repairedRemaining);
        PersistentDataContainer targetData = targetDamageable.getPersistentDataContainer();
        targetData.set(lastMergeEpochKey, PersistentDataType.INTEGER, epoch);
        target.setItemMeta(targetDamageable);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.0f);
        player.sendActionBar(configuredMessage(player, "combat.durability-combined", Map.of()));
        return true;
    }

    public void recordKill(Player killer) {
        if (!isParticipant(killer)) {
            return;
        }
        mergeEpochs.merge(killer.getUniqueId(), 1, Integer::sum);
        repairInventoryAfterKill(killer);
        MutableBuffState state = buffs.get(killer.getUniqueId());
        if (state == null || state.pendingKillBoosts <= 0) {
            return;
        }
        state.pendingKillBoosts--;
        BuffType granted = choosePermanentKillBoost(state);
        if (granted == null) {
            return;
        }
        if (granted == BuffType.DAMAGE) {
            state.damageStacks++;
        } else if (granted == BuffType.HEALING) {
            state.healingStacks++;
        }
        String boostPath = granted == BuffType.DAMAGE
            ? "combat.boost-triggered-damage"
            : "combat.boost-triggered-healing";
        String percent = percent(granted == BuffType.DAMAGE ? damagePerBook() : healingPerBook());
        Component boost = configuredMessage(killer, boostPath, Map.of("percent", percent));
        killer.sendActionBar(configuredMessage(
            killer,
            "combat.boost-triggered",
            Map.of(),
            Map.of("boost", boost)
        ));
    }

    public BuffSnapshot buffs(UUID playerId) {
        MutableBuffState state = buffs.get(playerId);
        if (state == null) {
            return new BuffSnapshot(0, 0, 0);
        }
        return new BuffSnapshot(state.damageStacks, state.healingStacks, state.pendingKillBoosts);
    }

    public double damageMultiplier(Player player) {
        BuffSnapshot snapshot = buffs(player.getUniqueId());
        return 1.0 + snapshot.damageStacks()
            * plugin.getConfig().getDouble("upgrades.damage-per-book", 0.03);
    }

    public double healingMultiplier(Player player) {
        BuffSnapshot snapshot = buffs(player.getUniqueId());
        return 1.0 + snapshot.healingStacks()
            * plugin.getConfig().getDouble("upgrades.healing-per-book", 0.05);
    }

    public void resetPlayer(UUID playerId) {
        buffs.remove(playerId);
        mergeEpochs.remove(playerId);
    }

    public void resetMatch() {
        buffs.clear();
        mergeEpochs.clear();
    }

    public void markDeathWeapons(List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            WeaponClass.infer(drop).ifPresent(weaponClass -> drop.editMeta(meta -> {
                PersistentDataContainer data = meta.getPersistentDataContainer();
                data.set(deathWeaponKey, PersistentDataType.BYTE, (byte) 1);
                data.set(weaponClassKey, PersistentDataType.STRING,
                    weaponClass.name().toLowerCase(Locale.ROOT));
            }));
        }
    }

    public boolean isDeathWeapon(ItemStack item) {
        return hasByteFlag(item, deathWeaponKey);
    }

    public boolean mergeDeathWeapon(Player player, ItemStack source) {
        if (!isParticipant(player) || !isDeathWeapon(source)) {
            return false;
        }
        WeaponClass weaponClass = weaponClass(source).orElse(null);
        if (weaponClass == null) {
            return false;
        }
        ItemStack target = findFusionTarget(player, weaponClass);
        if (target == null) {
            source.editMeta(meta -> meta.getPersistentDataContainer().remove(deathWeaponKey));
            return false;
        }
        mergeWeaponProperties(source, target);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK,
            SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.sendActionBar(configuredMessage(player, "combat.weapon-absorbed", Map.of()));
        return true;
    }

    public void setWeaponUpgrade(ItemStack weapon, String upgradeId, int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Upgrade level cannot be negative");
        }
        NamespacedKey key = upgradeKey(upgradeId);
        weapon.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, level));
    }

    public int getWeaponUpgrade(ItemStack weapon, String upgradeId) {
        if (isEmpty(weapon)) {
            return 0;
        }
        return weapon.getItemMeta().getPersistentDataContainer()
            .getOrDefault(upgradeKey(upgradeId), PersistentDataType.INTEGER, 0);
    }

    public int maximumLevel(Enchantment enchantment) {
        if (enchantment.equals(Enchantment.MENDING)) {
            return 3;
        }
        if (enchantment.equals(Enchantment.THORNS)) {
            return Math.max(1, plugin.getConfig().getInt("upgrades.thorns-maximum-level", 4));
        }
        return enchantment.getMaxLevel()
            + Math.max(0, plugin.getConfig().getInt("upgrades.maximum-extra-enchantment-levels", 3));
    }

    public Optional<EquipmentSlot> armorSlot(ItemStack item) {
        if (isEmpty(item)) {
            return Optional.empty();
        }
        String name = item.getType().name();
        if (name.endsWith("_HELMET") || item.getType() == Material.TURTLE_HELMET) {
            return Optional.of(EquipmentSlot.HEAD);
        }
        if (name.endsWith("_CHESTPLATE")) {
            return Optional.of(EquipmentSlot.CHEST);
        }
        if (name.endsWith("_LEGGINGS")) {
            return Optional.of(EquipmentSlot.LEGS);
        }
        if (name.endsWith("_BOOTS")) {
            return Optional.of(EquipmentSlot.FEET);
        }
        return Optional.empty();
    }

    public boolean isArmor(ItemStack item) {
        return armorSlot(item).isPresent();
    }

    public int compareArmor(ItemStack first, ItemStack second) {
        int materialComparison = Integer.compare(armorMaterialTier(first.getType()), armorMaterialTier(second.getType()));
        if (materialComparison != 0) {
            return materialComparison;
        }
        int enchantmentComparison = Integer.compare(enchantmentScore(first), enchantmentScore(second));
        if (enchantmentComparison != 0) {
            return enchantmentComparison;
        }
        return Double.compare(remainingDurabilityRatio(first), remainingDurabilityRatio(second));
    }

    void repairInventoryAfterKill(Player player) {
        List<ItemStack> damaged = damageableInventoryItems(player);
        int mendingLevel = allInventoryItems(player).stream()
            .mapToInt(this::killMendingLevel)
            .max()
            .orElse(0);
        if (mendingLevel <= 0) {
            return;
        }
        List<Double> configuredRatios = plugin.getConfig().getDoubleList("upgrades.kill-mending-percentages");
        List<Double> ratios = configuredRatios.size() >= 3
            ? configuredRatios
            : List.of(0.01, 0.03, 0.05);
        double ratio = Math.max(0.0, ratios.get(Math.min(mendingLevel, ratios.size()) - 1));
        int repairPool = (int) Math.ceil(damaged.stream()
            .map(ItemStack::getItemMeta)
            .filter(Damageable.class::isInstance)
            .map(Damageable.class::cast)
            .mapToInt(Damageable::getMaxDamage)
            .sum() * ratio);
        distributeRepair(damaged, repairPool);
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE,
            SoundCategory.PLAYERS, 0.7f, 1.0f);
    }

    private ItemStack createEnchantmentBook(Enchantment enchantment, int level, String kind) {
        Objects.requireNonNull(enchantment, "enchantment");
        if (level < 1 || level > maximumLevel(enchantment)) {
            throw new IllegalArgumentException("Invalid level " + level + " for " + enchantment.getKey());
        }
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        item.editMeta(meta -> {
            if (meta instanceof EnchantmentStorageMeta storageMeta) {
                storageMeta.addStoredEnchant(enchantment, level, true);
            }
            meta.displayName(noItalic(enchantment.displayName(level).color(NamedTextColor.AQUA)));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(lootKindKey, PersistentDataType.STRING, kind);
            data.set(enchantmentKey, PersistentDataType.STRING, enchantment.getKey().toString());
            data.set(enchantmentLevelKey, PersistentDataType.INTEGER, level);
        });
        return item;
    }

    private boolean applyArmorBook(Player player, ItemStack book) {
        Enchantment enchantment = readEnchantment(book).orElse(null);
        int level = readEnchantmentLevel(book);
        if (enchantment == null || level <= 0) {
            return false;
        }
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (isEmpty(armor) || !enchantment.canEnchantItem(armor)) {
                continue;
            }
            ItemMeta meta = armor.getItemMeta();
            int current = meta.getEnchantLevel(enchantment);
            if (!meta.hasConflictingEnchant(enchantment) && current < maximumLevel(enchantment)) {
                candidates.add(armor);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        ItemStack target = candidates.get(random.nextInt(candidates.size()));
        if (!applyEnchantmentUpgrade(target, enchantment, level)) {
            return false;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE,
            SoundCategory.PLAYERS, 1.0f, 1.0f);
        int appliedLevel = target.getEnchantmentLevel(enchantment);
        player.sendActionBar(configuredMessage(
            player,
            "combat.armor-enchanted",
            Map.of("level", appliedLevel),
            Map.of("enchantment", enchantment.description().color(NamedTextColor.AQUA))
        ));
        return true;
    }

    private boolean applyBoostToken(Player player, ItemStack token) {
        String encoded = token.getItemMeta().getPersistentDataContainer()
            .get(boostTypeKey, PersistentDataType.STRING);
        if (encoded == null) {
            return false;
        }
        BuffType type;
        try {
            type = BuffType.valueOf(encoded);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        MutableBuffState state = buffs.computeIfAbsent(player.getUniqueId(), ignored -> new MutableBuffState());
        String feedbackPath;
        switch (type) {
            case DAMAGE -> {
                if (state.damageStacks >= maximumDamageStacks()) {
                    return false;
                }
                state.damageStacks++;
                feedbackPath = "combat.boost-damage";
            }
            case HEALING -> {
                if (state.healingStacks >= maximumHealingStacks()) {
                    return false;
                }
                state.healingStacks++;
                feedbackPath = "combat.boost-healing";
            }
            case NEXT_KILL -> {
                state.pendingKillBoosts++;
                feedbackPath = "combat.boost-next-kill";
            }
            default -> throw new IllegalStateException("Unhandled boost " + type);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT,
            SoundCategory.PLAYERS, 1.0f, 1.0f);
        String percent = type == BuffType.DAMAGE ? percent(damagePerBook()) : percent(healingPerBook());
        player.sendActionBar(configuredMessage(player, feedbackPath, Map.of("percent", percent)));
        return true;
    }

    private boolean applyEnchantmentUpgrade(ItemStack target, Enchantment enchantment, int incomingLevel) {
        ItemMeta meta = target.getItemMeta();
        if (meta.hasConflictingEnchant(enchantment) && !meta.hasEnchant(enchantment)) {
            return false;
        }
        int current = meta.getEnchantLevel(enchantment);
        int maximum = maximumLevel(enchantment);
        int desired = incomingLevel == current
            ? Math.min(maximum, current + 1)
            : Math.min(maximum, Math.max(current, incomingLevel));
        if (desired <= current) {
            return false;
        }
        meta.addEnchant(enchantment, desired, true);
        if (enchantment.equals(Enchantment.MENDING)) {
            meta.getPersistentDataContainer().set(killMendingKey, PersistentDataType.INTEGER, desired);
        }
        target.setItemMeta(meta);
        return true;
    }

    private Optional<Enchantment> readEnchantment(ItemStack item) {
        if (isEmpty(item)) {
            return Optional.empty();
        }
        String key = item.getItemMeta().getPersistentDataContainer()
            .get(enchantmentKey, PersistentDataType.STRING);
        if (key == null) {
            return Optional.empty();
        }
        return KNOWN_ENCHANTMENTS.stream()
            .filter(enchantment -> enchantment.getKey().toString().equals(key))
            .findFirst();
    }

    private int readEnchantmentLevel(ItemStack item) {
        if (isEmpty(item)) {
            return 0;
        }
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(enchantmentLevelKey, PersistentDataType.INTEGER, 0);
    }

    private String lootKind(ItemStack item) {
        if (isEmpty(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
            .get(lootKindKey, PersistentDataType.STRING);
    }

    private Optional<WeaponClass> weaponClass(ItemStack item) {
        if (isEmpty(item)) {
            return Optional.empty();
        }
        String stored = item.getItemMeta().getPersistentDataContainer()
            .get(weaponClassKey, PersistentDataType.STRING);
        if (stored != null) {
            try {
                return Optional.of(WeaponClass.valueOf(stored.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Fall through to material inference.
            }
        }
        return WeaponClass.infer(item);
    }

    private ItemStack findFusionTarget(Player player, WeaponClass weaponClass) {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
            .filter(item -> !isEmpty(item))
            .filter(item -> weaponClass(item).orElse(null) == weaponClass)
            .filter(item -> !isDeathWeapon(item))
            .min(Comparator.comparingInt(this::weaponUpgradeScore)
                .thenComparingDouble(this::remainingDurabilityRatio))
            .orElse(null);
    }

    private void mergeWeaponProperties(ItemStack source, ItemStack target) {
        ItemMeta sourceMeta = source.getItemMeta();
        ItemMeta targetMeta = target.getItemMeta();
        for (Map.Entry<Enchantment, Integer> entry : sourceMeta.getEnchants().entrySet()) {
            int targetLevel = targetMeta.getEnchantLevel(entry.getKey());
            if (entry.getValue() > targetLevel && !targetMeta.hasConflictingEnchant(entry.getKey())) {
                targetMeta.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }

        PersistentDataContainer sourceData = sourceMeta.getPersistentDataContainer();
        PersistentDataContainer targetData = targetMeta.getPersistentDataContainer();
        for (NamespacedKey key : sourceData.getKeys()) {
            if (!key.getNamespace().equals(namespace)
                || !key.getKey().startsWith(UPGRADE_PREFIX)) {
                continue;
            }
            Integer sourceLevel = sourceData.get(key, PersistentDataType.INTEGER);
            int targetLevel = targetData.getOrDefault(key, PersistentDataType.INTEGER, 0);
            if (sourceLevel != null && sourceLevel > targetLevel) {
                targetData.set(key, PersistentDataType.INTEGER, sourceLevel);
            }
        }
        int sourceMending = sourceData.getOrDefault(killMendingKey, PersistentDataType.INTEGER, 0);
        int targetMending = targetData.getOrDefault(killMendingKey, PersistentDataType.INTEGER, 0);
        if (sourceMending > targetMending) {
            targetData.set(killMendingKey, PersistentDataType.INTEGER, sourceMending);
        }
        mergeAttributeModifiers(sourceMeta, targetMeta);

        if (sourceMeta instanceof Damageable sourceDamage && targetMeta instanceof Damageable targetDamage
            && !targetMeta.isUnbreakable() && sourceDamage.hasMaxDamage() && targetDamage.hasMaxDamage()
            && sourceDamage.getMaxDamage() == targetDamage.getMaxDamage()) {
            targetDamage.setDamage(Math.min(targetDamage.getDamage(), sourceDamage.getDamage()));
        }
        target.setItemMeta(targetMeta);
    }

    private int weaponUpgradeScore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        int score = meta.getEnchants().values().stream().mapToInt(Integer::intValue).sum();
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals(namespace) && key.getKey().startsWith(UPGRADE_PREFIX)) {
                score += meta.getPersistentDataContainer()
                    .getOrDefault(key, PersistentDataType.INTEGER, 0);
            }
        }
        return score;
    }

    private BuffType choosePermanentKillBoost(MutableBuffState state) {
        if (state.damageStacks >= maximumDamageStacks() && state.healingStacks >= maximumHealingStacks()) {
            return null;
        }
        if (state.damageStacks >= maximumDamageStacks()) {
            return BuffType.HEALING;
        }
        if (state.healingStacks >= maximumHealingStacks()) {
            return BuffType.DAMAGE;
        }
        return random.nextBoolean() ? BuffType.DAMAGE : BuffType.HEALING;
    }

    private int maximumDamageStacks() {
        return Math.max(0, plugin.getConfig().getInt("upgrades.damage-maximum-books", 5));
    }

    private int maximumHealingStacks() {
        return Math.max(0, plugin.getConfig().getInt("upgrades.healing-maximum-books", 5));
    }

    private double damagePerBook() {
        return Math.max(0.0D, plugin.getConfig().getDouble("upgrades.damage-per-book", 0.03D));
    }

    private double healingPerBook() {
        return Math.max(0.0D, plugin.getConfig().getDouble("upgrades.healing-per-book", 0.05D));
    }

    private List<ItemStack> damageableInventoryItems(Player player) {
        return allInventoryItems(player).stream()
            .filter(item -> {
                ItemMeta meta = item.getItemMeta();
                return meta instanceof Damageable damageable
                    && !meta.isUnbreakable() && damageable.getDamage() > 0;
            })
            .toList();
    }

    private List<ItemStack> allInventoryItems(Player player) {
        List<ItemStack> result = new ArrayList<>();
        appendNonEmpty(result, player.getInventory().getStorageContents());
        appendNonEmpty(result, player.getInventory().getArmorContents());
        appendNonEmpty(result, player.getInventory().getExtraContents());
        return result;
    }

    private void appendNonEmpty(List<ItemStack> output, ItemStack[] items) {
        for (ItemStack item : items) {
            if (!isEmpty(item)) {
                output.add(item);
            }
        }
    }

    private void mergeAttributeModifiers(ItemMeta sourceMeta, ItemMeta targetMeta) {
        if (!sourceMeta.hasAttributeModifiers()) {
            return;
        }
        var sourceModifiers = sourceMeta.getAttributeModifiers();
        if (sourceModifiers == null) {
            return;
        }
        for (Map.Entry<Attribute, AttributeModifier> entry : sourceModifiers.entries()) {
            Attribute attribute = entry.getKey();
            AttributeModifier incoming = entry.getValue();
            Collection<AttributeModifier> targetModifiers = targetMeta.getAttributeModifiers(attribute);
            AttributeModifier matching = (targetModifiers == null ? List.<AttributeModifier>of() : targetModifiers).stream()
                .filter(modifier -> modifier.getKey().equals(incoming.getKey()))
                .findFirst()
                .orElse(null);
            if (matching != null && Math.abs(matching.getAmount()) >= Math.abs(incoming.getAmount())) {
                continue;
            }
            if (matching != null) {
                targetMeta.removeAttributeModifier(attribute, matching);
            }
            targetMeta.addAttributeModifier(attribute, incoming);
        }
    }

    private int killMendingLevel(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(killMendingKey, PersistentDataType.INTEGER, 0);
    }

    private void distributeRepair(List<ItemStack> items, int repairPool) {
        List<ItemStack> remaining = new ArrayList<>(items);
        int points = repairPool;
        while (points > 0 && !remaining.isEmpty()) {
            int share = Math.max(1, points / remaining.size());
            for (int index = remaining.size() - 1; index >= 0 && points > 0; index--) {
                ItemStack item = remaining.get(index);
                ItemMeta rawMeta = item.getItemMeta();
                if (!(rawMeta instanceof Damageable damageable)) {
                    remaining.remove(index);
                    continue;
                }
                int repaired = Math.min(damageable.getDamage(), Math.min(share, points));
                damageable.setDamage(damageable.getDamage() - repaired);
                item.setItemMeta(damageable);
                points -= repaired;
                if (damageable.getDamage() == 0) {
                    remaining.remove(index);
                }
            }
        }
    }

    private int enchantmentScore(ItemStack item) {
        return item.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
    }

    private double remainingDurabilityRatio(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable) || !damageable.hasMaxDamage() || damageable.getMaxDamage() <= 0) {
            return 1.0;
        }
        return (damageable.getMaxDamage() - damageable.getDamage()) / (double) damageable.getMaxDamage();
    }

    private int armorMaterialTier(Material material) {
        String name = material.name();
        if (name.startsWith("NETHERITE_")) {
            return 7;
        }
        if (name.startsWith("DIAMOND_")) {
            return 6;
        }
        if (name.startsWith("IRON_") || material == Material.TURTLE_HELMET) {
            return 5;
        }
        if (name.startsWith("COPPER_")) {
            return 4;
        }
        if (name.startsWith("CHAINMAIL_")) {
            return 3;
        }
        if (name.startsWith("GOLDEN_")) {
            return 2;
        }
        if (name.startsWith("LEATHER_")) {
            return 1;
        }
        return 0;
    }

    private NamespacedKey upgradeKey(String upgradeId) {
        Objects.requireNonNull(upgradeId, "upgradeId");
        String normalized = upgradeId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Upgrade id cannot be blank");
        }
        return new NamespacedKey(plugin, UPGRADE_PREFIX + normalized);
    }

    private boolean hasByteFlag(ItemStack item, NamespacedKey key) {
        return !isEmpty(item) && item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(key, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private Component configuredMessage(Player player, String path, Map<String, ?> placeholders) {
        return text == null ? Component.empty() : text.message(player, path, placeholders);
    }

    private Component configuredMessage(
        Player player,
        String path,
        Map<String, ?> placeholders,
        Map<String, Component> componentPlaceholders
    ) {
        return text == null
            ? Component.empty()
            : text.messageWithComponents(player, path, placeholders, componentPlaceholders);
    }

    private static String percent(double ratio) {
        return BigDecimal.valueOf(ratio * 100.0D).stripTrailingZeros().toPlainString();
    }

    private Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private static final class MutableBuffState {
        private int damageStacks;
        private int healingStacks;
        private int pendingKillBoosts;
    }
}
