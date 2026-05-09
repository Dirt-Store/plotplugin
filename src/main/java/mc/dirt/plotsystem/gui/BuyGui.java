package mc.dirt.plotsystem.gui;

import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.data.PlotData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

public class BuyGui {

    // Prefix مميز يسهل التعرف على الـ GUI من الـ title
    // نستخدم § + حرف نادر (§b) ثم الاسم بدلاً من encode معقد
    public static final String PREFIX = "\u00A7b\u00A7r\u00A7BUY\u00A7:";

    private final PlotPlugin plugin;
    private final Logger log;

    public BuyGui(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void open(Player player, PlotData data) {
        try {
            int price = plugin.getConfigManager().getBasePrice();
            int days  = plugin.getConfigManager().getBaseDays();

            // Title = PREFIX + regionName — يُستخدم لاحقاً لاستخراج الاسم
            String title = PREFIX + data.getRegionName();
            // Paper يدعم 256 حرف في الـ title لكن نبقيها قصيرة
            if (title.length() > 256) title = title.substring(0, 256);

            Inventory inv = Bukkit.createInventory(null, 9, title);

            ItemStack buyItem = plugin.getConfigManager().getGuiItem(
                    "buy-plot",
                    String.valueOf(price),
                    String.valueOf(days),
                    -1
            );

            int slot = plugin.getConfigManager().getGuiItemSlot("buy-plot");
            inv.setItem(slot, buyItem);
            player.openInventory(inv);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to open BuyGui for " + player.getName() + ": " + e.getMessage());
        }
    }

    /** يرجع اسم الريجن من الـ title أو null إذا مو BuyGui */
    public static String extractRegionName(String title) {
        if (!title.startsWith(PREFIX)) return null;
        return title.substring(PREFIX.length());
    }

    /** يرجع true إذا كان الـ inventory هذا BuyGui */
    public static boolean isBuyGui(String title) {
        return title.startsWith(PREFIX);
    }
}
