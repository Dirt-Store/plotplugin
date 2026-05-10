package com.plotdirt.command;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private final PlotDirt plugin;

    public PlotCommand(PlotDirt plugin) {
        this.plugin = plugin;
    }

    // ── World lookup helper ───────────────────────────────────────────────────

    /**
     * FIX-A: Returns the world that contains this plot's WG region.
     *
     * Old code looped Bukkit.getWorlds() (allocates a new List) and called
     * regionExists() (WorldGuard container lookup) on every world, for every
     * command. A BoxPVP server typically has 3–5 worlds → 3–5 unnecessary
     * WorldGuard lookups per command.
     *
     * New approach: the plot's sign Location already holds the world reference
     * (set when the admin right-clicks the sign). We use that directly in O(1).
     * If the sign has not been registered yet (signLocation == null), we fall
     * back to the old scan so new plots still work correctly.
     */
    private World getPlotWorld(Plot plot) {
        if (plot.getSignLocation() != null && plot.getSignLocation().getWorld() != null) {
            return plot.getSignLocation().getWorld();
        }
        // Fallback for plots without a registered sign yet
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plot.getName())) {
                return world;
            }
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create"        -> cmdCreate(sender, args);
            case "delete"        -> cmdDelete(sender, args);
            case "buy"           -> cmdBuy(sender, args);
            case "transfer"      -> cmdTransfer(sender, args);
            case "addmember"     -> cmdAddMember(sender, args);
            case "removemember"  -> cmdRemoveMember(sender, args);
            case "addmanager"    -> cmdAddManager(sender, args);
            case "removemanager" -> cmdRemoveManager(sender, args);
            case "setcoin"       -> cmdSetCoin(sender, args);
            case "paused"        -> cmdPaused(sender, args);
            case "unpaused"      -> cmdUnpaused(sender, args);
            case "whitelist"     -> cmdWhitelist(sender);
            case "unwhitelist"   -> cmdUnwhitelist(sender);
            case "reload"        -> cmdReload(sender);
            default              -> cmdOpenPlot(sender, args);
        }
        return true;
    }

    // ── /plot create <name> ───────────────────────────────────────────────────

    private void cmdCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotdirt.create")) {
            sender.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot create §7<name>")); return; }
        String name = args[1];

        if (plugin.getPlotManager().plotExists(name)) {
            sender.sendMessage(msg("plot-already-exists", "{name}", name));
            return;
        }

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
        if (!sender.hasPermission("plotdirt.delete")) {
            sender.sendMessage(msg("no-permission"));
            return;
        }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot delete §7<name>")); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { sender.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        // Full WG reset: remove all members/managers/owners + clear flags
        // FIX-A: use getPlotWorld() instead of looping all worlds
        World deleteWorld = getPlotWorld(plot);
        if (deleteWorld != null) {
            plugin.getWorldGuardManager().resetPlotRegion(deleteWorld, name);
        }

        plugin.getPlotManager().deletePlot(name);
        sender.sendMessage(msg("plot-deleted", "{name}", name));
    }

    // ── /plot buy <name> ──────────────────────────────────────────────────────

    private void cmdBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot buy §7<name>")); return; }
        String name = args[1];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        if (plot.isOwned()) { player.sendMessage(msg("plot-not-available")); return; }

        // Check limit before opening the buy GUI
        int maxP = plugin.getConfigManager().getMaxPlotsPerPlayer();
        if (maxP > 0 && !player.hasPermission("plotdirt.bypass-limit")) {
            if (plugin.getPlotManager().countOwnedPlots(player.getUniqueId()) >= maxP) {
                player.sendMessage(msg("plot-limit-reached", "{max}", String.valueOf(maxP)));
                return;
            }
        }

        plugin.getBuyGUI().open(player, plot);
    }

    /**
     * Executes the actual plot purchase for a player.
     * Called directly by GUIListener on emerald click, and can be called from any entry point.
     * Contains the purchase lock, limit check, currency check, and ownership assignment.
     */
    public void executePurchase(Player player, Plot plot) {
        if (!plugin.getPlotManager().tryAcquirePurchaseLock(player.getUniqueId())) {
            return; // duplicate click — already processing
        }

        try {
            // 1. Plot still available?
            if (plot.isOwned()) {
                player.sendMessage(msg("plot-not-available"));
                return;
            }

            // 2. Limit check — FIRST, before anything else
            int maxPlots = plugin.getConfigManager().getMaxPlotsPerPlayer();
            if (maxPlots > 0 && !player.hasPermission("plotdirt.bypass-limit")) {
                int owned = plugin.getPlotManager().countOwnedPlots(player.getUniqueId());
                if (owned >= maxPlots) {
                    player.sendMessage(msg("plot-limit-reached", "{max}", String.valueOf(maxPlots)));
                    return;
                }
            }

            // 3. Currency item configured?
            if (!plugin.getCurrencyManager().hasCurrencyItem()) {
                player.sendMessage(msg("coin-not-set"));
                return;
            }

            // 4. Player has enough currency?
            int cost = plugin.getConfigManager().getBuyCost();
            int days = plugin.getConfigManager().getBuyDays();
            if (plugin.getCurrencyManager().countCurrencyItems(player) < cost) {
                player.sendMessage(msg("not-enough-currency", "{cost}", String.valueOf(cost)));
                return;
            }

            // 5. Execute purchase
            plugin.getCurrencyManager().takeCurrencyItems(player, cost);

            plot.setOwner(player.getUniqueId());
            plot.setExpiryTime(System.currentTimeMillis() + TimeUtil.daysToMs(days));
            plot.setPaused(false);

            // FIX-D: update ownership count cache
            plugin.getPlotManager().updateOwnershipCache(null, player.getUniqueId());
            // BUG FIX #2: cache the new owner's name so updateSign() can display it
            // without hitting the filesystem on the main thread.
            plugin.getSignManager().cacheOwnerNameAsync(player.getUniqueId());

            String name = plot.getName();
            // FIX-A: use signLocation world — O(1) vs O(n worlds)
            World purchaseWorld = getPlotWorld(plot);
            if (purchaseWorld != null) {
                plugin.getWorldGuardManager().grantPlayerAccess(purchaseWorld, name, player.getUniqueId());
            }

            plugin.getPlotManager().savePlotAsync(plot);
            plugin.getSignManager().updateSign(plot);

            player.sendMessage(msg("bought", "{name}", name,
                    "{cost}", String.valueOf(cost), "{days}", String.valueOf(days)));
            player.playSound(player.getLocation(),
                    plugin.getConfigManager().getSound("plot-bought"), 1f, 1f);

        } finally {
            plugin.getPlotManager().releasePurchaseLock(player.getUniqueId());
        }
    }

    // ── /plot <name> ──────────────────────────────────────────────────────────

    private void cmdOpenPlot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        String name = args[0];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }
        plugin.getPlotGUI().open(player, plot);
    }

    // ── /plot transfer <name> <player> ────────────────────────────────────────

    private void cmdTransfer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 3) { sender.sendMessage(raw("§cUsage: §e/plot transfer §7<name> <player>")); return; }
        String name = args[1];
        String targetName = args[2];
        Plot plot = plugin.getPlotManager().getPlot(name);
        if (plot == null) { player.sendMessage(msg("plot-not-found", "{name}", name)); return; }

        if (!plot.isOwner(player.getUniqueId()) && !player.hasPermission("plotdirt.admin")) {
            player.sendMessage(msg("not-owner")); return;
        }

        // BUG FIX #7: Check online players first (no I/O). Only fall back to the
        // deprecated getOfflinePlayer(name) — which may read from the filesystem —
        // when the player is not currently online.
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        OfflinePlayer target;
        if (onlineTarget != null) {
            target = onlineTarget;
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
            target = op;
        }
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(msg("player-not-found", "{player}", targetName)); return;
        }

        UUID oldOwner = plot.getOwner();

        // Check the target doesn't already own too many plots
        int maxPlots = plugin.getConfigManager().getMaxPlotsPerPlayer();
        if (maxPlots > 0) {
            boolean bypass = target.getPlayer() != null
                    && target.getPlayer().hasPermission("plotdirt.bypass-limit");
            if (!bypass && plugin.getPlotManager().countOwnedPlots(target.getUniqueId()) >= maxPlots) {
                player.sendMessage(msg("transfer-target-limit-reached",
                        "{player}", targetName, "{max}", String.valueOf(maxPlots)));
                return;
            }
        }

        // Revoke old owner's WG access, grant new owner — FIX-A: O(1) world lookup
        World transferWorld = getPlotWorld(plot);
        if (transferWorld != null) {
            if (oldOwner != null) plugin.getWorldGuardManager().revokePlayerAccess(transferWorld, name, oldOwner);
            plugin.getWorldGuardManager().grantPlayerAccess(transferWorld, name, target.getUniqueId());
        }

        // FIX-D: update ownership count cache for old and new owner
        plugin.getPlotManager().updateOwnershipCache(oldOwner, target.getUniqueId());
        plot.setOwner(target.getUniqueId());
        // BUG FIX #2: cache the new owner so updateSign() has their name ready.
        plugin.getSignManager().cacheOwnerNameAsync(target.getUniqueId());
        plugin.getPlotManager().savePlotAsync(plot);
        plugin.getSignManager().updateSign(plot);

        player.sendMessage(msg("transferred", "{name}", name, "{player}", targetName));
    }

    // ── /plot addmember <plotName> [playerName] ───────────────────────────────

    private void cmdAddMember(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot addmember §7<name> [player]")); return; }
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

        UUID targetId = target.getUniqueId();

        // FIX: use hasAnyRole() so we correctly detect if the player is already
        // the owner or a manager — not just a member — before adding.
        if (plot.hasAnyRole(targetId)) {
            player.sendMessage(msg("already-member", "{player}", targetName)); return;
        }

        plot.addMember(targetId);

        // FIX-A: O(1) world lookup
        World addMemberWorld = getPlotWorld(plot);
        if (addMemberWorld != null) {
            plugin.getWorldGuardManager().grantPlayerAccess(addMemberWorld, plotName, targetId);
        }

        plugin.getPlotManager().savePlotAsync(plot);
        player.sendMessage(msg("member-added", "{player}", targetName, "{name}", plotName));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("member-added"), 1f, 1f);
    }

    // ── /plot removemember <plotName> [playerName] ────────────────────────────

    private void cmdRemoveMember(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot removemember §7<name> [player]")); return; }
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

        // FIX: remove ONLY this member — do not touch the plot owner, other members,
        // managers, or WG flags. Only this player's WG access is revoked.
        plot.removeMember(targetId);

        // FIX-A: O(1) world lookup
        World removeMemberWorld = getPlotWorld(plot);
        if (removeMemberWorld != null) {
            plugin.getWorldGuardManager().revokePlayerAccess(removeMemberWorld, plotName, targetId);
        }

        plugin.getPlotManager().savePlotAsync(plot);
        player.sendMessage(msg("member-removed", "{player}", targetName, "{name}", plotName));
        player.playSound(player.getLocation(), plugin.getConfigManager().getSound("member-removed"), 1f, 1f);
    }

    // ── /plot addmanager <plotName> [playerName] ──────────────────────────────

    private void cmdAddManager(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot addmanager §7<name> [player]")); return; }
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

        UUID targetId = target.getUniqueId();

        // FIX: check all roles to prevent false duplicate alerts
        if (plot.isManager(targetId)) {
            player.sendMessage(msg("already-manager", "{player}", targetName)); return;
        }

        // If they were a regular member, promote them (remove from members, add to managers)
        if (plot.isMember(targetId)) {
            plot.removeMember(targetId);
        }

        plot.addManager(targetId);

        // FIX-A: O(1) world lookup
        World addManagerWorld = getPlotWorld(plot);
        if (addManagerWorld != null) {
            plugin.getWorldGuardManager().grantPlayerAccess(addManagerWorld, plotName, targetId);
        }

        plugin.getPlotManager().savePlotAsync(plot);
        player.sendMessage(msg("manager-added", "{player}", targetName, "{name}", plotName));
    }

    // ── /plot removemanager <plotName> [playerName] ───────────────────────────

    private void cmdRemoveManager(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot removemanager §7<name> [player]")); return; }
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

        // FIX-A: O(1) world lookup
        World removeManagerWorld = getPlotWorld(plot);
        if (removeManagerWorld != null) {
            plugin.getWorldGuardManager().revokePlayerAccess(removeManagerWorld, plotName, targetId);
        }

        plugin.getPlotManager().savePlotAsync(plot);
        player.sendMessage(msg("manager-removed", "{player}", targetName, "{name}", plotName));
    }

    // ── /plot setcoin ─────────────────────────────────────────────────────────

    private void cmdSetCoin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(raw("§cThis command can only be used by players.")); return; }
        if (!player.hasPermission("plotdirt.setcoin")) { player.sendMessage(msg("no-permission")); return; }

        var item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(msg("no-item-in-hand")); return;
        }

        plugin.getCurrencyManager().setCurrencyItem(item);
        player.sendMessage(msg("coin-set"));
    }

    // ── /plot paused <name> ───────────────────────────────────────────────────

    private void cmdPaused(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotdirt.paused")) { sender.sendMessage(msg("no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot paused §7<name>")); return; }
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
        if (!sender.hasPermission("plotdirt.paused")) { sender.sendMessage(msg("no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(raw("§cUsage: §e/plot unpaused §7<name>")); return; }
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

    private void cmdWhitelist(CommandSender sender) {
        if (!sender.hasPermission("plotdirt.whitelist")) { sender.sendMessage(msg("no-permission")); return; }
        plugin.getPlotManager().setGlobalWhitelist(true);
        sender.sendMessage(msg("whitelist-enabled"));
        plugin.getSignManager().updateAllSigns();
    }

    // ── /plot unwhitelist ─────────────────────────────────────────────────────

    private void cmdUnwhitelist(CommandSender sender) {
        if (!sender.hasPermission("plotdirt.whitelist")) { sender.sendMessage(msg("no-permission")); return; }
        plugin.getPlotManager().setGlobalWhitelist(false);
        sender.sendMessage(msg("whitelist-disabled"));
        plugin.getSignManager().updateAllSigns();
    }

    // ── /plot reload ──────────────────────────────────────────────────────────

    private void cmdReload(CommandSender sender) {
        if (!sender.hasPermission("plotdirt.reload")) {
            sender.sendMessage(msg("no-permission"));
            return;
        }
        // Full live reload: config.yml + GUIs + signs — no restart required
        plugin.getConfigManager().reload();
        sender.sendMessage(raw("§aPlotDirt config reloaded successfully."));
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of(
                    "create", "delete", "buy", "transfer",
                    "addmember", "removemember", "addmanager", "removemanager",
                    "setcoin", "paused", "unpaused", "whitelist", "unwhitelist", "reload"));
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

    /** Convert a §-coded string to an Adventure Component for sendMessage(). */
    private static Component raw(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private Optional<Player> player(CommandSender sender) {
        return sender instanceof Player p ? Optional.of(p) : Optional.empty();
    }

    private void promptChatInput(Player player, String action, String plotName) {
        plugin.getGUIListener().getPendingChatInput().put(
                player.getUniqueId(), new String[]{action, plotName});
        player.sendMessage(msg("input-member-name"));
    }

    /**
     * FIX-G: Direct dispatch from ChatInputListener — avoids Bukkit.dispatchCommand()
     * string parsing overhead and any injection risk from plotName containing spaces.
     */
    public void dispatchFromChat(Player player, String action, String plotName, String targetName) {
        String[] args = new String[]{action, plotName, targetName};
        switch (action.toLowerCase()) {
            case "addmember"     -> cmdAddMember(player, args);
            case "removemember"  -> cmdRemoveMember(player, args);
            case "addmanager"    -> cmdAddManager(player, args);
            case "removemanager" -> cmdRemoveManager(player, args);
            case "transfer"      -> cmdTransfer(player, args);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(raw("§b/plot create <name> §7- Create a plot"));
        sender.sendMessage(raw("§b/plot delete <name> §7- Delete a plot"));
        sender.sendMessage(raw("§b/plot buy <name> §7- Buy a plot"));
        sender.sendMessage(raw("§b/plot <name> §7- View plot GUI"));
        sender.sendMessage(raw("§b/plot transfer <name> <player> §7- Transfer ownership"));
        sender.sendMessage(raw("§b/plot addmember <name> [player] §7- Add member"));
        sender.sendMessage(raw("§b/plot removemember <name> [player] §7- Remove member"));
        sender.sendMessage(raw("§b/plot addmanager <name> [player] §7- Add manager"));
        sender.sendMessage(raw("§b/plot removemanager <name> [player] §7- Remove manager"));
        sender.sendMessage(raw("§b/plot setcoin §7- Set currency item"));
        sender.sendMessage(raw("§b/plot paused <name> §7- Pause timer"));
        sender.sendMessage(raw("§b/plot unpaused <name> §7- Resume timer"));
        sender.sendMessage(raw("§b/plot whitelist §7- Pause all timers (global)"));
        sender.sendMessage(raw("§b/plot unwhitelist §7- Resume all timers"));
        sender.sendMessage(raw("§b/plot reload §7- Reload config live"));
    }
}
