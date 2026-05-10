package com.plotdirt.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class ItemBuilder {

    private ItemBuilder() {}

    public static ItemStack of(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(toComponent(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = of(material, name);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.lore(lore.stream().map(ItemBuilder::toComponent).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack skull(OfflinePlayer player, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            meta.displayName(toComponent(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component toComponent(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }
}
