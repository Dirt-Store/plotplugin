package com.plotplugin.gui;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.util.ItemBuilder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
                LegacyComponentSerializer.legacySection().deserialize(
                        ID_PREFIX + plot.getName() + "\0" + title));

        boolean canManage = plot.canManage(viewer.getUniqueId());
        boolean isOwner   = plot.isOwner(viewer.getUniqueId());

        // ── Row 1: management action buttons (slots 0-8) ────────────────────
        if (canManage) {
            // Add Member
            inv.setItem(
                slot("add-member-slot"),
                ItemBuilder.of(mat("add-member"), name("add-member"), lore("add-member")));

            // Remove Member
            inv.setItem(
                slot("remove-member-slot"),
                ItemBuilder.of(mat("remove-member"), name("remove-member"), lore("remove-member")));

            // Add Manager (owner only)
            if (isOwner) {
                inv.setItem(
                    slot("add-manager-slot"),
                    ItemBuilder.of(mat("add-manager"), name("add-manager"), lore("add-manager")));

                inv.setItem(
                    slot("remove-manager-slot"),
                    ItemBuilder.of(mat("remove-manager"), name("remove-manager"), lore("remove-manager")));

                // Transfer Owner button — visible only to the current owner
                inv.setItem(
                    slot("transfer-owner-slot"),
                    ItemBuilder.of(mat("transfer-owner"), name("transfer-owner"), lore("transfer-owner")));
            }

            // Buy Time
            int renewCost = plugin.getConfigManager().getRenewCostPerDay();
            int maxDays   = plugin.getConfigManager().getMaxDurationDays();
            inv.setItem(
                slot("buy-time-slot"),
                ItemBuilder.of(mat("buy-time"), name("buy-time"),
                    lore("buy-time", "{cost}", String.valueOf(renewCost), "{max}", String.valueOf(maxDays))));
        }

        // ── Row 2: decorative glass pane filler ─────────────────────────────
        ItemStack filler = ItemBuilder.filler(mat("filler"));
        for (int slot : plugin.getConfigManager().getGuiGlassPaneSlots("plot-gui")) {
            inv.setItem(slot, filler);
        }

        // ── Rows 3-6: player heads (deduplicated, ordered: owner → managers → members) ──
        int headSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "heads-start-slot");
        for (UUID uuid : buildDisplayList(plot)) {
            if (headSlot >= rows * 9) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String playerName = op.getName() != null ? op.getName() : uuid.toString();
            String role       = getRole(plot, uuid);
            String headName   = LegacyComponentSerializer.legacySection().serialize(
                    LegacyComponentSerializer.legacyAmpersand()
                            .deserialize("&f" + playerName + " &7(" + role + ")"));
            inv.setItem(headSlot++, ItemBuilder.skull(op, headName));
        }

        viewer.openInventory(inv);
    }

    /**
     * Build the ordered, deduplicated list of UUIDs to show as player heads.
     *
     * Order: owner → managers → members.
     * A player can only hold one role at a time (enforced by the command layer),
     * but we deduplicate here as a safety net so no UUID ever appears twice.
     */
    private List<UUID> buildDisplayList(Plot plot) {
        LinkedHashSet<UUID> seen = new LinkedHashSet<>();
        if (plot.getOwner() != null) seen.add(plot.getOwner());
        seen.addAll(plot.getManagers());
        seen.addAll(plot.getMembers());
        return new ArrayList<>(seen);
    }

    private String getRole(Plot plot, UUID uuid) {
        if (plot.isOwner(uuid))   return "Owner";
        if (plot.isManager(uuid)) return "Manager";
        return "Member";
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private int slot(String key) {
        return plugin.getConfigManager().getGuiSlot("plot-gui", key);
    }

    private Material mat(String item) {
        return plugin.getConfigManager().getGuiItemMaterial("plot-gui", item);
    }

    private String name(String item) {
        return plugin.getConfigManager().getGuiItemName("plot-gui", item);
    }

    private List<String> lore(String item, String... replacements) {
        return replacements.length == 0
                ? plugin.getConfigManager().getGuiItemLore("plot-gui", item)
                : plugin.getConfigManager().getGuiItemLore("plot-gui", item, replacements);
    }
}
