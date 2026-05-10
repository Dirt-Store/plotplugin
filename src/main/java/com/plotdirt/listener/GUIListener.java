package com.plotdirt.listener;

import com.plotdirt.PlotDirt;
import com.plotdirt.gui.PlotGUIHolder;
import com.plotdirt.model.Plot;
import com.plotdirt.util.TimeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIListener implements Listener {

    private final PlotDirt plugin;

    // Async-safe: ChatInputListener reads this from AsyncChatEvent thread
    private final Map<UUID, String[]> pendingChatInput = new ConcurrentHashMap<>();

    public GUIListener(PlotDirt plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

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

        // FIX-B/C: read from cached values — no YAML I/O on the event thread
        int addMemberSlot     = plugin.getConfigManager().getCachedPlotGuiAddMemberSlot();
        int removeMemberSlot  = plugin.getConfigManager().getCachedPlotGuiRemoveMemberSlot();
        int addManagerSlot    = plugin.getConfigManager().getCachedPlotGuiAddManagerSlot();
        int removeManagerSlot = plugin.getConfigManager().getCachedPlotGuiRemoveManagerSlot();
        int buyTimeSlot       = plugin.getConfigManager().getCachedPlotGuiBuyTimeSlot();
        int transferSlot      = plugin.getConfigManager().getCachedPlotGuiTransferSlot();

        String plotName = plot.getName();

        if (slot == addMemberSlot) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"addmember", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-member-name"));

        } else if (slot == removeMemberSlot) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"removemember", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-member-name"));

        } else if (slot == addManagerSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"addmanager", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-member-name"));

        } else if (slot == removeManagerSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"removemanager", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-member-name"));

        } else if (slot == transferSlot && plot.isOwner(player.getUniqueId())) {
            player.closeInventory();
            pendingChatInput.put(player.getUniqueId(), new String[]{"transfer", plotName});
            player.sendMessage(plugin.getConfigManager().getMessage("input-transfer-name"));

        } else if (slot == buyTimeSlot && plot.isOwner(player.getUniqueId())) {
            // Only the owner can extend the plot duration
            player.closeInventory();
            handleBuyTime(player, plot);
        }
    }

    private void handleBuyGUI(InventoryClickEvent event, Player player, Plot plot) {
        int emeraldSlot = plugin.getConfigManager().getCachedBuyGuiEmeraldSlot(); // FIX-B
        if (event.getRawSlot() != emeraldSlot) return;

        player.closeInventory();
        // Call executePurchase directly — no dispatchCommand, guaranteed to run on main thread
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getPlotCommand().executePurchase(player, plot));
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
        player.playSound(player.getLocation(),
                plugin.getConfigManager().getSound("plot-bought"), 1f, 1f);
    }

    private void playClick(Player player) {
        player.playSound(player.getLocation(),
                plugin.getConfigManager().getSound("gui-click"), 1f, 1f);
    }

    public Map<UUID, String[]> getPendingChatInput() {
        return pendingChatInput;
    }
}

