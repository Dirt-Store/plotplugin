package mc.dirt.plotsystem.config;

import mc.dirt.plotsystem.PlotPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ConfigManager {

    private final PlotPlugin plugin;
    private final Logger log;
    private FileConfiguration cfg;

    // Currency
    private ItemStack currencyItem;

    // Pricing
    private int basePrice;
    private int baseDays;
    private int buyTimeCost;
    private int buyTimeMaxDays;

    public ConfigManager(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        cfg = plugin.getConfig();
        loadCurrency();
        loadPricing();
    }

    // ── Currency ─────────────────────────────────────────────────────────────

    private void loadCurrency() {
        try {
            String matName = cfg.getString("currency.material", "EMERALD");
            Material mat = Material.matchMaterial(matName);
            if (mat == null) {
                log.warning("[PlotSystem] Invalid currency material '" + matName + "', defaulting to EMERALD.");
                mat = Material.EMERALD;
            }
            currencyItem = buildItem(mat,
                    cfg.getString("currency.name", "&aEmerald"),
                    cfg.getStringList("currency.lore"));
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to load currency config: " + e.getMessage());
            currencyItem = new ItemStack(Material.EMERALD);
        }
    }

    private ItemStack buildItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void loadPricing() {
        basePrice = cfg.getInt("plot.base-price", 30);
        baseDays = cfg.getInt("plot.base-days", 3);
        buyTimeCost = cfg.getInt("plot.buy-time-cost", 10);
        buyTimeMaxDays = cfg.getInt("plot.buy-time-max-days", 12);
    }

    // ── GUI helpers ───────────────────────────────────────────────────────────

    public ItemStack getGuiItem(String key) {
        return getGuiItem(key, null, null, -1);
    }

    public ItemStack getGuiItem(String key, String price, String days, int daysFilled) {
        ConfigurationSection sec = cfg.getConfigurationSection("gui-items." + key);
        if (sec == null) {
            log.warning("[PlotSystem] Missing gui-items." + key + " in config.");
            return new ItemStack(Material.STONE);
        }
        try {
            String matName = sec.getString("material", "STONE");
            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = Material.STONE;

            String name = sec.getString("name", key);
            List<String> lore = sec.getStringList("lore");

            // Replace placeholders
            if (price != null) {
                name = name.replace("{price}", price);
                lore = lore.stream()
                        .map(l -> l.replace("{price}", price))
                        .collect(Collectors.toList());
            }
            if (days != null) {
                name = name.replace("{days}", days);
                lore = lore.stream()
                        .map(l -> l.replace("{days}", days))
                        .collect(Collectors.toList());
            }
            if (daysFilled >= 0) {
                String df = String.valueOf(daysFilled);
                lore = lore.stream()
                        .map(l -> l.replace("{days}", df))
                        .collect(Collectors.toList());
                name = name.replace("{days}", df);
            }
            // buy-time extra placeholders
            lore = lore.stream()
                    .map(l -> l.replace("{cost}", String.valueOf(buyTimeCost))
                                .replace("{max}", String.valueOf(buyTimeMaxDays)))
                    .collect(Collectors.toList());
            name = name.replace("{cost}", String.valueOf(buyTimeCost))
                       .replace("{max}", String.valueOf(buyTimeMaxDays));

            return buildItem(mat, name, lore);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to build GUI item '" + key + "': " + e.getMessage());
            return new ItemStack(Material.STONE);
        }
    }

    public int getGuiItemSlot(String key) {
        return cfg.getInt("gui-items." + key + ".slot", 0);
    }

    // ── Sign formats ──────────────────────────────────────────────────────────

    public String getSignLine1() {
        return color(cfg.getString("sign.line1", "&8[&6Plot&8]"));
    }

    public String getSignLineAvailable(String price) {
        return color(cfg.getString("sign.line2-available", "&a{price} items").replace("{price}", price));
    }

    public String getSignLineOwnedPrice(String price) {
        return color(cfg.getString("sign.line2-owned", "&c{price} items").replace("{price}", price));
    }

    public String getSignLine3Available() {
        return color(cfg.getString("sign.line3-available", "&aAvailable"));
    }

    public String getSignLine3Owned() {
        return color(cfg.getString("sign.line3-owned", "&cOwned"));
    }

    public String getSignLine4Owned(int days, String owner) {
        return color(cfg.getString("sign.line4-owned", "&f{days}d &7{owner}")
                .replace("{days}", String.valueOf(days))
                .replace("{owner}", owner));
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    public String msg(String key) {
        String raw = cfg.getString("messages." + key, "&c[PlotSystem] Missing message: " + key);
        return color(raw);
    }

    public String msg(String key, String... replacements) {
        String m = msg(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            m = m.replace(replacements[i], replacements[i + 1]);
        }
        return m;
    }

    // ── GUI Titles ────────────────────────────────────────────────────────────

    public String getBuyTitle() {
        return color(cfg.getString("gui-titles.buy", "&2Buy Plot"));
    }

    public String getManageTitle() {
        return color(cfg.getString("gui-titles.manage", "&8Manage Plot"));
    }

    // ── Pricing getters ───────────────────────────────────────────────────────

    public int getBasePrice() { return basePrice; }
    public int getBaseDays() { return baseDays; }
    public int getBuyTimeCost() { return buyTimeCost; }
    public int getBuyTimeMaxDays() { return buyTimeMaxDays; }

    // ── Currency ──────────────────────────────────────────────────────────────

    public ItemStack getCurrencyItem() { return currencyItem.clone(); }

    /**
     * Overrides the in-memory currency item (persisted separately in plots.yml
     * via PlotManager, or just re-loaded from config).
     * The admin sets this via /plot setcoin — we save it back to config.
     */
    public void setCurrencyItem(ItemStack item) {
        this.currencyItem = item.clone();
        // Persist to config
        Material mat = item.getType();
        String name = "";
        List<String> lore = List.of();
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            name = meta.hasDisplayName() ? meta.getDisplayName() : "";
            lore = meta.hasLore() ? meta.getLore() : List.of();
        }
        cfg.set("currency.material", mat.name());
        cfg.set("currency.name", name);
        cfg.set("currency.lore", lore);
        try {
            plugin.saveConfig();
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to save currency to config: " + e.getMessage());
        }
    }

    /**
     * Check if an ItemStack matches the currency item (material + name + lore).
     */
    public boolean isCurrency(ItemStack item) {
        if (item == null || item.getType() != currencyItem.getType()) return false;
        // If currency has no meta (plain item), any item of same type matches
        if (!currencyItem.hasItemMeta()) return true;
        if (!item.hasItemMeta()) return false;
        ItemMeta cm = currencyItem.getItemMeta();
        ItemMeta im = item.getItemMeta();
        if (cm.hasDisplayName() && !cm.getDisplayName().equals(im.getDisplayName())) return false;
        if (cm.hasLore() && !cm.getLore().equals(im.getLore())) return false;
        return true;
    }

    /**
     * Count how many currency items a player's inventory contains.
     */
    public int countCurrency(org.bukkit.entity.Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCurrency(item)) count += item.getAmount();
        }
        return count;
    }

    /**
     * Remove exactly 'amount' currency items from player inventory.
     * Returns true if successful, false if not enough items (nothing removed).
     */
    public boolean removeCurrency(org.bukkit.entity.Player player, int amount) {
        if (countCurrency(player) < amount) return false;
        int toRemove = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && toRemove > 0; i++) {
            ItemStack item = contents[i];
            if (!isCurrency(item)) continue;
            if (item.getAmount() <= toRemove) {
                toRemove -= item.getAmount();
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - toRemove);
                toRemove = 0;
            }
        }
        player.getInventory().setContents(contents);
        return true;
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
