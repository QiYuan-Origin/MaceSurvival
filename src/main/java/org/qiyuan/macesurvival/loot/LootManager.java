package org.qiyuan.macesurvival.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.arena.ChestTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loot comes from loot.yml, including the original tables.* list format.
 * Transactions consume their exact source once. Custom counters are authoritative;
 * native damage is only the visual mirror, refreshed without changing enchants.
 */
public final class LootManager {
    private final MaceSurvival plugin;
    private final NamespacedKey durabilityKey;
    private final NamespacedKey capacityKey;
    private final NamespacedKey oldDurabilityKey;
    private final NamespacedKey oldCapacityKey;
    private final NamespacedKey loreKey;
    private final Set<String> reported = new HashSet<>();

    public LootManager(MaceSurvival plugin) {
        this.plugin = plugin;
        durabilityKey = new NamespacedKey(plugin, "durability");
        capacityKey = new NamespacedKey(plugin, "max-durability");
        oldDurabilityKey = new NamespacedKey(plugin, "custom_durability");
        oldCapacityKey = new NamespacedKey(plugin, "max_durability");
        loreKey = new NamespacedKey(plugin, "durability_lore");
    }

    public static String tierKey(int stars) {
        return switch (Math.max(1, Math.min(4, stars))) {
            case 1 -> "one-star";
            case 2 -> "two-star";
            case 3 -> "three-star";
            default -> "four-star";
        };
    }

    /** Accept the original tables.* lists and the previous rewrite's keyed maps. */
    List<ConfigurationSection> table(int stars) {
        var config = plugin.configs().loot();
        String key = tierKey(stars);
        List<ConfigurationSection> result = new ArrayList<>();
        for (String path : List.of("tables." + key, key, stars + "-star")) {
            if (config.isList(path)) {
                for (Map<?, ?> map : config.getMapList(path)) {
                    MemoryConfiguration section = new MemoryConfiguration();
                    map.forEach((k, v) -> section.set(String.valueOf(k), v));
                    result.add(section);
                }
            } else {
                ConfigurationSection section = config.getConfigurationSection(path);
                if (section != null) {
                    for (String child : section.getKeys(false)) {
                        ConfigurationSection entry = section.getConfigurationSection(child);
                        if (entry != null) result.add(entry);
                    }
                }
            }
            if (!result.isEmpty()) break;
        }
        return result.isEmpty() && stars >= 4 ? table(3) : result;
    }

