package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
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
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * End-to-end test that a sleeping {@link Location} really is skipped by
 * {@link TickerTask#run()}.
 *
 * The sleep check in the ticking loop is now handed the {@link io.github.bakedlibs.dough.blocks.ChunkPosition}
 * that {@code tickChunk} is already iterating, instead of allocating a fresh one per
 * {@link Location} per cycle. If that threaded {@code ChunkPosition} did not agree with the
 * one {@code sleepLocation} derives, sleep would silently stop working in the one place it
 * matters - which the unit-level {@code isAsleep} tests would not notice.
 */
class TestTickerTaskSleepSkipsTicking {

    private static ServerMock server;
    private static Slimefun plugin;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);
        Slimefun.getCfg().setValue("URID.enable-tickers", true);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test TickerTask#run() ticks an awake Location, skips it while asleep, and resumes after waking")
    void testRunSkipsSleepingLocations() {
        AtomicInteger ticks = new AtomicInteger(0);

        ItemGroup group = TestUtilities.getItemGroup(plugin, "ticker_sleep_skip_test");
        SlimefunItemStack stack = new SlimefunItemStack("TEST_SLEEP_SKIP_MACHINE", Material.FURNACE, "&7Test Sleep Skip Machine");

        SlimefunItem item = new SlimefunItem(group, stack, RecipeType.NULL, new ItemStack[9]);
        item.addItemHandler(new BlockTicker() {

            @Override
            public boolean isSynchronized() {
                return false;
            }

            @Override
            public void tick(Block b, SlimefunItem sfItem, Config data) {
                ticks.incrementAndGet();
            }
        });
        item.register(plugin);

        World world = TestUtilities.createWorld(server);
        Block block = world.getBlockAt(3, 65, 3);
        Location l = block.getLocation();

        BlockStorage.addBlockInfo(block, "id", stack.getItemId());
        Slimefun.getTickerTask().enableTicker(l);

        // tickChunk() bails out on unloaded chunks, and MockBukkit does not load chunks
        // implicitly when a Block is fetched.
        world.loadChunk(l.getBlockX() >> 4, l.getBlockZ() >> 4);

        // Awake: the ticker runs
        Slimefun.getTickerTask().run();
        int afterFirstRun = ticks.get();
        Assertions.assertEquals(1, afterFirstRun, "Test premise broken: an awake ticking Location was not ticked by run()");

        // Asleep: the ticker must be skipped entirely
        Slimefun.getTickerTask().sleepLocation(l, 1000);
        Slimefun.getTickerTask().run();
        Assertions.assertEquals(afterFirstRun, ticks.get(), "A sleeping Location must be skipped by run()");

        // Awake again: the ticker resumes
        Slimefun.getTickerTask().wakeLocation(l);
        Slimefun.getTickerTask().run();
        Assertions.assertEquals(afterFirstRun + 1, ticks.get(), "A woken Location must be ticked again by run()");

        Slimefun.getTickerTask().disableTicker(l);
    }
}
