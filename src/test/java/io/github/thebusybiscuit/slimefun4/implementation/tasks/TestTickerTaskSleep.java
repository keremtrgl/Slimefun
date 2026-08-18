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

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
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
    @DisplayName("Test a sleeping Location wakes itself back up once enough cycles have elapsed")
    void testSleepExpiresAfterCycles() {
        Location l = worldA.getBlockAt(4, 65, 4).getLocation();

        // Sleep for 2 cycles - TickerTask#run() is public and safe to invoke directly
        // in a MockBukkit context (it does not depend on the Bukkit scheduler actually
        // firing), so we use it here to advance currentCycle without any test-only
        // production code.
        Slimefun.getTickerTask().sleepLocation(l, 2);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        // 1 cycle has elapsed - not enough yet, should still be asleep
        Slimefun.getTickerTask().run();
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        // 2 cycles have now elapsed - the lazy-expiry branch in isAsleep should fire
        // and report the Location as awake again
        Slimefun.getTickerTask().run();
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

    @Test
    @DisplayName("Test disableTicker clears any sleep state for that Location")
    void testDisableTickerClearsSleepState() {
        Location l = worldA.getBlockAt(11, 65, 11).getLocation();

        Slimefun.getTickerTask().enableTicker(l);
        Slimefun.getTickerTask().sleepLocation(l, 1000);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        // Breaking a machine while it is asleep must not leave its sleep entry behind -
        // isAsleep(l) is the only thing that lazily expires entries, and it never runs
        // again for a Location that is no longer ticking.
        Slimefun.getTickerTask().disableTicker(l);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "disableTicker must clear the sleep entry, otherwise it leaks for the lifetime of the server");
    }

    @Test
    @DisplayName("Test a new machine placed at a sleeping machine's coordinates is not stale-skipped")
    void testReplacedMachineIsNotStaleSkipped() {
        Location l = worldA.getBlockAt(12, 65, 12).getLocation();

        // A machine goes to sleep for a long time...
        Slimefun.getTickerTask().enableTicker(l);
        Slimefun.getTickerTask().sleepLocation(l, 1000);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        // ...gets broken...
        Slimefun.getTickerTask().disableTicker(l);

        // ...and a brand new one is placed at the exact same coordinates.
        Slimefun.getTickerTask().enableTicker(l);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l), "A newly placed machine must not inherit the sleep state of the machine that used to occupy those coordinates");
    }

    @Test
    @DisplayName("Test disableTicker on a Location that was never asleep is harmless")
    void testDisableTickerWithoutSleepState() {
        Location l = worldA.getBlockAt(13, 65, 13).getLocation();

        Slimefun.getTickerTask().enableTicker(l);

        Assertions.assertDoesNotThrow(() -> Slimefun.getTickerTask().disableTicker(l));
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }
}
