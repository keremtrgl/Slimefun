package io.github.thebusybiscuit.slimefun4.core.networks;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.api.network.Network;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNet;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

/**
 * Regression tests for the "Multiple Regulators connected" guard.
 *
 * That guard lives in {@code EnergyNet#tick(Block)} / {@code CargoNet#tick(Block)} and only
 * ever fires if {@code getNetworkFromLocationOrCreate} hands a second regulator the ALREADY
 * EXISTING {@link Network} it is standing inside of, instead of silently creating a brand new
 * one for it. A pure O(1) regulator-index lookup cannot do that (a second regulator was never
 * anyone's creating regulator), so a linear-scan fallback is required on an index miss.
 *
 * Without the fallback, two regulators wired into the same network run as two fully independent
 * networks - duplicated generator output for energy, duplicated routing for cargo.
 */
class TestDuplicateRegulatorGuard {

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
    @DisplayName("Test a second Location inside an existing EnergyNet resolves to that same EnergyNet")
    void testSecondEnergyRegulatorResolvesToExistingNetwork() {
        World world = server.addSimpleWorld("duplicate_regulator_energy_" + System.nanoTime());
        Location regulator = world.getBlockAt(0, 65, 0).getLocation();

        EnergyNet network = EnergyNet.getNetworkFromLocationOrCreate(regulator);

        // Discover the surrounding nodes so the Network actually spans more than its own
        // regulator Location (Network#discoverNeighbors populates the position set).
        network.tick();

        Location secondRegulator = world.getBlockAt(1, 65, 0).getLocation();
        Assertions.assertTrue(network.connectsTo(secondRegulator), "Test setup is broken: the second Location is not part of the Network");

        // The index cannot know about this Location - only the linear-scan fallback can.
        Assertions.assertTrue(Slimefun.getNetworkManager().getNetworkAtRegulator(secondRegulator, EnergyNet.class).isEmpty());

        int networksBefore = Slimefun.getNetworkManager().getNetworkList().size();
        EnergyNet resolved = EnergyNet.getNetworkFromLocationOrCreate(secondRegulator);

        Assertions.assertSame(network, resolved, "A second regulator inside an existing EnergyNet must resolve to that same EnergyNet, otherwise the 'Multiple Energy Regulators connected' guard can never fire");
        Assertions.assertEquals(networksBefore, Slimefun.getNetworkManager().getNetworkList().size(), "No additional Network may be registered for a second regulator");

        Slimefun.getNetworkManager().unregisterNetwork(network);
    }

    @Test
    @DisplayName("Test a second Location inside an existing CargoNet resolves to that same CargoNet")
    void testSecondCargoManagerResolvesToExistingNetwork() {
        // CargoNet#classifyLocation reads the block id from BlockStorage, so this world
        // needs a real BlockStorage attached (unlike EnergyNet, which short-circuits its
        // own regulator Location).
        World world = TestUtilities.createWorld(server);
        Location regulator = world.getBlockAt(0, 65, 0).getLocation();
        BlockStorage.addBlockInfo(regulator, "id", "CARGO_MANAGER");

        CargoNet network = CargoNet.getNetworkFromLocationOrCreate(regulator);
        network.tick();

        Location secondManager = world.getBlockAt(1, 65, 0).getLocation();
        Assertions.assertTrue(network.connectsTo(secondManager), "Test setup is broken: the second Location is not part of the Network");
        Assertions.assertTrue(Slimefun.getNetworkManager().getNetworkAtRegulator(secondManager, CargoNet.class).isEmpty());

        int networksBefore = Slimefun.getNetworkManager().getNetworkList().size();
        CargoNet resolved = CargoNet.getNetworkFromLocationOrCreate(secondManager);

        Assertions.assertSame(network, resolved, "A second Cargo Manager inside an existing CargoNet must resolve to that same CargoNet, otherwise the 'Multiple Cargo Regulators connected' guard can never fire");
        Assertions.assertEquals(networksBefore, Slimefun.getNetworkManager().getNetworkList().size(), "No additional Network may be registered for a second Cargo Manager");

        Slimefun.getNetworkManager().unregisterNetwork(network);
    }

    @Test
    @DisplayName("Test an unrelated Location still creates a brand new Network")
    void testUnrelatedLocationStillCreatesNewNetwork() {
        World world = server.addSimpleWorld("duplicate_regulator_unrelated_" + System.nanoTime());
        Location regulator = world.getBlockAt(0, 65, 0).getLocation();

        EnergyNet network = EnergyNet.getNetworkFromLocationOrCreate(regulator);
        network.tick();

        // Far outside of the Network's discovered node set
        Location unrelated = world.getBlockAt(500, 65, 500).getLocation();
        Assertions.assertFalse(network.connectsTo(unrelated));

        EnergyNet other = EnergyNet.getNetworkFromLocationOrCreate(unrelated);

        Assertions.assertNotSame(network, other);
        Assertions.assertEquals(unrelated, other.getRegulator());

        Slimefun.getNetworkManager().unregisterNetwork(network);
        Slimefun.getNetworkManager().unregisterNetwork(other);
    }
}
