package me.mrCookieSlime.Slimefun.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;

class TestBlockStorage {

    private static ServerMock server;
    private static Slimefun plugin;
    private World world;

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
    public void setupWorld() {
        // addSimpleWorld() alone does not fire a WorldLoadEvent in MockBukkit, so
        // BlockStorage never gets registered for the world automatically. Mirror the
        // same manual registration TestUtilities.createWorld() already uses elsewhere
        // in this test suite so that BlockStorage.getStorage(world) resolves.
        world = TestUtilities.createWorld(server);
    }

    @Test
    @DisplayName("Test storing and retrieving block info round-trips correctly")
    void testStoreAndRetrieveBlockInfo() {
        Block block = world.getBlockAt(100, 65, -200);

        Assertions.assertFalse(BlockStorage.hasBlockInfo(block));

        BlockStorage.addBlockInfo(block, "id", "TEST_MACHINE");
        BlockStorage.addBlockInfo(block, "custom_key", "custom_value");

        Assertions.assertTrue(BlockStorage.hasBlockInfo(block));
        Assertions.assertEquals("TEST_MACHINE", BlockStorage.getLocationInfo(block.getLocation(), "id"));
        Assertions.assertEquals("custom_value", BlockStorage.getLocationInfo(block.getLocation(), "custom_key"));
    }

    @Test
    @DisplayName("Test negative and large coordinates round-trip correctly")
    void testExtremeCoordinates() {
        // Note: MockBukkit's simple test worlds only support Y in [0, 128), unlike a
        // real 1.18+ world (-64..320), so Y=0 stands in for the brief's Y=-64 here;
        // the X/Z magnitudes are what this test is actually exercising.
        Block block = world.getBlockAt(-3000000, 0, 3000000);

        BlockStorage.addBlockInfo(block, "id", "EDGE_MACHINE");

        Assertions.assertEquals("EDGE_MACHINE", BlockStorage.getLocationInfo(block.getLocation(), "id"));
    }

    @Test
    @DisplayName("Test that two blocks in the same world at different coordinates don't collide")
    void testNoCrossBlockCollision() {
        Block blockA = world.getBlockAt(5, 70, 5);
        Block blockB = world.getBlockAt(5, 71, 5);

        BlockStorage.addBlockInfo(blockA, "id", "MACHINE_A");
        BlockStorage.addBlockInfo(blockB, "id", "MACHINE_B");

        Assertions.assertEquals("MACHINE_A", BlockStorage.getLocationInfo(blockA.getLocation(), "id"));
        Assertions.assertEquals("MACHINE_B", BlockStorage.getLocationInfo(blockB.getLocation(), "id"));
    }

    @Test
    @DisplayName("Test deleteLocationInfoUnsafely removes stored data")
    void testDeleteLocationInfo() {
        Block block = world.getBlockAt(1, 65, 1);
        BlockStorage.addBlockInfo(block, "id", "TEMP_MACHINE");

        Assertions.assertTrue(BlockStorage.hasBlockInfo(block));

        BlockStorage.deleteLocationInfoUnsafely(block.getLocation(), true);

        Assertions.assertFalse(BlockStorage.hasBlockInfo(block));
    }

    @Test
    @DisplayName("Test moveLocationInfoUnsafely transfers data to the new location")
    void testMoveLocationInfo() {
        Block from = world.getBlockAt(10, 65, 10);
        Block to = world.getBlockAt(20, 65, 20);

        BlockStorage.addBlockInfo(from, "id", "MOVED_MACHINE");
        BlockStorage.moveLocationInfoUnsafely(from.getLocation(), to.getLocation());

        Assertions.assertFalse(BlockStorage.hasBlockInfo(from));
        Assertions.assertEquals("MOVED_MACHINE", BlockStorage.getLocationInfo(to.getLocation(), "id"));
    }

    @Test
    @DisplayName("Test getRawStorage returns an immutable snapshot containing stored blocks")
    void testGetRawStorage() {
        Block block = world.getBlockAt(50, 65, 50);
        BlockStorage.addBlockInfo(block, "id", "RAW_MACHINE");

        java.util.Map<Location, me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config> raw = BlockStorage.getRawStorage(world);

        Assertions.assertNotNull(raw);
        Assertions.assertEquals("RAW_MACHINE", raw.get(block.getLocation()).getString("id"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> raw.put(block.getLocation(), null));
    }

    @Test
    @DisplayName("Test moveLocationInfoUnsafely also transfers an existing inventory to the new location")
    void testMoveLocationInfoTransfersInventory() {
        Block from = world.getBlockAt(30, 65, 30);
        Block to = world.getBlockAt(40, 65, 40);

        BlockStorage.addBlockInfo(from, "id", "INVENTORY_MACHINE");

        BlockStorage storage = BlockStorage.getStorage(world);
        Assertions.assertNotNull(storage);

        // Give `from` a real inventory (bypassing setBlockInfo's own preset-lookup
        // plumbing, per this test's own minimal BlockMenuPreset) so the
        // storage.inventories.containsKey(fromPacked) branch in
        // moveLocationInfoUnsafely is actually exercised, not just the block-info
        // copy that the other move test already covers.
        TestBlockMenuPreset preset = new TestBlockMenuPreset("test_inventory_preset_" + System.nanoTime());
        BlockMenu original = storage.loadInventory(from.getLocation(), preset);

        Assertions.assertTrue(BlockStorage.hasInventory(from));
        Assertions.assertFalse(BlockStorage.hasInventory(to));

        BlockStorage.moveLocationInfoUnsafely(from.getLocation(), to.getLocation());

        Assertions.assertFalse(BlockStorage.hasInventory(from));
        Assertions.assertTrue(BlockStorage.hasInventory(to));
        Assertions.assertSame(original, BlockStorage.getInventory(to.getLocation()));
    }

    /**
     * A minimal, self-contained {@link BlockMenuPreset} used only to give a test
     * {@link Block} a real inventory, without needing a fully registered
     * {@link io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem}.
     */
    private static final class TestBlockMenuPreset extends BlockMenuPreset {

        TestBlockMenuPreset(String id) {
            super(id, "Test Inventory");
        }

        @Override
        public void init() {
            setSize(9);
        }

        @Override
        public boolean canOpen(Block b, Player p) {
            return true;
        }

        @Override
        public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
            return new int[0];
        }
    }
}
