package io.github.thebusybiscuit.slimefun4.implementation.items.androids;

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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class TestProgrammableAndroidSleep {

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

    private Block setUpAndroid(World world, int x, int z, ProgrammableAndroid android, SlimefunItemStack item) {
        Block block = world.getBlockAt(x, 65, z);
        block.setType(Material.PLAYER_HEAD);

        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        BlockStorage.addBlockInfo(block, "paused", "false");
        BlockStorage.addBlockInfo(block, "script", "START-TURN_LEFT-REPEAT");
        BlockStorage.addBlockInfo(block, "index", "0");
        BlockStorage.addBlockInfo(block, "rotation", "NORTH");

        return block;
    }

    @Test
    @DisplayName("Test a ProgrammableAndroid sleeps when its fuel slot is empty")
    void testSleepsWhenIdle() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "android_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_IDLE_ANDROID", Material.PLAYER_HEAD, "&7Test Android");

        ProgrammableAndroid android = new ProgrammableAndroid(group, 1, item, RecipeType.NULL, new ItemStack[9]);
        android.register(plugin);

        Block block = setUpAndroid(TestUtilities.createWorld(server), 1, 1, android, item);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        android.tick(block, BlockStorage.getLocationInfo(block.getLocation()));

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }

    @Test
    @DisplayName("Test a ProgrammableAndroid does not sleep once fuel is available")
    void testDoesNotSleepWithFuel() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "android_sleep_test_active");
        SlimefunItemStack item = new SlimefunItemStack("TEST_ACTIVE_ANDROID", Material.PLAYER_HEAD, "&7Test Active Android");

        ProgrammableAndroid android = new ProgrammableAndroid(group, 1, item, RecipeType.NULL, new ItemStack[9]);
        android.register(plugin);

        Block block = setUpAndroid(TestUtilities.createWorld(server), 2, 2, android, item);

        BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(43, new ItemStack(Material.COAL));

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        // First tick: fuel is 0, but the fuel slot has coal, so it gets consumed instead of sleeping.
        android.tick(block, BlockStorage.getLocationInfo(block.getLocation()));

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));
        Config data = BlockStorage.getLocationInfo(block.getLocation());
        Assertions.assertTrue(Float.parseFloat(data.getString("fuel")) > 0, "Fuel should have been refilled from the coal item");

        // Second tick: fuel is now available, so a script instruction runs - still no sleep.
        android.tick(block, BlockStorage.getLocationInfo(block.getLocation()));

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }
}
