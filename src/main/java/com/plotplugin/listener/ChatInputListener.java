package com.plotplugin.listener;

import com.plotplugin.PlotPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.UUID;

public class ChatInputListener implements Listener {

    private final PlotPlugin plugin;
    private final GUIListener guiListener;

    public ChatInputListener(PlotPlugin plugin, GUIListener guiListener) {
        this.plugin = plugin;
        this.guiListener = guiListener;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String[] pending = guiListener.getPendingChatInput().get(uuid);
        if (pending == null) return;

        event.setCancelled(true);
        guiListener.getPendingChatInput().remove(uuid);

        String input = event.getMessage().trim();
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(plugin.getConfigManager().getMessage("input-cancelled"));
            return;
        }

        String action = pending[0];
        String plotName = pending[1];

        // Dispatch command on main thread
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                player.performCommand("plot " + action + " " + plotName + " " + input));
    }
}
