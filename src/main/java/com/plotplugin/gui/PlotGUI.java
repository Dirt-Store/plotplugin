package com.plotplugin.gui;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.util.ItemBuilder;
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

    public static final String ID_PREFIX = "plot_gui:";

    private final PlotPlugin plugin;

    public PlotGUI(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Plot plot) {
        String title = plugin.getConfigManager().getGuiTitle("plot-gui", "{name}", plot.getName());
        int rows = plugin.getConfigManager().getGuiRows("plot-gui");
        Inventory inv = Bukkit.createInventory(null, rows * 9,
                LegacyComponentSerializer.legacySection().deserialize(ID_PREFIX + plot.getName() + "\0" + title));

        boolean canManage = plot.canManage(viewer.getUniqueId());

        // Row 1: management buttons (slots 0-8)
        if (canManage) {
            // Add Member
            int addMemberSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "add-member-slot");
            Material addMemberMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "add-member");
            String addMemberName = plugin.getConfigManager().getGuiItemName("plot-gui", "add-member");
            List<String> addMemberLore = plugin.getConfigManager().getGuiItemLore("plot-gui", "add-member");
            inv.setItem(addMemberSlot, ItemBuilder.of(addMemberMat, addMemberName, addMemberLore));

            // Remove Member
            int removeMemberSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-member-slot");
            Material removeMemberMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "remove-member");
            String removeMemberName = plugin.getConfigManager().getGuiItemName("plot-gui", "remove-member");
            List<String> removeMemberLore = plugin.getConfigManager().getGuiItemLore("plot-gui", "remove-member");
            inv.setItem(removeMemberSlot, ItemBuilder.of(removeMemberMat, removeMemberName, removeMemberLore));

            // Add Manager (owner only)
            if (plot.isOwner(viewer.getUniqueId())) {
                int addManagerSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "add-manager-slot");
                Material addManagerMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "add-manager");
                String addManagerName = plugin.getConfigManager().getGuiItemName("plot-gui", "add-manager");
                List<String> addManagerLore = plugin.getConfigManager().getGuiItemLore("plot-gui", "add-manager");
                inv.setItem(addManagerSlot, ItemBuilder.of(addManagerMat, addManagerName, addManagerLore));

                int removeManagerSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-manager-slot");
                Material removeManagerMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "remove-manager");
                String removeManagerName = plugin.getConfigManager().getGuiItemName("plot-gui", "remove-manager");
                List<String> removeManagerLore = plugin.getConfigManager().getGuiItemLore("plot-gui", "remove-manager");
                inv.setItem(removeManagerSlot, ItemBuilder.of(removeManagerMat, removeManagerName, removeManagerLore));
            }

            // Buy Time
            int buyTimeSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "buy-time-slot");
            int renewCost = plugin.getConfigManager().getRenewCostPerDay();
            int maxDays = plugin.getConfigManager().getMaxDurationDays();
            Material buyTimeMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "buy-time");
            String buyTimeName = plugin.getConfigManager().getGuiItemName("plot-gui", "buy-time");
            List<String> buyTimeLore = plugin.getConfigManager().getGuiItemLore("plot-gui", "buy-time",
                    "{cost}", String.valueOf(renewCost),
                    "{max}", String.valueOf(maxDays));
            inv.setItem(buyTimeSlot, ItemBuilder.of(buyTimeMat, buyTimeName, buyTimeLore));
        }

        // Row 2: glass pane filler (slots 9-17)
        List<Integer> glassSlots = plugin.getConfigManager().getGuiGlassPaneSlots("plot-gui");
        Material glassMat = plugin.getConfigManager().getGuiItemMaterial("plot-gui", "filler");
        ItemStack filler = ItemBuilder.filler(glassMat);
        for (int slot : glassSlots) {
            inv.setItem(slot, filler);
        }

        // Rows 3-6: player heads (slots 18-53)
        int headSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "heads-start-slot");
        List<UUID> displayed = buildDisplayList(plot);
        for (UUID uuid : displayed) {
            if (headSlot >= rows * 9) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String role = getRole(plot, uuid);
            String playerName = op.getName() != null ? op.getName() : uuid.toString();
            inv.setItem(headSlot++, ItemBuilder.skull(op,
                    LegacyComponentSerializer.legacyAmpersand()
                            .deserialize("&f" + playerName + " &7(" + role + ")").toLegacyText()));
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
        if (plot.isOwner(uuid)) return "Owner";
        if (plot.isManager(uuid)) return "Manager";
        return "Member";
    }
}
