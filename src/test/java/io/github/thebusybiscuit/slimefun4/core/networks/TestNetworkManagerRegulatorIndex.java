package io.github.thebusybiscuit.slimefun4.core.networks;

import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNet;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.World;

class TestNetworkManagerRegulatorIndex {

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
    @DisplayName("Test getNetworkAtRegulator finds a registered network in O(1) by its regulator Location")
    void testFindsRegisteredNetwork() {
        World world = server.addSimpleWorld("network_index_test_" + System.nanoTime());
        Block regulatorBlock = world.getBlockAt(0, 65, 0);
        Location regulatorLocation = regulatorBlock.getLocation();

        EnergyNet network = EnergyNet.getNetworkFromLocationOrCreate(regulatorLocation);

        Optional<EnergyNet> found = Slimefun.getNetworkManager().getNetworkAtRegulator(regulatorLocation, EnergyNet.class);

        Assertions.assertTrue(found.isPresent());
        Assertions.assertSame(network, found.get());
    }

    @Test
    @DisplayName("Test getNetworkAtRegulator returns empty for an unregistered Location")
    void testReturnsEmptyForUnknownLocation() {
        World world = server.addSimpleWorld("network_index_test_2_" + System.nanoTime());
        Block unknownBlock = world.getBlockAt(999, 65, 999);

        Optional<EnergyNet> found = Slimefun.getNetworkManager().getNetworkAtRegulator(unknownBlock.getLocation(), EnergyNet.class);

        Assertions.assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("Test the index is cleared after unregisterNetwork")
    void testIndexClearedOnUnregister() {
        World world = server.addSimpleWorld("network_index_test_3_" + System.nanoTime());
        Block regulatorBlock = world.getBlockAt(5, 65, 5);
        Location regulatorLocation = regulatorBlock.getLocation();

        EnergyNet network = EnergyNet.getNetworkFromLocationOrCreate(regulatorLocation);
        Slimefun.getNetworkManager().unregisterNetwork(network);

        Optional<EnergyNet> found = Slimefun.getNetworkManager().getNetworkAtRegulator(regulatorLocation, EnergyNet.class);

        Assertions.assertTrue(found.isEmpty());
    }
}
