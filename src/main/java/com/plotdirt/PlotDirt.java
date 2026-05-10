package com.plotdirt;

import com.plotdirt.command.PlotCommand;
import com.plotdirt.gui.BuyGUI;
import com.plotdirt.gui.PlotGUI;
import com.plotdirt.gui.PlotGUIHolder;
import com.plotdirt.listener.ChatInputListener;
import com.plotdirt.listener.GUIListener;
import com.plotdirt.listener.SignListener;
import com.plotdirt.manager.*;
import com.plotdirt.task.PlotTimerTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PlotDirt extends JavaPlugin {

    private ConfigManager configManager;
    private PlotManager plotManager;
    private WorldGuardManager worldGuardManager;
    private CurrencyManager currencyManager;
    private SignManager signManager;
    private PlotGUI plotGUI;
    private BuyGUI buyGUI;
    private GUIListener guiListener;
    private BukkitTask timerTask;

    @Override
    public void onEnable() {
        // Managers
        configManager = new ConfigManager(this);
        configManager.load();

        worldGuardManager = new WorldGuardManager(getLogger());
        // SignManager must be created before plotManager.load() because
        // load() calls signManager.rebuildIndex() to build the O(1) sign lookup.
        signManager = new SignManager(this);
        plotManager = new PlotManager(this);
        plotManager.load();

        currencyManager = new CurrencyManager(this);

        // GUI
        plotGUI = new PlotGUI(this);
        buyGUI = new BuyGUI(this);

        // Listeners
        guiListener = new GUIListener(this);
        getServer().getPluginManager().registerEvents(guiListener, this);
        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatInputListener(this, guiListener), this);

        // Commands
        PlotCommand plotCommand = new PlotCommand(this);
        var cmd = getCommand("plot");
        if (cmd != null) {
            cmd.setExecutor(plotCommand);
            cmd.setTabCompleter(plotCommand);
        }

        // Timer task: runs every 200 ticks (10 seconds).
        PlotTimerTask task = new PlotTimerTask(this);
        timerTask = task.runTaskTimer(this, 40L, 200L);

        // Initial sign update + WG sync (delayed so worlds are fully ready)
        getServer().getScheduler().runTaskLater(this, () -> {
            signManager.updateAllSigns();
            worldGuardManager.syncAllPlots(plotManager.getAllPlots());
            getLogger().info("WorldGuard regions synced with plot data.");
        }, 40L);

        getLogger().info("PlotDirt enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null && !timerTask.isCancelled()) {
            timerTask.cancel();
        }
        if (plotManager != null) {
            plotManager.save();
        }
        if (configManager != null) {
            configManager.close();
        }
        getLogger().info("PlotDirt disabled. Data saved.");
    }

    /**
     * Re-create GUI instances so updated config (titles, slots, materials,
     * prices) are picked up immediately after /plot reload without restart.
     * Also closes any open PlotDirt inventories so players aren't stuck on a
     * stale GUI.
     */
    public void reloadGUIs() {
        // Close any open PlotDirt GUIs so players reopen fresh ones
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof PlotGUIHolder) {
                p.closeInventory();
            }
        }
        plotGUI = new PlotGUI(this);
        buyGUI  = new BuyGUI(this);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public ConfigManager getConfigManager() { return configManager; }
    public PlotManager getPlotManager() { return plotManager; }
    public WorldGuardManager getWorldGuardManager() { return worldGuardManager; }
    public CurrencyManager getCurrencyManager() { return currencyManager; }
    public SignManager getSignManager() { return signManager; }
    public PlotGUI getPlotGUI() { return plotGUI; }
    public BuyGUI getBuyGUI() { return buyGUI; }
    public GUIListener getGUIListener() { return guiListener; }
}
