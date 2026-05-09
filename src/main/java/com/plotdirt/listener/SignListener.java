package com.plotdirt.listener;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
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

    private final PlotDirt plugin;

    public SignListener(PlotDirt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block broken = event.getBlock();
        Location loc = broken.getLocation();

        UUID playerId = player.getUniqueId();

        // If player is in pending sign registration mode, let them break any sign
        // to register it — but prevent breaking already-registered plot signs.
        if (plugin.getPlotManager().hasPendingSignRegistration(playerId)) {
            // Only act if it's a sign block
            if (!Tag.SIGNS.isTagged(broken.getType())) return;

            // Check if this sign is already registered to a different plot
            Plot alreadyRegistered = plugin.getSignManager().getPlotBySignLocation(loc);
            if (alreadyRegistered != null) {
                // This sign belongs to another plot — deny and inform
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("sign-break-denied"));
                return;
            }
            // Not a registered sign — allow the break; the interact handler handles registration
            return;
        }

        // Outside pending mode: block breaking of any registered sign or its attached block
        Plot plot = plugin.getSignManager().getPlotBySignLocation(loc);
        if (plot == null) return;

        event.setCancelled(true);
        player.sendMessage(plugin.getConfigManager().getMessage("sign-break-denied"));
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

        // Pending sign registration: right-click registers the sign
        if (plugin.getPlotManager().hasPendingSignRegistration(playerId)) {
            event.setCancelled(true);
            String plotName = plugin.getPlotManager().getPendingSignRegistration(playerId);
            Plot plot = plugin.getPlotManager().getPlot(plotName);
            if (plot == null) {
                plugin.getPlotManager().clearPendingSignRegistration(playerId);
                return;
            }

            // Don't allow registering a sign already used by another plot
            Plot existing = plugin.getSignManager().getPlotBySignLocation(block.getLocation());
            if (existing != null && !existing.getName().equalsIgnoreCase(plotName)) {
                player.sendMessage(plugin.getConfigManager().getMessage("sign-break-denied"));
                return;
            }

            Location signLoc = block.getLocation();
            plot.setSignLocation(signLoc);

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

        // Normal right-click on a plot sign — open GUI
        Plot plot = plugin.getSignManager().getPlotBySignLocation(block.getLocation());
        if (plot == null) return;

        event.setCancelled(true);

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
        return signBlock.getRelative(BlockFace.DOWN);
    }
}
