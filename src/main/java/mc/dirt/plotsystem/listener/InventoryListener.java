package mc.dirt.plotsystem.listener;

import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.config.ConfigManager;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.data.RoleType;
import mc.dirt.plotsystem.gui.BuyGui;
import mc.dirt.plotsystem.gui.ManageGui;
import mc.dirt.plotsystem.manager.PlotManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class InventoryListener implements Listener {

    private final PlotPlugin plugin;
    private final Logger log;
    private final ConfigManager cfg;
    private final PlotManager pm;
    private final ManageGui manageGui;

    // Players currently in a chat-input flow: uuid → (flowType, regionName)
    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();

    public InventoryListener(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = plugin.getConfigManager();
        this.pm = plugin.getPlotManager();
        this.manageGui = plugin.getManageGui();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        String title = event.getView().getTitle();

        // ── Buy GUI ───────────────────────────────────────────────────────────
        if (BuyGui.isBuyGui(title)) {
            event.setCancelled(true);
            String regionName = BuyGui.extractRegionName(title);
            if (regionName == null) return;
            PlotData data = pm.getPlot(regionName);
            if (data == null) {
                player.sendMessage(cfg.msg("plot-not-found", "{name}", regionName));
                player.closeInventory();
                return;
            }
            int clickedSlot = event.getRawSlot();
            int buySlot = cfg.getGuiItemSlot("buy-plot");
            if (clickedSlot == buySlot) {
                handleBuyClick(player, data);
            }
            return;
        }

        // ── Manage GUI ────────────────────────────────────────────────────────
        if (ManageGui.isManageGui(title)) {
            event.setCancelled(true);
            String regionName = ManageGui.extractRegionName(title);
            if (regionName == null) return;
            PlotData data = pm.getPlot(regionName);
            if (data == null) {
                player.sendMessage(cfg.msg("plot-not-found", "{name}", regionName));
                player.closeInventory();
                return;
            }

            RoleType actorRole = data.getRole(player.getUniqueId());
            if (actorRole == null) {
                player.closeInventory();
                return;
            }

            int slot = event.getRawSlot();
            if (slot == ManageGui.SLOT_ADD_MEMBER) {
                startChatInput(player, regionName, InputType.ADD_MEMBER);
            } else if (slot == ManageGui.SLOT_REMOVE_MEMBER) {
                startChatInput(player, regionName, InputType.REMOVE_MEMBER);
            } else if (slot == ManageGui.SLOT_ADD_MANAGER && actorRole == RoleType.OWNER) {
                startChatInput(player, regionName, InputType.ADD_MANAGER);
            } else if (slot == ManageGui.SLOT_BUY_TIME) {
                handleBuyTime(player, data);
            }
        }
    }

    // ── Buy plot ──────────────────────────────────────────────────────────────

    private void handleBuyClick(Player player, PlotData data) {
        if (data.isOwned()) {
            player.sendMessage(ChatColor.RED + "This plot is already owned.");
            player.closeInventory();
            return;
        }

        int price = cfg.getBasePrice();
        if (!cfg.removeCurrency(player, price)) {
            int has = cfg.countCurrency(player);
            player.sendMessage(cfg.msg("not-enough-currency",
                    "{amount}", String.valueOf(price - has),
                    "{item}", cfg.getCurrencyItem().getType().name().toLowerCase()));
            player.closeInventory();
            return;
        }

        try {
            pm.buyPlot(data, player);
            player.sendMessage(cfg.msg("plot-bought",
                    "{name}", data.getRegionName(),
                    "{price}", String.valueOf(price),
                    "{days}", String.valueOf(cfg.getBaseDays())));
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to complete buy for " + player.getName() + ": " + e.getMessage());
        }
        player.closeInventory();
    }

    // ── Buy time ──────────────────────────────────────────────────────────────

    private void handleBuyTime(Player player, PlotData data) {
        int maxDays = cfg.getBuyTimeMaxDays();
        if (data.getDaysRemaining() >= maxDays) {
            player.sendMessage(cfg.msg("plot-at-max-days", "{max}", String.valueOf(maxDays)));
            return;
        }

        int cost = cfg.getBuyTimeCost();
        if (!cfg.removeCurrency(player, cost)) {
            int has = cfg.countCurrency(player);
            player.sendMessage(cfg.msg("not-enough-currency",
                    "{amount}", String.valueOf(cost - has),
                    "{item}", cfg.getCurrencyItem().getType().name().toLowerCase()));
            return;
        }

        boolean success = pm.addDays(data, 1);
        if (!success) {
            // Refund
            player.getInventory().addItem(cfg.getCurrencyItem());
            player.sendMessage(cfg.msg("plot-at-max-days", "{max}", String.valueOf(maxDays)));
        } else {
            player.sendMessage(cfg.msg("time-bought",
                    "{days}", "1",
                    "{total}", String.valueOf(data.getDaysRemaining())));
        }
        // Reopen to refresh days display
        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) manageGui.open(player, data);
        }, 1L);
    }

    // ── Chat input flow ───────────────────────────────────────────────────────

    private void startChatInput(Player player, String regionName, InputType type) {
        player.closeInventory();
        pendingInputs.put(player.getUniqueId(), new PendingInput(type, regionName));
        player.sendMessage(cfg.msg("enter-player-name"));
    }

    public boolean hasPendingInput(UUID uuid) {
        return pendingInputs.containsKey(uuid);
    }

    public void handleChatInput(Player player, String input) {
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) return;

        PlotData data = pm.getPlot(pending.regionName());
        if (data == null) {
            player.sendMessage(cfg.msg("plot-not-found", "{name}", pending.regionName()));
            return;
        }

        // Resolve player
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(input);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            player.sendMessage(cfg.msg("player-not-found", "{player}", input));
            return;
        }

        UUID targetUUID = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : input;

        switch (pending.type()) {
            case ADD_MEMBER -> {
                if (data.getRole(targetUUID) != null) {
                    player.sendMessage(cfg.msg("already-member", "{player}", targetName));
                    return;
                }
                boolean ok = pm.addMember(data, targetUUID, targetName, RoleType.MEMBER, player);
                player.sendMessage(ok
                        ? cfg.msg("member-added", "{player}", targetName)
                        : cfg.msg("already-member", "{player}", targetName));
            }
            case REMOVE_MEMBER -> {
                RoleType targetRole = data.getRole(targetUUID);
                if (targetRole == null) {
                    player.sendMessage(cfg.msg("member-not-found", "{player}", targetName));
                    return;
                }
                if (targetRole == RoleType.OWNER) {
                    player.sendMessage(cfg.msg("cannot-remove-owner"));
                    return;
                }
                RoleType actorRole = data.getRole(player.getUniqueId());
                if (actorRole == RoleType.MANAGER && targetRole == RoleType.MANAGER) {
                    player.sendMessage(cfg.msg("managers-cannot-touch-managers"));
                    return;
                }
                boolean ok = pm.removeMember(data, targetUUID, player);
                player.sendMessage(ok
                        ? cfg.msg("member-removed", "{player}", targetName)
                        : cfg.msg("member-not-found", "{player}", targetName));
            }
            case ADD_MANAGER -> {
                if (data.getRole(player.getUniqueId()) != RoleType.OWNER) {
                    player.sendMessage(cfg.msg("no-permission"));
                    return;
                }
                if (data.getRole(targetUUID) != null) {
                    // Already a member — promote
                    data.removeMember(targetUUID);
                    pm.removeWGMember(data, targetUUID);
                }
                boolean ok = pm.addMember(data, targetUUID, targetName, RoleType.MANAGER, player);
                player.sendMessage(ok
                        ? cfg.msg("manager-added", "{player}", targetName)
                        : cfg.msg("already-member", "{player}", targetName));
            }
        }

        // Reopen manage GUI
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) manageGui.open(player, data);
        }, 1L);
    }

    // ── Record types ──────────────────────────────────────────────────────────

    public enum InputType { ADD_MEMBER, REMOVE_MEMBER, ADD_MANAGER }

    public record PendingInput(InputType type, String regionName) {}
}
