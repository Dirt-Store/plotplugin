package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import com.plotdirt.model.Plot;
import com.plotdirt.storage.SQLiteStorage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ConfigManager {

    private final PlotDirt plugin;
    private FileConfiguration config;
    private SQLiteStorage storage;

    // FIX-B: Cache hot-path values so GUIListener and PlotGUI never read YAML
    // on the event thread. Populated in load() and refreshed in reload().
    // Slot indices and economy values are constant between reloads.
    private int cachedPlotGuiAddMemberSlot;
    private int cachedPlotGuiRemoveMemberSlot;
    private int cachedPlotGuiAddManagerSlot;
    private int cachedPlotGuiRemoveManagerSlot;
    private int cachedPlotGuiBuyTimeSlot;
    private int cachedPlotGuiTransferSlot;
    private int cachedPlotGuiHeadsStartSlot;
    private int cachedBuyGuiEmeraldSlot;
    private int cachedBuyCost;
    private int cachedBuyDays;
    private int cachedRenewCostPerDay;
    private int cachedMaxDurationDays;
    private int cachedMaxPlotsPerPlayer;
    private List<Integer> cachedPlotGuiGlassPaneSlots;
    private int cachedDefaultMemberLimit;
    private boolean cachedClearMembersOnTransfer;

    // Getters for cached values — O(1), no YAML I/O
    public int getCachedPlotGuiAddMemberSlot()    { return cachedPlotGuiAddMemberSlot; }
    public int getCachedPlotGuiRemoveMemberSlot()  { return cachedPlotGuiRemoveMemberSlot; }
    public int getCachedPlotGuiAddManagerSlot()    { return cachedPlotGuiAddManagerSlot; }
    public int getCachedPlotGuiRemoveManagerSlot() { return cachedPlotGuiRemoveManagerSlot; }
    public int getCachedPlotGuiBuyTimeSlot()       { return cachedPlotGuiBuyTimeSlot; }
    public int getCachedPlotGuiTransferSlot()      { return cachedPlotGuiTransferSlot; }
    public int getCachedPlotGuiHeadsStartSlot()    { return cachedPlotGuiHeadsStartSlot; }
    public int getCachedBuyGuiEmeraldSlot()        { return cachedBuyGuiEmeraldSlot; }
    public List<Integer> getCachedPlotGuiGlassPaneSlots() { return cachedPlotGuiGlassPaneSlots; }
    public int getCachedDefaultMemberLimit()              { return cachedDefaultMemberLimit; }
    public boolean isClearMembersOnTransfer()             { return cachedClearMembersOnTransfer; }

    public ConfigManager(PlotDirt plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // SQLite storage — no plots.yml
        storage = new SQLiteStorage(plugin);
        storage.open();

        populateCache(); // FIX-B
    }

    /** Close the database cleanly on shutdown. */
    public void close() {
        if (storage != null) storage.close();
    }

    /**
     * Full live reload: reloads config.yml, re-creates all GUIs so GUI titles/
     * slots/materials are picked up immediately, and re-syncs sign text.
     * The DB connection is kept open — no data loss.
     */
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        populateCache(); // FIX-B: refresh cached values after reload

        // Re-create GUI instances so any changed title, slot, or material
        // is reflected the next time a player opens them.
        plugin.reloadGUIs();

        // Refresh all signs with new sign-line config immediately.
        plugin.getSignManager().updateAllSigns();
    }

    /** FIX-B: Read all hot-path config values once and cache them in fields. */
    private void populateCache() {
        cachedPlotGuiAddMemberSlot    = config.getInt("gui.plot-gui.add-member-slot", 10);
        cachedPlotGuiRemoveMemberSlot = config.getInt("gui.plot-gui.remove-member-slot", 12);
        cachedPlotGuiAddManagerSlot   = config.getInt("gui.plot-gui.add-manager-slot", 14);
        cachedPlotGuiRemoveManagerSlot= config.getInt("gui.plot-gui.remove-manager-slot", 16);
        cachedPlotGuiBuyTimeSlot      = config.getInt("gui.plot-gui.buy-time-slot", 22);
        cachedPlotGuiTransferSlot     = config.getInt("gui.plot-gui.transfer-slot", 20);
        cachedPlotGuiHeadsStartSlot   = config.getInt("gui.plot-gui.heads-start-slot", 27);
        cachedBuyGuiEmeraldSlot       = config.getInt("gui.buy-gui.emerald-slot", 13);
        cachedBuyCost                 = config.getInt("settings.buy-cost", 30);
        cachedBuyDays                 = config.getInt("settings.buy-days", 3);
        cachedRenewCostPerDay         = config.getInt("settings.renew-cost-per-day", 10);
        cachedMaxDurationDays         = config.getInt("settings.max-duration-days", 12);
        cachedMaxPlotsPerPlayer       = config.getInt("settings.max-plots-per-player", 1);
        cachedPlotGuiGlassPaneSlots   = config.getIntegerList("gui.plot-gui.glass-pane-slots");
        cachedDefaultMemberLimit      = config.getInt("settings.default-member-limit", 10);
        cachedClearMembersOnTransfer  = config.getBoolean("settings.clear-members-on-transfer", false);
    }

    public SQLiteStorage getStorage() { return storage; }

    // ── Economy ──────────────────────────────────────────────────────────────

    public int getMaxDurationDays() {
        return config.getInt("settings.max-duration-days", 12);
    }

    public int getBuyCost() {
        return config.getInt("settings.buy-cost", 30);
    }

    public int getBuyDays() {
        return config.getInt("settings.buy-days", 3);
    }

    public int getRenewCostPerDay() {
        return config.getInt("settings.renew-cost-per-day", 10);
    }

    /** Maximum plots a single player may own simultaneously. 0 = unlimited. */
    public int getMaxPlotsPerPlayer() {
        return config.getInt("settings.max-plots-per-player", 1);
    }

    // ── Currency item ─────────────────────────────────────────────────────────

    public ItemStack getCurrencyItem() {
        return config.contains("currency.item")
                ? config.getItemStack("currency.item")
                : null;
    }

    public void setCurrencyItem(ItemStack item) {
        config.set("currency.item", item);
        plugin.saveConfig();
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    public String getMessage(String key) {
        String prefix = config.getString("messages.prefix", "&8[&bPlot&8] &r");
        String msg = config.getString("messages." + key, "&cMissing message: " + key);
        return colorize(prefix + msg);
    }

    public String getMessage(String key, String... replacements) {
        String msg = getMessage(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    // ── Sign ─────────────────────────────────────────────────────────────────

    /**
     * Returns the configured lines for the given sign state section
     * (e.g. "available", "owned", "for-sale"), with placeholders replaced.
     * Supports %count%, %limit%, %owner%, %price%, %time%.
     * Returns a list of exactly 4 strings (padded/trimmed as needed).
     */
    public List<String> getSignLines(String section, String... replacements) {
        List<String> lines = config.getStringList("signs." + section);
        if (lines.isEmpty()) {
            // Graceful fallback if section is missing
            lines = List.of("", "", "", "");
        }
        // Mutable copy for replacement + colorize
        List<String> result = new java.util.ArrayList<>(lines);
        result.replaceAll(this::colorize);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            final String key = replacements[i];
            final String val = replacements[i + 1];
            result.replaceAll(line -> line.replace(key, val));
        }
        // Ensure exactly 4 lines
        while (result.size() < 4) result.add("");
        return result.subList(0, 4);
    }

    // ── Member limit ──────────────────────────────────────────────────────────

    /**
     * Returns the effective member limit for the given plot:
     * the per-plot override if set, or the global default otherwise.
     */
    public int getEffectiveMemberLimit(Plot plot) {
        int override = plot.getMemberLimit();
        return (override >= 0) ? override : cachedDefaultMemberLimit;
    }

    // ── GUI ───────────────────────────────────────────────────────────────────

    public String getGuiTitle(String gui) {
        return colorize(config.getString("gui." + gui + ".title", gui));
    }

    public String getGuiTitle(String gui, String... replacements) {
        String title = getGuiTitle(gui);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            title = title.replace(replacements[i], replacements[i + 1]);
        }
        return title;
    }

    public int getGuiRows(String gui) {
        return config.getInt("gui." + gui + ".rows", 6);
    }

    public int getGuiSlot(String gui, String slot) {
        return config.getInt("gui." + gui + "." + slot, 0);
    }

    public String getGuiItemName(String gui, String item) {
        return colorize(config.getString("gui." + gui + ".items." + item + ".name", " "));
    }

    public String getGuiItemName(String gui, String item, String... replacements) {
        String name = getGuiItemName(gui, item);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            name = name.replace(replacements[i], replacements[i + 1]);
        }
        return name;
    }

    public List<String> getGuiItemLore(String gui, String item) {
        List<String> lore = config.getStringList("gui." + gui + ".items." + item + ".lore");
        lore.replaceAll(this::colorize);
        return lore;
    }

    public List<String> getGuiItemLore(String gui, String item, String... replacements) {
        List<String> lore = getGuiItemLore(gui, item);
        lore.replaceAll(line -> {
            String l = line;
            for (int i = 0; i < replacements.length - 1; i += 2) {
                l = l.replace(replacements[i], replacements[i + 1]);
            }
            return l;
        });
        return lore;
    }

    public Material getGuiItemMaterial(String gui, String item) {
        String mat = config.getString("gui." + gui + ".items." + item + ".material", "GRAY_STAINED_GLASS_PANE");
        try {
            return Material.valueOf(mat);
        } catch (IllegalArgumentException e) {
            return Material.GRAY_STAINED_GLASS_PANE;
        }
    }

    public List<Integer> getGuiGlassPaneSlots(String gui) {
        return config.getIntegerList("gui." + gui + ".glass-pane-slots");
    }

    // ── Sounds ────────────────────────────────────────────────────────────────

    public Sound getSound(String key) {
        String val = config.getString("sounds." + key, "UI_BUTTON_CLICK");
        try {
            return Sound.valueOf(val);
        } catch (IllegalArgumentException e) {
            try { return Sound.valueOf("UI_BUTTON_CLICK"); } catch (Exception ex) { return Sound.valueOf("BLOCK_NOTE_BLOCK_PLING"); }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String colorize(String s) {
        if (s == null) return "";
        return LegacyComponentSerializer.legacySection().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(s));
    }
}
