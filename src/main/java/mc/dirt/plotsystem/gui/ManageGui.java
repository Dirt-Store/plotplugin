package mc.dirt.plotsystem.gui;

import mc.dirt.plotsystem.PlotPlugin;
import mc.dirt.plotsystem.data.PlotData;
import mc.dirt.plotsystem.data.RoleType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class ManageGui {

    // Prefix مختلف تماماً عن BuyGui لا يوجد تعارض
    public static final String PREFIX = "\u00A7d\u00A7r\u00A7MGE\u00A7:";

    private final PlotPlugin plugin;
    private final Logger log;

    // Slot constants (54-slot inventory)
    public static final int SLOT_ADD_MEMBER    = 0;
    public static final int SLOT_REMOVE_MEMBER = 1;
    public static final int SLOT_ADD_MANAGER   = 2;
    public static final int SLOT_BUY_TIME      = 3;

    private static final int BARRIER_START = 9;
    private static final int BARRIER_END   = 17;
    private static final int HEAD_START    = 18;

    public ManageGui(PlotPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void open(Player viewer, PlotData data) {
        try {
            String title = PREFIX + data.getRegionName();
            if (title.length() > 256) title = title.substring(0, 256);

            Inventory inv = Bukkit.createInventory(null, 54, title);

            RoleType viewerRole = data.getRole(viewer.getUniqueId());
            boolean isOwner = viewerRole == RoleType.OWNER;

            // Row 1 — أزرار الأكشن
            inv.setItem(SLOT_ADD_MEMBER,    plugin.getConfigManager().getGuiItem("add-member"));
            inv.setItem(SLOT_REMOVE_MEMBER, plugin.getConfigManager().getGuiItem("remove-member"));
            if (isOwner) {
                inv.setItem(SLOT_ADD_MANAGER, plugin.getConfigManager().getGuiItem("add-manager"));
            }
            inv.setItem(SLOT_BUY_TIME, plugin.getConfigManager().getGuiItem(
                    "buy-time", null, String.valueOf(data.getDaysRemaining()), data.getDaysRemaining()
            ));

            // Row 2 — فاصل زجاجي
            ItemStack pane = plugin.getConfigManager().getGuiItem("glass-pane");
            for (int i = BARRIER_START; i <= BARRIER_END; i++) {
                inv.setItem(i, pane);
            }

            // Rows 3-6 — رؤوس اللاعبين
            int slot = HEAD_START;

            // الأونر أولاً
            slot = addHead(inv, slot, data.getOwnerUUID(), data.getOwnerName(), RoleType.OWNER);

            // المنجرز
            for (Map.Entry<UUID, RoleType> entry : data.getMembers().entrySet()) {
                if (entry.getValue() != RoleType.MANAGER || slot >= 54) continue;
                slot = addHead(inv, slot, entry.getKey(),
                        data.getMemberNames().getOrDefault(entry.getKey(), "Unknown"), RoleType.MANAGER);
            }

            // الممبرز
            for (Map.Entry<UUID, RoleType> entry : data.getMembers().entrySet()) {
                if (entry.getValue() != RoleType.MEMBER || slot >= 54) continue;
                slot = addHead(inv, slot, entry.getKey(),
                        data.getMemberNames().getOrDefault(entry.getKey(), "Unknown"), RoleType.MEMBER);
            }

            viewer.openInventory(inv);
        } catch (Exception e) {
            log.severe("[PlotSystem] Failed to open ManageGui for " + viewer.getName() + ": " + e.getMessage());
        }
    }

    private int addHead(Inventory inv, int slot, UUID uuid, String name, RoleType role) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                meta.setOwningPlayer(op);
                meta.setDisplayName(ChatColor.YELLOW + name);
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Role: " + roleColor(role) + role.name());
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        } catch (Exception e) {
            log.warning("[PlotSystem] Failed to add player head for " + name + ": " + e.getMessage());
        }
        return slot + 1;
    }

    private String roleColor(RoleType role) {
        return switch (role) {
            case OWNER   -> ChatColor.GOLD.toString();
            case MANAGER -> ChatColor.AQUA.toString();
            case MEMBER  -> ChatColor.GREEN.toString();
        };
    }

    /** يرجع true إذا كان الـ inventory هذا ManageGui */
    public static boolean isManageGui(String title) {
        return title.startsWith(PREFIX);
    }

    /** يرجع اسم الريجن من الـ title أو null */
    public static String extractRegionName(String title) {
        if (!title.startsWith(PREFIX)) return null;
        return title.substring(PREFIX.length());
    }
}
