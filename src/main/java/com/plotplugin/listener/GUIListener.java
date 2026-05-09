package com.plotplugin.listener;

import com.plotplugin.PlotPlugin;
import com.plotplugin.gui.BuyGUI;
import com.plotplugin.gui.PlotGUI;
import com.plotplugin.model.Plot;
import com.plotplugin.util.TimeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GUIListener implements Listener {

    private final PlotPlugin plugin;

    // Players awaiting chat input for GUI actions: player -> [action, plotName]
    private final Map<UUID, String[]> pendingChatInput = new HashMap<>();

    public GUIListener(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Read title from the open view - NOT from the inventory object
        String rawTitle = LegacyComponentSerializer.legacySection()
                .serialize(event.getView().title());

        // Only intercept our own GUIs — all other inventories are completely untouched
        if (rawTitle.startsWith(PlotGUI.ID_PREFIX)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            handlePlotGUI(event, player, rawTitle);
        } else if (rawTitle.startsWith(BuyGUI.ID_PREFIX)) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null) return;
            handleBuyGUI(event, player, rawTitle);
        }
        // Anything else: vanilla handles it normally (player inventory, chests, etc.)
    }

    private void handlePlotGUI(InventoryClickEvent event, Player player, String rawTitle) {
        String plotName = extractPlotName(rawTitle, PlotGUI.ID_PREFIX);
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) return;

        int slot = event.getRawSlot();
        if (!plot.canManage(player.getUniqueId())) return;

        playClick(player);

        int addMemberSlot     = plugin.getConfigManager().getGuiSlot("plot-gui", "add-member-slot");
        int removeMemberSlot  = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-member-slot");
        int addManagerSlot    = plugin.getConfigManager().getGuiSlot("plot-gui", "add-manager-slot");
        int removeManagerSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-manager-slot");
        int buyTimeSlot       = plugin.getConfigManager().getGuiSlot("plot-gui", "buy-time-slot");

        if (slot == addMemberSlot) {
            player.closeInventory();
            dispatchCommand(player, "plot addmember " + plotName);
        } else if (slot == removeMemberSlot) {
            player.closeInventory();
            dispatchCommand(player, "plot removemember " + plotName);
        } else if (slot == addManagerSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            dispatchCommand(player, "plot addmanager " + plotName);
        } else if (slot == removeManagerSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            dispatchCommand(player, "plot removemanager " + plotName);
        } else if (slot == buyTimeSlot) {
            player.closeInventory();
            handleBuyTime(player, plot);
        }
    }

    private void handleBuyGUI(InventoryClickEvent event, Player player, String rawTitle) {
        String plotName = extractPlotName(rawTitle, BuyGUI.ID_PREFIX);
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) return;

        int emeraldSlot = plugin.getConfigManager().getGuiSlot("buy-gui", "emerald-slot");
        if (event.getRawSlot() != emeraldSlot) return;

        player.closeInventory();
        playClick(player);
        dispatchCommand(player, "plot buy " + plotName + " __confirm__");
    }

    private void handleBuyTime(Player player, Plot plot) {
        int renewCost = plugin.getConfigManager().getRenewCostPerDay();
        int maxDays   = plugin.getConfigManager().getMaxDurationDays();
        long maxMs    = TimeUtil.daysToMs(maxDays);

        if (plot.getRemainingMs() >= maxMs) {
            player.sendMessage(plugin.getConfigManager().getMessage("max-duration",
                    "{max}", String.valueOf(maxDays)));
            return;
        }
        if (!plugin.getCurrencyManager().hasCurrencyItem()) {
            player.sendMessage(plugin.getConfigManager().getMessage("coin-not-set"));
            return;
        }
        if (plugin.getCurrencyManager().countCurrencyItems(player) < renewCost) {
            player.sendMessage(plugin.getConfigManager().getMessage("not-enough-currency",
                    "{cost}", String.valueOf(renewCost)));
            return;
        }

        plugin.getCurrencyManager().takeCurrencyItems(player, renewCost);

        long addMs = TimeUtil.daysToMs(1);
        if (plot.isPaused()) {
            plot.setPausedRemainingMs(Math.min(plot.getPausedRemainingMs() + addMs, maxMs));
        } else {
            long newExpiry = Math.min(plot.getExpiryTime() + addMs,
                    System.currentTimeMillis() + maxMs);
            plot.setExpiryTime(newExpiry);
        }

        plugin.getPlotManager().savePlot(plot);
        plugin.getSignManager().updateSign(plot);

        player.sendMessage(plugin.getConfigManager().getMessage("renew-success",
                "{name}", plot.getName(), "{days}", "1", "{cost}", String.valueOf(renewCost)));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("plot-bought"), 1f, 1f);
    }

    private void dispatchCommand(Player player, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
    }

    private String extractPlotName(String rawTitle, String prefix) {
        String after = rawTitle.substring(prefix.length());
        int nullIdx = after.indexOf('\0');
        return nullIdx >= 0 ? after.substring(0, nullIdx) : after;
    }

    private void playClick(Player player) {
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("gui-click"), 1f, 1f);
    }

    public Map<UUID, String[]> getPendingChatInput() {
        return pendingChatInput;
    }
}
