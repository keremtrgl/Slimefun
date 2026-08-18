package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotHopperable;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.ElectricFurnace;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;

/**
 * Tests for {@link MachineWakeListener}.
 *
 * These deliberately register the REALISTIC set of Slimefun listeners that run before
 * {@link MachineWakeListener} in priority order - {@link SlimefunItemInteractListener} and
 * {@link HopperListener} - because both of them legitimately CANCEL the very events this
 * listener observes, for the very machines this feature targets. Testing
 * {@link MachineWakeListener} in isolation hides that interaction entirely.
 */
class TestMachineWakeListener {

    private static ServerMock server;
    private static Slimefun plugin;
    private static SlimefunItem electricFurnace;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);

        // Manually register the listeners since MockBukkit.load() doesn't invoke registerListeners().
        // The order here does not decide execution order - EventPriority does - but all three must
        // be present for the cancellation interaction to be reproduced.
        new BlockListener(plugin);
        new SlimefunItemInteractListener(plugin);
        new HopperListener(plugin);
        new MachineWakeListener(plugin);

        // Required before an AContainer can be registered
        Slimefun.getCfg().setValue("URID.enable-tickers", true);

        ItemGroup testGroup = TestUtilities.getItemGroup(plugin, "machine_wake_test");

        electricFurnace = new ElectricFurnace(testGroup, SlimefunItems.ELECTRIC_FURNACE, RecipeType.NULL, new ItemStack[] {})
            .setCapacity(100)
            .setEnergyConsumption(10)
            .setProcessingSpeed(1);
        electricFurnace.register(plugin);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test right-clicking a sleeping Slimefun block wakes it")
    void testInteractWakesSleepingBlock() {
        World world = TestUtilities.createWorld(server);
        Block block = new BlockMock(Material.CHEST, new Location(world, TestUtilities.randomInt(), 100, TestUtilities.randomInt()));

        Location l = block.getLocation();

        BlockStorage.addBlockInfo(block, "id", "TEST_MACHINE");
        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        Player player = server.addPlayer();
        ItemStack itemStack = new ItemStack(Material.AIR);
        player.getInventory().setItemInMainHand(itemStack);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, itemStack, block, BlockFace.UP);
        server.getPluginManager().callEvent(event);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test right-clicking a non-Slimefun block does nothing")
    void testInteractIgnoresNonSlimefunBlock() {
        World world = TestUtilities.createWorld(server);
        Block block = new BlockMock(Material.CHEST, new Location(world, TestUtilities.randomInt(), 100, TestUtilities.randomInt()));

        Player player = server.addPlayer();
        ItemStack itemStack = new ItemStack(Material.AIR);
        player.getInventory().setItemInMainHand(itemStack);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, itemStack, block, BlockFace.UP);

        Assertions.assertDoesNotThrow(() -> server.getPluginManager().callEvent(event));
    }

    /**
     * Regression test for the {@code ignoreCancelled = true} bug on {@code onInteract}.
     *
     * {@link SlimefunItemInteractListener} cancels the {@link PlayerInteractEvent} at NORMAL
     * priority whenever it opens an {@link ElectricFurnace}'s {@code BlockMenu}, which means a
     * MONITOR handler with {@code ignoreCancelled = true} never ran for the flagship AContainers.
     */
    @Test
    @DisplayName("Test right-clicking an AContainer wakes it even though the interact event gets cancelled")
    void testInteractWakesAContainerDespiteCancellation() {
        Player player = server.addPlayer();
        ItemStack itemStack = electricFurnace.getItem();
        player.getInventory().setItemInMainHand(itemStack);

        World world = TestUtilities.createWorld(server);
        Block block = TestUtilities.placeSlimefunBlock(server, itemStack, world, player);
        Location l = block.getLocation();

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, itemStack, block, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        // Confirm the premise: opening the machine's GUI really does cancel the event.
        Assertions.assertTrue(event.isCancelled(), "Test premise broken: SlimefunItemInteractListener no longer cancels the interact event when opening a BlockMenu");
        Assertions.assertSame(Result.DENY, event.useInteractedBlock());

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "Right-clicking an AContainer must wake it even though the PlayerInteractEvent was cancelled by SlimefunItemInteractListener");
    }

    /**
     * Regression test for the {@code ignoreCancelled = true} bug on {@code onInventoryMove}.
     *
     * {@link HopperListener} cancels the {@link InventoryMoveItemEvent} at NORMAL priority for
     * every {@link NotHopperable} destination - which includes {@link ElectricFurnace} and the
     * rest of the electric machines a furnace farm is built from.
     */
    @Test
    @DisplayName("Test a hopper feeding a NotHopperable machine wakes it even though the move event gets cancelled")
    void testHopperFeedWakesMachineDespiteCancellation() {
        Player player = server.addPlayer();
        ItemStack itemStack = electricFurnace.getItem();
        player.getInventory().setItemInMainHand(itemStack);

        World world = TestUtilities.createWorld(server);
        Block block = TestUtilities.placeSlimefunBlock(server, itemStack, world, player);
        Location l = block.getLocation();

        Assertions.assertInstanceOf(NotHopperable.class, BlockStorage.check(l), "Test premise broken: the ElectricFurnace is no longer NotHopperable");

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        // A hopper feeding the vanilla block that backs the machine. HopperListener only looks
        // at the destination's Location and the source's InventoryType, so mocks are enough here.
        Inventory source = Mockito.mock(Inventory.class);
        Mockito.when(source.getType()).thenReturn(InventoryType.HOPPER);
        Mockito.when(source.getLocation()).thenReturn(null);

        Inventory destination = Mockito.mock(Inventory.class);
        Mockito.when(destination.getType()).thenReturn(InventoryType.DISPENSER);
        Mockito.when(destination.getLocation()).thenReturn(l);

        InventoryMoveItemEvent event = new InventoryMoveItemEvent(source, new ItemStack(Material.IRON_ORE), destination, true);
        server.getPluginManager().callEvent(event);

        // Confirm the premise: HopperListener really does cancel this.
        Assertions.assertTrue(event.isCancelled(), "Test premise broken: HopperListener no longer cancels hopper inserts into NotHopperable machines");

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "A hopper feeding a NotHopperable machine must still wake it even though HopperListener cancelled the event");
    }
}
