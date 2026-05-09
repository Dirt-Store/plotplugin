package mc.dirt.plotsystem.listener;

import mc.dirt.plotsystem.PlotPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.logging.Logger;

public class ChatListener implements Listener {

    private final PlotPlugin plugin;
    private final Logger log;
    private final InventoryListener inventoryListener;

    public ChatListener(PlotPlugin plugin, InventoryListener inventoryListener) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.inventoryListener = inventoryListener;
    }

    /*
     * AsyncPlayerChatEvent هو الـ event الصحيح للـ Paper 1.20.x
     * (io.papermc.paper.event.player.AsyncChatEvent هو البديل الجديد لكن
     *  AsyncPlayerChatEvent لا يزال يعمل في 1.20.6 وهو أبسط للاستخدام هنا)
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!inventoryListener.hasPendingInput(player.getUniqueId())) return;

        event.setCancelled(true);
        String input = event.getMessage().trim();

        // يجب أن يتم على الـ main thread لأن Bukkit API ليس thread-safe
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                inventoryListener.handleChatInput(player, input);
            } catch (Exception e) {
                log.severe("[PlotSystem] Error handling chat input from " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
