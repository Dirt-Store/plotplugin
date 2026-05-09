package com.plotplugin.storage;

import com.plotplugin.PlotPlugin;
import com.plotplugin.model.Plot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * SQLite-backed persistence for all plot data.
 * Replaces plots.yml — no YAML files are written for plot data.
 */
public class SQLiteStorage {

    private final PlotPlugin plugin;
    private Connection connection;

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
                attached_z          REAL
            )
            """;

    private static final String CREATE_MEMBERS = """
            CREATE TABLE IF NOT EXISTS plot_members (
                plot_name TEXT NOT NULL,
                uuid      TEXT NOT NULL,
                role      TEXT NOT NULL,  -- 'member' or 'manager'
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

    public SQLiteStorage(PlotPlugin plugin) {
        this.plugin = plugin;
    }

    public void open() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        File dbFile = new File(dataFolder, "plots.db");
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // Performance pragmas
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            createTables();
            plugin.getLogger().info("[SQLiteStorage] Database opened: " + dbFile.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] Could not open database", e);
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[SQLiteStorage] Error closing database", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(CREATE_PLOTS);
            st.execute(CREATE_MEMBERS);
            st.execute(CREATE_META);
        }
    }

    // ── Load ───────────────────────────────────────────────────────────────────

    /**
     * Load all plots from the database. Returns an empty map on failure.
     */
    public Map<String, Plot> loadAll() {
        Map<String, Plot> result = new LinkedHashMap<>();
        if (connection == null) return result;

        // 1. Load base plot rows
        String sql = "SELECT * FROM plots";
        try (PreparedStatement ps = connection.prepareStatement(sql);
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

                // Sign location
                String signWorld = rs.getString("sign_world");
                if (signWorld != null) {
                    World w = Bukkit.getWorld(signWorld);
                    if (w != null) {
                        plot.setSignLocation(new Location(w,
                                rs.getDouble("sign_x"),
                                rs.getDouble("sign_y"),
                                rs.getDouble("sign_z")));
                    }
                }

                // Attached block
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] Failed to load plots", e);
        }

        // 2. Load members / managers
        String memberSql = "SELECT plot_name, uuid, role FROM plot_members";
        try (PreparedStatement ps = connection.prepareStatement(memberSql);
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] Failed to load plot members", e);
        }

        return result;
    }

    /**
     * Load the global-whitelist flag from meta table.
     */
    public boolean loadGlobalWhitelist() {
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM meta WHERE key = 'global_whitelist'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return "true".equals(rs.getString("value"));
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[SQLiteStorage] Could not read global_whitelist", e);
        }
        return false;
    }

    // ── Save (full) ────────────────────────────────────────────────────────────

    /**
     * Persist all plots and global-whitelist in a single transaction.
     */
    public void saveAll(Collection<Plot> plots, boolean globalWhitelist) {
        if (connection == null) return;
        try {
            connection.setAutoCommit(false);

            // Upsert each plot
            for (Plot plot : plots) {
                upsertPlot(plot);
                upsertMembers(plot);
            }

            // Remove plots not in the collection
            Set<String> names = new HashSet<>();
            plots.forEach(p -> names.add(p.getName().toLowerCase()));
            pruneDeleted(names);

            // Global whitelist meta
            saveGlobalWhitelist(globalWhitelist);

            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] Save failed, rolling back", e);
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ── Async save ────────────────────────────────────────────────────────────

    /**
     * Save a single plot asynchronously — avoids blocking the main thread on I/O.
     * The callback (if any) is run back on the main thread after saving.
     */
    public void savePlotAsync(Plot plot, Runnable onComplete) {
        // Capture mutable state on the main thread before going async
        Plot snapshot = snapshot(plot);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            savePlot(snapshot);
            if (onComplete != null) {
                plugin.getServer().getScheduler().runTask(plugin, onComplete);
            }
        });
    }

    /** Shallow copy of a plot for safe async hand-off (captures all fields). */
    private Plot snapshot(Plot src) {
        Plot copy = new Plot(src.getName());
        copy.setOwner(src.getOwner());
        copy.setExpiryTime(src.getExpiryTime());
        copy.setPaused(src.isPaused());
        copy.setPausedRemainingMs(src.getPausedRemainingMs());
        copy.setSignLocation(src.getSignLocation());
        copy.setSignAttachedBlock(src.getSignAttachedBlock());
        src.getMembers().forEach(copy::addMember);
        src.getManagers().forEach(copy::addManager);
        return copy;
    }

    // ── Save (single plot) ────────────────────────────────────────────────────

    /**
     * Persist only one plot — fast path for frequent single-plot updates.
     */
    public void savePlot(Plot plot) {
        if (connection == null) return;
        try {
            connection.setAutoCommit(false);
            upsertPlot(plot);
            upsertMembers(plot);
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] savePlot failed", e);
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deletePlot(String name) {
        if (connection == null) return;
        try {
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
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[SQLiteStorage] deletePlot failed", e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void upsertPlot(Plot plot) throws SQLException {
        String sql = """
                INSERT INTO plots (name, owner, expiry_time, paused, paused_remaining_ms,
                                   sign_world, sign_x, sign_y, sign_z,
                                   attached_world, attached_x, attached_y, attached_z)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                    attached_z          = excluded.attached_z
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
            ps.executeUpdate();
        }
    }

    private void upsertMembers(Plot plot) throws SQLException {
        // Delete existing rows for this plot, then re-insert
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

    private void pruneDeleted(Set<String> keepNames) throws SQLException {
        // Remove any plots from DB that are no longer in memory
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM plots");
             ResultSet rs = ps.executeQuery()) {
            List<String> toDelete = new ArrayList<>();
            while (rs.next()) {
                String n = rs.getString("name");
                if (!keepNames.contains(n)) toDelete.add(n);
            }
            for (String n : toDelete) {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM plots WHERE name = ?")) {
                    del.setString(1, n);
                    del.executeUpdate();
                }
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM plot_members WHERE plot_name = ?")) {
                    del.setString(1, n);
                    del.executeUpdate();
                }
            }
        }
    }

    private void saveGlobalWhitelist(boolean value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta (key, value) VALUES ('global_whitelist', ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, String.valueOf(value));
            ps.executeUpdate();
        }
    }
}
