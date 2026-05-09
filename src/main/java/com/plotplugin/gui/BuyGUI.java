package com.plotplugin.gui;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.util.ItemBuilder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class BuyGUI {

    public static final String ID_PREFIX = "buy_gui:";

    private final PlotPlugin plugin;

    public BuyGUI(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Plot plot) {
        String title = plugin.getConfigManager().getGuiTitle("buy-gui", "{name}", plot.getName());
        int rows = plugin.getConfigManager().getGuiRows("buy-gui");
        Inventory inv = Bukkit.createInventory(null, rows * 9,
                LegacyComponentSerializer.legacySection().deserialize(ID_PREFIX + plot.getName() + "\0" + title));

        // Fill with glass panes
        Material filler = plugin.getConfigManager().getGuiItemMaterial("buy-gui", "filler");
        ItemStack glass = ItemBuilder.filler(filler);
        for (int i = 0; i < rows * 9; i++) {
            inv.setItem(i, glass);
        }

        // Emerald in center
        int emeraldSlot = plugin.getConfigManager().getGuiSlot("buy-gui", "emerald-slot");
        int cost = plugin.getConfigManager().getBuyCost();
        int days = plugin.getConfigManager().getBuyDays();

        Material emeraldMat = plugin.getConfigManager().getGuiItemMaterial("buy-gui", "emerald");
        String emeraldName = plugin.getConfigManager().getGuiItemName("buy-gui", "emerald");
        List<String> emeraldLore = plugin.getConfigManager().getGuiItemLore("buy-gui", "emerald",
                "{cost}", String.valueOf(cost),
                "{days}", String.valueOf(days));
        inv.setItem(emeraldSlot, ItemBuilder.of(emeraldMat, emeraldName, emeraldLore));

        viewer.openInventory(inv);
    }
}
