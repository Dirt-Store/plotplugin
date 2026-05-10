package com.plotdirt.storage;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * SQLite-backed persistence for all plot data.
 * Thread-safe: all DB access goes through a ReentrantLock so async saves
 * cannot race with main-thread saves on the same Connection.
 */
public class SQLiteStorage {

    private final PlotDirt plugin;
    private Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    // ── Schema ─────────────────────────────────────────────────────────────────

    private static final String CREATE_PLOTS = """
            CREATE TABLE IF NOT EXISTS plots (
                name                TEXT    PRIMARY KEY,
                owner               TEXT,
                expiry_time         INTEGER NOT NULL DEFAULT -1,
                paused              INTEGER NOT NULL DEFAULT 0,
                paused_remaining_ms INTEGER NOT NULL DEFAULT -1,
                sign_world          TEXT,
                sign_x              REAL,
                sign_y              REAL,
                sign_z              REAL,
                attached_world      TEXT,
                attached_x          REAL,
                attached_y          REAL,
                attached_z          REAL,
                member_limit        INTEGER NOT NULL DEFAULT -1
            )
            """;

    private static final String CREATE_MEMBERS = """
            CREATE TABLE IF NOT EXISTS plot_members (
                plot_name TEXT NOT NULL,
                uuid      TEXT NOT NULL,
                role      TEXT NOT NULL,
                PRIMARY KEY (plot_name, uuid)
            )
            """;

