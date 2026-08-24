package org.qiyuan.macesurvival.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.persistence.PersistentDataType;
import org.qiyuan.macesurvival.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 构建物品。所有名字和 lore 走 Text，因此 & / § / MiniMessage / JSON 都能用，且默认不斜体。 */
public final class ItemBuilder {

    private final ItemStack stack;
    private final ItemMeta meta;

    private ItemBuilder(Material material, int amount) {
        this.stack = new ItemStack(material, Math.max(1, amount));
        this.meta = stack.getItemMeta();
    }

    private ItemBuilder(ItemStack existing) {
        this.stack = existing.clone();
        this.meta = stack.getItemMeta();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material, 1);
    }

    public static ItemBuilder of(Material material, int amount) {
        return new ItemBuilder(material, amount);
    }

    public static ItemBuilder copyOf(ItemStack existing) {
        return new ItemBuilder(existing);
    }

    /** 解析配置里的 material 串，支持 head-<player> 和 basehead-<base64>。 */
    public static ItemBuilder fromMaterialString(String raw, String viewerName) {
        if (raw == null || raw.isBlank()) {
            return of(Material.STONE);
        }
        String value = raw.trim();
        String lower = value.toLowerCase();
        if (lower.startsWith("head-")) {
            String owner = value.substring(5).replace("%player_name%", viewerName == null ? "" : viewerName);
            return of(Material.PLAYER_HEAD).skullOwner(owner);
        }
        if (lower.startsWith("basehead-")) {
            return of(Material.PLAYER_HEAD).skullTexture(value.substring(9));
        }
        Material material = Material.matchMaterial(value.toUpperCase());
        return of(material == null ? Material.STONE : material);
    }

    public ItemBuilder name(String raw) {
        if (meta != null) {
            meta.displayName(Text.parse(raw).decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public ItemBuilder name(String raw, Map<String, String> placeholders) {
        return name(Text.applyPlaceholders(raw, placeholders));
    }

    public ItemBuilder nameComponent(Component component) {
        if (meta != null) {
            meta.displayName(component.decoration(TextDecoration.ITALIC, false));
        }
        return this;
    }

    public ItemBuilder lore(List<String> raw) {
        return loreWith(raw, Map.of());
    }

    public ItemBuilder loreWith(List<String> raw, Map<String, String> placeholders) {
        if (meta == null || raw == null) {
            return this;
        }
        List<Component> lines = new ArrayList<>();
        for (String line : raw) {
            lines.add(Text.parse(Text.applyPlaceholders(line, placeholders))
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        return this;
    }

    public ItemBuilder loreComponents(List<Component> lines) {
        if (meta != null && lines != null) {
            List<Component> fixed = new ArrayList<>();
            for (Component line : lines) {
                fixed.add(line.decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(fixed);
        }
        return this;
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        if (meta != null && enchantment != null && level > 0) {
            meta.addEnchant(enchantment, level, true);
        }
        return this;
    }

    /** 假附魔光效，用于菜单里表示"已选中"。 */
    public ItemBuilder glow() {
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
        }
        return this;
    }

    public ItemBuilder unbreakable() {
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        return this;
    }

    public ItemBuilder hideAttributes() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        return this;
    }

    public ItemBuilder hideEnchants() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }

    /** 剩余可用次数，用满值和当前值算出耐久条位置。 */
    public ItemBuilder durabilityBar(int remaining, int max) {
        if (meta instanceof Damageable damageable && max > 0) {
            int maxDurability = stack.getType().getMaxDurability();
            if (maxDurability > 0) {
                int clamped = Math.max(0, Math.min(remaining, max));
                double ratio = (double) clamped / max;
                int damage = (int) Math.round(maxDurability * (1.0 - ratio));
                damageable.setDamage(Math.max(0, Math.min(damage, maxDurability - 1)));
            }
        }
        return this;
    }

    /** item_model 组件，材质包接进来之后靠这个换贴图。 */
    public ItemBuilder itemModel(String namespacedKey) {
        if (meta != null && namespacedKey != null && !namespacedKey.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(namespacedKey.toLowerCase());
            if (key != null) {
                meta.setItemModel(key);
            }
        }
        return this;
    }

    public ItemBuilder pdc(NamespacedKey key, String value) {
        if (meta != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }
        return this;
    }

    public ItemBuilder pdc(NamespacedKey key, int value) {
        if (meta != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        }
        return this;
    }

    public ItemBuilder skullOwner(String name) {
        if (meta instanceof SkullMeta skull && name != null && !name.isBlank()) {
            skull.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(name));
        }
        return this;
    }

    public ItemBuilder skullTexture(String base64) {
        if (meta instanceof SkullMeta skull) {
            PlayerProfile profile = org.bukkit.Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(base64.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
            profile.setProperty(new ProfileProperty("textures", base64));
            skull.setPlayerProfile(profile);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
