package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.HashMap;
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

        String line1;
        String line2 = cfg.getSignLine("line-price", "{price}", String.valueOf(cfg.getBuyCost()));
        String line3;
        String line4;

        boolean isGlobalWhitelist = plugin.getPlotManager().isGlobalWhitelist();

        // BUG FIX #2: read owner name from the in-memory cache — no filesystem I/O
        // on the main thread. cacheOwnerNameAsync() was already called when the
        // plot was purchased / transferred / loaded, so the name is almost always
        // available. If it isn't yet (first-tick race), fall back to "Unknown".
        String ownerName = null;
        if (plot.getOwner() != null) {
            ownerName = ownerNameCache.getOrDefault(plot.getOwner(), "Unknown");
        }

        switch (plot.getState()) {
            case AVAILABLE -> {
                line1 = cfg.getSignLine("line-available");
                line3 = cfg.getSignLine("line-none");
                line4 = cfg.getSignLine("line-none");
            }
            case PAUSED -> {
                line1 = isGlobalWhitelist
                        ? cfg.getSignLine("line-fixed")
                        : cfg.getSignLine("line-paused");
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
            default -> { // OWNED
                line1 = cfg.getSignLine("line-owned");
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
        }

        side.line(0, toComponent(line1));
        side.line(1, toComponent(line2));
        side.line(2, toComponent(line3));
        side.line(3, toComponent(line4));

        // FIX-E: force=false — we are only updating text, not block state.
        // force=true would send a full block refresh packet to every nearby
        // player on every sign update tick (every 20s × n_active_plots).
        // false is sufficient; the sign TileEntity update is still sent.
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
