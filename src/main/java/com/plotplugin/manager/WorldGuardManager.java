package com.plotplugin.manager;

import com.plotplugin.model.Plot;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * WorldGuard integration.
 *
 * FLAG STRATEGY — restrict non-members only:
 *   BUILD / CHEST_ACCESS / USE are set to DENY with group NON_MEMBERS.
 *   This means the DENY applies only to players who are NOT WG members or owners.
 *   WG members and owners bypass these flags entirely and can build, interact,
 *   and open chests freely inside the plot.
 *
 * Root-cause of the original bug:
 *   Calling region.setFlag(Flags.BUILD, StateFlag.State.DENY) without a group
 *   applies the DENY to EVERYONE including WG members. By pairing the flag with
 *   RegionGroup.NON_MEMBERS the restriction is scoped only to unauthorised players.
 *
 * On plot expiry / clear:
 *   All WG members and owners are removed. The NON_MEMBERS DENY flags remain,
 *   so the now-empty region stays locked for all players.
 */
public class WorldGuardManager {

    private final Logger logger;

    public WorldGuardManager(Logger logger) {
        this.logger = logger;
    }

    // ── Region lookup ─────────────────────────────────────────────────────────

    public boolean regionExists(World world, String name) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return false;
        return rm.hasRegion(name);
    }

    public RegionManager getRegionManager(World world) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    public boolean hasRegion(World world, String name) {
        return regionExists(world, name);
    }

    // ── Grant / revoke ────────────────────────────────────────────────────────

    /**
     * Add a player to the WG region as a member so they can build/use the plot.
     * Also ensures the NON_MEMBERS DENY flags are applied on the region.
     *
     * Call for: member added, manager added.
     */
    public void grantPlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().addPlayer(playerUuid);
        ensureDenyFlags(region);
        saveQuietly(rm);
    }

    /**
     * Add a player as WG owner (used for the plot owner).
     * Owners bypass all flag restrictions and can manage the WG region.
     */
    public void grantOwnerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getOwners().addPlayer(playerUuid);
        ensureDenyFlags(region);
        saveQuietly(rm);
    }

    /**
     * Remove a player from both WG member and owner lists.
     * Called when a member/manager/owner is removed from the plot.
     */
    public void revokePlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().removePlayer(playerUuid);
        region.getOwners().removePlayer(playerUuid);
        saveQuietly(rm);
    }

    /**
     * Remove all members/owners and keep NON_MEMBERS DENY flags (called on plot expiry).
     */
    public void clearMembers(World world, String regionName) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().clear();
        region.getOwners().clear();
        ensureDenyFlags(region);
        saveQuietly(rm);
    }

    /**
     * Fully sync the WG region to match the Plot model.
     * Clears all existing WG members/owners, then re-adds them from the plot data.
     * Call after bulk changes (e.g., ownership transfer, plugin startup sync).
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
        for (UUID manager : plot.getManagers()) {
            region.getMembers().addPlayer(manager);
        }
        for (UUID member : plot.getMembers()) {
            region.getMembers().addPlayer(member);
        }

        ensureDenyFlags(region);
        saveQuietly(rm);
    }

    // ── Flag helpers ──────────────────────────────────────────────────────────

    /**
     * Apply BUILD / CHEST_ACCESS / USE as DENY for NON_MEMBERS only.
     *
     * RegionGroup.NON_MEMBERS ensures WG members and owners are never blocked —
     * they see no flag restriction at all. Players with no role in the region
     * are denied build, interaction, and chest access.
     */
    private void ensureDenyFlags(ProtectedRegion region) {
        region.setFlag(Flags.BUILD, StateFlag.State.DENY);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);

        region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);

        region.setFlag(Flags.USE, StateFlag.State.DENY);
        region.setFlag(Flags.USE.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
    }

    /** Persist WG changes to disk silently. In-memory changes apply immediately. */
    private void saveQuietly(RegionManager rm) {
        try {
            rm.save();
        } catch (StorageException e) {
            logger.warning("[PlotPlugin] Failed to save WorldGuard region data: " + e.getMessage());
        }
    }
}
