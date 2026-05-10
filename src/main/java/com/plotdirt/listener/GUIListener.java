package com.plotdirt.listener;

import com.plotdirt.PlotDirt;
import com.plotdirt.gui.PlotGUIHolder;
import com.plotdirt.model.Plot;
import com.plotdirt.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GUIListener implements Listener {

    private final PlotDirt plugin;

    // Players awaiting chat input for GUI actions: player -> [action, plotName]
    private final Map<UUID, String[]> pendingChatInput = new HashMap<>();

    public GUIListener(PlotDirt plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Identify our GUI via the InventoryHolder — never parse the title string
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof PlotGUIHolder gui)) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null) return;

        Plot plot = plugin.getPlotManager().getPlot(gui.getPlotName());
        if (plot == null) return;

        playClick(player);

        if (gui.getType() == PlotGUIHolder.Type.PLOT_GUI) {
            handlePlotGUI(event, player, plot);
        } else if (gui.getType() == PlotGUIHolder.Type.BUY_GUI) {
            handleBuyGUI(event, player, plot);
        }
    }

    private void handlePlotGUI(InventoryClickEvent event, Player player, Plot plot) {
        if (!plot.canManage(player.getUniqueId())) return;

        int slot = event.getRawSlot();

        int addMemberSlot     = plugin.getConfigManager().getGuiSlot("plot-gui", "add-member-slot");
        int removeMemberSlot  = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-member-slot");
        int addManagerSlot    = plugin.getConfigManager().getGuiSlot("plot-gui", "add-manager-slot");
        int removeManagerSlot = plugin.getConfigManager().getGuiSlot("plot-gui", "remove-manager-slot");
        int buyTimeSlot       = plugin.getConfigManager().getGuiSlot("plot-gui", "buy-time-slot");
        int transferSlot      = plugin.getConfigManager().getGuiSlot("plot-gui", "transfer-slot");

        String plotName = plot.getName();

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
        } else if (slot == transferSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"transfer", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-transfer-name"));
        } else if (slot == buyTimeSlot) {
            player.closeInventory();
            handleBuyTime(player, plot);
        }
    }

    private void handleBuyGUI(InventoryClickEvent event, Player player, Plot plot) {
        int emeraldSlot = plugin.getConfigManager().getGuiSlot("buy-gui", "emerald-slot");
        if (event.getRawSlot() != emeraldSlot) return;

        player.closeInventory();
        dispatchCommand(player, "plot buy " + plot.getName() + " __confirm__");
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

        plugin.getPlotManager().savePlotAsync(plot);
        plugin.getSignManager().updateSign(plot);

        player.sendMessage(plugin.getConfigManager().getMessage("renew-success",
                "{name}", plot.getName(), "{days}", "1", "{cost}", String.valueOf(renewCost)));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("plot-bought"), 1f, 1f);
    }

    private void dispatchCommand(Player player, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(player, command));
    }

    private void playClick(Player player) {
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("gui-click"), 1f, 1f);
    }

    public Map<UUID, String[]> getPendingChatInput() {
        return pendingChatInput;
    }
}
