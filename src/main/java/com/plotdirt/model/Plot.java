package com.plotdirt.model;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Plot {

    public enum State {
        AVAILABLE, OWNED, PAUSED
    }

    private final String name;
    private UUID owner;
    private final List<UUID> members;
    private final List<UUID> managers;
    private long expiryTime; // Unix timestamp in ms, -1 if not owned
    private boolean paused;
    private long pausedRemainingMs; // ms remaining when paused
    private Location signLocation;
    private Location signAttachedBlock;
    /**
     * Per-plot member limit override. -1 means "use the global default from config".
     * Counts owner + managers + members combined.
     */
    private int memberLimit = -1;

    public Plot(String name) {
        this.name = name;
        this.members = new ArrayList<>();
        this.managers = new ArrayList<>();
        this.expiryTime = -1;
        this.paused = false;
        this.pausedRemainingMs = -1;
    }

    public String getName() { return name; }

    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }

    public List<UUID> getMembers() { return members; }
    public List<UUID> getManagers() { return managers; }

    public long getExpiryTime() { return expiryTime; }
    public void setExpiryTime(long expiryTime) { this.expiryTime = expiryTime; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public long getPausedRemainingMs() { return pausedRemainingMs; }
    public void setPausedRemainingMs(long pausedRemainingMs) { this.pausedRemainingMs = pausedRemainingMs; }

    public Location getSignLocation() { return signLocation; }
    public void setSignLocation(Location signLocation) { this.signLocation = signLocation; }

    public Location getSignAttachedBlock() { return signAttachedBlock; }
    public void setSignAttachedBlock(Location signAttachedBlock) { this.signAttachedBlock = signAttachedBlock; }

    /** -1 = use global default. Any value ≥ 0 is the per-plot override. */
    public int getMemberLimit() { return memberLimit; }
    public void setMemberLimit(int memberLimit) { this.memberLimit = memberLimit; }

    /**
     * Returns the total number of players attached to this plot:
     * 1 (owner, if any) + managers.size() + members.size().
     */
    public int getTotalPlayerCount() {
        int count = (owner != null) ? 1 : 0;
        count += managers.size();
        count += members.size();
        return count;
    }

    public State getState() {
        if (owner == null) return State.AVAILABLE;
        if (paused) return State.PAUSED;
        return State.OWNED;
    }

    public boolean isOwned() { return owner != null; }

    public boolean isExpired() {
        if (owner == null) return false;
        if (paused) return false;
        return System.currentTimeMillis() > expiryTime;
    }

    public long getRemainingMs() {
        if (owner == null) return 0;
        if (paused) return pausedRemainingMs;
        return Math.max(0, expiryTime - System.currentTimeMillis());
    }

    public boolean isMember(UUID uuid) { return members.contains(uuid); }
    public boolean isManager(UUID uuid) { return managers.contains(uuid); }
    public boolean isOwner(UUID uuid) { return uuid != null && uuid.equals(owner); }

    public boolean canManage(UUID uuid) {
        return isOwner(uuid) || isManager(uuid);
    }

    /**
     * Returns true if the player is ALREADY on this plot in ANY role
     * (owner, manager, or member). Use this for duplicate-add checks so we
     * never produce a false "not added" while the player is already the owner.
     */
    public boolean hasAnyRole(UUID uuid) {
        return isOwner(uuid) || isManager(uuid) || isMember(uuid);
    }

    public void addMember(UUID uuid) { if (!members.contains(uuid)) members.add(uuid); }
    public void removeMember(UUID uuid) { members.remove(uuid); }

    public void addManager(UUID uuid) { if (!managers.contains(uuid)) managers.add(uuid); }
    public void removeManager(UUID uuid) { managers.remove(uuid); }

    /**
     * Full reset to "for sale" state.
     * Clears owner, all members, all managers, expiry timer, and pause state.
     * WorldGuard flags must be cleared separately via WorldGuardManager.
     */
    public void expire() {
        this.owner = null;
        this.members.clear();
        this.managers.clear();
        this.expiryTime = -1;
        this.paused = false;
        this.pausedRemainingMs = -1;
    }
}
