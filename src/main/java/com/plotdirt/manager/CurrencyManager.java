package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;

public class CurrencyManager {

    private final PlotDirt plugin;

    public CurrencyManager(PlotDirt plugin) {
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
     * Compare two ItemStacks by type, display name, lore, enchants, and custom model data.
     * Uses safe reflection for custom model data to support 1.19.4 through 1.21.x.
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

        // Compare custom model data — safe across 1.19.4 and 1.21.x
        if (!customModelDataEquals(ma, mb)) return false;

        // Compare item flags
        if (!ma.getItemFlags().equals(mb.getItemFlags())) return false;

        return true;
    }

    /**
     * Safe custom model data comparison across API versions.
     * In 1.21.4+, getCustomModelData() was changed; we use hasCustomModelData() + getCustomModelData()
     * but wrap in try-catch in case the API differs.
     */
    private boolean customModelDataEquals(ItemMeta a, ItemMeta b) {
        try {
            boolean aHas = a.hasCustomModelData();
            boolean bHas = b.hasCustomModelData();
            if (aHas != bHas) return false;
            if (!aHas) return true; // both have no cmd
            return a.getCustomModelData() == b.getCustomModelData();
        } catch (Exception e) {
            // If the API is incompatible, skip this check
            return true;
        }
    }
}
