package com.plotplugin.manager;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import com.plotplugin.storage.SQLiteStorage;

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
        SQLiteStorage storage = plugin.getConfigManager().getStorage();
        plots.putAll(storage.loadAll());
        globalWhitelist = storage.loadGlobalWhitelist();
    }

    /** Full save — all plots + global-whitelist. Used on shutdown / bulk changes. */
    public void save() {
        plugin.getConfigManager().getStorage().saveAll(plots.values(), globalWhitelist);
    }

    /** Fast single-plot save — use this after any change to just one plot. */
    public void savePlot(Plot plot) {
        plugin.getConfigManager().getStorage().savePlot(plot);
    }

    /** Async version — use this in hot paths (buy, addmember, etc.) to avoid main-thread I/O stalls. */
    public void savePlotAsync(Plot plot) {
        plugin.getConfigManager().getStorage().savePlotAsync(plot, null);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Plot createPlot(String name) {
        Plot plot = new Plot(name);
        plots.put(name.toLowerCase(), plot);
        savePlot(plot);
        return plot;
    }

    public boolean deletePlot(String name) {
        Plot plot = getPlot(name);
        if (plot == null) return false;
        plots.remove(name.toLowerCase());
        plugin.getConfigManager().getStorage().deletePlot(name);
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
        for (Plot plot : plots.values()) {
            if (!plot.isOwned()) continue;
            if (whitelist && !plot.isPaused()) {
                long remaining = plot.getExpiryTime() - System.currentTimeMillis();
                if (remaining > 0) {
                    plot.setPaused(true);
                    plot.setPausedRemainingMs(remaining);
                }
            } else if (!whitelist && plot.isPaused() && plot.getPausedRemainingMs() > 0) {
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
}
