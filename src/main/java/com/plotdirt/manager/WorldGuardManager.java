package com.plotdirt.manager;

import com.plotdirt.model.Plot;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * WorldGuard integration.
 *
 * FLAG STRATEGY:
 *   Each flag (BUILD / CHEST_ACCESS / USE / INTERACT) is set to:
 *       StateFlag  = ALLOW
 *       GroupFlag  = MEMBERS
 *   Equivalent to: /rg flag <name> build allow -g members
 *
 *   This means only WG members (and owners) can build/use.
 *   Non-members are denied automatically because the ALLOW only applies to MEMBERS.
 *
 * On plot expiry:
 *   All members are cleared — flags stay, so nobody can build.
 *
 * NOTE: Plot owner is added as WG *member* (not owner), same as regular members/managers.
 */
public class WorldGuardManager {

    private final Logger logger;

    public WorldGuardManager(Logger logger) {
        this.logger = logger;
    }

    public boolean regionExists(World world, String name) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return false;
        return rm.hasRegion(name);
    }

    public RegionManager getRegionManager(World world) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    // ── Grant / revoke ────────────────────────────────────────────────────────

    /**
     * Add a player to the WG region as a member so they can build/use the plot.
     * Also ensures the region-wide flags are set to ALLOW for MEMBERS.
     *
     * Call this when:
     *  - a player buys a plot (owner) — owner is added as WG member, not WG owner
     *  - a member is added  (/plot addmember)
     *  - a manager is added (/plot addmanager)
     *  - ownership is transferred (/plot transfer)
     */
    public void grantPlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().addPlayer(playerUuid);
        ensureAllowMemberFlags(region);
    }

    /**
     * Remove a player's membership from the WG region.
     * If the region has no members left after removal, flags are cleared too
     * so the region returns to its default vanilla state (no stale ALLOW flags).
     */
    public void revokePlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().removePlayer(playerUuid);
        region.getOwners().removePlayer(playerUuid); // also clean legacy WG owners if any

        // If no members remain, remove the ALLOW flags entirely.
        // Leaving ALLOW flags on an empty member list is effectively a no-op,
        // but removing them keeps the region in a clean default state.
        if (region.getMembers().size() == 0 && region.getOwners().size() == 0) {
            region.setFlag(Flags.BUILD, null);
            region.setFlag(Flags.BUILD.getRegionGroupFlag(), null);
            region.setFlag(Flags.CHEST_ACCESS, null);
            region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), null);
            region.setFlag(Flags.USE, null);
            region.setFlag(Flags.USE.getRegionGroupFlag(), null);
            region.setFlag(Flags.INTERACT, null);
            region.setFlag(Flags.INTERACT.getRegionGroupFlag(), null);
        }
    }

    /**
     * Full plot reset — removes ALL members/owners AND removes the ALLOW-MEMBERS
     * flags so the region returns to its vanilla default state ("for sale").
     *
     * Call this when:
     *  - A plot expires (timer runs out)
     *  - A plot is deleted via /plot delete
     */
    public void resetPlotRegion(World world, String regionName) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().clear();
        region.getOwners().clear();

        // Remove the flags entirely so the region is in its default state.
        region.setFlag(Flags.BUILD, null);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(), null);
        region.setFlag(Flags.CHEST_ACCESS, null);
        region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), null);
        region.setFlag(Flags.USE, null);
        region.setFlag(Flags.USE.getRegionGroupFlag(), null);
        region.setFlag(Flags.INTERACT, null);
        region.setFlag(Flags.INTERACT.getRegionGroupFlag(), null);
    }

    /**
     * Sync all WG members/owners of a region to exactly match the Plot model.
     * Useful when multiple members/managers are set at once or on plugin start.
     */
    public void syncPlot(World world, Plot plot) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(plot.getName());
        if (region == null) return;

        region.getMembers().clear();
        region.getOwners().clear();

        if (plot.getOwner() != null) {
            region.getMembers().addPlayer(plot.getOwner());
        }
        for (UUID member : plot.getMembers()) {
            region.getMembers().addPlayer(member);
        }
        for (UUID manager : plot.getManagers()) {
            region.getMembers().addPlayer(manager);
        }

        // Only set ALLOW-MEMBERS flags when someone actually owns/is on this plot.
        // An available (unowned) plot should have no flags so it behaves like a
        // vanilla region — setting ALLOW on an empty member list is harmless but
        // could mask config issues, and a clean state is always safer.
        if (region.getMembers().size() > 0) {
            ensureAllowMemberFlags(region);
        } else {
            // Clear any stale flags left from a previous owner
            region.setFlag(Flags.BUILD, null);
            region.setFlag(Flags.BUILD.getRegionGroupFlag(), null);
            region.setFlag(Flags.CHEST_ACCESS, null);
            region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), null);
            region.setFlag(Flags.USE, null);
            region.setFlag(Flags.USE.getRegionGroupFlag(), null);
            region.setFlag(Flags.INTERACT, null);
            region.setFlag(Flags.INTERACT.getRegionGroupFlag(), null);
        }
    }

    /**
     * Re-sync every plot's WG region to match the plugin's data on startup.
     * Fixes desync after a reload (WG reads regions.yml fresh but plugin DB has the members).
     * Uses signLocation world (O(1)) before falling back to a world scan.
     * Call this after plots are loaded and worlds are available.
     */
    public void syncAllPlots(Collection<Plot> plots) {
        for (Plot plot : plots) {
            World world = null;
            // Fast path: signLocation already holds the world reference
            if (plot.getSignLocation() != null && plot.getSignLocation().getWorld() != null) {
                world = plot.getSignLocation().getWorld();
            } else {
                // Fallback: scan worlds (only for plots without a registered sign)
                for (World w : Bukkit.getWorlds()) {
                    if (regionExists(w, plot.getName())) { world = w; break; }
                }
            }
            if (world != null) syncPlot(world, plot);
        }
    }

    // ── Flag helpers ──────────────────────────────────────────────────────────

    /**
     * Sets the correct flags on the region:
     *   ALLOW for MEMBERS on all important flags.
     *   Equivalent to: /rg flag <name> build allow -g members
     *
     * Non-members cannot build because no ALLOW is granted to them.
     */
    private void ensureAllowMemberFlags(ProtectedRegion region) {
        applyAllowMembers(region, Flags.BUILD);
        applyAllowMembers(region, Flags.CHEST_ACCESS);
        applyAllowMembers(region, Flags.USE);
        applyAllowMembers(region, Flags.INTERACT);
    }

    /** Applies ALLOW for MEMBERS group on a given flag. */
    private void applyAllowMembers(ProtectedRegion region, com.sk89q.worldguard.protection.flags.StateFlag flag) {
        region.setFlag(flag, StateFlag.State.ALLOW);
        region.setFlag(flag.getRegionGroupFlag(), RegionGroup.MEMBERS);
    }
}
