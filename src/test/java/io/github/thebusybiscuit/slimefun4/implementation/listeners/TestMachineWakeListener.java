package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestMachineWakeListener {

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
    @DisplayName("Test right-clicking a sleeping Slimefun block wakes it")
    void testInteractWakesSleepingBlock() {
        World world = server.addSimpleWorld("wake_listener_test_" + System.nanoTime());
        // Register the world with BlockStorage so addBlockInfo will work
        Slimefun.getRegistry().getWorlds().put(world.getName(), new BlockStorage(world));

        Block block = world.getBlockAt(1, 65, 1);
        Location l = block.getLocation();

        BlockStorage.addBlockInfo(block, "id", "TEST_MACHINE");
        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        Player player = server.addPlayer();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, new ItemStack(org.bukkit.Material.AIR), block, org.bukkit.block.BlockFace.UP);
        server.getPluginManager().callEvent(event);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test right-clicking a non-Slimefun block does nothing")
    void testInteractIgnoresNonSlimefunBlock() {
        World world = server.addSimpleWorld("wake_listener_test_2_" + System.nanoTime());
        Block block = world.getBlockAt(2, 65, 2);

        Player player = server.addPlayer();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, new ItemStack(org.bukkit.Material.AIR), block, org.bukkit.block.BlockFace.UP);

        Assertions.assertDoesNotThrow(() -> server.getPluginManager().callEvent(event));
    }
}
