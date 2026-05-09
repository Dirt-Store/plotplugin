package mc.dirt.plotsystem.command;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.config.ConfigManager;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.data.RoleType;
import mc.dirt.plotsystem.gui.BuyGui;
import mc.dirt.plotsystem.gui.ManageGui;
import mc.dirt.plotsystem.manager.PlotManager;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.logging.Logger;

public class PlotCommand implements CommandExecutor {

    private final PlotPlugin plugin;
    private final Logger log;
    private final ConfigManager cfg;
    private final PlotManager pm;
    private final BuyGui buyGui;
    private final ManageGui manageGui;

    public PlotCommand(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = plugin.getConfigManager();
        this.pm = plugin.getPlotManager();
        this.buyGui = plugin.getBuyGui();
        this.manageGui = plugin.getManageGui();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setcoin" -> handleSetCoin(player, args);
            case "create"  -> handleCreate(player, args);
            case "buy"     -> handleBuy(player, args);
            case "remove"  -> handleRemove(player, args);
            default        -> handleManage(player, args[0]);
        }

        return true;
    }

    // ── /plot setcoin ─────────────────────────────────────────────────────────

    private void handleSetCoin(Player player, String[] args) {
        if (!player.hasPermission("plotsystem.admin")) {
            player.sendMessage(cfg.msg("no-permission"));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage(cfg.msg("no-item-in-hand"));
            return;
        }

        cfg.setCurrencyItem(held);

        String itemName = held.hasItemMeta() && held.getItemMeta().hasDisplayName()
                ? held.getItemMeta().getDisplayName()
                : held.getType().name();
        player.sendMessage(cfg.msg("coin-set", "{item}", itemName));
    }

    // ── /plot create <region> ─────────────────────────────────────────────────

    private void handleCreate(Player player, String[] args) {
        if (!player.hasPermission("plotsystem.admin")) {
            player.sendMessage(cfg.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(cfg.msg("usage-create"));
            return;
        }

        String regionName = args[1].toLowerCase();

        if (pm.plotExists(regionName)) {
            player.sendMessage(cfg.msg("plot-already-exists", "{name}", regionName));
            return;
        }

        // Verify WG region exists in player's world
        World world = player.getWorld();
        ProtectedRegion region = pm.getWGRegion(regionName, world.getName());
        if (region == null) {
            player.sendMessage(cfg.msg("region-not-found", "{name}", regionName));
            return;
        }

        pm.createPlot(regionName, world.getName());

        // Give sign to admin's main hand
        ItemStack sign = makeSign(regionName);
        giveItem(player, sign);

        player.sendMessage(cfg.msg("plot-created", "{name}", regionName));
        player.sendMessage(cfg.msg("sign-in-hand-hint"));
    }

    private ItemStack makeSign(String regionName) {
        ItemStack sign = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = sign.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "[Plot] " + regionName);
            sign.setItemMeta(meta);
        }
        return sign;
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    // ── /plot buy <regionName> ────────────────────────────────────────────────

    private void handleBuy(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(cfg.msg("usage-buy"));
            return;
        }
        String regionName = args[1].toLowerCase();
        PlotData data = pm.getPlot(regionName);
        if (data == null) {
            player.sendMessage(cfg.msg("plot-not-found", "{name}", regionName));
            return;
        }
        if (data.isOwned()) {
            player.sendMessage(ChatColor.RED + "That plot is already owned.");
            return;
        }
        buyGui.open(player, data);
    }

    // ── /plot remove <region> ─────────────────────────────────────────────────

    private void handleRemove(Player player, String[] args) {
        if (!player.hasPermission("plotsystem.admin")) {
            player.sendMessage(cfg.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(cfg.msg("usage-remove"));
            return;
        }

        String regionName = args[1].toLowerCase();
        PlotData data = pm.getPlot(regionName);
        if (data == null) {
            player.sendMessage(cfg.msg("plot-not-found", "{name}", regionName));
            return;
        }

        // Remove sign block if placed
        Location signLoc = data.getSignLocation();
        if (signLoc != null) {
            Block block = signLoc.getBlock();
            if (block.getType().name().contains("SIGN")) {
                block.setType(Material.AIR);
            }
        }

        pm.removePlot(regionName);
        player.sendMessage(cfg.msg("plot-removed", "{name}", regionName));
    }

    // ── /plot <regionName> → manage ───────────────────────────────────────────

    private void handleManage(Player player, String regionName) {
        PlotData data = pm.getPlot(regionName.toLowerCase());
        if (data == null) {
            player.sendMessage(cfg.msg("plot-not-found", "{name}", regionName));
            return;
        }
        if (!data.isOwned()) {
            player.sendMessage(ChatColor.RED + "That plot has no owner yet. Use /plot buy " + regionName + " to buy it.");
            return;
        }
        RoleType role = data.getRole(player.getUniqueId());
        if (role != RoleType.OWNER && role != RoleType.MANAGER) {
            player.sendMessage(cfg.msg("not-owner-or-manager"));
            return;
        }
        manageGui.open(player, data);
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "--- Plot Commands ---");
        if (player.hasPermission("plotsystem.admin")) {
            player.sendMessage(ChatColor.YELLOW + "/plot setcoin" + ChatColor.GRAY + " — set currency item");
            player.sendMessage(ChatColor.YELLOW + "/plot create <region>" + ChatColor.GRAY + " — create a plot");
            player.sendMessage(ChatColor.YELLOW + "/plot remove <region>" + ChatColor.GRAY + " — remove a plot");
        }
        player.sendMessage(ChatColor.YELLOW + "/plot buy <region>" + ChatColor.GRAY + " — buy a plot");
        player.sendMessage(ChatColor.YELLOW + "/plot <region>" + ChatColor.GRAY + " — manage your plot");
    }
}
