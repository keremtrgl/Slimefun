package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import javax.annotation.Nonnull;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;

import io.github.thebusybiscuit.slimefun4.core.attributes.NotHopperable;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * This {@link Listener} prevents item from being transferred to
 * and from {@link AContainer} using a hopper.
 *
 * @author CURVX
 *
 * @see NotHopperable
 *
 */
public class HopperListener implements Listener {

    public HopperListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onHopperInsert(InventoryMoveItemEvent e) {
        Location destination = e.getDestination().getLocation();

        if (destination != null && e.getSource().getType() == InventoryType.HOPPER && BlockStorage.check(destination) instanceof NotHopperable) {
            e.setCancelled(true);
            return;
        }

        Location source = e.getSource().getLocation();

        if (source != null && e.getDestination().getType() == InventoryType.HOPPER && BlockStorage.check(source) instanceof NotHopperable) {
            e.setCancelled(true);
        }
    }
}
