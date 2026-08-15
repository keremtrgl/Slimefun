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
 * wakes its own regulator, and CargoUtils wakes the destination block on a
 * successful delivery, both by calling
 * {@link io.github.thebusybiscuit.slimefun4.implementation.tasks.TickerTask#wakeLocation(Location)}
 * directly - firing a Bukkit event per item transfer would itself be wasteful.
 */
public class MachineWakeListener implements Listener {

    public MachineWakeListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /*
     * Note: these handlers deliberately do NOT use ignoreCancelled = true.
     *
     * Waking a machine is a purely observational side effect - it must happen even when
     * the event ends up cancelled, and cancellation is the NORMAL case for exactly the
     * machines this feature targets:
     *
     * - SlimefunItemInteractListener#openInventory cancels the PlayerInteractEvent
     *   (at NORMAL priority, so always before MONITOR) whenever a player right-clicks a
     *   Slimefun block that has a BlockMenu - i.e. every AContainer.
     * - HopperListener#onHopperInsert cancels the InventoryMoveItemEvent for every
     *   NotHopperable destination, which includes ElectricFurnace, ElectricSmeltery,
     *   ElectricOreGrinder, ElectricIngotPulverizer, AutoDrier and AutoBrewer.
     *
     * With ignoreCancelled = true these handlers were skipped in precisely those cases,
     * leaving AContainers with no working event-driven wake path at all.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();

        if (b != null) {
            wakeIfSlimefunBlock(b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
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
