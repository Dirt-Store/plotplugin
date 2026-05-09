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

import java.lang.reflect.Method;

public class SignManager {

    private final PlotDirt plugin;

    // Detect if the new Sign API (1.20+: getSide / SignSide) is available
    private static final boolean NEW_SIGN_API;

    static {
        boolean newApi = false;
        try {
            Class.forName("org.bukkit.block.sign.Side");
            Sign.class.getMethod("getSide", Class.forName("org.bukkit.block.sign.Side"));
            newApi = true;
        } catch (Exception ignored) {}
        NEW_SIGN_API = newApi;
    }

    public SignManager(PlotDirt plugin) {
        this.plugin = plugin;
    }

    public void updateSign(Plot plot) {
        Location loc = plot.getSignLocation();
        if (loc == null) return;
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        ConfigManager cfg = plugin.getConfigManager();

        String line1;
        String line2 = cfg.getSignLine("line-price", "{price}", String.valueOf(cfg.getBuyCost()));
        String line3;
        String line4;

        boolean isGlobalWhitelist = plugin.getPlotManager().isGlobalWhitelist();

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
                String ownerName = Bukkit.getOfflinePlayer(plot.getOwner()).getName();
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
            default -> { // OWNED
                line1 = cfg.getSignLine("line-owned");
                String ownerName = Bukkit.getOfflinePlayer(plot.getOwner()).getName();
                line3 = cfg.getSignLine("line-owner", "{owner}", ownerName != null ? ownerName : "Unknown");
                line4 = cfg.getSignLine("line-time", "{time}", TimeUtil.format(plot.getRemainingMs()));
            }
        }

        if (NEW_SIGN_API) {
            setSignLinesNewApi(sign, line1, line2, line3, line4);
        } else {
            setSignLinesLegacy(sign, line1, line2, line3, line4);
        }

        sign.update(true, false);
    }

    /**
     * 1.20+ API: sign.getSide(Side.FRONT).line(n, component)
     */
    private void setSignLinesNewApi(Sign sign, String l1, String l2, String l3, String l4) {
        try {
            Class<?> sideClass = Class.forName("org.bukkit.block.sign.Side");
            Object frontSide = sideClass.getField("FRONT").get(null);
            Method getSide = Sign.class.getMethod("getSide", sideClass);
            Object signSide = getSide.invoke(sign, frontSide);
            Method lineSetter = signSide.getClass().getMethod("line", int.class, Component.class);
            lineSetter.invoke(signSide, 0, toComponent(l1));
            lineSetter.invoke(signSide, 1, toComponent(l2));
            lineSetter.invoke(signSide, 2, toComponent(l3));
            lineSetter.invoke(signSide, 3, toComponent(l4));
        } catch (Exception e) {
            setSignLinesLegacy(sign, l1, l2, l3, l4);
        }
    }

    /**
     * 1.19.x API: sign.line(n, component)
     */
    @SuppressWarnings("deprecation")
    private void setSignLinesLegacy(Sign sign, String l1, String l2, String l3, String l4) {
        sign.line(0, toComponent(l1));
        sign.line(1, toComponent(l2));
        sign.line(2, toComponent(l3));
        sign.line(3, toComponent(l4));
    }

    public void updateAllSigns() {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            updateSign(plot);
        }
    }

    private Component toComponent(String legacyText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacyText);
    }

    public Plot getPlotBySignLocation(Location loc) {
        for (Plot plot : plugin.getPlotManager().getAllPlots()) {
            Location sLoc = plot.getSignLocation();
            Location aLoc = plot.getSignAttachedBlock();
            if ((sLoc != null && sLoc.equals(loc)) || (aLoc != null && aLoc.equals(loc))) {
                return plot;
            }
        }
        return null;
    }

    public void scheduleSignRestore(Plot plot) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location loc = plot.getSignLocation();
            if (loc == null) return;
            updateSign(plot);
        }, 1L);
    }
}
