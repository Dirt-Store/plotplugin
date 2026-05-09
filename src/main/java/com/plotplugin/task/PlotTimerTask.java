package com.plotplugin.task;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class PlotTimerTask extends BukkitRunnable {

    private final PlotPlugin plugin;
    private int signUpdateCounter = 0;

    public PlotTimerTask(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // Check for expired plots every tick cycle
        List<Plot> expired = plugin.getPlotManager().getExpiredPlots();
        for (Plot plot : expired) {
            handleExpiry(plot);
        }

        // Update signs every 20 ticks (1 second)
        signUpdateCounter++;
        if (signUpdateCounter >= 20) {
            signUpdateCounter = 0;
            plugin.getSignManager().updateAllSigns();
        }
    }

    private void handleExpiry(Plot plot) {
        // Find the world for this plot's WG region
        // We'll try all worlds since we don't store region world in Plot
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            RegionManager rm = plugin.getWorldGuardManager().getRegionManager(world);
            if (rm != null && rm.hasRegion(plot.getName())) {
                plugin.getWorldGuardManager().clearMembers(world, plot.getName());
                break;
            }
        }

        plot.expire();
        plugin.getPlotManager().save();
        plugin.getSignManager().updateSign(plot);

        // Broadcast expiry
        String msg = plugin.getConfigManager().getMessage("plot-expired", "{name}", plot.getName());
        Bukkit.broadcast(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(msg));

        // Play expiry sound to all online players
        org.bukkit.Sound sound = plugin.getConfigManager().getSound("plot-expired");
        Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), sound, 1f, 1f));
    }
}
