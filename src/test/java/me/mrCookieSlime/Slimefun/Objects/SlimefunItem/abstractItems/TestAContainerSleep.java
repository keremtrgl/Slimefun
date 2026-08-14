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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
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
}
