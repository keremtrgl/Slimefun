package me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems;

import org.bukkit.Material;
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
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.World;

class TestAContainerSleep {

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
    @DisplayName("Test an AContainer machine sleeps when its input has no matching recipe")
    void testSleepsWhenIdle() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "acontainer_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_IDLE_MACHINE", Material.FURNACE, "&7Test Machine");

        MockAContainer machine = new MockAContainer(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.setEnergyConsumption(1);
        machine.setProcessingSpeed(1);
        machine.register(plugin);

        World world = TestUtilities.createWorld(server);
        Block block = world.getBlockAt(1, 65, 1);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        // Input is empty, so findNextRecipe() will find nothing and the machine should sleep
        machine.tickForTesting(block);

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }

    @Test
    @DisplayName("Test an AContainer machine does not sleep while actively processing a recipe")
    void testDoesNotSleepWhileProcessing() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "acontainer_sleep_test_active");
        SlimefunItemStack item = new SlimefunItemStack("TEST_ACTIVE_MACHINE", Material.FURNACE, "&7Test Active Machine");

        MockAContainer machine = new MockAContainer(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.setEnergyConsumption(1);
        machine.setProcessingSpeed(1);

        // Give the machine a real recipe to match against, so findNextRecipe() succeeds
        // instead of hitting the "no matching recipe" idle path from testSleepsWhenIdle().
        ItemStack recipeInput = new ItemStack(Material.COBBLESTONE);
        ItemStack recipeOutput = new ItemStack(Material.STONE);
        machine.registerRecipe(5, recipeInput, recipeOutput);

        machine.register(plugin);

        World world = TestUtilities.createWorld(server);
        Block block = world.getBlockAt(2, 65, 2);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());

        // Fill the machine's input slot with a matching item and give it enough charge
        // so takeCharge() succeeds - the machine has real work to do and must never sleep.
        BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(machine.getInputSlots()[0], recipeInput.clone());
        machine.setCharge(block.getLocation(), 1000);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        // First tick: no operation yet, but a matching recipe exists, so one is started.
        machine.tickForTesting(block);

        Assertions.assertNotNull(machine.getMachineProcessor().getOperation(block));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        // Second tick: an operation is already in progress and charge is available,
        // so the machine must advance the operation and must NOT sleep.
        machine.tickForTesting(block);

        CraftingOperation operation = machine.getMachineProcessor().getOperation(block);
        Assertions.assertNotNull(operation);
        Assertions.assertTrue(operation.getProgress() > 0, "The operation should have progressed while actively processing");
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }
}
