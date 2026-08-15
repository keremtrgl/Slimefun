package io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.accelerators;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestCropGrowthAcceleratorSleep {

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
    @DisplayName("Test a crop stays ungrown when the accelerator has no fertilizer, without scanning the world")
    void testNoFertilizerMeansNoGrowth() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.register(plugin);

        World world = server.addSimpleWorld("crop_accelerator_sleep_test_world_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setType(Material.WHEAT);
        Ageable ageable = (Ageable) cropBlock.getBlockData();
        ageable.setAge(0);
        cropBlock.setBlockData(ageable);

        // No fertilizer in the input inventory - the crop must not grow
        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(0, afterTick.getAge());
    }

    @Test
    @DisplayName("Test a crop still grows when fertilizer is present")
    void testGrowsWithFertilizer() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test_2");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR_2", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.register(plugin);

        World world = server.addSimpleWorld("crop_accelerator_sleep_test_world_2_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(machine.getInputSlots()[0], io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems.FERTILIZER.item());

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setType(Material.WHEAT);
        Ageable ageable = (Ageable) cropBlock.getBlockData();
        ageable.setAge(0);
        cropBlock.setBlockData(ageable);

        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(1, afterTick.getAge());
    }
}
