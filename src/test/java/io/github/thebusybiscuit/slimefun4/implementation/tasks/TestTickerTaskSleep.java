package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestTickerTaskSleep {

    private static ServerMock server;
    private static Slimefun plugin;
    private World worldA;
    private World worldB;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @BeforeEach
    public void setupWorlds() {
        worldA = server.addSimpleWorld("ticker_sleep_test_a_" + System.nanoTime());
        worldB = server.addSimpleWorld("ticker_sleep_test_b_" + System.nanoTime());
    }

    @Test
    @DisplayName("Test a fresh Location starts awake")
    void testFreshLocationIsAwake() {
        Location l = worldA.getBlockAt(1, 65, 1).getLocation();

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test sleepLocation marks a Location as asleep")
    void testSleepMarksAsleep() {
        Location l = worldA.getBlockAt(2, 65, 2).getLocation();

        Slimefun.getTickerTask().sleepLocation(l, 100);

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test wakeLocation clears a sleeping Location immediately")
    void testWakeClearsSleep() {
        Location l = worldA.getBlockAt(3, 65, 3).getLocation();

        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        Slimefun.getTickerTask().wakeLocation(l);
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test two identical local coordinates in different worlds don't share sleep state")
    void testCrossWorldIsolation() {
        Block blockInA = worldA.getBlockAt(7, 65, 7);
        Block blockInB = worldB.getBlockAt(7, 65, 7);

        Slimefun.getTickerTask().sleepLocation(blockInA.getLocation(), 100);

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(blockInA.getLocation()));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(blockInB.getLocation()));
    }

    @Test
    @DisplayName("Test BlockTicker convenience methods delegate correctly")
    void testBlockTickerConvenienceMethods() {
        class TestTicker extends me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker {
            @Override
            public boolean isSynchronized() {
                return false;
            }

            @Override
            public void tick(Block b, io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem item, me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config data) {
                // no-op, this test calls sleep/wakeUp/isSleeping directly
            }

            void triggerSleep(Block b) {
                sleep(b, 50);
            }

            void triggerWake(Block b) {
                wakeUp(b);
            }

            boolean triggerIsSleeping(Block b) {
                return isSleeping(b);
            }
        }

        Block block = worldA.getBlockAt(9, 65, 9);
        TestTicker ticker = new TestTicker();

        Assertions.assertFalse(ticker.triggerIsSleeping(block));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        ticker.triggerSleep(block);

        Assertions.assertTrue(ticker.triggerIsSleeping(block));
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        ticker.triggerWake(block);

        Assertions.assertFalse(ticker.triggerIsSleeping(block));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }
}