    private static final String CREATE_META = """
            CREATE TABLE IF NOT EXISTS meta (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public SQLiteStorage(PlotDirt plugin) {
        this.plugin = plugin;
    }

    public void open() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File dbFile = new File(dataFolder, "plots.db");
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                // BUG FIX #5: set a busy timeout so async writes don't throw
                // "database is locked" when the main-thread save and an async
                // save contend on the SQLite file lock briefly (WAL reduces
                // this but doesn't eliminate it entirely).
                st.execute("PRAGMA busy_timeout=3000"); // wait up to 3 s
                // Increase page cache to reduce I/O on servers with many plots
                st.execute("PRAGMA cache_size=-4000"); // ~4 MB
            }
            createTables();
            migrateSchema();
            plugin.getLogger().info("[PlotDirt-DB] Database opened: " + dbFile.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotDirt-DB] Could not open database", e);
        }
    }

    public void close() {
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[PlotDirt-DB] Error closing database", e);
        } finally {
            lock.unlock();
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(CREATE_PLOTS);
            st.execute(CREATE_MEMBERS);
            st.execute(CREATE_META);
        }
    }

    /**
     * Adds any columns introduced after the initial schema so existing
     * databases are transparently upgraded without data loss.
     */
    private void migrateSchema() {
        try (Statement st = connection.createStatement()) {
            // member_limit column — added in 1.4.0
            st.execute("ALTER TABLE plots ADD COLUMN member_limit INTEGER NOT NULL DEFAULT -1");
        } catch (SQLException ignored) {
            // "duplicate column name" → column already exists, nothing to do
        }
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    public Map<String, Plot> loadAll() {
        Map<String, Plot> result = new LinkedHashMap<>();
        if (connection == null) return result;

        lock.lock();
        try {
            // 1. Load base plot rows
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM plots");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String name = rs.getString("name");
                    Plot plot = new Plot(name);

                    String ownerStr = rs.getString("owner");
                    if (ownerStr != null && !ownerStr.isBlank()) {
                        plot.setOwner(UUID.fromString(ownerStr));
                    }

                    plot.setExpiryTime(rs.getLong("expiry_time"));
                    plot.setPaused(rs.getInt("paused") == 1);
                    plot.setPausedRemainingMs(rs.getLong("paused_remaining_ms"));
                    plot.setMemberLimit(rs.getInt("member_limit"));

                    String signWorld = rs.getString("sign_world");
                    if (signWorld != null) {
                        World w = Bukkit.getWorld(signWorld);
                        if (w != null) {
                            plot.setSignLocation(new Location(w,
                                    rs.getDouble("sign_x"),
                                    rs.getDouble("sign_y"),
                                    rs.getDouble("sign_z")));
                        } else {
                            plugin.getLogger().warning("[PlotDirt-DB] World '" + signWorld
                                    + "' not loaded — sign location for plot '" + name + "' skipped.");
                        }
                    }

                    String attWorld = rs.getString("attached_world");
                    if (attWorld != null) {
                        World w = Bukkit.getWorld(attWorld);
                        if (w != null) {
                            plot.setSignAttachedBlock(new Location(w,
                                    rs.getDouble("attached_x"),
                                    rs.getDouble("attached_y"),
                                    rs.getDouble("attached_z")));
                        }
                    }

                    result.put(name.toLowerCase(), plot);
                }
            }

            // 2. Load members / managers
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT plot_name, uuid, role FROM plot_members");
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String plotName = rs.getString("plot_name").toLowerCase();
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    String role = rs.getString("role");

                    Plot plot = result.get(plotName);
                    if (plot == null) continue;

                    if ("manager".equals(role)) {
                        plot.addManager(uuid);
                    } else {
                        plot.addMember(uuid);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotDirt-DB] Failed to load plots", e);
        } finally {
            lock.unlock();
        }

        return result;
    }

    public boolean loadGlobalWhitelist() {
        if (connection == null) return false;
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM meta WHERE key = 'global_whitelist'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return "true".equals(rs.getString("value"));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[PlotDirt-DB] Could not read global_whitelist", e);
        } finally {
            lock.unlock();
        }
        return false;
    }

    // ── Save (full) ────────────────────────────────────────────────────────────

    public void saveAll(Collection<Plot> plots, boolean globalWhitelist) {
        if (connection == null) return;
        lock.lock();
        try {
            connection.setAutoCommit(false);
            for (Plot plot : plots) {
                upsertPlot(plot);
                upsertMembers(plot);
            }
            Set<String> names = new HashSet<>();
            plots.forEach(p -> names.add(p.getName().toLowerCase()));
            pruneDeleted(names);
            saveGlobalWhitelistInternal(globalWhitelist);
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotDirt-DB] Save failed, rolling back", e);
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            lock.unlock();
        }
    }

    // ── Async save ────────────────────────────────────────────────────────────

    public void savePlotAsync(Plot plot, Runnable onComplete) {
        Plot snapshot = snapshot(plot);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            savePlot(snapshot);
            if (onComplete != null) {
                plugin.getServer().getScheduler().runTask(plugin, onComplete);
            }
        });
    }

    private Plot snapshot(Plot src) {
        Plot copy = new Plot(src.getName());
        copy.setOwner(src.getOwner()); // UUID is immutable — safe
        copy.setExpiryTime(src.getExpiryTime());
        copy.setPaused(src.isPaused());
        copy.setPausedRemainingMs(src.getPausedRemainingMs());
        copy.setMemberLimit(src.getMemberLimit());

        // BUG FIX #1: clone Location objects instead of copying the reference.
        // Location is mutable; the async thread would otherwise read a Location
        // that the main thread can mutate concurrently (sign registration, plot delete).
        if (src.getSignLocation() != null) {
            copy.setSignLocation(src.getSignLocation().clone());
        }
        if (src.getSignAttachedBlock() != null) {
            copy.setSignAttachedBlock(src.getSignAttachedBlock().clone());
        }

        // BUG FIX #4: snapshot the lists under synchronization so the async
        // thread never reads a partially-modified ArrayList.
        // getMembers()/getManagers() return the live backing lists, so we take
        // a defensive copy while still on the main thread (savePlotAsync is
        // always called from the main thread before the async task is posted).
        new java.util.ArrayList<>(src.getMembers()).forEach(copy::addMember);
        new java.util.ArrayList<>(src.getManagers()).forEach(copy::addManager);
        return copy;
    }

    // ── Save (single plot) ────────────────────────────────────────────────────

    public void savePlot(Plot plot) {
        if (connection == null) return;
        lock.lock();
        try {
            connection.setAutoCommit(false);
            upsertPlot(plot);
            upsertMembers(plot);
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotDirt-DB] savePlot failed", e);
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            lock.unlock();
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deletePlot(String name) {
        if (connection == null) return;
        lock.lock();
        try {
            // Both deletes in one transaction
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM plots WHERE name = ?")) {
                ps.setString(1, name.toLowerCase());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM plot_members WHERE plot_name = ?")) {
                ps.setString(1, name.toLowerCase());
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[PlotDirt-DB] deletePlot failed", e);
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            lock.unlock();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void upsertPlot(Plot plot) throws SQLException {
        String sql = """
                INSERT INTO plots (name, owner, expiry_time, paused, paused_remaining_ms,
                                   sign_world, sign_x, sign_y, sign_z,
                                   attached_world, attached_x, attached_y, attached_z,
                                   member_limit)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(name) DO UPDATE SET
                    owner               = excluded.owner,
                    expiry_time         = excluded.expiry_time,
                    paused              = excluded.paused,
                    paused_remaining_ms = excluded.paused_remaining_ms,
                    sign_world          = excluded.sign_world,
                    sign_x              = excluded.sign_x,
                    sign_y              = excluded.sign_y,
                    sign_z              = excluded.sign_z,
                    attached_world      = excluded.attached_world,
                    attached_x          = excluded.attached_x,
                    attached_y          = excluded.attached_y,
                    attached_z          = excluded.attached_z,
                    member_limit        = excluded.member_limit
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, plot.getName().toLowerCase());
            ps.setString(2, plot.getOwner() != null ? plot.getOwner().toString() : null);
            ps.setLong(3, plot.getExpiryTime());
            ps.setInt(4, plot.isPaused() ? 1 : 0);
            ps.setLong(5, plot.getPausedRemainingMs());

            Location sign = plot.getSignLocation();
            if (sign != null && sign.getWorld() != null) {
                ps.setString(6, sign.getWorld().getName());
                ps.setDouble(7, sign.getX());
                ps.setDouble(8, sign.getY());
                ps.setDouble(9, sign.getZ());
            } else {
                ps.setNull(6, Types.VARCHAR);
                ps.setNull(7, Types.REAL);
                ps.setNull(8, Types.REAL);
                ps.setNull(9, Types.REAL);
            }

            Location att = plot.getSignAttachedBlock();
            if (att != null && att.getWorld() != null) {
                ps.setString(10, att.getWorld().getName());
                ps.setDouble(11, att.getX());
                ps.setDouble(12, att.getY());
                ps.setDouble(13, att.getZ());
            } else {
                ps.setNull(10, Types.VARCHAR);
                ps.setNull(11, Types.REAL);
                ps.setNull(12, Types.REAL);
                ps.setNull(13, Types.REAL);
            }

            ps.setInt(14, plot.getMemberLimit());
            ps.executeUpdate();
        }
    }

    private void upsertMembers(Plot plot) throws SQLException {
        try (PreparedStatement del = connection.prepareStatement(
                "DELETE FROM plot_members WHERE plot_name = ?")) {
            del.setString(1, plot.getName().toLowerCase());
            del.executeUpdate();
        }

        String ins = "INSERT OR IGNORE INTO plot_members (plot_name, uuid, role) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(ins)) {
            for (UUID uuid : plot.getMembers()) {
                ps.setString(1, plot.getName().toLowerCase());
                ps.setString(2, uuid.toString());
                ps.setString(3, "member");
                ps.addBatch();
            }
            for (UUID uuid : plot.getManagers()) {
                ps.setString(1, plot.getName().toLowerCase());
                ps.setString(2, uuid.toString());
                ps.setString(3, "manager");
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Prune orphaned plots with a single parameterized DELETE using a NOT IN clause.
     * Previously issued one DELETE per orphan — now one query total.
     */
    private void pruneDeleted(Set<String> keepNames) throws SQLException {
        if (keepNames.isEmpty()) {
            // No plots to keep — delete everything
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM plots");
                st.execute("DELETE FROM plot_members");
            }
            return;
        }

        // Build "DELETE FROM plots WHERE name NOT IN (?,?,?...)"
        String placeholders = String.join(",", Collections.nCopies(keepNames.size(), "?"));

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM plots WHERE name NOT IN (" + placeholders + ")")) {
            int i = 1;
            for (String name : keepNames) ps.setString(i++, name);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM plot_members WHERE plot_name NOT IN (" + placeholders + ")")) {
            int i = 1;
            for (String name : keepNames) ps.setString(i++, name);
            ps.executeUpdate();
        }
    }

    private void saveGlobalWhitelistInternal(boolean value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta (key, value) VALUES ('global_whitelist', ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, String.valueOf(value));
            ps.executeUpdate();
        }
    }

}
