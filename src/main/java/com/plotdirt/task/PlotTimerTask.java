package com.plotdirt.task;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.UUID;

public class PlotTimerTask extends BukkitRunnable {

    private final PlotDirt plugin;

    private int signUpdateCounter = 0;
    private static final int SIGN_UPDATE_INTERVAL = 2;

    public PlotTimerTask(PlotDirt plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        List<Plot> expired = plugin.getPlotManager().getExpiredPlots();
        if (!expired.isEmpty()) {
            for (Plot plot : expired) {
                handleExpiry(plot);
            }
        }

        // BUG FIX #6: Only update signs when the timer is actually running (owner exists
        // and plot is not paused). Paused/available plots don't change between ticks,
        // so we skip the block-state write entirely for them — saving main-thread time
        // proportional to the number of idle plots.
        signUpdateCounter++;
        if (signUpdateCounter >= SIGN_UPDATE_INTERVAL) {
            signUpdateCounter = 0;
            for (Plot plot : plugin.getPlotManager().getAllPlots()) {
                // Only plots actively counting down need periodic refresh
                if (plot.isOwned() && !plot.isPaused()) {
                    plugin.getSignManager().updateSign(plot);
                }
            }
        }
    }

    private void handleExpiry(Plot plot) {
        // Full WG reset: remove all members AND clear all flags so the plot
        // is truly back to its default "for sale" (no-member-access) state.
        // FIX-A: use signLocation world — no getWorlds() scan
        org.bukkit.World plotWorld = (plot.getSignLocation() != null && plot.getSignLocation().getWorld() != null)
                ? plot.getSignLocation().getWorld() : null;
        if (plotWorld == null) {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (plugin.getWorldGuardManager().regionExists(w, plot.getName())) { plotWorld = w; break; }
            }
        }
        if (plotWorld != null) {
            plugin.getWorldGuardManager().resetPlotRegion(plotWorld, plot.getName());
        }

        UUID oldOwner = plot.getOwner();

        // Reset plot model to default "for sale" state
        plot.expire(); // clears owner, members, managers, timer, paused

        // FIX-D: remove from ownership count cache on expire
        plugin.getPlotManager().updateOwnershipCache(oldOwner, null);
        // Evict owner name from cache now that the plot is free
        plugin.getSignManager().evictNameCache(oldOwner);

        plugin.getPlotManager().savePlotAsync(plot);
        plugin.getSignManager().updateSign(plot);

        String msg = plugin.getConfigManager().getMessage("plot-expired", "{name}", plot.getName());
        Component component = LegacyComponentSerializer.legacySection().deserialize(msg);
        Bukkit.getServer().broadcast(component);

        Sound sound = plugin.getConfigManager().getSound("plot-expired");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }
}
