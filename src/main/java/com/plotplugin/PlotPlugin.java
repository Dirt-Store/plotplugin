package com.plotplugin;

import com.plotplugin.command.PlotCommand;
import com.plotplugin.gui.BuyGUI;
import com.plotplugin.gui.PlotGUI;
import com.plotplugin.listener.ChatInputListener;
import com.plotplugin.listener.GUIListener;
import com.plotplugin.listener.SignListener;
import com.plotplugin.manager.*;
import com.plotplugin.task.PlotTimerTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PlotPlugin extends JavaPlugin {

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
        plotManager = new PlotManager(this);
        plotManager.load();

        currencyManager = new CurrencyManager(this);
        signManager = new SignManager(this);

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

        // Timer task: runs every tick
        PlotTimerTask task = new PlotTimerTask(this);
        timerTask = task.runTaskTimer(this, 20L, 1L);

        // Initial sign update
        getServer().getScheduler().runTaskLater(this, signManager::updateAllSigns, 40L);

        getLogger().info("PlotPlugin enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null && !timerTask.isCancelled()) {
            timerTask.cancel();
        }
        if (plotManager != null) {
            plotManager.save();
        }
        getLogger().info("PlotPlugin disabled. Data saved.");
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
