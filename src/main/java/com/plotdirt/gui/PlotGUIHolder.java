package com.plotdirt.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Custom InventoryHolder that carries the plot name and GUI type.
 * This is the ONLY source of truth for identifying our GUIs —
 * we never embed metadata in the visible inventory title string anymore.
 */
public class PlotGUIHolder implements InventoryHolder {

    public enum Type { PLOT_GUI, BUY_GUI }

    private final String plotName;
    private final Type type;
    private Inventory inventory;

    public PlotGUIHolder(String plotName, Type type) {
        this.plotName = plotName;
        this.type = type;
    }

    public String getPlotName() { return plotName; }
    public Type getType() { return type; }

    public void setInventory(Inventory inv) { this.inventory = inv; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }
}
