package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.storage.SQLiteStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class PlotManager {

    private final PlotDirt plugin;
    private final Map<String, Plot> plots = new ConcurrentHashMap<>();
    private boolean globalWhitelist = false;

    // Tracks players awaiting sign break for plot registration: player -> plotName
    private final Map<UUID, String> pendingSignRegistration = new ConcurrentHashMap<>();

    public PlotManager(PlotDirt plugin) {
        this.plugin = plugin;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public void load() {
        plots.clear();
        SQLiteStorage storage = plugin.getConfigManager().getStorage();
        plots.putAll(storage.loadAll());
        globalWhitelist = storage.loadGlobalWhitelist();
        // Rebuild the O(1) sign lookup index after load
        plugin.getSignManager().rebuildIndex();
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
        // Remove from sign index before removing from map
        plugin.getSignManager().deindexPlot(plot);
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

    // ── Purchase lock ─────────────────────────────────────────────────────────

    /**
     * Players whose purchase is currently being processed.
     * Prevents duplicate purchases when a player clicks the buy button
     * multiple times before the first transaction completes.
     */
    private final Set<UUID> purchaseLock = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to acquire the purchase lock for the given player.
     * Returns {@code true} if the lock was acquired (safe to proceed),
     * or {@code false} if another purchase is already in flight.
     */
    public boolean tryAcquirePurchaseLock(UUID playerUuid) {
        return purchaseLock.add(playerUuid);
    }

    /** Releases the purchase lock so the player can buy again in the future. */
    public void releasePurchaseLock(UUID playerUuid) {
        purchaseLock.remove(playerUuid);
    }

    // ── Ownership helpers ─────────────────────────────────────────────────────

    /** Returns how many plots this player currently owns. */
    public int countOwnedPlots(UUID playerUuid) {
        int count = 0;
        for (Plot p : plots.values()) {
            if (playerUuid.equals(p.getOwner())) count++;
        }
        return count;
    }

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
