package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.block.BlockMock;

class TestMachineWakeListener {

    private static ServerMock server;
    private static Slimefun plugin;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);

        // Manually register the listener since MockBukkit.load() doesn't invoke registerListeners()
        new MachineWakeListener(plugin);
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

        // Register the world with BlockStorage so addBlockInfo will work
        Slimefun.getRegistry().getWorlds().put(world.getName(), new BlockStorage(world));

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

        // Register the world with BlockStorage
        Slimefun.getRegistry().getWorlds().put(world.getName(), new BlockStorage(world));

        Player player = server.addPlayer();
        ItemStack itemStack = new ItemStack(Material.AIR);
        player.getInventory().setItemInMainHand(itemStack);

        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, itemStack, block, BlockFace.UP);

        Assertions.assertDoesNotThrow(() -> server.getPluginManager().callEvent(event));
    }
}
