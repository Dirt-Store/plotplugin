package mc.dirt.plotsystem.task;

import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.manager.PlotManager;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Runs once every in-game day (20 minutes real-time) to decrement plot days
 * and expire plots that hit 0.
 *
 * For a real server you may want to persist last-tick time and compute elapsed
 * real days on startup — this simple version ticks every 24000 server ticks.
 */
public class ExpiryTask implements Runnable {

    // 24000 ticks = 20 real minutes = 1 Minecraft day
    private static final long TICK_INTERVAL = 24000L;

    private final PlotPlugin plugin;
    private final Logger log;
    private BukkitTask task;

    public ExpiryTask(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, TICK_INTERVAL, TICK_INTERVAL);
        log.info("[PlotSystem] ExpiryTask started (interval: " + TICK_INTERVAL + " ticks).");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        PlotManager pm = plugin.getPlotManager();
        List<PlotData> toExpire = new ArrayList<>();

        for (PlotData data : pm.getAllPlots()) {
            if (!data.isOwned()) continue;
            int days = data.getDaysRemaining() - 1;
            data.setDaysRemaining(days);
            pm.savePlot(data);
            pm.updateSign(data);

            if (days <= 0) {
                toExpire.add(data);
            }
        }

        for (PlotData data : toExpire) {
            try {
                String name = data.getRegionName();
                pm.expirePlot(data);
                // Broadcast to online players in the world
                String msg = plugin.getConfigManager().msg("plot-expired", "{name}", name);
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(msg));
                log.info("[PlotSystem] Plot '" + name + "' expired.");
            } catch (Exception e) {
                log.severe("[PlotSystem] Failed to expire plot '" + data.getRegionName() + "': " + e.getMessage());
            }
        }
    }
}
