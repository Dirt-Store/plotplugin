package mc.dirt.plotsystem.manager;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.data.RoleType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class PlotManager {

    private final PlotPlugin plugin;
    private final Logger log;
    private final PlotStorage storage;

    private final Map<String, PlotData> plots   = new HashMap<>();
    private final Map<String, String>   signIndex = new HashMap<>();

    public PlotManager(PlotPlugin plugin) {
        this.plugin  = plugin;
        this.log     = plugin.getLogger();
        this.storage = new PlotStorage(plugin);
        loadAll();
    }

    private void loadAll() {
        try {
            Map<String, PlotData> loaded = storage.loadAll();
            plots.putAll(loaded);
            for (PlotData data : loaded.values()) {
                if (data.getSignLocation() != null) {
                    indexSign(data.getSignLocation(), data.getRegionName());
                }
                // زامن كل بلوت مملوك مع WG عند البدء لضمان صحة الـ flags
                if (data.isOwned()) {
                    World world = Bukkit.getWorld(data.getWorldName());
                    if (world != null) syncWGRegion(world, data);
                }
            }
            log.info("[PlotSystem] Loaded " + plots.size() + " plots.");
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to load plots on startup: " + e.getMessage());
        }
    }

    // ── Plot CRUD ─────────────────────────────────────────────────────────────

    public boolean plotExists(String regionName) {
        return plots.containsKey(regionName.toLowerCase());
    }

    public PlotData getPlot(String regionName) {
        return plots.get(regionName.toLowerCase());
    }

    public Collection<PlotData> getAllPlots() {
        return plots.values();
    }

    public void createPlot(String regionName, String worldName) {
        PlotData data = new PlotData(regionName.toLowerCase(), worldName);
        plots.put(regionName.toLowerCase(), data);
        storage.savePlot(data);
    }

    public void removePlot(String regionName) {
        PlotData data = plots.remove(regionName.toLowerCase());
        if (data != null) {
            if (data.getSignLocation() != null) unindexSign(data.getSignLocation());
            if (data.isOwned()) {
                World world = Bukkit.getWorld(data.getWorldName());
                if (world != null) clearWGMembers(world, data.getRegionName());
            }
        }
        storage.deletePlot(regionName.toLowerCase());
    }

    public void savePlot(PlotData data) {
        storage.savePlot(data);
    }

    public void closeStorage() {
        storage.close();
    }

    // ── Sign management ───────────────────────────────────────────────────────

    public void registerSignLocation(PlotData data, Location loc) {
        if (data.getSignLocation() != null) unindexSign(data.getSignLocation());
        data.setSignLocation(loc);
        indexSign(loc, data.getRegionName());
        storage.savePlot(data);
    }

    private void indexSign(Location loc, String regionName) {
        signIndex.put(locKey(loc), regionName.toLowerCase());
    }

    private void unindexSign(Location loc) {
        signIndex.remove(locKey(loc));
    }

    public PlotData getPlotBySign(Location loc) {
        String key = signIndex.get(locKey(loc));
        return key == null ? null : plots.get(key);
    }

    public boolean isProtectedSign(Location loc) {
        return signIndex.containsKey(locKey(loc));
    }

    private String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    // ── Sign update ───────────────────────────────────────────────────────────

    public void updateSign(PlotData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Block block = loc.getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        try {
            sign.setLine(0, plugin.getConfigManager().getSignLine1());
            sign.setLine(1, data.isOwned()
                    ? plugin.getConfigManager().getSignLineOwnedPrice(String.valueOf(plugin.getConfigManager().getBasePrice()))
                    : plugin.getConfigManager().getSignLineAvailable(String.valueOf(plugin.getConfigManager().getBasePrice())));
            sign.setLine(2, data.isOwned()
                    ? plugin.getConfigManager().getSignLine3Owned()
                    : plugin.getConfigManager().getSignLine3Available());
            sign.setLine(3, data.isOwned()
                    ? plugin.getConfigManager().getSignLine4Owned(data.getDaysRemaining(), data.getOwnerName())
                    : "");
            sign.update(true);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to update sign for '" + data.getRegionName() + "': " + e.getMessage());
        }
    }

    // ── Ownership ─────────────────────────────────────────────────────────────

    /**
     * يشتري البلوت: يعيّن الأونر، يضبط الأيام، يمنح صلاحيات WG الصحيحة.
     *
     * الإصلاح الجوهري: addWGOwner يطبق DENY فقط على NON_MEMBERS
     * حتى يقدر الأونر يبني — بدلاً من DENY للكل.
     */
    public void buyPlot(PlotData data, Player buyer) {
        try {
            data.setOwner(buyer.getUniqueId(), buyer.getName());
            data.setDaysRemaining(plugin.getConfigManager().getBaseDays());

            World world = Bukkit.getWorld(data.getWorldName());
            if (world != null) {
                addWGOwner(world, data.getRegionName(), buyer.getUniqueId());
            } else {
                log.warning("[PlotSystem] World '" + data.getWorldName() + "' not found for plot '" + data.getRegionName() + "'.");
            }

            storage.savePlot(data);
            updateSign(data);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to buy plot '" + data.getRegionName() + "': " + e.getMessage());
            throw e;
        }
    }

    /**
     * ينهي البلوت: يمسح الأونر والأعضاء من WG ويحدث اللوحة.
     */
    public void expirePlot(PlotData data) {
        try {
            World world = Bukkit.getWorld(data.getWorldName());
            if (world != null) clearWGMembers(world, data.getRegionName());
            data.clearOwner();
            storage.savePlot(data);
            updateSign(data);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to expire plot '" + data.getRegionName() + "': " + e.getMessage());
        }
    }

    // ── WG Region lookup (للأوامر) ────────────────────────────────────────────

    public ProtectedRegion getWGRegion(String regionName, String worldName) {
        try {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                log.warning("[PlotSystem] World '" + worldName + "' not found.");
                return null;
            }
            RegionManager rm = getRegionManager(world);
            if (rm == null) {
                log.warning("[PlotSystem] No RegionManager for world '" + worldName + "'.");
                return null;
            }
            return rm.getRegion(regionName);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to get WG region '" + regionName + "': " + e.getMessage());
            return null;
        }
    }

    public ProtectedRegion getWGRegion(PlotData data) {
        return getWGRegion(data.getRegionName(), data.getWorldName());
    }

    // ── Member management ─────────────────────────────────────────────────────

    /**
     * يضيف عضو أو منجر للبلوت.
     *
     * الإصلاح: addWGMember يطبق DENY للـ NON_MEMBERS فقط
     * مما يسمح للعضو المضاف بالبناء فعلاً داخل البلوت.
     */
    public boolean addMember(PlotData data, UUID uuid, String name, RoleType role, Player actor) {
        if (role == RoleType.MANAGER && data.getRole(actor.getUniqueId()) != RoleType.OWNER) return false;
        if (data.getRole(uuid) != null) return false;

        data.addMember(uuid, name, role);

        World world = Bukkit.getWorld(data.getWorldName());
        if (world != null) {
            addWGMember(world, data.getRegionName(), uuid);
        } else {
            log.warning("[PlotSystem] World '" + data.getWorldName() + "' not found when adding member to '" + data.getRegionName() + "'.");
        }

        storage.savePlot(data);
        return true;
    }

    public boolean removeMember(PlotData data, UUID uuid, Player actor) {
        RoleType targetRole = data.getRole(uuid);
        RoleType actorRole  = data.getRole(actor.getUniqueId());
        if (targetRole == null || targetRole == RoleType.OWNER) return false;
        if (actorRole == RoleType.MANAGER && targetRole == RoleType.MANAGER) return false;

        data.removeMember(uuid);

        World world = Bukkit.getWorld(data.getWorldName());
        if (world != null) revokeWGAccess(world, data.getRegionName(), uuid);

        storage.savePlot(data);
        return true;
    }

    /** يستخدمه InventoryListener عند ترقية ممبر لمنجر (إزالة ثم إضافة) */
    public void removeWGMember(PlotData data, UUID uuid) {
        World world = Bukkit.getWorld(data.getWorldName());
        if (world != null) revokeWGAccess(world, data.getRegionName(), uuid);
    }

    public boolean addDays(PlotData data, int days) {
        int newDays = data.getDaysRemaining() + days;
        int max = plugin.getConfigManager().getBuyTimeMaxDays();
        if (newDays > max) return false;
        data.addDays(days);
        storage.savePlot(data);
        updateSign(data);
        return true;
    }

    // ── WorldGuard helpers ────────────────────────────────────────────────────

    private RegionManager getRegionManager(World world) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.get(BukkitAdapter.adapt(world));
    }

    /**
     * يضيف اللاعب كـ WG owner ويطبق DENY للـ NON_MEMBERS.
     * الـ WG owners يتجاوزون كل الـ flags تلقائياً.
     */
    private void addWGOwner(World world, String regionName, UUID uuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) {
            log.warning("[PlotSystem] WG region '" + regionName + "' not found in world '" + world.getName() + "'.");
            return;
        }
        region.getOwners().addPlayer(uuid);
        applyDenyFlagsForNonMembers(region);
        saveRegionQuietly(rm);
    }

    /**
     * يضيف اللاعب كـ WG member ويطبق DENY للـ NON_MEMBERS.
     * الـ WG members يتجاوزون الـ flags المقيّدة للـ NON_MEMBERS.
     */
    private void addWGMember(World world, String regionName, UUID uuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) {
            log.warning("[PlotSystem] WG region '" + regionName + "' not found in world '" + world.getName() + "'.");
            return;
        }
        region.getMembers().addPlayer(uuid);
        applyDenyFlagsForNonMembers(region);
        saveRegionQuietly(rm);
    }

    /** يحذف اللاعب من الـ members والـ owners */
    private void revokeWGAccess(World world, String regionName, UUID uuid) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;
        region.getMembers().removePlayer(uuid);
        region.getOwners().removePlayer(uuid);
        saveRegionQuietly(rm);
    }

    /** يمسح جميع الأعضاء (عند انتهاء البلوت أو حذفه) */
    private void clearWGMembers(World world, String regionName) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(regionName);
        if (region == null) return;
        region.getMembers().clear();
        region.getOwners().clear();
        applyDenyFlagsForNonMembers(region);
        saveRegionQuietly(rm);
    }

    /**
     * يزامن الـ WG region مع بيانات البلوت بالكامل.
     * يستخدم عند تحميل السيرفر لتصحيح أي flags قديمة.
     */
    private void syncWGRegion(World world, PlotData data) {
        RegionManager rm = getRegionManager(world);
        if (rm == null) return;
        ProtectedRegion region = rm.getRegion(data.getRegionName());
        if (region == null) return;

        region.getMembers().clear();
        region.getOwners().clear();

        if (data.getOwnerUUID() != null) region.getOwners().addPlayer(data.getOwnerUUID());
        for (UUID uuid : data.getMembers().keySet()) region.getMembers().addPlayer(uuid);

        applyDenyFlagsForNonMembers(region);
        saveRegionQuietly(rm);
    }

    /**
     * ═══════════════════════════════════════════════════════
     * الإصلاح الجوهري لمشكلة البناء
     * ═══════════════════════════════════════════════════════
     *
     * المشكلة القديمة:
     *   region.setFlag(Flags.BUILD, DENY)
     *   → يمنع الكل بما فيهم الـ members والـ owners
     *
     * الحل:
     *   region.setFlag(Flags.BUILD, DENY)
     *   region.setFlag(Flags.BUILD.getRegionGroupFlag(), NON_MEMBERS)
     *   → يمنع فقط اللاعبين غير المضافين في الـ region
     *   → الـ members والـ owners يبنون بحرية تامة
     *
     * نفس الشيء لـ CHEST_ACCESS و USE.
     */
    private void applyDenyFlagsForNonMembers(ProtectedRegion region) {
        region.setFlag(Flags.BUILD,        StateFlag.State.DENY);
        region.setFlag(Flags.BUILD.getRegionGroupFlag(),        RegionGroup.NON_MEMBERS);

        region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
        region.setFlag(Flags.CHEST_ACCESS.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);

        region.setFlag(Flags.USE,          StateFlag.State.DENY);
        region.setFlag(Flags.USE.getRegionGroupFlag(),          RegionGroup.NON_MEMBERS);
    }

    private void saveRegionQuietly(RegionManager rm) {
        try {
            rm.save();
        } catch (StorageException e) {
            log.warning("[PlotSystem] Failed to save WG region data: " + e.getMessage());
        }
    }
}
