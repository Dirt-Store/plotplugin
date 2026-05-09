package com.plotplugin.manager;

import com.plotplugin.PlotPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;

public class CurrencyManager {

    private final PlotPlugin plugin;

    public CurrencyManager(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack getCurrencyItem() {
        return plugin.getConfigManager().getCurrencyItem();
    }

    public void setCurrencyItem(ItemStack item) {
        plugin.getConfigManager().setCurrencyItem(item.clone());
    }

    public boolean hasCurrencyItem() {
        return getCurrencyItem() != null;
    }

    /**
     * Count how many matching currency items the player has.
     */
    public int countCurrencyItems(Player player) {
        ItemStack currency = getCurrencyItem();
        if (currency == null) return 0;
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && matches(item, currency)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Remove a given amount of currency items from the player's inventory.
     * Returns true if successful.
     */
    public boolean takeCurrencyItems(Player player, int amount) {
        if (countCurrencyItems(player) < amount) return false;
        ItemStack currency = getCurrencyItem();
        if (currency == null) return false;

        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !matches(item, currency)) continue;
            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            if (item.getAmount() - take == 0) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        player.getInventory().setContents(contents);
        return true;
    }

    /**
     * Compare two ItemStacks by full ItemMeta (name, lore, enchants, custom model data, etc.)
     */
    public boolean matches(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;

        ItemMeta ma = a.getItemMeta();
        ItemMeta mb = b.getItemMeta();

        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;

        // Compare display name
        if (!Objects.equals(ma.hasDisplayName() ? ma.displayName() : null,
                            mb.hasDisplayName() ? mb.displayName() : null)) return false;

        // Compare lore
        if (!Objects.equals(ma.hasLore() ? ma.lore() : null,
                            mb.hasLore() ? mb.lore() : null)) return false;

        // Compare enchants
        if (!ma.getEnchants().equals(mb.getEnchants())) return false;

        // Compare custom model data
        if (ma.hasCustomModelData() != mb.hasCustomModelData()) return false;
        if (ma.hasCustomModelData() && ma.getCustomModelData() != mb.getCustomModelData()) return false;

        // Compare item flags
        if (!ma.getItemFlags().equals(mb.getItemFlags())) return false;

        // Serialize remaining meta for deep comparison
        return ma.serialize().equals(mb.serialize());
    }
}
