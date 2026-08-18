package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.implementation.items.cargo.CargoManager;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.World;

class TestCargoNetSleep {

    private static ServerMock server;
    private static Slimefun plugin;

    @BeforeAll
    public static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);

        // CargoNetworkTask#run() unconditionally submits a profiler entry tagged with
        // the CARGO_MANAGER SlimefunItem. In a bare unit test environment SlimefunItemSetup#setup()
        // never runs (see Slimefun#onUnitTestStart()), so SlimefunItems.CARGO_MANAGER.getItem()
        // would otherwise resolve to null. We can't call the full SlimefunItemSetup.setup(plugin)
        // here either - it's gated by a JVM-static one-shot flag shared with TestRegistration.
        // Registering just the one item we need mirrors the pattern already used by
        // TestCargoNodeListener for other cargo items.
        ItemGroup itemGroup = TestUtilities.getItemGroup(plugin, "cargo_sleep_test");
        new CargoManager(itemGroup, SlimefunItems.CARGO_MANAGER, RecipeType.NULL, new ItemStack[9]).register(plugin);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test a CargoNet with no input nodes sleeps its regulator after an idle pass")
    void testSleepsWhenNoInputsMoveAnything() {
        World world = server.addSimpleWorld("cargo_sleep_test_" + System.nanoTime());
        Block regulatorBlock = world.getBlockAt(0, 65, 0);
        Location regulatorLocation = regulatorBlock.getLocation();

        CargoNet network = CargoNet.getNetworkFromLocationOrCreate(regulatorLocation);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(regulatorLocation));

        CargoNetworkTask task = new CargoNetworkTask(network, new java.util.HashMap<>(), new java.util.HashMap<>());
        task.run();

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(regulatorLocation));
    }
}
