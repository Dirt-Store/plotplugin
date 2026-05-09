package com.plotplugin.manager;

import com.plotplugin.model.Plot;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * WorldGuard integration.
 *
 * FLAG STRATEGY — الطريقة الصحيحة:
 *   • كل فلاق (BUILD / CHEST_ACCESS / USE / INTERACT) يتحط بقيمتين:
 *       1. StateFlag  = DENY   (القيمة الافتراضية للكل)
 *       2. GroupFlag  = NON_MEMBERS (يطبق الـ DENY على غير الأعضاء فقط)
 *   • الأعضاء (members + owners) يتجاوزون الفلاق تلقائياً لأن WG
 *     يطبق الـ DENY على NON_MEMBERS فقط — مش على الكل.
 *   • هذا يعادل: /rg flag <name> build deny -g non_members
 *
 * عند انتهاء البلوت:
 *   • يتم مسح كل الأعضاء والملاك — الفلاقات تبقى، يعني ما أحد يقدر يبني.
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
     * Also ensures the region-wide flags are set to DENY (so non-members stay blocked).
     *
     * Call this when:
     *  - a player buys a plot (owner)
     *  - a member is added  (/plot addmember)
     *  - a manager is added (/plot addmanager)
     */
    public void grantPlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        // Add as WG member — WG automatically lets members bypass the DENY flag
        region.getMembers().addPlayer(playerUuid);

        // Ensure the non-member default is DENY (set once; harmless if already set)
        ensureDenyFlags(region);
    }

    /**
     * Add a player as WG *owner* (used for the plot owner specifically).
     * Owners in WG have full control over the region, matching the plot-owner role.
     */
    public void grantOwnerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getOwners().addPlayer(playerUuid);
        ensureDenyFlags(region);
    }

    /**
     * Remove a player's membership from the WG region.
     */
    public void revokePlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().removePlayer(playerUuid);
        region.getOwners().removePlayer(playerUuid);
        // DENY flags stay — no one else gets in
    }

    /**
     * Remove all members/owners and keep DENY flags (called on plot expiry).
     */
    public void clearMembers(World world, String regionName) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().clear();
        region.getOwners().clear();
        ensureDenyFlags(region);
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
            region.getOwners().addPlayer(plot.getOwner());
        }
        for (UUID member : plot.getMembers()) {
            region.getMembers().addPlayer(member);
        }
        for (UUID manager : plot.getManagers()) {
            // Managers get WG member status (same build access, plugin controls the manage permission)
            region.getMembers().addPlayer(manager);
        }

        ensureDenyFlags(region);
    }

    public boolean hasRegion(World world, String name) {
        return regionExists(world, name);
    }

    /**
     * Re-sync every plot's WG region to match the plugin's data on startup.
     * Fixes desync after a reload (WG reads regions.yml fresh but plugin DB has the members).
     * Call this after plots are loaded and worlds are available.
     */
    public void syncAllPlots(java.util.Collection<com.plotplugin.model.Plot> plots) {
        for (com.plotplugin.model.Plot plot : plots) {
            for (World world : org.bukkit.Bukkit.getWorlds()) {
                if (regionExists(world, plot.getName())) {
                    syncPlot(world, plot);
                    break;
                }
            }
        }
    }

    // ── Flag helpers ──────────────────────────────────────────────────────────

    /**
     * يحط الفلاقات الصحيحة على الريجن:
     *   - DENY لـ NON_MEMBERS على كل الفلاقات المهمة
     *   - يعادل: /rg flag <name> build deny -g non_members
     *
     * الأعضاء والملاك يبنون بحرية لأن الـ DENY يطبق على NON_MEMBERS فقط.
     */
    private void ensureDenyFlags(ProtectedRegion region) {
        applyDenyNonMembers(region, Flags.BUILD);
        applyDenyNonMembers(region, Flags.CHEST_ACCESS);
        applyDenyNonMembers(region, Flags.USE);
        applyDenyNonMembers(region, Flags.INTERACT);
    }

    /** يطبق DENY على غير الأعضاء لفلاق معين. */
    private void applyDenyNonMembers(ProtectedRegion region, com.sk89q.worldguard.protection.flags.StateFlag flag) {
        region.setFlag(flag, StateFlag.State.DENY);
        region.setFlag(flag.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
    }
}
