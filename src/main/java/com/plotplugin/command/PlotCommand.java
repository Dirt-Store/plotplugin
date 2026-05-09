package com.plotplugin.command;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.util.TimeUtil;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class PlotCommand implements CommandExecutor, TabCompleter {

    private final PlotPlugin plugin;

    public PlotCommand(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create" -> cmdCreate(sender, args);
            case "delete" -> cmdDelete(sender, args);
            case "buy" -> cmdBuy(sender, args);
            case "transfer" -> cmdTransfer(sender, args);
            case "addmember" -> cmdAddMember(sender, args);
            case "removemember" -> cmdRemoveMember(sender, args);
            case "addmanager" -> cmdAddManager(sender, args);
            case "removemanager" -> cmdRemoveManager(sender, args);
            case "setcoin" -> cmdSetCoin(sender, args);
            case "paused" -> cmdPaused(sender, args);
            case "unpaused" -> cmdUnpaused(sender, args);
            case "whitelist" -> cmdWhitelist(sender, args);
            case "unwhitelist" -> cmdUnwhitelist(sender, args);
            default -> {
                // /plot <name> -> open plot GUI
                cmdOpenPlot(sender, args);
            }
        }
        return true;
    }

    // ── /plot create <name> ───────────────────────────────────────────────────

    private void cmdCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.create")) {
            sender.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot create <name>"); return; }
        String name = args[1];

        if (plugin.getPlotManager().plotExists(name)) {
            sender.sendMessage(msg("plot-already-exists", "{name}", name));
            return;
        }

        // Verify WG region exists in any loaded world
        boolean regionFound = false;
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, name)) {
                regionFound = true;
                break;
            }
        }
        if (!regionFound) {
            sender.sendMessage(msg("region-not-found", "{name}", name));
            return;
        }

        plugin.getPlotManager().createPlot(name);
        sender.sendMessage(msg("plot-created", "{name}", name));

        if (sender instanceof Player player) {
            plugin.getPlotManager().setPendingSignRegistration(player.getUniqueId(), name);
            player.sendMessage(msg("break-sign"));
        }
    }

    // ── /plot delete <name> ───────────────────────────────────────────────────

    private void cmdDelete(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.delete")) {
            sender.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot delete <name>"); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { sender.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        plugin.getPlotManager().deletePlot(name);
        sender.sendMessage(msg("plot-deleted", "{name}", name));
    }

    // ── /plot buy <name> ──────────────────────────────────────────────────────

    private void cmdBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot buy <name>"); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        // If called with __confirm__ it's from the GUI emerald click
        boolean confirmed = args.length >= 3 && args[2].equals("__confirm__");

        if (!confirmed) {
            // Open buy GUI
            if (plot.isOwned()) { player.sendMessage(msg("plot-not-available")); return; }
            plugin.getBuyGUI().open(player, plot);
            return;
        }

        // Actual purchase
        if (plot.isOwned()) { player.sendMessage(msg("plot-not-available")); return; }

        if (!plugin.getCurrencyManager().hasCurrencyItem()) {
            player.sendMessage(msg("coin-not-set"));
            return;
        }

        int cost = plugin.getConfigManager().getBuyCost();
        int days = plugin.getConfigManager().getBuyDays();

        if (plugin.getCurrencyManager().countCurrencyItems(player) < cost) {
            player.sendMessage(msg("not-enough-currency", "{cost}", String.valueOf(cost)));
            return;
        }

        plugin.getCurrencyManager().takeCurrencyItems(player, cost);

        plot.setOwner(player.getUniqueId());
        plot.setExpiryTime(System.currentTimeMillis() + TimeUtil.daysToMs(days));
        plot.setPaused(false);

        // Grant WG owner access (plot owner → WG owner, so they have full region control)
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, name)) {
                plugin.getWorldGuardManager().grantOwnerAccess(world, name, player.getUniqueId());
                break;
            }
        }

        plugin.getPlotManager().savePlot(plot); // fast single-plot save
        plugin.getSignManager().updateSign(plot);

        player.sendMessage(msg("bought", "{name}", name, "{cost}", String.valueOf(cost), "{days}", String.valueOf(days)));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("plot-bought"), 1f, 1f);
    }

    // ── /plot <name> ──────────────────────────────────────────────────────────

    private void cmdOpenPlot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        String name = args[0];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }
        plugin.getPlotGUI().open(player, plot);
    }

    // ── /plot transfer <name> <player> ────────────────────────────────────────

    private void cmdTransfer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (!player.hasPermission("plotplugin.transfer") && !isOwner(player, args.length > 1 ? args[1] : "")) {
            player.sendMessage(msg("no-permission")); return;
        }
        if (args.length < 3) { sender.sendMessage("&cUsage: /plot transfer <name> <player>"); return; }
        String name = args[1];
        String targetName = args[2];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("plotplugin.admin")) {
            player.sendMessage(msg("not-owner")); return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(msg("player-not-found", "{player}", targetName)); return;
        }

        UUID oldOwner = plot.getOwner();
        // Update WG: revoke old owner, grant new owner
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, name)) {
                if (oldOwner != null) plugin.getWorldGuardManager().revokePlayerAccess(world, name, oldOwner);
                plugin.getWorldGuardManager().grantOwnerAccess(world, name, target.getUniqueId());
                break;
            }
        }
        plot.setOwner(target.getUniqueId());
        plugin.getPlotManager().savePlot(plot);
        plugin.getSignManager().updateSign(plot);

        player.sendMessage(msg("transferred", "{name}", name, "{player}", targetName));
    }

    // ── /plot addmember <plotName> [playerName] ───────────────────────────────

    private void cmdAddMember(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot addmember <name> [player]"); return; }
        String plotName = args[1];
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", plotName)); return; }
        if (!plot.canManage(player.getUniqueId())) { player.sendMessage(msg("not-owner-or-manager")); return; }

        if (args.length < 3) {
            promptChatInput(player, "addmember", plotName);
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(msg("player-not-found", "{player}", targetName)); return;
        }
        if (plot.isMember(target.getUniqueId())) {
            player.sendMessage(msg("already-member", "{player}", targetName)); return;
        }

        plot.addMember(target.getUniqueId());

        // Grant WG access
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plotName)) {
                plugin.getWorldGuardManager().grantPlayerAccess(world, plotName, target.getUniqueId());
                break;
            }
        }

        plugin.getPlotManager().savePlot(plot);
        player.sendMessage(msg("member-added", "{player}", targetName, "{name}", plotName));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("member-added"), 1f, 1f);
    }

    // ── /plot removemember <plotName> [playerName] ────────────────────────────

    private void cmdRemoveMember(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot removemember <name> [player]"); return; }
        String plotName = args[1];
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", plotName)); return; }
        if (!plot.canManage(player.getUniqueId())) { player.sendMessage(msg("not-owner-or-manager")); return; }

        if (args.length < 3) {
            promptChatInput(player, "removemember", plotName);
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetId = target.getUniqueId();

        // Managers cannot remove owners or other managers
        if (plot.isManager(player.getUniqueId()) && !plot.isOwner(player.getUniqueId())) {
            if (plot.isOwner(targetId) || plot.isManager(targetId)) {
                player.sendMessage(msg("cannot-manage-owner")); return;
            }
        }

        if (!plot.isMember(targetId)) {
            player.sendMessage(msg("not-member", "{player}", targetName)); return;
        }

        plot.removeMember(targetId);

        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plotName)) {
                plugin.getWorldGuardManager().revokePlayerAccess(world, plotName, targetId);
                break;
            }
        }

        plugin.getPlotManager().savePlot(plot);
        player.sendMessage(msg("member-removed", "{player}", targetName, "{name}", plotName));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("member-removed"), 1f, 1f);
    }

    // ── /plot addmanager <plotName> [playerName] ──────────────────────────────

    private void cmdAddManager(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot addmanager <name> [player]"); return; }
        String plotName = args[1];
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", plotName)); return; }
        if (!plot.isOwner(player.getUniqueId())) { player.sendMessage(msg("not-owner")); return; }

        if (args.length < 3) {
            promptChatInput(player, "addmanager", plotName);
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(msg("player-not-found", "{player}", targetName)); return;
        }
        if (plot.isManager(target.getUniqueId())) {
            player.sendMessage(msg("already-manager", "{player}", targetName)); return;
        }

        plot.addManager(target.getUniqueId());

        // Grant WG member access to manager (they can build/use in the plot)
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plotName)) {
                plugin.getWorldGuardManager().grantPlayerAccess(world, plotName, target.getUniqueId());
                break;
            }
        }

        plugin.getPlotManager().savePlot(plot);
        player.sendMessage(msg("manager-added", "{player}", targetName, "{name}", plotName));
    }

    // ── /plot removemanager <plotName> [playerName] ───────────────────────────

    private void cmdRemoveManager(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot removemanager <name> [player]"); return; }
        String plotName = args[1];
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", plotName)); return; }
        if (!plot.isOwner(player.getUniqueId())) { player.sendMessage(msg("not-owner")); return; }

        if (args.length < 3) {
            promptChatInput(player, "removemanager", plotName);
            return;
        }

        String targetName = args[2];
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetId = target.getUniqueId();

        if (!plot.isManager(targetId)) {
            player.sendMessage(msg("not-manager", "{player}", targetName)); return;
        }

        plot.removeManager(targetId);

        // Revoke WG access for manager
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plotName)) {
                plugin.getWorldGuardManager().revokePlayerAccess(world, plotName, targetId);
                break;
            }
        }

        plugin.getPlotManager().savePlot(plot);
        player.sendMessage(msg("manager-removed", "{player}", targetName, "{name}", plotName));
    }

    // ── /plot setcoin ─────────────────────────────────────────────────────────

    private void cmdSetCoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("&cPlayers only."); return; }
        if (!player.hasPermission("plotplugin.setcoin")) { player.sendMessage(msg("no-permission")); return; }

        var item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(msg("no-item-in-hand")); return;
        }

        plugin.getCurrencyManager().setCurrencyItem(item);
        player.sendMessage(msg("coin-set"));
    }

    // ── /plot paused <name> ───────────────────────────────────────────────────

    private void cmdPaused(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.paused")) { sender.sendMessage(msg("no-permission")); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot paused <name>"); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { sender.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        if (plot.isPaused()) return;
        long remaining = plot.getExpiryTime() - System.currentTimeMillis();
        plot.setPaused(true);
        plot.setPausedRemainingMs(Math.max(0, remaining));
        plugin.getPlotManager().savePlot(plot);
        plugin.getSignManager().updateSign(plot);
        sender.sendMessage(msg("timer-paused", "{name}", name));
        player(sender).ifPresent(p -> p.playSound(p.getLocation(),
                plugin.getConfigManager().getSound("timer-paused"), 1f, 1f));
    }

    // ── /plot unpaused <name> ─────────────────────────────────────────────────

    private void cmdUnpaused(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.paused")) { sender.sendMessage(msg("no-permission")); return; }
        if (args.length < 2) { sender.sendMessage("&cUsage: /plot unpaused <name>"); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { sender.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        if (!plot.isPaused()) return;
        long newExpiry = System.currentTimeMillis() + plot.getPausedRemainingMs();
        plot.setExpiryTime(newExpiry);
        plot.setPaused(false);
        plot.setPausedRemainingMs(-1);
        plugin.getPlotManager().savePlot(plot);
        plugin.getSignManager().updateSign(plot);
        sender.sendMessage(msg("timer-unpaused", "{name}", name));
        player(sender).ifPresent(p -> p.playSound(p.getLocation(),
                plugin.getConfigManager().getSound("timer-unpaused"), 1f, 1f));
    }

    // ── /plot whitelist ───────────────────────────────────────────────────────

    private void cmdWhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.whitelist")) { sender.sendMessage(msg("no-permission")); return; }
        plugin.getPlotManager().setGlobalWhitelist(true);
        sender.sendMessage(msg("whitelist-enabled"));
        plugin.getSignManager().updateAllSigns();
    }

    // ── /plot unwhitelist ─────────────────────────────────────────────────────

    private void cmdUnwhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotplugin.whitelist")) { sender.sendMessage(msg("no-permission")); return; }
        plugin.getPlotManager().setGlobalWhitelist(false);
        sender.sendMessage(msg("whitelist-disabled"));
        plugin.getSignManager().updateAllSigns();
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "create", "delete", "buy", "transfer",
                    "addmember", "removemember", "addmanager", "removemanager",
                    "setcoin", "paused", "unpaused", "whitelist", "unwhitelist"));
            plugin.getPlotManager().getAllPlots().forEach(p -> subs.add(p.getName()));
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2) {
            return plugin.getPlotManager().getAllPlots().stream()
                    .map(Plot::getName)
                    .filter(n -> n.startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String msg(String key, String... replacements) {
        return plugin.getConfigManager().getMessage(key, replacements);
    }

    private boolean isOwner(Player player, String plotName) {
        Plot plot = plugin.getPlotManager().getPlot(plotName);
        return plot != null && plot.isOwner(player.getUniqueId());
    }

    private Optional<Player> player(CommandSender sender) {
        return sender instanceof Player p ? Optional.of(p) : Optional.empty();
    }

    private void promptChatInput(Player player, String action, String plotName) {
        // Store pending input for GUIListener chat handler
        plugin.getGUIListener().getPendingChatInput().put(
                player.getUniqueId(), new String[]{action, plotName});
        player.sendMessage(msg("input-member-name"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§b/plot create <name> §7- Create a plot");
        sender.sendMessage("§b/plot delete <name> §7- Delete a plot");
        sender.sendMessage("§b/plot buy <name> §7- Buy a plot");
        sender.sendMessage("§b/plot <name> §7- View plot GUI");
        sender.sendMessage("§b/plot transfer <name> <player> §7- Transfer ownership");
        sender.sendMessage("§b/plot addmember <name> [player] §7- Add member");
        sender.sendMessage("§b/plot removemember <name> [player] §7- Remove member");
        sender.sendMessage("§b/plot addmanager <name> [player] §7- Add manager");
        sender.sendMessage("§b/plot removemanager <name> [player] §7- Remove manager");
        sender.sendMessage("§b/plot setcoin §7- Set currency item");
        sender.sendMessage("§b/plot paused <name> §7- Pause timer");
        sender.sendMessage("§b/plot unpaused <name> §7- Resume timer");
        sender.sendMessage("§b/plot whitelist §7- Pause all timers (global)");
        sender.sendMessage("§b/plot unwhitelist §7- Resume all timers");
    }
}
