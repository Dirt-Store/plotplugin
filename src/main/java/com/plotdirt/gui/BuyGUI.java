package com.plotdirt.gui;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class BuyGUI {

    private final PlotDirt plugin;

    public BuyGUI(PlotDirt plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Plot plot) {
        // Clean title — no prefix garbage
        String titleStr = plugin.getConfigManager().getGuiTitle("buy-gui", "{name}", plot.getName());
        Component title = LegacyComponentSerializer.legacySection().deserialize(titleStr);
        int rows = plugin.getConfigManager().getGuiRows("buy-gui");

        PlotGUIHolder holder = new PlotGUIHolder(plot.getName(), PlotGUIHolder.Type.BUY_GUI);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inv);

        // FIX-F: fill the backing array then set all at once with setContents().
        // The old per-slot loop called setItem() up to 54 times; each setItem
        // marks that slot dirty and Paper/Spigot sends incremental update packets.
        // setContents() marks everything dirty in one pass and sends a single
        // window contents packet — much cheaper for the network and the main thread.
        Material filler = plugin.getConfigManager().getGuiItemMaterial("buy-gui", "filler");
        ItemStack glass = ItemBuilder.filler(filler);
        ItemStack[] contents = new ItemStack[rows * 9];
        java.util.Arrays.fill(contents, glass);
        inv.setContents(contents);

        // Emerald in center
        int emeraldSlot = plugin.getConfigManager().getGuiSlot("buy-gui", "emerald-slot");
        int cost = plugin.getConfigManager().getBuyCost();
        int days = plugin.getConfigManager().getBuyDays();

        Material emeraldMat = plugin.getConfigManager().getGuiItemMaterial("buy-gui", "emerald");
        String emeraldName  = plugin.getConfigManager().getGuiItemName("buy-gui", "emerald");
        List<String> emeraldLore = plugin.getConfigManager().getGuiItemLore("buy-gui", "emerald",
                "{cost}", String.valueOf(cost),
                "{days}", String.valueOf(days));
        inv.setItem(emeraldSlot, ItemBuilder.of(emeraldMat, emeraldName, emeraldLore));

        viewer.openInventory(inv);
    }
}