    public List<ItemStack> generateLoot(int stars) {
        List<WeightedEntry> entries = new ArrayList<>();
        long total = 0;
        for (ConfigurationSection section : table(stars)) {
            int weight = section.getInt("weight", 1);
            if (weight <= 0) continue;
            Material material = Material.matchMaterial(section.getString("material", "BOOK"));
            if (material == null || material.isAir() || !material.isItem()) {
                warn("material:" + section.getString("material"), "Invalid loot material: " + section.getString("material"));
                continue;
            }
            entries.add(new WeightedEntry(section, material, weight));
            total += weight;
        }
        if (entries.isEmpty()) {
            warn("table:" + stars, "No valid loot entries for " + tierKey(stars));
            return List.of();
        }
        var config = plugin.configs().loot();
        int rolls = Math.max(0, Math.min(54, config.getInt("rolls." + tierKey(stars), 2 + stars * 2)));
        double empty = Math.max(0, Math.min(100, config.getDouble("empty-chance." + tierKey(stars), 0)));
        List<ItemStack> output = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < rolls; i++) {
            if (random.nextDouble(100) < empty) continue;
            long cursor = random.nextLong(total);
            for (WeightedEntry entry : entries) {
                cursor -= entry.weight();
                if (cursor < 0) {
                    ItemStack item = build(entry.section(), entry.material());
                    if (item != null) output.add(item);
                    break;
                }
            }
        }
        return output;
    }

    public List<ItemStack> roll(ChestTier tier) { return generateLoot(tier.getStars()); }
    public List<ItemStack> preview(int stars) { return generateLoot(stars); }

    public void fillChest(Inventory inventory, ChestTier tier) {
        inventory.clear();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) slots.add(slot);
        Collections.shuffle(slots, ThreadLocalRandom.current());
        List<ItemStack> items = generateLoot(tier.getStars());
        for (int i = 0; i < Math.min(slots.size(), items.size()); i++) inventory.setItem(slots.get(i), items.get(i));
    }

    private ItemStack build(ConfigurationSection entry, Material material) {
        String passive = entry.getString("passive", "");
        if (!passive.isBlank()) return plugin.enchants().createPassiveBook(passive, entry.getInt("level", 1));
        String book = entry.getString("book", "");
        if (!book.isBlank() || material == Material.ENCHANTED_BOOK) {
            if (book.isBlank()) book = entry.getString("enchant", "");
            EnchantManager.EnchantSpec spec = plugin.enchants().parseSpec(book, entry.getInt("level", 1));
            if (spec == null) { warn("book:" + book, "Invalid or empty loot enchantment book: " + book); return null; }
            return plugin.enchants().createEnchantBook(spec.enchantment(), spec.level(), entry.getBoolean("auto-apply", false));
        }
        int min = Math.max(1, Math.min(material.getMaxStackSize(), entry.getInt("min", entry.getInt("amount", 1))));
        int max = Math.max(min, Math.min(material.getMaxStackSize(), entry.getInt("max", min)));
        ItemStack item = new ItemStack(material, ThreadLocalRandom.current().nextInt(min, max + 1));
        if (entry.getInt("durability", 0) > 0 && material.getMaxDurability() > 0) initializeDurability(item, entry.getInt("durability"));
        String enchant = entry.getString("enchant", "");
        if (!enchant.isBlank()) {
            EnchantManager.EnchantSpec spec = plugin.enchants().parseSpec(enchant, entry.getInt("level", 1));
            if (spec != null) plugin.enchants().applyEnchantment(item, spec.enchantment(), spec.level());
        }
        String potion = entry.getString("potion", "");
        if (!potion.isBlank() && item.getItemMeta() instanceof PotionMeta meta) {
            try {
                meta.setBasePotionType(PotionType.valueOf(potion.toUpperCase(Locale.ROOT)));
                item.setItemMeta(meta);
            } catch (IllegalArgumentException exception) { warn("potion:" + potion, "Invalid loot potion: " + potion); return null; }
        }
        return item;
    }

    public ItemStack createDurableItem(Material material, int durability) {
        ItemStack item = new ItemStack(material);
        initializeDurability(item, durability);
        return item;
    }

    public void initializeDurability(ItemStack item, int durability) {
        if (item == null || item.getType().getMaxDurability() <= 0) return;
        ItemMeta meta = item.getItemMeta();
        int capacity = Math.max(1, durability);
        meta.getPersistentDataContainer().set(capacityKey, PersistentDataType.INTEGER, capacity);
        meta.getPersistentDataContainer().set(oldCapacityKey, PersistentDataType.INTEGER, capacity);
        item.setItemMeta(meta);
        setCustomDurability(item, capacity);
    }

    public boolean hasCustomDurability(ItemStack item) {
        if (item == null || item.getAmount() <= 0 || item.getType().isAir() || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(durabilityKey, PersistentDataType.INTEGER) || pdc.has(oldDurabilityKey, PersistentDataType.INTEGER);
    }

    public int getCustomDurability(ItemStack item) {
        if (!hasCustomDurability(item)) return 0;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return Math.max(0, pdc.getOrDefault(durabilityKey, PersistentDataType.INTEGER,
            pdc.getOrDefault(oldDurabilityKey, PersistentDataType.INTEGER, 0)));
    }

    public int getMaxDurability(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return Math.max(1, pdc.getOrDefault(capacityKey, PersistentDataType.INTEGER,
            pdc.getOrDefault(oldCapacityKey, PersistentDataType.INTEGER, Math.max(1, getCustomDurability(item)))));
    }

    /** Refresh only our lore line, preserving enchantments and other item data. */
    public void setCustomDurability(ItemStack item, int durability) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        int remaining = Math.max(0, durability);
        int capacity = Math.max(getMaxDurability(item), remaining);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(durabilityKey, PersistentDataType.INTEGER, remaining);
        pdc.set(oldDurabilityKey, PersistentDataType.INTEGER, remaining);
        pdc.set(capacityKey, PersistentDataType.INTEGER, capacity);
        pdc.set(oldCapacityKey, PersistentDataType.INTEGER, capacity);
        if (meta instanceof Damageable damageable && item.getType().getMaxDurability() > 0) {
            int vanillaMax = item.getType().getMaxDurability();
            // 1.21.11 stops gliding at maxDamage - 1, before another wear event
            // can reach us. A merged high-capacity elytra must remain usable
            // until its authoritative custom counter is consumed completely.
            int lastUsableDamage = vanillaMax - (item.getType() == Material.ELYTRA ? 2 : 1);
            damageable.setDamage(Math.max(0, Math.min(lastUsableDamage,
                (int) Math.round(vanillaMax * (1D - (double) remaining / capacity)))));
        }
        List<net.kyori.adventure.text.Component> lore = meta.lore();
        lore = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
        int previous = pdc.getOrDefault(loreKey, PersistentDataType.INTEGER, -1);
        var line = plugin.messages().get("loot.item-durability", Map.of("durability", String.valueOf(remaining), "max", String.valueOf(capacity)))
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        if (previous >= 0 && previous < lore.size()) lore.set(previous, line);
        else { previous = lore.size(); lore.add(line); }
        pdc.set(loreKey, PersistentDataType.INTEGER, previous);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** One cursor item grants half its remaining durability to the exact target. */
    public boolean merge(Player player, ItemStack source, ItemStack target) {
        if (!plugin.configs().main().getBoolean("loot.merge-durability", true)
            || source == target || !hasCustomDurability(source) || !hasCustomDurability(target)
            || source.getType() != target.getType() || target.getAmount() != 1
            || getCustomDurability(target) >= getMaxDurability(target)) return false;
        int gain = getCustomDurability(source) / 2;
        if (gain < 1 || getCustomDurability(target) < 1) return false;
        setCustomDurability(target, (int) Math.min(Integer.MAX_VALUE, (long) getCustomDurability(target) + gain));
        source.setAmount(source.getAmount() - 1);
        player.sendMessage(plugin.messages().prefixed("loot.merged", Map.of("durability", String.valueOf(getCustomDurability(target)))));
        return true;
    }

    public boolean tryMerge(Player player, ItemStack pickup) {
        for (ItemStack target : player.getInventory().getStorageContents()) if (merge(player, pickup, target)) return true;
        return false;
    }
    public boolean mergeInto(Player player, ItemStack pickup) { return tryMerge(player, pickup); }
    public void mergeMaceDurability(Player player, ItemStack pickup) { tryMerge(player, pickup); }

    public void damageItem(ItemStack item, int amount) {
        if (!hasCustomDurability(item) || amount <= 0) return;
        setCustomDurability(item, Math.max(0, getCustomDurability(item) - amount));
        if (getCustomDurability(item) == 0) item.setAmount(0);
    }

    public boolean damage(Player player, ItemStack item, int amount) {
        if (!hasCustomDurability(item) || amount <= 0) return false;
        int remaining = Math.max(0, getCustomDurability(item) - amount);
        if (remaining > 0) { setCustomDurability(item, remaining); return false; }
        item.setAmount(0);
        Location at = player.getLocation().clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // The hit can eliminate the last opponent and finish the round in
            // this same tick. Never detonate an expired use in a later phase.
            if (!player.isOnline() || player.isDead() || !plugin.game().active() || !plugin.game().participant(player)
                || plugin.arenas().getArenaWorld() != at.getWorld() || player.getWorld() != at.getWorld()) return;
            player.sendMessage(plugin.messages().prefixed("loot.exploded"));
            if (plugin.configs().main().getBoolean("loot.durability-explosion", true)) {
                float power = (float) Math.max(0, Math.min(8, plugin.configs().main().getDouble("loot.explosion-power", 2.4)));
                boolean blocks = plugin.configs().main().getBoolean("loot.explosion-breaks-blocks", false);
                at.getWorld().createExplosion(at, power, false, blocks, player);
            }
        });
        return true;
    }

    public boolean consume(Player player, int slot, ItemStack item) { return damage(player, item, 1); }

    /** A confirmed kill repairs carried custom items with Mending once. */
    public void onKill(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (!hasCustomDurability(item) || item.getEnchantmentLevel(Enchantment.MENDING) <= 0) continue;
            int max = getMaxDurability(item);
            double fraction = Math.max(0, Math.min(1, plugin.configs().loot().getDouble("mending.kill-repair-fraction", 0.25)));
            if (fraction == 0) continue;
            long gain = Math.max(1, Math.round(max * fraction)) * item.getEnchantmentLevel(Enchantment.MENDING);
            setCustomDurability(item, (int) Math.min(max, getCustomDurability(item) + gain));
        }
    }

    private void warn(String key, String message) { if (reported.add(key)) plugin.getLogger().warning(message); }
    public void reload() { reported.clear(); }
    private record WeightedEntry(ConfigurationSection section, Material material, int weight) { }
}
