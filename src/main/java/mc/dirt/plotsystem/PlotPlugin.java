package mc.dirt.plotsystem;

import mc.dirt.plotsystem.command.PlotCommand;
import mc.dirt.plotsystem.config.ConfigManager;
import mc.dirt.plotsystem.gui.BuyGui;
import mc.dirt.plotsystem.gui.ManageGui;
import mc.dirt.plotsystem.listener.ChatListener;
import mc.dirt.plotsystem.listener.InventoryListener;
import mc.dirt.plotsystem.listener.SignListener;
import mc.dirt.plotsystem.manager.PlotManager;
import mc.dirt.plotsystem.task.ExpiryTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class PlotPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PlotManager plotManager;
    private BuyGui buyGui;
    private ManageGui manageGui;
    private ExpiryTask expiryTask;
    private InventoryListener inventoryListener;

    @Override
    public void onEnable() {
        try {
            configManager    = new ConfigManager(this);
            plotManager      = new PlotManager(this);
            buyGui           = new BuyGui(this);
            manageGui        = new ManageGui(this);
            inventoryListener = new InventoryListener(this);

            getServer().getPluginManager().registerEvents(new SignListener(this), this);
            getServer().getPluginManager().registerEvents(inventoryListener, this);
            getServer().getPluginManager().registerEvents(new ChatListener(this, inventoryListener), this);

            Objects.requireNonNull(getCommand("plot")).setExecutor(new PlotCommand(this));

            expiryTask = new ExpiryTask(this);
            expiryTask.start();

            getLogger().info("[PlotSystem] Enabled successfully.");
        } catch (Exception e) {
            getLogger().severe("[PlotSystem] Failed to enable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (expiryTask  != null) expiryTask.stop();
            if (plotManager != null) plotManager.closeStorage();
            getLogger().info("[PlotSystem] Disabled.");
        } catch (Exception e) {
            getLogger().severe("[PlotSystem] Error during disable: " + e.getMessage());
        }
    }

    public ConfigManager getConfigManager()          { return configManager; }
    public PlotManager   getPlotManager()            { return plotManager; }
    public BuyGui        getBuyGui()                 { return buyGui; }
    public ManageGui     getManageGui()              { return manageGui; }
    public InventoryListener getInventoryListener()  { return inventoryListener; }
}
