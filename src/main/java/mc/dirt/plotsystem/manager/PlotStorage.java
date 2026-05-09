package mc.dirt.plotsystem.manager;

import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.data.RoleType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed storage for plot data.
 *
 * Schema:
 *   plots   — one row per plot
 *   members — one row per plot member
 */
public class PlotStorage {

    private final PlotPlugin plugin;
    private final Logger log;
    private Connection connection;

    public PlotStorage(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        openConnection();
        createTables();
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private void openConnection() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "plots.db");
            plugin.getDataFolder().mkdirs();

            // نحاول الـ driver المشادة أولاً، ثم الأصلي كـ fallback
            try {
                Class.forName("mc.dirt.plotsystem.libs.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                Class.forName("org.sqlite.JDBC");
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            log.info("[PlotSystem] SQLite connection opened: " + dbFile.getName());
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to open SQLite connection: " + e.getMessage());
        }
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            openConnection();
        }
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("[PlotSystem] SQLite connection closed.");
            }
        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to close SQLite connection: " + e.getMessage());
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void createTables() {
        String plots = """
                CREATE TABLE IF NOT EXISTS plots (
                    region      TEXT    PRIMARY KEY,
                    world       TEXT    NOT NULL,
                    sign_world  TEXT,
                    sign_x      INTEGER,
                    sign_y      INTEGER,
                    sign_z      INTEGER,
                    owner_uuid  TEXT,
                    owner_name  TEXT,
                    days        INTEGER DEFAULT 0
                )""";

        String members = """
                CREATE TABLE IF NOT EXISTS members (
                    region  TEXT NOT NULL,
                    uuid    TEXT NOT NULL,
                    name    TEXT NOT NULL,
                    role    TEXT NOT NULL,
                    PRIMARY KEY (region, uuid),
                    FOREIGN KEY (region) REFERENCES plots(region) ON DELETE CASCADE
                )""";

        try (Statement st = conn().createStatement()) {
            st.execute(plots);
            st.execute(members);
        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to create SQLite tables: " + e.getMessage());
        }
    }

    // ── Load all ──────────────────────────────────────────────────────────────

    public Map<String, PlotData> loadAll() {
        Map<String, PlotData> result = new HashMap<>();
        String sql = "SELECT region, world, sign_world, sign_x, sign_y, sign_z, owner_uuid, owner_name, days FROM plots";
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                PlotData data = rowToPlot(rs);
                if (data != null) result.put(data.getRegionName(), data);
            }
            loadAllMembers(result);

        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to load all plots: " + e.getMessage());
        }
        return result;
    }

    private PlotData rowToPlot(ResultSet rs) {
        try {
            String region = rs.getString("region");
            String world  = rs.getString("world");
            PlotData data = new PlotData(region, world);

            String signWorld = rs.getString("sign_world");
            if (signWorld != null) {
                World w = Bukkit.getWorld(signWorld);
                if (w != null) {
                    data.setSignLocation(new Location(w,
                            rs.getInt("sign_x"),
                            rs.getInt("sign_y"),
                            rs.getInt("sign_z")));
                }
            }

            String ownerUUID = rs.getString("owner_uuid");
            if (ownerUUID != null) {
                data.setOwner(UUID.fromString(ownerUUID), rs.getString("owner_name"));
                data.setDaysRemaining(rs.getInt("days"));
            }
            return data;
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to parse plot row: " + e.getMessage());
            return null;
        }
    }

    private void loadAllMembers(Map<String, PlotData> plots) {
        if (plots.isEmpty()) return;
        String sql = "SELECT region, uuid, name, role FROM members";
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String region = rs.getString("region");
                PlotData data = plots.get(region);
                if (data == null) continue;
                try {
                    UUID uuid     = UUID.fromString(rs.getString("uuid"));
                    String name   = rs.getString("name");
                    RoleType role = RoleType.valueOf(rs.getString("role"));
                    data.addMember(uuid, name, role);
                } catch (Exception e) {
                    log.warning("[PlotSystem] Skipping invalid member row in plot '" + region + "': " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to load members: " + e.getMessage());
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    public void savePlot(PlotData data) {
        String upsert = """
                INSERT INTO plots (region, world, sign_world, sign_x, sign_y, sign_z, owner_uuid, owner_name, days)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(region) DO UPDATE SET
                    world      = excluded.world,
                    sign_world = excluded.sign_world,
                    sign_x     = excluded.sign_x,
                    sign_y     = excluded.sign_y,
                    sign_z     = excluded.sign_z,
                    owner_uuid = excluded.owner_uuid,
                    owner_name = excluded.owner_name,
                    days       = excluded.days
                """;
        try {
            conn().setAutoCommit(false);

            try (PreparedStatement ps = conn().prepareStatement(upsert)) {
                ps.setString(1, data.getRegionName());
                ps.setString(2, data.getWorldName());

                Location sign = data.getSignLocation();
                if (sign != null && sign.getWorld() != null) {
                    ps.setString(3, sign.getWorld().getName());
                    ps.setInt(4, sign.getBlockX());
                    ps.setInt(5, sign.getBlockY());
                    ps.setInt(6, sign.getBlockZ());
                } else {
                    ps.setNull(3, Types.VARCHAR);
                    ps.setNull(4, Types.INTEGER);
                    ps.setNull(5, Types.INTEGER);
                    ps.setNull(6, Types.INTEGER);
                }

                if (data.isOwned()) {
                    ps.setString(7, data.getOwnerUUID().toString());
                    ps.setString(8, data.getOwnerName());
                    ps.setInt(9, data.getDaysRemaining());
                } else {
                    ps.setNull(7, Types.VARCHAR);
                    ps.setNull(8, Types.VARCHAR);
                    ps.setInt(9, 0);
                }
                ps.executeUpdate();
            }

            // احذف الأعضاء القديمين وأعد إضافتهم بشكل atomic
            try (PreparedStatement del = conn().prepareStatement("DELETE FROM members WHERE region = ?")) {
                del.setString(1, data.getRegionName());
                del.executeUpdate();
            }

            if (data.isOwned() && !data.getMembers().isEmpty()) {
                String ins = "INSERT INTO members (region, uuid, name, role) VALUES (?, ?, ?, ?)";
                try (PreparedStatement insPst = conn().prepareStatement(ins)) {
                    for (Map.Entry<UUID, RoleType> entry : data.getMembers().entrySet()) {
                        insPst.setString(1, data.getRegionName());
                        insPst.setString(2, entry.getKey().toString());
                        insPst.setString(3, data.getMemberNames().getOrDefault(entry.getKey(), "Unknown"));
                        insPst.setString(4, entry.getValue().name());
                        insPst.addBatch();
                    }
                    insPst.executeBatch();
                }
            }

            conn().commit();
        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to save plot '" + data.getRegionName() + "': " + e.getMessage());
            tryRollback();
        } finally {
            trySetAutoCommit(true);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deletePlot(String regionName) {
        String sql = "DELETE FROM plots WHERE region = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, regionName);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("[PlotSystem] Failed to delete plot '" + regionName + "': " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void tryRollback() {
        try { conn().rollback(); }
        catch (SQLException ex) { log.severe("[PlotSystem] Rollback failed: " + ex.getMessage()); }
    }

    private void trySetAutoCommit(boolean value) {
        try { conn().setAutoCommit(value); }
        catch (SQLException ex) { log.severe("[PlotSystem] setAutoCommit(" + value + ") failed: " + ex.getMessage()); }
    }
}
