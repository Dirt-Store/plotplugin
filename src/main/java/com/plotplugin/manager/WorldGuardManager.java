package com.plotplugin.manager;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;

import java.util.UUID;
import java.util.logging.Logger;

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

    /**
     * Add a player as a member AND set build/chest-access/use flags to ALLOW for members.
     * This is called when a player buys a plot or is added as a member/manager.
     */
    public void grantPlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        // Add player as a region member
        region.getMembers().addPlayer(playerUuid);

        // Set flags so members can build, use chests, and interact
        setAccessFlags(region, StateFlag.State.ALLOW);
    }

    /**
     * Remove a player's membership from the region.
     * Flags stay as-is (they apply to all remaining members).
     */
    public void revokePlayerAccess(World world, String regionName, UUID playerUuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().removePlayer(playerUuid);

        // If no members left, deny access
        if (region.getMembers().size() == 0) {
            setAccessFlags(region, StateFlag.State.DENY);
        }
    }

    /**
     * Clear all members and reset flags to DENY (called on plot expiry).
     */
    public void clearMembers(World world, String regionName) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;

        region.getMembers().clear();
        setAccessFlags(region, StateFlag.State.DENY);
    }

    /**
     * Set build, chest-access and use flags on the region.
     */
    private void setAccessFlags(ProtectedRegion region, StateFlag.State state) {
        region.setFlag(Flags.BUILD, state);
        region.setFlag(Flags.CHEST_ACCESS, state);
        region.setFlag(Flags.USE, state);
    }

    public boolean hasRegion(World world, String name) {
        return regionExists(world, name);
    }
}
