package com.plotdirt.manager;

import com.plotdirt.PlotDirt;
import com.plotdirt.storage.SQLiteStorage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

public class ConfigManager {

    private final PlotDirt plugin;
    private FileConfiguration config;
    private SQLiteStorage storage;

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
    }

    /** Close the database cleanly on shutdown. */
    public void close() {
        if (storage != null) storage.close();
    }

    /** Reload config.yml without restarting the server. DB connection is kept open. */
    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();
    }

    public SQLiteStorage getStorage() { return storage; }
    public FileConfiguration getConfig() { return config; }

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

    public String getSignLine(String key) {
        return colorize(config.getString("sign." + key, ""));
    }

    public String getSignLine(String key, String... replacements) {
        String line = getSignLine(key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            line = line.replace(replacements[i], replacements[i + 1]);
        }
        return line;
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

    public java.util.List<String> getGuiItemLore(String gui, String item) {
        java.util.List<String> lore = config.getStringList("gui." + gui + ".items." + item + ".lore");
        lore.replaceAll(this::colorize);
        return lore;
    }

    public java.util.List<String> getGuiItemLore(String gui, String item, String... replacements) {
        java.util.List<String> lore = getGuiItemLore(gui, item);
        lore.replaceAll(line -> {
            String l = line;
            for (int i = 0; i < replacements.length - 1; i += 2) {
                l = l.replace(replacements[i], replacements[i + 1]);
            }
            return l;
        });
        return lore;
    }

    public org.bukkit.Material getGuiItemMaterial(String gui, String item) {
        String mat = config.getString("gui." + gui + ".items." + item + ".material", "GRAY_STAINED_GLASS_PANE");
        try {
            return org.bukkit.Material.valueOf(mat);
        } catch (IllegalArgumentException e) {
            return org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
        }
    }

    public java.util.List<Integer> getGuiGlassPaneSlots(String gui) {
        return config.getIntegerList("gui." + gui + ".glass-pane-slots");
    }

    // ── Sounds ────────────────────────────────────────────────────────────────

    public org.bukkit.Sound getSound(String key) {
        String val = config.getString("sounds." + key, "UI_BUTTON_CLICK");
        try {
            return org.bukkit.Sound.valueOf(val);
        } catch (IllegalArgumentException e) {
            try { return org.bukkit.Sound.valueOf("UI_BUTTON_CLICK"); } catch (Exception ex) { return org.bukkit.Sound.valueOf("BLOCK_NOTE_BLOCK_PLING"); }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private String colorize(String s) {
        if (s == null) return "";
        net.kyori.adventure.text.Component component =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize(s);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(component);
    }
}
