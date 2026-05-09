package com.plotplugin.listener;

import com.plotplugin.PlotPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class ChatInputListener implements Listener {

    private final PlotPlugin plugin;
    private final GUIListener guiListener;

    public ChatInputListener(PlotPlugin plugin, GUIListener guiListener) {
        this.plugin = plugin;
        this.guiListener = guiListener;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String[] pending = guiListener.getPendingChatInput().get(uuid);
        if (pending == null) return;

        event.setCancelled(true);
        guiListener.getPendingChatInput().remove(uuid);

        String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();

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
