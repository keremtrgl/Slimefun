package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * This {@link Listener} wakes up any sleeping Slimefun machine (see
 * {@link io.github.thebusybiscuit.slimefun4.implementation.tasks.TickerTask#sleepLocation(Location, int)})
 * whenever a Player interacts with it directly, or an {@link org.bukkit.inventory.Inventory}
 * it owns receives or loses items via a hopper/dropper/dispenser.
 *
 * Cargo delivery does not go through this listener - {@link io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet}
 * wakes its own regulator directly, since firing a Bukkit event per item
 * transfer would itself be wasteful.
 *
 * @author TheBusyBiscuit
 */
public class MachineWakeListener implements Listener {

    public MachineWakeListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();

        if (b != null) {
            wakeIfSlimefunBlock(b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        wakeIfSlimefunBlock(e.getSource().getLocation());
        wakeIfSlimefunBlock(e.getDestination().getLocation());
    }

    private void wakeIfSlimefunBlock(@Nullable Location l) {
        if (l != null && BlockStorage.hasBlockInfo(l)) {
            Slimefun.getTickerTask().wakeLocation(l);
        }
    }

}
