package com.plotplugin.listener;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

public class SignListener implements Listener {

    private final PlotPlugin plugin;

    public SignListener(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block broken = event.getBlock();
        Location loc = broken.getLocation();

        // Check if this is a plot sign or its attached block
        Plot plot = plugin.getSignManager().getPlotBySignLocation(loc);
        if (plot == null) return;

        // Check pending sign registration (player breaking sign to register it)
        if (plugin.getPlotManager().hasPendingSignRegistration(player.getUniqueId())) {
            String pendingPlotName = plugin.getPlotManager().getPendingSignRegistration(player.getUniqueId());
            Plot pendingPlot = plugin.getPlotManager().getPlot(pendingPlotName);

            // Only allow if this isn't already a registered sign
            if (pendingPlot != null && Tag.SIGNS.isTagged(broken.getType())) {
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("sign-break-denied"));
                return;
            }
        }

        // Block breaking of registered sign/attached block
        event.setCancelled(true);
        player.sendMessage(plugin.getConfigManager().getMessage("sign-break-denied"));
        // Restore sign content in case it flickered
        plugin.getSignManager().scheduleSignRestore(plot);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!Tag.SIGNS.isTagged(block.getType())) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Check if player is in pending sign registration mode
        if (plugin.getPlotManager().hasPendingSignRegistration(playerId)) {
            event.setCancelled(true);
            String plotName = plugin.getPlotManager().getPendingSignRegistration(playerId);
            Plot plot = plugin.getPlotManager().getPlot(plotName);
            if (plot == null) {
                plugin.getPlotManager().clearPendingSignRegistration(playerId);
                return;
            }

            // Register sign
            Location signLoc = block.getLocation();
            plot.setSignLocation(signLoc);

            // Find attached block
            Block attached = getAttachedBlock(block);
            if (attached != null) {
                plot.setSignAttachedBlock(attached.getLocation());
            }

            plugin.getPlotManager().clearPendingSignRegistration(playerId);
            plugin.getPlotManager().save();
            plugin.getSignManager().updateSign(plot);

            player.sendMessage(plugin.getConfigManager().getMessage("sign-registered",
                    "{name}", plotName));
            player.playSound(player.getLocation(),
                    plugin.getConfigManager().getSound("sign-registered"), 1f, 1f);
            return;
        }

        // Check if player right-clicked a plot sign
        Plot plot = plugin.getSignManager().getPlotBySignLocation(block.getLocation());
        if (plot == null) return;

        event.setCancelled(true);

        // Open appropriate GUI
        if (!plot.isOwned()) {
            plugin.getBuyGUI().open(player, plot);
        } else {
            plugin.getPlotGUI().open(player, plot);
        }
    }

    private Block getAttachedBlock(Block signBlock) {
        if (signBlock.getBlockData() instanceof WallSign wallSign) {
            BlockFace facing = wallSign.getFacing();
            return signBlock.getRelative(facing.getOppositeFace());
        }
        // Floor sign - return block below
        return signBlock.getRelative(BlockFace.DOWN);
    }
}
