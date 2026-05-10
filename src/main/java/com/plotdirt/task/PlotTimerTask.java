package com.plotdirt.task;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class PlotTimerTask extends BukkitRunnable {

    private final PlotDirt plugin;

    // Task runs every 200 ticks (10 seconds) — expiry precision of 10s is fine,
    // no reason to burn a main-thread tick every second just to check timestamps.
    private int signUpdateCounter = 0;
    // Signs refresh every 2 cycles = every 20 seconds (unchanged for players).
    private static final int SIGN_UPDATE_INTERVAL = 2;

    public PlotTimerTask(PlotDirt plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // Collect expired plots on the main thread (safe ConcurrentHashMap read)
        List<Plot> expired = plugin.getPlotManager().getExpiredPlots();
        if (!expired.isEmpty()) {
            for (Plot plot : expired) {
                handleExpiry(plot);
            }
        }

        // Update signs every ~20 seconds
        signUpdateCounter++;
        if (signUpdateCounter >= SIGN_UPDATE_INTERVAL) {
            signUpdateCounter = 0;
            // Run sign updates async-friendly: schedule on main thread but only
            // do the block state writes (already on main thread here, this is fine).
            plugin.getSignManager().updateAllSigns();
        }
    }

    private void handleExpiry(Plot plot) {
        // Find the world that has the WG region — stop at first match
        for (World world : Bukkit.getWorlds()) {
            RegionManager rm = plugin.getWorldGuardManager().getRegionManager(world);
            if (rm != null && rm.hasRegion(plot.getName())) {
                plugin.getWorldGuardManager().clearMembers(world, plot.getName());
                break;
            }
        }

        plot.expire();

        // Persist asynchronously — no reason to block the main thread
        plugin.getPlotManager().savePlotAsync(plot);
        plugin.getSignManager().updateSign(plot);

        // Build message once, broadcast once
        String msg = plugin.getConfigManager().getMessage("plot-expired", "{name}", plot.getName());
        net.kyori.adventure.text.Component component =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection().deserialize(msg);
        Bukkit.getServer().broadcast(component);

        // Play sound: only to online players, get the list once
        Sound sound = plugin.getConfigManager().getSound("plot-expired");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }
}
