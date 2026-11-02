package org.qiyuan.macesurvival.loot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.qiyuan.macesurvival.MaceSurvival;
import org.qiyuan.macesurvival.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weapon books stay in the inventory until applied to an exact target. Passive
 * books apply on pickup; the pickup transaction, not this manager, removes them.
 * All passive state belongs to one round and is explicitly cleared on reset.
 */
public final class EnchantManager {
    private final MaceSurvival plugin;
    private final NamespacedKey autoApplyKey;
    private final NamespacedKey passiveKey;
    private final NamespacedKey passiveLevelKey;
    private final Map<UUID, PassiveState> states = new HashMap<>();

    public EnchantManager(MaceSurvival plugin) {
        this.plugin = plugin;
        autoApplyKey = new NamespacedKey(plugin, "auto_apply_enchant");
        passiveKey = new NamespacedKey(plugin, "passive_book");
        passiveLevelKey = new NamespacedKey(plugin, "passive_book_level");
    }

    public ItemStack createEnchantBook(Enchantment enchant, int level, boolean autoApply) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchant, Math.max(1, level), true);
        if (autoApply) meta.getPersistentDataContainer().set(autoApplyKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        return book;
    }

    public ItemStack createPassiveBook(String kind, int level) {
        String id = passiveId(kind);
        if (!List.of("damage-boost", "healing-boost", "kill-boost").contains(id)) {
            plugin.getLogger().warning("Unknown passive book: " + kind);
            return null;
        }
        level = Math.max(1, Math.min(passiveCap(), level));
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.getPersistentDataContainer().set(passiveKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(passiveLevelKey, PersistentDataType.INTEGER, level);
        meta.setEnchantmentGlintOverride(true);
        String path = "passive-books." + id;
        var config = plugin.configs().loot();
        String defaultName = switch (id) { case "damage-boost" -> "战斗本能"; case "healing-boost" -> "复苏之风"; default -> "乘胜追击"; };
        meta.displayName(Text.parse(config.getString(path + ".name", defaultName)).decoration(TextDecoration.ITALIC, false));
        double heal = Math.max(0, config.getDouble("passive-books.kill-boost.heal", 4));
        Map<String, String> variables = Map.of("level", String.valueOf(level), "damage_percent", number(fraction("damage-boost") * 100),
            "healing_percent", number(fraction("healing-boost") * 100), "heal", number(heal), "hearts", number(heal / 2), "max_level", String.valueOf(passiveCap()));
        List<Component> lore = new ArrayList<>();
        for (String line : config.getStringList(path + ".lore")) {
            for (var entry : variables.entrySet()) line = line.replace("{" + entry.getKey() + "}", entry.getValue());
            lore.add(Text.parse(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        book.setItemMeta(meta);
        return book;
    }

    public boolean isPassiveBook(ItemStack item) {
        return item != null && item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(passiveKey, PersistentDataType.STRING);
    }

    public boolean isAutoApply(ItemStack item) {
        return isPassiveBook(item) || (item != null && item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(autoApplyKey, PersistentDataType.BYTE));
    }

    /** Legacy entrypoint consumes one book only after a real state change. */
    public void autoApplyBook(Player player, ItemStack book) {
        if (isAutoApply(book) && applyFromBook(player, book)) book.setAmount(book.getAmount() - 1);
    }

    public boolean applyFromBook(Player player, ItemStack book) {
        if (!participating(player)) return false;
        if (isPassiveBook(book)) return applyPassive(player, book);
        if (!isAutoApply(book)) return false;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (applyToItem(book, held)) return true;
        for (ItemStack target : player.getInventory().getStorageContents()) if (applyToItem(book, target)) return true;
        return false;
    }

    public void applyBookToItem(ItemStack book, ItemStack target) { applyToItem(book, target); }

    public boolean applyByHand(Player player, ItemStack book, ItemStack target) {
        if (!participating(player) || !plugin.configs().main().getBoolean("loot.books-apply-on-right-click", true)) return false;
        if (isPassiveBook(book)) return applyPassive(player, book);
        boolean applied = applyToItem(book, target);
        if (applied) player.sendMessage(plugin.messages().prefixed("loot.enchant-applied"));
        return applied;
    }

    public boolean applyByHand(Player player, ItemStack book) {
        return applyByHand(player, book, player.getInventory().getItemInMainHand());
    }

    /** Return true only when the target actually changes; equal books stay intact. */
    public boolean applyToItem(ItemStack book, ItemStack target) {
        if (target == null || target.getType().isAir() || isPassiveBook(book)) return false;
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : storedOf(book).entrySet()) {
            changed |= applyEnchantment(target, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    public boolean applyEnchantment(ItemStack target, Enchantment enchant, int level) {
        if (target == null || enchant == null || !compatible(target, enchant)) return false;
        int cap = plugin.configs().main().getBoolean("loot.respect-max-level", false)
            ? enchant.getMaxLevel() : Math.max(1, plugin.configs().main().getInt("loot.absolute-max-level", 10));
        int current = target.getEnchantmentLevel(enchant);
        int next = Math.min(cap, Math.max(1, level));
        if (next <= current) return false;
        ItemMeta meta = target.getItemMeta();
        meta.addEnchant(enchant, next, true);
        target.setItemMeta(meta);
        return true;
    }

    private boolean compatible(ItemStack item, Enchantment enchant) {
        Material material = item.getType();
        if (enchant.equals(Enchantment.WIND_BURST) || enchant.equals(Enchantment.DENSITY) || enchant.equals(Enchantment.BREACH)) return material == Material.MACE;
        if (enchant.equals(Enchantment.LUNGE)) return material.name().endsWith("_SPEAR");
        if (enchant.equals(Enchantment.SHARPNESS)) return material == Material.MACE || material.name().endsWith("_SPEAR")
            || material.name().endsWith("_SWORD") || material.name().endsWith("_AXE");
        if (enchant.equals(Enchantment.UNBREAKING) || enchant.equals(Enchantment.MENDING)) return material.getMaxDurability() > 0;
        return enchant.canEnchantItem(item);
    }

    public Map<Enchantment, Integer> storedOf(ItemStack book) {
        return book != null && book.getType() == Material.ENCHANTED_BOOK && book.getItemMeta() instanceof EnchantmentStorageMeta meta
            ? meta.getStoredEnchants() : Map.of();
    }

    /** Accept minecraft:wind_burst:2, WIND_BURST:2 and the previous key+level form. */
    public EnchantSpec parseSpec(String raw, int defaultLevel) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        int level = Math.max(1, defaultLevel);
        int separator = key.lastIndexOf(':');
        if (separator >= 0 && key.substring(separator + 1).matches("[0-9]+")) {
            try { level = Math.max(1, Math.min(1000, Integer.parseInt(key.substring(separator + 1)))); }
            catch (NumberFormatException exception) { return null; }
            key = key.substring(0, separator);
        }
        key = switch (key) { case "storm", "windstorm", "wind_burst" -> "wind_burst"; case "durability" -> "unbreaking"; default -> key; };
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        if (namespaced == null) return null;
        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(namespaced);
        return enchantment == null ? null : new EnchantSpec(enchantment, level);
    }

    /** Compatibility preview method; fillChest already rolls books in its table. */
    public ItemStack rollEnchantBook(int stars) {
        List<ConfigurationSection> books = plugin.loot().table(stars).stream()
            .filter(entry -> entry.contains("book") || entry.contains("passive") || "ENCHANTED_BOOK".equalsIgnoreCase(entry.getString("material")))
            .filter(entry -> entry.getInt("weight", 1) > 0).toList();
        long total = books.stream().mapToLong(entry -> entry.getInt("weight", 1)).sum();
        if (total == 0) return null;
        long cursor = ThreadLocalRandom.current().nextLong(total);
        for (ConfigurationSection entry : books) {
            cursor -= entry.getInt("weight", 1);
            if (cursor >= 0) continue;
            if (entry.contains("passive")) return createPassiveBook(entry.getString("passive"), entry.getInt("level", 1));
            EnchantSpec spec = parseSpec(entry.getString("book", entry.getString("enchant", "")), entry.getInt("level", 1));
            return spec == null ? null : createEnchantBook(spec.enchantment(), spec.level(), entry.getBoolean("auto-apply", false));
        }
        return null;
    }

    private boolean applyPassive(Player player, ItemStack book) {
        var pdc = book.getItemMeta().getPersistentDataContainer();
        String id = passiveId(pdc.getOrDefault(passiveKey, PersistentDataType.STRING, ""));
        int level = Math.max(1, Math.min(passiveCap(), pdc.getOrDefault(passiveLevelKey, PersistentDataType.INTEGER, 1)));
        PassiveState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new PassiveState());
        int before;
        int after;
        switch (id) {
            case "damage-boost" -> { before = state.damage; state.damage = Math.min(passiveCap(), state.damage + level); after = state.damage; }
            case "healing-boost" -> { before = state.healing; state.healing = Math.min(passiveCap(), state.healing + level); after = state.healing; }
            case "kill-boost" -> { before = state.charges; state.charges = Math.min(passiveCap(), state.charges + level); after = state.charges; }
            default -> { return false; }
        }
        if (after == before) return false;
        String name = book.getItemMeta().hasDisplayName()
            ? PlainTextComponentSerializer.plainText().serialize(book.getItemMeta().displayName()) : id;
        player.sendMessage(plugin.messages().prefixed("enchant.passive-applied", Map.of("book", name, "level", String.valueOf(after))));
        return true;
    }

    public double damageMultiplier(Player player) {
        PassiveState state = states.get(player.getUniqueId());
        return state == null ? 1 : Math.max(1, Math.min(5, 1 + state.damage * fraction("damage-boost")));
    }

    public double healingMultiplier(Player player) {
        PassiveState state = states.get(player.getUniqueId());
        return state == null ? 1 : Math.max(1, Math.min(5, 1 + state.healing * fraction("healing-boost")));
    }

    public void onKill(Player player) {
        if (!participating(player)) return;
        PassiveState state = states.get(player.getUniqueId());
        if (state == null || state.charges <= 0) return;
        state.charges--;
        state.damage = Math.min(passiveCap(), state.damage + 1);
        state.healing = Math.min(passiveCap(), state.healing + 1);
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20 : maxHealth.getValue();
        double heal = Math.max(0, plugin.configs().loot().getDouble("passive-books.kill-boost.heal", 4)) * healingMultiplier(player);
        if (!player.isDead()) player.setHealth(Math.min(max, player.getHealth() + heal));
        player.sendMessage(plugin.messages().prefixed("enchant.kill-boost", Map.of("charges", String.valueOf(state.charges))));
    }

    public void clear(Player player) { states.remove(player.getUniqueId()); }
    public void clearRound() { states.clear(); }
    public void reload() {
        int cap = passiveCap();
        for (PassiveState state : states.values()) {
            state.damage = Math.min(cap, state.damage);
            state.healing = Math.min(cap, state.healing);
            state.charges = Math.min(cap, state.charges);
        }
    }
    private int passiveCap() { return Math.max(1, Math.min(100, plugin.configs().loot().getInt("passive-books.max-level", 10))); }
    private double fraction(String kind) { return Math.max(0, Math.min(1, plugin.configs().loot().getDouble("passive-books." + kind + ".per-level", 0.1))); }
    private boolean participating(Player player) { return player != null && player.isOnline() && !player.isDead() && plugin.game().active() && plugin.game().participant(player)
        && player.getGameMode() == GameMode.SURVIVAL && player.getWorld() == plugin.arenas().getArenaWorld() && !plugin.drops().isDropping(player); }
    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private static String passiveId(String raw) { return raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('_', '-'); }
    public record EnchantSpec(Enchantment enchantment, int level) { }
    private static final class PassiveState { private int damage; private int healing; private int charges; }
}
