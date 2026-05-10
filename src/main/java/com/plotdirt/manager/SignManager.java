package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SignManager {

    private final PlotDirt plugin;

    /**
     * Fast O(1) reverse lookup: Location → Plot.
     * Populated on load and kept in sync whenever a sign is registered or a plot is deleted.
     * Avoids the O(n) linear scan in getPlotBySignLocation() that fires on every right-click.
     */
    private final Map<Location, Plot> signIndex = new HashMap<>();

    /**
     * BUG FIX #2: Cache owner display names so updateSign() never calls
     * Bukkit.getOfflinePlayer() on the main thread.
     * Key = owner UUID, Value = cached name (or "Unknown" fallback).
     * Populated async on purchase/transfer/load; invalidated on plot expire.
     */
    private final Map<UUID, String> ownerNameCache = new ConcurrentHashMap<>();

    public SignManager(PlotDirt plugin) {
        this.plugin = plugin;
    }

    // ── Name cache ────────────────────────────────────────────────────────────

    /**
     * Populate the owner-name cache for the given UUID asynchronously.
     * Safe to call from the main thread; the cache lookup in updateSign()
     * never touches the filesystem.
     */
    public void cacheOwnerNameAsync(UUID uuid) {
        if (uuid == null || ownerNameCache.containsKey(uuid)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // getOfflinePlayer() may hit the filesystem — intentionally off main thread
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            ownerNameCache.put(uuid, name != null ? name : "Unknown");
        });
    }

    /** Pre-warm the cache for all currently owned plots (call after load). */
    public void warmNameCache() {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            if (plot.getOwner() != null) cacheOwnerNameAsync(plot.getOwner());
        }
    }

    /** Evict a UUID from the name cache (call when a plot expires or is deleted). */
    public void evictNameCache(UUID uuid) {
        if (uuid != null) ownerNameCache.remove(uuid);
    }

    /**
     * Returns the cached display name for the given UUID, or the UUID string as
     * a fallback if the async warm hasn't completed yet. Never touches the filesystem.
     */
    public String getCachedName(UUID uuid) {
        if (uuid == null) return "Unknown";
        return ownerNameCache.getOrDefault(uuid, uuid.toString());
    }

    // ── Index management ──────────────────────────────────────────────────────

    /**
     * Rebuild the sign index from scratch. Call once after plots are loaded.
     */
    public void rebuildIndex() {
        signIndex.clear();
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            indexPlot(plot);
        }
    }

    /** Add a plot's sign + attached-block locations to the index. */
    public void indexPlot(Plot plot) {
        if (plot.getSignLocation() != null) {
            signIndex.put(plot.getSignLocation(), plot);
        }
        if (plot.getSignAttachedBlock() != null) {
            signIndex.put(plot.getSignAttachedBlock(), plot);
        }
    }

    /** Remove a plot's locations from the index (call before deleting). */
    public void deindexPlot(Plot plot) {
        if (plot.getSignLocation() != null) {
            signIndex.remove(plot.getSignLocation());
        }
        if (plot.getSignAttachedBlock() != null) {
            signIndex.remove(plot.getSignAttachedBlock());
        }
    }

    // ── Sign update ───────────────────────────────────────────────────────────

    public void updateSign(Plot plot) {
        Location loc = plot.getSignLocation();
        if (loc == null) return;
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        ConfigManager cfg = plugin.getConfigManager();
        SignSide side = sign.getSide(Side.FRONT);

        // Compute shared placeholders
        int count = plot.getTotalPlayerCount();
        int limit = cfg.getEffectiveMemberLimit(plot);
        String countStr = String.valueOf(count);
        String limitStr = String.valueOf(limit);
        String priceStr = String.valueOf(cfg.getBuyCost());

        boolean isGlobalWhitelist = plugin.getPlotManager().isGlobalWhitelist();

        // BUG FIX #2: read owner name from cache — no filesystem I/O on main thread
        String ownerName = "Unknown";
        if (plot.getOwner() != null) {
            ownerName = ownerNameCache.getOrDefault(plot.getOwner(), "Unknown");
        }

        String section = switch (plot.getState()) {
            case AVAILABLE -> "available";
            case PAUSED    -> isGlobalWhitelist ? "for-sale" : "owned";
            default        -> "owned"; // OWNED
        };

        List<String> lines = cfg.getSignLines(section,
                "%count%", countStr,
                "%limit%", limitStr,
                "%price%", priceStr,
                "%owner%", ownerName,
                "%time%",  com.plotdirt.util.TimeUtil.format(plot.getRemainingMs()));

        for (int i = 0; i < 4; i++) {
            side.line(i, toComponent(lines.get(i)));
        }

        // FIX-E: force=false — text-only update, no full block refresh packet
        sign.update(false, false);
    }

    public void updateAllSigns() {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            updateSign(plot);
        }
    }

    private Component toComponent(String legacyText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacyText);
    }

    /**
     * O(1) lookup by Location. Replaces the old O(n) linear scan.
     */
    public Plot getPlotBySignLocation(Location loc) {
        return signIndex.get(loc);
    }

    public void scheduleSignRestore(Plot plot) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location loc = plot.getSignLocation();
            if (loc == null) return;
            updateSign(plot);
        }, 1L);
    }
}
