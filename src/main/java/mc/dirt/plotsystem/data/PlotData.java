package mc.dirt.plotsystem.data;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents all data for a single plot.
 * regionName matches the WorldGuard region name exactly.
 */
public class PlotData {

    private final String regionName;
    private String worldName;

    // Sign location — null until placed
    private Location signLocation;

    // null = available (no owner)
    private UUID ownerUUID;
    private String ownerName;

    // daysRemaining — only meaningful when plot is owned
    private int daysRemaining;

    // role map: uuid → role (MANAGER or MEMBER — owner stored separately)
    private final Map<UUID, RoleType> members = new HashMap<>();
    private final Map<UUID, String> memberNames = new HashMap<>();

    public PlotData(String regionName, String worldName) {
        this.regionName = regionName;
        this.worldName = worldName;
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public String getRegionName() { return regionName; }
    public String getWorldName() { return worldName; }
    public Location getSignLocation() { return signLocation; }
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public int getDaysRemaining() { return daysRemaining; }
    public Map<UUID, RoleType> getMembers() { return members; }
    public Map<UUID, String> getMemberNames() { return memberNames; }

    public boolean isOwned() { return ownerUUID != null; }

    public RoleType getRole(UUID uuid) {
        if (uuid.equals(ownerUUID)) return RoleType.OWNER;
        return members.getOrDefault(uuid, null);
    }

    // ── Setters ─────────────────────────────────────────────────────────────

    public void setSignLocation(Location signLocation) { this.signLocation = signLocation; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public void setOwner(UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name;
    }

    public void clearOwner() {
        this.ownerUUID = null;
        this.ownerName = null;
        this.daysRemaining = 0;
        members.clear();
        memberNames.clear();
    }

    public void setDaysRemaining(int days) { this.daysRemaining = days; }
    public void addDays(int days) { this.daysRemaining += days; }

    public void addMember(UUID uuid, String name, RoleType role) {
        members.put(uuid, role);
        memberNames.put(uuid, name);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        memberNames.remove(uuid);
    }
}
