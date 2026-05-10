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

        signUpdateCounter++;
        if (signUpdateCounter >= SIGN_UPDATE_INTERVAL) {
            signUpdateCounter = 0;
            plugin.getSignManager().updateAllSigns();
        }
    }

    private void handleExpiry(Plot plot) {
        // Full WG reset: remove all members AND clear all flags so the plot
        // is truly back to its default "for sale" (no-member-access) state.
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getWorldGuardManager().regionExists(world, plot.getName())) {
                plugin.getWorldGuardManager().resetPlotRegion(world, plot.getName());
                break;
            }
        }

        // Reset plot model to default "for sale" state
        plot.expire(); // clears owner, members, managers, timer, paused

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
