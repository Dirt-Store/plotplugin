package mc.dirt.plotsystem.listener;

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
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.logging.Logger;

public class SignListener implements Listener {

    private final PlotPlugin plugin;
    private final Logger log;
    private final ConfigManager cfg;
    private final PlotManager pm;
    private final BuyGui buyGui;
    private final ManageGui manageGui;

    public SignListener(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.cfg = plugin.getConfigManager();
        this.pm = plugin.getPlotManager();
        this.buyGui = plugin.getBuyGui();
        this.manageGui = plugin.getManageGui();
    }

    // ── Sign placement — detect plot signs ───────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            Block block = event.getBlockPlaced();
            if (!isSign(block.getType())) return;

            Player player = event.getPlayer();
            if (!player.hasPermission("plotsystem.admin")) return;

            ItemStack item = event.getItemInHand();
            String regionName = getPlotSignRegion(item);
            if (regionName == null) return;

            PlotData data = pm.getPlot(regionName);
            if (data == null) {
                log.warning("[PlotSystem] Admin placed plot sign for unknown region '" + regionName + "'.");
                return;
            }

            // Register sign location with plot
            pm.registerSignLocation(data, block.getLocation());
            // Write sign lines
            pm.updateSign(data);
        } catch (Exception e) {
            log.severe("[PlotSystem] Error handling block place: " + e.getMessage());
        }
    }

    // ── Sign protection ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        try {
            Location loc = event.getBlock().getLocation();
            if (!pm.isProtectedSign(loc)) return;

            Player player = event.getPlayer();
            if (player.hasPermission("plotsystem.admin")) return; // admins can break via /plot remove, not manually

            event.setCancelled(true);
            player.sendMessage(cfg.msg("sign-destroyed"));
        } catch (Exception e) {
            log.severe("[PlotSystem] Error protecting sign: " + e.getMessage());
        }
    }

    // ── Sign right-click ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        try {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!event.hasBlock()) return;
            Block block = event.getClickedBlock();
            if (block == null || !isSign(block.getType())) return;

            PlotData data = pm.getPlotBySign(block.getLocation());
            if (data == null) return;

            event.setCancelled(true);
            Player player = event.getPlayer();

            if (!data.isOwned()) {
                // Available — open buy GUI
                buyGui.open(player, data);
                return;
            }

            // Owned — only owner/manager can open manage GUI
            RoleType role = data.getRole(player.getUniqueId());
            if (role == RoleType.OWNER || role == RoleType.MANAGER) {
                manageGui.open(player, data);
            }
            // Other players: do nothing (silently)
        } catch (Exception e) {
            log.severe("[PlotSystem] Error handling sign interaction: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isSign(Material mat) {
        String name = mat.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") || name.endsWith("_HANGING_SIGN");
    }

    /**
     * Returns the region name encoded in the plot sign item name, or null if not a plot sign.
     * Plot sign items are named "&6[Plot] <regionName>" (translated).
     */
    private String getPlotSignRegion(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasDisplayName()) return null;
        String name = meta.getDisplayName();
        String prefix = ChatColor.GOLD + "[Plot] ";
        if (!name.startsWith(prefix)) return null;
        return name.substring(prefix.length()).toLowerCase();
    }
}
