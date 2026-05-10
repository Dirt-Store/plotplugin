package com.plotdirt.gui;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlotGUI {

    private final PlotDirt plugin;

    public PlotGUI(PlotDirt plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Plot plot) {
        // Title: clean, only what's in config — no hidden prefix garbage
        String titleStr = plugin.getConfigManager().getGuiTitle("plot-gui", "{name}", plot.getName());
        Component title = LegacyComponentSerializer.legacySection().deserialize(titleStr);

        int rows = plugin.getConfigManager().getGuiRows("plot-gui");

        // Holder carries plot identity — not encoded in the title string
        PlotGUIHolder holder = new PlotGUIHolder(plot.getName(), PlotGUIHolder.Type.PLOT_GUI);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inv);

        boolean isOwner   = plot.isOwner(viewer.getUniqueId());
        boolean canManage = plot.canManage(viewer.getUniqueId());

        if (canManage) {
            // Add Member
            int slot = plugin.getConfigManager().getGuiSlot("plot-gui", "add-member-slot");
            inv.setItem(slot, ItemBuilder.of(
                    plugin.getConfigManager().getGuiItemMaterial("plot-gui", "add-member"),
                    plugin.getConfigManager().getGuiItemName("plot-gui", "add-member"),
                    plugin.getConfigManager().getGuiItemLore("plot-gui", "add-member")));

            // Remove Member
            slot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-member-slot");
            inv.setItem(slot, ItemBuilder.of(
                    plugin.getConfigManager().getGuiItemMaterial("plot-gui", "remove-member"),
                    plugin.getConfigManager().getGuiItemName("plot-gui", "remove-member"),
                    plugin.getConfigManager().getGuiItemLore("plot-gui", "remove-member")));

            if (isOwner) {
                // Add Manager
                slot = plugin.getConfigManager().getGuiSlot("plot-gui", "add-manager-slot");
                inv.setItem(slot, ItemBuilder.of(
                        plugin.getConfigManager().getGuiItemMaterial("plot-gui", "add-manager"),
                        plugin.getConfigManager().getGuiItemName("plot-gui", "add-manager"),
                        plugin.getConfigManager().getGuiItemLore("plot-gui", "add-manager")));

                // Remove Manager
                slot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-manager-slot");
                inv.setItem(slot, ItemBuilder.of(
                        plugin.getConfigManager().getGuiItemMaterial("plot-gui", "remove-manager"),
                        plugin.getConfigManager().getGuiItemName("plot-gui", "remove-manager"),
                        plugin.getConfigManager().getGuiItemLore("plot-gui", "remove-manager")));

                // Transfer Ownership
                slot = plugin.getConfigManager().getGuiSlot("plot-gui", "transfer-slot");
                inv.setItem(slot, ItemBuilder.of(
                        plugin.getConfigManager().getGuiItemMaterial("plot-gui", "transfer"),
                        plugin.getConfigManager().getGuiItemName("plot-gui", "transfer"),
                        plugin.getConfigManager().getGuiItemLore("plot-gui", "transfer")));
            }

            // Buy Time
            int renewCost = plugin.getConfigManager().getRenewCostPerDay();
            int maxDays   = plugin.getConfigManager().getMaxDurationDays();
            slot = plugin.getConfigManager().getGuiSlot("plot-gui", "buy-time-slot");
            inv.setItem(slot, ItemBuilder.of(
                    plugin.getConfigManager().getGuiItemMaterial("plot-gui", "buy-time"),
                    plugin.getConfigManager().getGuiItemName("plot-gui", "buy-time"),
                    plugin.getConfigManager().getGuiItemLore("plot-gui", "buy-time",
                            "{cost}", String.valueOf(renewCost),
                            "{max}", String.valueOf(maxDays))));
        }

        // Glass pane filler — FIX-B: cached slot list, no YAML read
        Material glassMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "filler");
        ItemStack filler = ItemBuilder.filler(glassMat);
        for (int s : plugin.getConfigManager().getCachedPlotGuiGlassPaneSlots()) {
            inv.setItem(s, filler);
        }

        // Player heads — FIX-B: cached heads-start-slot
        int headSlot = plugin.getConfigManager().getCachedPlotGuiHeadsStartSlot();
        com.plotdirt.manager.SignManager signMgr = plugin.getSignManager();
        for (UUID uuid : buildDisplayList(plot)) {
            if (headSlot >= rows * 9) break;
            // BUG FIX #3: name from cache (no filesystem I/O) — kick off async cache
            // warm for any UUID that isn't cached yet so next open will be populated.
            signMgr.cacheOwnerNameAsync(uuid);
            String playerName = signMgr.getCachedName(uuid);
            String role       = getRole(plot, uuid);
            // skull() still needs an OfflinePlayer for the skin texture; that call is
            // acceptable because Paper caches skin data in memory for known players.
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String headName   = LegacyComponentSerializer.legacySection().serialize(
                    LegacyComponentSerializer.legacyAmpersand()
                            .deserialize("&f" + playerName + " &7(" + role + ")"));
            inv.setItem(headSlot++, ItemBuilder.skull(op, headName));
        }

        viewer.openInventory(inv);
    }

    private List<UUID> buildDisplayList(Plot plot) {
        List<UUID> list = new ArrayList<>();
        if (plot.getOwner() != null) list.add(plot.getOwner());
        list.addAll(plot.getManagers());
        list.addAll(plot.getMembers());
        return list;
    }

    private String getRole(Plot plot, UUID uuid) {
        if (plot.isOwner(uuid))   return "Owner";
        if (plot.isManager(uuid)) return "Manager";
        return "Member";
    }
}
