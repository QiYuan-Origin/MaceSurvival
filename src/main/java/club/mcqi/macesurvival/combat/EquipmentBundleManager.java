package club.mcqi.macesurvival.combat;

import club.mcqi.macesurvival.MaceSurvivalPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

final class EquipmentBundleManager {
    static final int CAPACITY = EquipmentBundleRules.CAPACITY;

    private final JavaPlugin plugin;
    private final NamespacedKey contentsKey;

    EquipmentBundleManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        contentsKey = new NamespacedKey(plugin, "equipment_bundle_contents");
    }

    boolean isBundle(ItemStack item) {
        return item != null && item.getType() == Material.BUNDLE
            && item.getItemMeta().getPersistentDataContainer().has(contentsKey, PersistentDataType.BYTE_ARRAY);
    }

    boolean isPackable(ItemStack item) {
        return item != null && EquipmentBundleRules.isPackable(item.getType().name(), item.getAmount());
    }

    boolean storeDuplicatePickup(Player player, ItemStack pickedUp) {
        if (!isPackable(pickedUp) || !hasUsableCategory(player.getInventory(), category(pickedUp))) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getStorageContents()) {
            if (isBundle(item) && add(item, pickedUp)) {
                notifyStored(player);
                return true;
            }
        }
        int emptySlot = inventory.firstEmpty();
        if (emptySlot < 0) {
            return false;
        }
        ItemStack bundle = createBundle();
        if (!add(bundle, pickedUp)) {
            return false;
        }
        inventory.setItem(emptySlot, bundle);
        notifyStored(player);
        return true;
    }

    boolean add(ItemStack bundle, ItemStack item) {
        if (!isBundle(bundle) || !isPackable(item)) {
            return false;
        }
        List<ItemStack> contents = contents(bundle);
        if (!EquipmentBundleRules.canAdd(contents.size(), item.getType().name(), item.getAmount())) {
            return false;
        }
        ItemStack stored = item.clone();
        stored.setAmount(1);
        contents.add(stored);
        writeContents(bundle, contents);
        return true;
    }

    ItemStack eject(Player player, ItemStack bundle) {
        if (!isBundle(bundle)) {
            return null;
        }
        List<ItemStack> contents = contents(bundle);
        if (contents.isEmpty()) {
            return null;
        }
        ItemStack ejected = contents.removeLast();
        writeContents(bundle, contents);
        Vector direction = player.getEyeLocation().getDirection().normalize().multiply(0.42).setY(0.18);
        Item dropped = player.getWorld().dropItem(player.getEyeLocation().subtract(0.0, 0.25, 0.0), ejected,
            entity -> entity.setVelocity(direction));
        dropped.setPickupDelay(12);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE,
            SoundCategory.PLAYERS, 0.9F, 1.0F);
        return ejected;
    }

    int size(ItemStack bundle) {
        return isBundle(bundle) ? contents(bundle).size() : 0;
    }

    private ItemStack createBundle() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        bundle.editMeta(meta -> meta.getPersistentDataContainer().set(
            contentsKey, PersistentDataType.BYTE_ARRAY, encode(List.of())));
        updatePresentation(bundle, List.of());
        return bundle;
    }

    private boolean hasUsableCategory(PlayerInventory inventory, String wantedCategory) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (!isBundle(item) && isPackable(item) && category(item).equals(wantedCategory)) {
                return true;
            }
        }
        ItemStack chest = inventory.getChestplate();
        return chest != null && !chest.getType().isAir() && category(chest).equals(wantedCategory);
    }

    private String category(ItemStack item) {
        return EquipmentBundleRules.category(item.getType().name());
    }

    private List<ItemStack> contents(ItemStack bundle) {
        byte[] encoded = bundle.getItemMeta().getPersistentDataContainer()
            .get(contentsKey, PersistentDataType.BYTE_ARRAY);
        return encoded == null ? new ArrayList<>() : decode(encoded);
    }

    private void writeContents(ItemStack bundle, List<ItemStack> contents) {
        List<ItemStack> stable = List.copyOf(contents);
        bundle.editMeta(meta -> meta.getPersistentDataContainer().set(
            contentsKey, PersistentDataType.BYTE_ARRAY, encode(stable)));
        updatePresentation(bundle, stable);
    }

    private void updatePresentation(ItemStack bundle, List<ItemStack> contents) {
        bundle.editMeta(meta -> {
            Component name = message(
                "combat.bundle-name",
                "Reserve Bundle",
                Map.of("count", contents.size(), "capacity", CAPACITY),
                NamedTextColor.AQUA
            );
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(message(
                "combat.bundle-capacity",
                contents.size() + "/" + CAPACITY + " equipment",
                Map.of("count", contents.size(), "capacity", CAPACITY),
                NamedTextColor.GRAY
            ));
            for (ItemStack stored : contents) {
                lore.add(Component.text("- ", NamedTextColor.DARK_GRAY)
                    .append(Component.translatable(stored.translationKey(), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(message(
                "combat.bundle-eject",
                "Right click to throw out the last item.",
                Map.of("count", contents.size(), "capacity", CAPACITY),
                NamedTextColor.DARK_GRAY
            ));
            meta.lore(List.copyOf(lore));
        });
    }

    private void notifyStored(Player player) {
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT,
            SoundCategory.PLAYERS, 0.8F, 1.0F);
        if (plugin instanceof MaceSurvivalPlugin maceSurvival) {
            player.sendActionBar(maceSurvival.text().message(player, "combat.bundle-stored", Map.of()));
        }
    }

    private Component message(
            String path,
            String fallback,
            Map<String, ?> placeholders,
            NamedTextColor fallbackColor
    ) {
        if (plugin instanceof MaceSurvivalPlugin maceSurvival) {
            return maceSurvival.text().messageOr(null, path, fallback, placeholders);
        }
        return Component.text(fallback, fallbackColor);
    }

    private byte[] encode(List<ItemStack> contents) {
        List<byte[]> serialized = contents.stream().map(ItemStack::serializeAsBytes).toList();
        return EquipmentBundlePayloadCodec.encode(serialized);
    }

    private List<ItemStack> decode(byte[] encoded) {
        List<ItemStack> contents = new ArrayList<>();
        try {
            for (byte[] serialized : EquipmentBundlePayloadCodec.decode(encoded)) {
                contents.add(ItemStack.deserializeBytes(serialized));
            }
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not read an equipment bundle; preserving the item", exception);
            return new ArrayList<>();
        }
        return contents;
    }
}
