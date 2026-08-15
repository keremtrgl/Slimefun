package io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.accelerators;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.World;

class TestCropGrowthAcceleratorSleep {

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

    /**
     * Builds a world the same way {@link TestUtilities#createWorld(ServerMock)} does (registering
     * it with Slimefun's {@code BlockStorage} registry so {@code BlockStorage.addBlockInfo(...)} and
     * {@code BlockStorage.getInventory(...)} actually work), but as a {@link WorldMock} subclass that
     * no-ops the specific {@code spawnParticle} overload used by {@code CropGrowthAccelerator#grow()}.
     *
     * MockBukkit-v1.21 3.133.2 does not implement
     * {@code spawnParticle(Particle, Location, int, double, double, double)} - it unconditionally
     * throws {@code UnimplementedOperationException}. Production code calls it unconditionally on a
     * successful growth tick, so without this override {@link #testGrowsWithFertilizer()} could never
     * pass against real (unmodified) production code.
     */
    private static World createTestWorld(String name) {
        WorldMock world = new WorldMock() {
            @Override
            public void spawnParticle(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
                // Intentionally no-op; see method javadoc above.
            }
        };
        world.setName(name);
        server.addWorld(world);
        Slimefun.getRegistry().getWorlds().put(world.getName(), new BlockStorage(world));
        return world;
    }

    /**
     * MockBukkit-v1.21 3.133.2 has no {@code Ageable} implementation for crop block data - calling
     * {@code Material.WHEAT.createBlockData()} (e.g. via {@code Block#setType}) returns a plain
     * {@code BlockDataMock} that cannot be cast to {@link Ageable}. We instead build our own minimal,
     * stateful {@link Ageable} mock and attach it directly via {@code Block#setBlockData}, which only
     * reads {@code getMaterial()} off the object it's given.
     */
    private static Ageable mockAgeableCrop(Material material, int maximumAge, int initialAge) {
        int[] age = { initialAge };
        Ageable ageable = Mockito.mock(Ageable.class);
        Mockito.when(ageable.getMaterial()).thenReturn(material);
        Mockito.when(ageable.getMaximumAge()).thenReturn(maximumAge);
        Mockito.when(ageable.getAge()).thenAnswer(invocation -> age[0]);
        Mockito.doAnswer(invocation -> {
            age[0] = invocation.getArgument(0);
            return null;
        }).when(ageable).setAge(Mockito.anyInt());
        return ageable;
    }

    @Test
    @DisplayName("Test a crop stays ungrown when the accelerator has no fertilizer, without scanning the world")
    void testNoFertilizerMeansNoGrowth() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.register(plugin);

        World world = createTestWorld("crop_accelerator_sleep_test_world_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setBlockData(mockAgeableCrop(Material.WHEAT, 7, 0));

        // No fertilizer in the input inventory - the crop must not grow
        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(0, afterTick.getAge());
    }

    @Test
    @DisplayName("Test a crop still grows when fertilizer is present")
    void testGrowsWithFertilizer() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test_2");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR_2", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.register(plugin);

        World world = createTestWorld("crop_accelerator_sleep_test_world_2_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(machine.getInputSlots()[0], io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems.FERTILIZER.item());

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setBlockData(mockAgeableCrop(Material.WHEAT, 7, 0));

        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(1, afterTick.getAge());
    }
}
