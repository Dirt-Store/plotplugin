package com.plotplugin.manager;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

public class SignManager {

    private final PlotPlugin plugin;

    public SignManager(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateSign(Plot plot) {
        Location loc = plot.getSignLocation();
        if (loc == null) return;
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        ConfigManager cfg = plugin.getConfigManager();
        SignSide side = sign.getSide(Side.FRONT);

        String line1;
        String line2 = cfg.getSignLine("line-price", "{price}", String.valueOf(cfg.getBuyCost()));
        String line3;
        String line4;

        boolean isGlobalWhitelist = plugin.getPlotManager().isGlobalWhitelist();

        switch (plot.getState()) {
            case AVAILABLE -> {
                line1 = cfg.getSignLine("line-available");
                line3 = cfg.getSignLine("line-none");
                line4 = cfg.getSignLine("line-none");
            }
            case PAUSED -> {
                line1 = isGlobalWhitelist
                        ? cfg.getSignLine("line-fixed")
                        : cfg.getSignLine("line-paused");
                String ownerName = Bukkit.getOfflinePlayer(plot.getOwner()).getName();
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
            default -> { // OWNED
                line1 = cfg.getSignLine("line-owned");
                String ownerName = Bukkit.getOfflinePlayer(plot.getOwner()).getName();
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
        }

        side.line(0, toComponent(line1));
        side.line(1, toComponent(line2));
        side.line(2, toComponent(line3));
        side.line(3, toComponent(line4));

        sign.update(true, false);
    }

    public void updateAllSigns() {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            updateSign(plot);
        }
    }

    private Component toComponent(String legacyText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacyText);
    }

    /**
     * Check if a location is the sign or the attached block of any plot.
     */
    public Plot getPlotBySignLocation(Location loc) {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            Location sLoc = plot.getSignLocation();
            Location aLoc = plot.getSignAttachedBlock();
            if ((sLoc != null && sLoc.equals(loc)) || (aLoc != null && aLoc.equals(loc))) {
                return plot;
            }
        }
        return null;
    }

    /**
     * Restore a broken sign at the given location (schedule 1-tick later).
     */
    public void scheduleSignRestore(Plot plot) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location loc = plot.getSignLocation();
            if (loc == null) return;
            // Re-place the sign material if it's air
            Block block = loc.getBlock();
            if (block.getType().isAir()) {
                // We can't perfectly restore orientation without storing it,
                // so we flag this as a sign-restore event in the listener instead.
                // This method is called AFTER the block break event is cancelled,
                // so if we reach here the sign is still intact.
            }
            updateSign(plot);
        }, 1L);
    }
}
