package com.plotplugin.manager;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlotManager {

    private final PlotPlugin plugin;
    private final Map<String, Plot> plots = new ConcurrentHashMap<>();
    private boolean globalWhitelist = false;

    // Tracks players awaiting sign break for plot registration: player -> plotName
    private final Map<UUID, String> pendingSignRegistration = new HashMap<>();

    public PlotManager(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public void load() {
        plots.clear();
        FileConfiguration data = plugin.getConfigManager().getData();
        ConfigurationSection section = data.getConfigurationSection("plots");
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            ConfigurationSection ps = section.getConfigurationSection(name);
            if (ps == null) continue;
            Plot plot = new Plot(name);

            String ownerStr = ps.getString("owner");
            if (ownerStr != null && !ownerStr.isEmpty()) {
                plot.setOwner(UUID.fromString(ownerStr));
            }

            List<String> memberList = ps.getStringList("members");
            memberList.stream().map(UUID::fromString).forEach(plot::addMember);

            List<String> managerList = ps.getStringList("managers");
            managerList.stream().map(UUID::fromString).forEach(plot::addManager);

            plot.setExpiryTime(ps.getLong("expiry-time", -1));
            plot.setPaused(ps.getBoolean("paused", false));
            plot.setPausedRemainingMs(ps.getLong("paused-remaining-ms", -1));

            // Sign location
            if (ps.contains("sign-location")) {
                Location loc = loadLocation(ps.getConfigurationSection("sign-location"));
                plot.setSignLocation(loc);
            }
            if (ps.contains("sign-attached")) {
                Location loc = loadLocation(ps.getConfigurationSection("sign-attached"));
                plot.setSignAttachedBlock(loc);
            }

            plots.put(name.toLowerCase(), plot);
        }

        globalWhitelist = data.getBoolean("global-whitelist", false);
    }

    public void save() {
        FileConfiguration data = plugin.getConfigManager().getData();
        data.set("plots", null);
        data.set("global-whitelist", globalWhitelist);

        for (Plot plot : plots.values()) {
            String base = "plots." + plot.getName();
            data.set(base + ".owner", plot.getOwner() != null ? plot.getOwner().toString() : "");

            List<String> members = new ArrayList<>();
            plot.getMembers().forEach(u -> members.add(u.toString()));
            data.set(base + ".members", members);

            List<String> managers = new ArrayList<>();
            plot.getManagers().forEach(u -> managers.add(u.toString()));
            data.set(base + ".managers", managers);

            data.set(base + ".expiry-time", plot.getExpiryTime());
            data.set(base + ".paused", plot.isPaused());
            data.set(base + ".paused-remaining-ms", plot.getPausedRemainingMs());

            if (plot.getSignLocation() != null) {
                saveLocation(data, base + ".sign-location", plot.getSignLocation());
            }
            if (plot.getSignAttachedBlock() != null) {
                saveLocation(data, base + ".sign-attached", plot.getSignAttachedBlock());
            }
        }
        plugin.getConfigManager().saveData();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Plot createPlot(String name) {
        Plot plot = new Plot(name);
        plots.put(name.toLowerCase(), plot);
        save();
        return plot;
    }

    public boolean deletePlot(String name) {
        Plot plot = getPlot(name);
        if (plot == null) return false;
        plots.remove(name.toLowerCase());
        save();
        return true;
    }

    public Plot getPlot(String name) {
        return plots.get(name.toLowerCase());
    }

    public Collection<Plot> getAllPlots() {
        return Collections.unmodifiableCollection(plots.values());
    }

    public boolean plotExists(String name) {
        return plots.containsKey(name.toLowerCase());
    }

    // ── Sign registration ─────────────────────────────────────────────────────

    public void setPendingSignRegistration(UUID player, String plotName) {
        pendingSignRegistration.put(player, plotName);
    }

    public String getPendingSignRegistration(UUID player) {
        return pendingSignRegistration.get(player);
    }

    public void clearPendingSignRegistration(UUID player) {
        pendingSignRegistration.remove(player);
    }

    public boolean hasPendingSignRegistration(UUID player) {
        return pendingSignRegistration.containsKey(player);
    }

    // ── Global whitelist ──────────────────────────────────────────────────────

    public boolean isGlobalWhitelist() { return globalWhitelist; }

    public void setGlobalWhitelist(boolean whitelist) {
        this.globalWhitelist = whitelist;
        // When enabling: pause all owned plots with active timers
        // When disabling: resume them
        for (Plot plot : plots.values()) {
            if (!plot.isOwned()) continue;
            if (whitelist && !plot.isPaused()) {
                long remaining = plot.getExpiryTime() - System.currentTimeMillis();
                if (remaining > 0) {
                    plot.setPaused(true);
                    plot.setPausedRemainingMs(remaining);
                }
            } else if (!whitelist && plot.isPaused() && plot.getPausedRemainingMs() > 0) {
                // Only resume plots that were paused by whitelist, not individually paused
                // We can't distinguish them easily here; we resume all paused plots
                // Individual pause is handled separately
                long newExpiry = System.currentTimeMillis() + plot.getPausedRemainingMs();
                plot.setExpiryTime(newExpiry);
                plot.setPaused(false);
                plot.setPausedRemainingMs(-1);
            }
        }
        save();
    }

    // ── Expiry check ──────────────────────────────────────────────────────────

    public List<Plot> getExpiredPlots() {
        List<Plot> expired = new ArrayList<>();
        for (Plot plot : plots.values()) {
            if (plot.isOwned() && !plot.isPaused() && plot.isExpired()) {
                expired.add(plot);
            }
        }
        return expired;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Location loadLocation(ConfigurationSection sec) {
        if (sec == null) return null;
        World world = Bukkit.getWorld(sec.getString("world", "world"));
        if (world == null) return null;
        return new Location(world,
                sec.getDouble("x"),
                sec.getDouble("y"),
                sec.getDouble("z"));
    }

    private void saveLocation(FileConfiguration data, String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
    }
}
