package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import java.util.HashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.ElectricFurnace;
import io.github.thebusybiscuit.slimefun4.implementation.listeners.BlockListener;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;

import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Regression tests for the third wake source in the ticking-performance design:
 * <em>CargoNet item delivery wakes its destination block</em>.
 *
 * Cargo writes straight into the target's {@code DirtyChestMenu} and fires no Bukkit event,
 * so {@code MachineWakeListener} can never observe it. Without an explicit
 * {@code wakeLocation} call in {@link CargoUtils#insert}, a machine that fell asleep with an
 * empty input buffer would keep sleeping for its full sleep window even though cargo had
 * already delivered new work to it.
 */
class TestCargoDeliveryWake {

    private static ServerMock server;
    private static Slimefun plugin;
    private static SlimefunItem electricFurnace;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);

        new BlockListener(plugin);

        // Required before an AContainer can be registered
        Slimefun.getCfg().setValue("URID.enable-tickers", true);

        ItemGroup testGroup = TestUtilities.getItemGroup(plugin, "cargo_delivery_wake_test");

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

    /**
     * An {@link ItemFilter} that accepts everything, without reading any cargo node config.
     * {@code update} is overridden to a no-op because the superclass constructor calls it.
     */
    private static class AcceptAllFilter extends ItemFilter {

        AcceptAllFilter(Block b) {
            super(b);
        }

        @Override
        public void update(Block b) {
            // Deliberately a no-op - this filter is not derived from any block's config
        }

        @Override
        public boolean test(ItemStack item) {
            return true;
        }
    }

    /**
     * A {@link CargoNet} whose nodes accept every item, so the tests can exercise
     * {@link CargoUtils#insert} without standing up a fully configured cargo node.
     */
    private static class AcceptAllCargoNet extends CargoNet {

        AcceptAllCargoNet(Location l) {
            super(l);
        }

        @Override
        protected ItemFilter getItemFilter(Block node) {
            return new AcceptAllFilter(node);
        }
    }

    private Block placeFurnace(World world, Player player) {
        ItemStack itemStack = electricFurnace.getItem();
        player.getInventory().setItemInMainHand(itemStack);

        return TestUtilities.placeSlimefunBlock(server, itemStack, world, player);
    }

    @Test
    @DisplayName("Test a cargo delivery into an empty slot wakes the destination machine")
    void testDeliveryIntoEmptySlotWakesTarget() {
        World world = TestUtilities.createWorld(server);
        Player player = server.addPlayer();

        Block target = placeFurnace(world, player);
        Location l = target.getLocation();

        Block node = world.getBlockAt(l.getBlockX(), l.getBlockY() + 1, l.getBlockZ());
        AcceptAllCargoNet network = new AcceptAllCargoNet(world.getBlockAt(0, 65, 0).getLocation());

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        ItemStack stack = new ItemStack(Material.IRON_ORE, 1);
        ItemStack leftover = CargoUtils.insert(network, new HashMap<>(), node, target, false, stack, ItemStackWrapper.wrap(stack));

        // Confirm the premise: the item really was delivered into the machine
        Assertions.assertNull(leftover, "Test premise broken: the item was not fully delivered into the machine");
        BlockMenu menu = BlockStorage.getInventory(l);
        Assertions.assertNotNull(menu.getItemInSlot(19));

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "A successful cargo delivery must wake the destination machine");
    }

    @Test
    @DisplayName("Test a cargo delivery merged into a partially filled slot wakes the destination machine")
    void testDeliveryIntoPartialStackWakesTarget() {
        World world = TestUtilities.createWorld(server);
        Player player = server.addPlayer();

        Block target = placeFurnace(world, player);
        Location l = target.getLocation();

        Block node = world.getBlockAt(l.getBlockX(), l.getBlockY() + 1, l.getBlockZ());
        AcceptAllCargoNet network = new AcceptAllCargoNet(world.getBlockAt(0, 65, 0).getLocation());

        // Pre-fill both input slots so the delivery has to take the "merge into an existing
        // stack" branch instead of the "empty slot" branch.
        BlockMenu menu = BlockStorage.getInventory(l);
        menu.replaceExistingItem(19, new ItemStack(Material.IRON_ORE, 1));
        menu.replaceExistingItem(20, new ItemStack(Material.IRON_ORE, 1));

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        ItemStack stack = new ItemStack(Material.IRON_ORE, 1);
        CargoUtils.insert(network, new HashMap<>(), node, target, false, stack, ItemStackWrapper.wrap(stack));

        // Confirm the premise: the item really was merged into the existing stack
        Assertions.assertEquals(2, BlockStorage.getInventory(l).getItemInSlot(19).getAmount(), "Test premise broken: the item was not merged into the existing stack");

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "A cargo delivery merged into an existing stack must wake the destination machine");
    }

    @Test
    @DisplayName("Test a rejected cargo delivery leaves the destination machine asleep")
    void testRejectedDeliveryDoesNotWakeTarget() {
        World world = TestUtilities.createWorld(server);
        Player player = server.addPlayer();

        Block target = placeFurnace(world, player);
        Location l = target.getLocation();

        Block node = world.getBlockAt(l.getBlockX(), l.getBlockY() + 1, l.getBlockZ());
        AcceptAllCargoNet network = new AcceptAllCargoNet(world.getBlockAt(0, 65, 0).getLocation());

        // Fill both input slots to the max with a DIFFERENT item, so nothing can be delivered
        BlockMenu menu = BlockStorage.getInventory(l);
        menu.replaceExistingItem(19, new ItemStack(Material.GOLD_INGOT, Material.GOLD_INGOT.getMaxStackSize()));
        menu.replaceExistingItem(20, new ItemStack(Material.GOLD_INGOT, Material.GOLD_INGOT.getMaxStackSize()));

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        ItemStack stack = new ItemStack(Material.IRON_ORE, 1);
        ItemStack leftover = CargoUtils.insert(network, new HashMap<>(), node, target, false, stack, ItemStackWrapper.wrap(stack));

        Assertions.assertSame(stack, leftover, "Test premise broken: the item should have bounced back");
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l), "A rejected delivery must not wake the destination machine");
    }
}
