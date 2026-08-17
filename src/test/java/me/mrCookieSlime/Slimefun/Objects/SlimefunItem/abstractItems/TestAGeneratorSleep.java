package me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.operations.FuelOperation;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

class TestAGeneratorSleep {

    private static ServerMock server;
    private static Slimefun plugin;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test an AGenerator sleeps when its fuel slot is empty")
    void testSleepsWhenIdle() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "agenerator_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_IDLE_GENERATOR", Material.FURNACE, "&7Test Generator");

        MockAGenerator machine = new MockAGenerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.setEnergyProduction(10);
        machine.register(plugin);

        World world = TestUtilities.createWorld(server);
        Block block = world.getBlockAt(1, 65, 1);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        machine.tickForTesting(block.getLocation());

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }

    @Test
    @DisplayName("Test an AGenerator does not sleep while actively burning fuel")
    void testDoesNotSleepWhileActive() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "agenerator_sleep_test_active");
        SlimefunItemStack item = new SlimefunItemStack("TEST_ACTIVE_GENERATOR", Material.FURNACE, "&7Test Active Generator");

        MockAGenerator machine = new MockAGenerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.setEnergyProduction(10);
        machine.register(plugin);

        World world = TestUtilities.createWorld(server);
        Block block = world.getBlockAt(2, 65, 2);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());

        BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(machine.getInputSlots()[0], new ItemStack(Material.COAL));

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        machine.tickForTesting(block.getLocation());

        Assertions.assertNotNull(machine.getMachineProcessor().getOperation(block.getLocation()));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        machine.tickForTesting(block.getLocation());

        FuelOperation operation = machine.getMachineProcessor().getOperation(block.getLocation());
        Assertions.assertNotNull(operation);
        Assertions.assertTrue(operation.getProgress() > 0, "The operation should have progressed while actively burning fuel");
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }
}
