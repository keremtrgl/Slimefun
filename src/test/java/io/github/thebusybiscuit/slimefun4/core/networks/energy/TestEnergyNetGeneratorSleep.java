package io.github.thebusybiscuit.slimefun4.core.networks.energy;

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
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.EnergyRegulator;
import io.github.thebusybiscuit.slimefun4.implementation.items.electric.generators.CoalGenerator;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class TestEnergyNetGeneratorSleep {

    private static ServerMock server;
    private static Slimefun plugin;
    private static CoalGenerator coalGenerator;

    @BeforeAll
    static void load() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(Slimefun.class);

        ItemGroup group = TestUtilities.getItemGroup(plugin, "energynet_generator_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_COAL_GENERATOR", Material.FURNACE, "&7Test Coal Generator");

        coalGenerator = new CoalGenerator(group, item, RecipeType.NULL, new ItemStack[9]);
        coalGenerator.setCapacity(1000);
        coalGenerator.setEnergyProduction(10);
        coalGenerator.register(plugin);

        new EnergyRegulator(group, SlimefunItems.ENERGY_REGULATOR, RecipeType.NULL, new ItemStack[9]).register(plugin);
    }

    @AfterAll
    static void unload() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("Test EnergyNet skips a sleeping generator until it is woken")
    void testSkipsSleepingGenerator() {
        World world = TestUtilities.createWorld(server);
        Block regulatorBlock = world.getBlockAt(0, 65, 0);
        Location regulatorLocation = regulatorBlock.getLocation();

        Block generatorBlock = world.getBlockAt(0, 66, 0);
        Location generatorLocation = generatorBlock.getLocation();
        BlockStorage.addBlockInfo(generatorBlock, "id", coalGenerator.getId());

        EnergyNet network = EnergyNet.getNetworkFromLocationOrCreate(regulatorLocation);
        network.tick(regulatorBlock);

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(generatorLocation), "The generator should fall asleep after a fuel-less tick");

        BlockMenu menu = BlockStorage.getInventory(generatorLocation);
        menu.replaceExistingItem(coalGenerator.getInputSlots()[0], new ItemStack(Material.COAL));

        network.tick(regulatorBlock);

        Assertions.assertNull(coalGenerator.getMachineProcessor().getOperation(generatorLocation), "A sleeping generator must not notice fuel inserted without waking it");
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(generatorLocation));

        Slimefun.getTickerTask().wakeLocation(generatorLocation);
        network.tick(regulatorBlock);

        Assertions.assertNotNull(coalGenerator.getMachineProcessor().getOperation(generatorLocation), "Once woken, the generator should find the fuel and start an operation");
        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(generatorLocation));
    }
}
