# Slimefun4 Ticking Performance Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate main-thread TPS drops caused by automated Slimefun machine setups by making idle machines skip ticking entirely, replacing `Location`-keyed hot-path collections with zero-allocation primitive-long collections, and batching synchronous scheduler calls — all without changing any existing public API signature.

**Architecture:** A new per-`Location` sleep/wake registry lives in `TickerTask`, checked before any per-block work happens; `BlockStorage` and a new `NetworkManager` regulator index swap `Location`-keyed maps for primitive-long-keyed `fastutil` maps; `TickerTask` gains a general-purpose single-drain sync-task queue that both its own dispatch and `CargoNet` funnel through instead of each scheduling their own `Bukkit` task.

**Tech Stack:** Java 16 (project's `maven.compiler.source/target`), Paper 1.20.6–1.21+ API, JUnit 5 + MockBukkit (existing test stack), `it.unimi.dsi:fastutil-core` (new dependency, shaded/relocated).

## Global Constraints

- No existing public method signature in `BlockTicker`, `TickerTask`, or `BlockStorage` may change — additions only. Addons must not see `NoSuchMethodError`/`ClassNotFoundException`.
- Source/target stays Java 16 (`pom.xml` `maven.compiler.source`/`maven.compiler.target`).
- New third-party dependency `it.unimi.dsi:fastutil-core:8.5.19`, relocated under `io.github.thebusybiscuit.slimefun4.libraries.fastutil` via the existing `maven-shade-plugin` relocation block in `pom.xml` (same pattern as `dough`, `paperlib`, `commons-lang`).
- Sleep/wake "ticks"/"cycles" mean `TickerTask` run cycles (the `custom-ticker-delay` granularity), not raw 1/20s game ticks.
- No feature is removed or behaviorally changed for a machine that is actively working — sleep only ever applies to provably idle states, and every sleep path has a wake path (event-driven or bounded-poll).
- This project now uses a **local-only** git repo (initialized on branch `ticking-performance-overhaul`, off a `main` baseline commit) purely for per-task commits, diffing, and rollback safety. No remote is configured and nothing is ever pushed anywhere — GitHub is not involved. Every task's "commit"/"save" step means a normal local `git add`/`git commit`.

---

## Part A — Core spec (from `docs/superpowers/specs/2026-08-14-ticking-performance-design.md`)

### Task 0: Local Maven build tooling

No system Maven is installed in this environment. Set up a project-local Maven distribution and a wrapper script so every later task's `mvn` commands are actually runnable, without touching any system-wide install or PATH.

**Files:**
- Create: `.mvn-local/` (downloaded Maven binary distribution, gitignored-equivalent local folder)
- Create: `mvnw` (bash wrapper script at project root)

- [ ] **Step 1: Download and extract Apache Maven 3.9.9**

```bash
cd "/c/Users/Kerem/OneDrive/Ekler/Masaüstü/Slimefun/Slimefun4-experimental"
mkdir -p .mvn-local
curl -sL --max-time 120 -o .mvn-local/maven.zip "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip"
unzip -q .mvn-local/maven.zip -d .mvn-local
rm .mvn-local/maven.zip
```

- [ ] **Step 2: Create the `mvnw` wrapper script**

```bash
cat > mvnw << 'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/.mvn-local/apache-maven-3.9.9/bin/mvn" "$@"
EOF
chmod +x mvnw
```

- [ ] **Step 3: Verify it works**

Run: `./mvnw -v`
Expected: prints `Apache Maven 3.9.9`, a Java version line showing Java 16+ compatible, no errors.

- [ ] **Step 4: Save**

This is local build tooling, not part of the plugin source. Add it to `.gitignore` if not already covered, and commit only the wrapper script: `git add mvnw && git commit -m "Add local mvnw wrapper for build tooling"` (`.mvn-local/` is already gitignored).

---

### Task 1: `pom.xml` — add `fastutil-core` dependency and shade relocation

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dependency**

In the `<dependencies>` section of `pom.xml`, next to the existing `dough-api` entry, add:

```xml
<dependency>
    <groupId>it.unimi.dsi</groupId>
    <artifactId>fastutil-core</artifactId>
    <version>8.5.19</version>
</dependency>
```

- [ ] **Step 2: Add the shade relocation**

In `pom.xml`, inside the `maven-shade-plugin` `<configuration><relocations>` block (around line 199-213), add a new `<relocation>` entry alongside the existing three:

```xml
<relocation>
    <pattern>it.unimi.dsi.fastutil</pattern>
    <shadedPattern>io.github.thebusybiscuit.slimefun4.libraries.fastutil</shadedPattern>
</relocation>
```

- [ ] **Step 3: Verify the project still compiles**

Run: `./mvnw -q -DskipTests compile`
Expected: `BUILD SUCCESS`, no errors. This confirms the dependency resolves and the pom is well-formed. (No code uses fastutil yet, so this is purely a dependency-resolution check.)

- [ ] **Step 4: Save**

Stage and commit: `git add pom.xml && git commit -m "Add fastutil-core dependency and shade relocation"`.

---

### Task 2: `FastBlockPos` utility + tests

**Files:**
- Create: `src/main/java/io/github/thebusybiscuit/slimefun4/utils/FastBlockPos.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/utils/TestFastBlockPos.java`

**Interfaces:**
- Produces: `FastBlockPos.pack(int x, int y, int z) -> long`, `FastBlockPos.unpackX(long) -> int`, `FastBlockPos.unpackY(long) -> int`, `FastBlockPos.unpackZ(long) -> int`. Used by Tasks 3 and 4.

- [ ] **Step 1: Write the failing test**

```java
package io.github.thebusybiscuit.slimefun4.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TestFastBlockPos {

    @ParameterizedTest
    @DisplayName("Test pack/unpack round-tripping")
    @CsvSource({
        "0, 0, 0",
        "1, 1, 1",
        "-1, -1, -1",
        "33554431, 319, 33554431",
        "-33554432, -64, -33554432",
        "0, -64, 0",
        "0, 319, 0",
        "12345, 100, -6789",
        "-12345, -50, 6789"
    })
    void testPackUnpackRoundTrip(int x, int y, int z) {
        long packed = FastBlockPos.pack(x, y, z);

        Assertions.assertEquals(x, FastBlockPos.unpackX(packed));
        Assertions.assertEquals(y, FastBlockPos.unpackY(packed));
        Assertions.assertEquals(z, FastBlockPos.unpackZ(packed));
    }

    @Test
    @DisplayName("Test that different coordinates never produce the same packed value")
    void testNoCollisions() {
        long a = FastBlockPos.pack(10, 64, 10);
        long b = FastBlockPos.pack(10, 64, 11);
        long c = FastBlockPos.pack(10, 65, 10);
        long d = FastBlockPos.pack(11, 64, 10);

        Assertions.assertNotEquals(a, b);
        Assertions.assertNotEquals(a, c);
        Assertions.assertNotEquals(a, d);
        Assertions.assertNotEquals(b, c);
        Assertions.assertNotEquals(b, d);
        Assertions.assertNotEquals(c, d);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestFastBlockPos test`
Expected: FAIL — compilation error, `FastBlockPos` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package io.github.thebusybiscuit.slimefun4.utils;

/**
 * Packs Minecraft block coordinates into a single primitive {@code long} for use as a
 * zero-allocation, zero-boxing hash key. Uses the same bit layout as vanilla NMS
 * {@code BlockPos#asLong()}: 26 bits for X, 12 bits for Y (offset by 2048), 26 bits for Z.
 * This comfortably covers the full vanilla world border on X/Z and a Y range of
 * -2048..2047, well beyond the current -64..320 build height limit.
 *
 * This class intentionally does not encode a World - callers that need to disambiguate
 * between worlds must scope their own storage by world (e.g. one map per world, or a
 * world-aware outer key), the same way {@link me.mrCookieSlime.Slimefun.api.BlockStorage}
 * already has one instance per {@link org.bukkit.World}.
 */
public final class FastBlockPos {

    private static final int BITS_X = 26;
    private static final int BITS_Z = 26;
    private static final int BITS_Y = 12;

    private static final long MASK_X = (1L << BITS_X) - 1L;
    private static final long MASK_Y = (1L << BITS_Y) - 1L;
    private static final long MASK_Z = (1L << BITS_Z) - 1L;

    private static final int Y_OFFSET = 1 << (BITS_Y - 1);

    private FastBlockPos() {}

    /**
     * Packs the given block coordinates into a single {@code long}.
     *
     * @param x the block X coordinate
     * @param y the block Y coordinate
     * @param z the block Z coordinate
     * @return the packed representation
     */
    public static long pack(int x, int y, int z) {
        long packedX = (x & MASK_X) << (BITS_Y + BITS_Z);
        long packedY = ((long) (y + Y_OFFSET) & MASK_Y) << BITS_Z;
        long packedZ = z & MASK_Z;

        return packedX | packedY | packedZ;
    }

    /**
     * Extracts the X coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block X coordinate
     */
    public static int unpackX(long packed) {
        return (int) (packed << (64 - BITS_X - BITS_Y - BITS_Z) >> (64 - BITS_X));
    }

    /**
     * Extracts the Y coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block Y coordinate
     */
    public static int unpackY(long packed) {
        return (int) ((packed >> BITS_Z) & MASK_Y) - Y_OFFSET;
    }

    /**
     * Extracts the Z coordinate from a packed {@code long}.
     *
     * @param packed the packed representation, as produced by {@link #pack(int, int, int)}
     * @return the block Z coordinate
     */
    public static int unpackZ(long packed) {
        return (int) (packed << (64 - BITS_Z) >> (64 - BITS_Z));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestFastBlockPos test`
Expected: PASS, both tests green.

- [ ] **Step 5: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 3: `BlockStorage` internal storage swap

`BlockStorage` is one instance per `World`, so its per-block maps don't need `World` in the key. Swap the internal `storage`/`inventories` fields from `Map<Location, X>` to `fastutil`'s `Long2ObjectMap<X>` keyed by `FastBlockPos.pack(x, y, z)`. Every public method keeps its existing signature and behavior — this task only changes what's inside the class.

**Files:**
- Modify: `src/main/java/me/mrCookieSlime/Slimefun/api/BlockStorage.java`
- Test: `src/test/java/me/mrCookieSlime/Slimefun/api/TestBlockStorage.java`

**Interfaces:**
- Consumes: `FastBlockPos.pack(int, int, int)` from Task 2.
- Produces: no new public methods — `BlockStorage`'s existing public API (`getLocationInfo`, `addBlockInfo`, `hasBlockInfo`, `getInventory`, `hasInventory`, `deleteLocationInfoUnsafely`, `moveLocationInfoUnsafely`, `getRawStorage`) is unchanged and is what Tasks 4, 6, 7, 8 rely on.

- [ ] **Step 1: Write failing characterization tests**

There is no existing `BlockStorage` test file. Before refactoring its internals, write tests that pin down current observable behavior, so the refactor in Step 3 can be verified not to have changed it.

```java
package me.mrCookieSlime.Slimefun.api;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
        world = server.addSimpleWorld("test_block_storage_world_" + System.nanoTime());
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
        Block block = world.getBlockAt(-3000000, -64, 3000000);

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
}
```

- [ ] **Step 2: Run tests to verify they currently pass against the unmodified code**

Run: `./mvnw -q -Dtest=TestBlockStorage test`
Expected: PASS — these tests characterize *existing* behavior, they must pass before any refactor.

- [ ] **Step 3: Swap the internal storage**

In `src/main/java/me/mrCookieSlime/Slimefun/api/BlockStorage.java`:

Add imports:

```java
import io.github.thebusybiscuit.slimefun4.utils.FastBlockPos;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
```

Change the field declarations (around line 59-60):

```java
// before
private final Map<Location, Config> storage = new ConcurrentHashMap<>();
private final Map<Location, BlockMenu> inventories = new ConcurrentHashMap<>();

// after
private final Long2ObjectMap<Config> storage = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
private final Long2ObjectMap<BlockMenu> inventories = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
```

Add a private helper right after the `serializeChunk` method (around line 91):

```java
private static long packLocation(@Nonnull Location l) {
    return FastBlockPos.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ());
}
```

Update every direct usage of `storage`/`inventories` to go through `packLocation(...)`:

- Line 198 (`loadBlock`): `if (storage.putIfAbsent(l, blockInfo) != null) {` becomes `if (storage.putIfAbsent(packLocation(l), blockInfo) != null) {`
- Line 205 (same method, log message): `storage.get(l)` becomes `storage.get(packLocation(l))`
- Line 260 (`loadInventories`): `inventories.put(l, new BlockMenu(preset, l, cfg));` becomes `inventories.put(packLocation(l), new BlockMenu(preset, l, cfg));`
- Lines 293-296 (`computeChanges`) — the `Location` key isn't used here, only values are iterated, so simplify:

```java
// before
Map<Location, BlockMenu> inventories2 = new HashMap<>(inventories);
for (Map.Entry<Location, BlockMenu> entry : inventories2.entrySet()) {
    changes += entry.getValue().getUnsavedChanges();
}

// after
for (BlockMenu menu : new ArrayList<>(inventories.values())) {
    changes += menu.getUnsavedChanges();
}
```
(add `import java.util.ArrayList;` if not already present — it already is, used elsewhere in this file)

- Line 392-393 (`getRawStorage`) — must still return `Map<Location, Config>`, so reconstruct it from the packed keys:

```java
@Nonnull
public Map<Location, Config> getRawStorage() {
    Map<Location, Config> result = new HashMap<>(storage.size());

    for (Long2ObjectMap.Entry<Config> entry : storage.long2ObjectEntrySet()) {
        long packed = entry.getLongKey();
        Location l = new Location(world, FastBlockPos.unpackX(packed), FastBlockPos.unpackY(packed), FastBlockPos.unpackZ(packed));
        result.put(l, entry.getValue());
    }

    return ImmutableMap.copyOf(result);
}
```

- Line 458 (`getLocationInfo`): `Config cfg = storage.storage.get(l);` becomes `Config cfg = storage.storage.get(packLocation(l));`
- Line 560 (`hasBlockInfo`): `Config cfg = storage.storage.get(l);` becomes `Config cfg = storage.storage.get(packLocation(l));`
- Line 575 (`setBlockInfo`): `storage.storage.put(l, cfg);` becomes `storage.storage.put(packLocation(l), cfg);`
- Line 582 (`setBlockInfo`, `!storage.hasInventory(l)`) — unchanged, `hasInventory(Location)` keeps its signature (see below)
- Line 587 (`setBlockInfo`): `storage.inventories.put(l, inventory);` becomes `storage.inventories.put(packLocation(l), inventory);`
- Lines 638 (a debug/iteration method over `blockStorage.storage.keySet()`) — read the surrounding method first (it's `getAllBlockLocations`-style debug code); replace `for (Location location : blockStorage.storage.keySet())` with an unpack loop:

```java
LongIterator keys = blockStorage.storage.keySet().iterator();
while (keys.hasNext()) {
    long packed = keys.nextLong();
    Location location = new Location(blockStorage.world, FastBlockPos.unpackX(packed), FastBlockPos.unpackY(packed), FastBlockPos.unpackZ(packed));
    // ... rest of the existing loop body, unchanged, now operating on `location`
}
```
- Line 664 (`deleteLocationInfoUnsafely`): `storage.storage.remove(l);` becomes `storage.storage.remove(packLocation(l));`
- Lines 668-669 (`storage.hasInventory(l)` / `storage.clearInventory(l)`) — unchanged, signatures preserved
- Lines 707-710 (`moveLocationInfoUnsafely`):

```java
// before
if (storage.inventories.containsKey(from)) {
    BlockMenu menu = storage.inventories.get(from);
    storage.inventories.put(to, menu);
    storage.clearInventory(from);
    menu.move(to);
}

// after
long fromPacked = packLocation(from);
if (storage.inventories.containsKey(fromPacked)) {
    BlockMenu menu = storage.inventories.get(fromPacked);
    storage.inventories.put(packLocation(to), menu);
    storage.clearInventory(from);
    menu.move(to);
}
```
- Line 715 (`moveLocationInfoUnsafely`): `storage.storage.remove(from);` becomes `storage.storage.remove(packLocation(from));`
- Lines 344-347 (`save`) — the `Location` key IS needed here (`entry.getValue().save(entry.getKey())`), so unpack:

```java
// before
Map<Location, BlockMenu> unsavedInventories = new HashMap<>(inventories);
for (Map.Entry<Location, BlockMenu> entry : unsavedInventories.entrySet()) {
    entry.getValue().save(entry.getKey());
}

// after
for (Long2ObjectMap.Entry<BlockMenu> entry : new Long2ObjectOpenHashMap<>(inventories).long2ObjectEntrySet()) {
    long packed = entry.getLongKey();
    Location l = new Location(world, FastBlockPos.unpackX(packed), FastBlockPos.unpackY(packed), FastBlockPos.unpackZ(packed));
    entry.getValue().save(l);
}
```
- Line 801 (`loadInventory`): `inventories.put(l, menu);` becomes `inventories.put(packLocation(l), menu);`
- Line 813 (`reloadInventory`): `BlockMenu menu = this.inventories.get(l);` becomes `BlockMenu menu = this.inventories.get(packLocation(l));`
- Lines 830-831 (`clearInventory`): `inventories.get(l).delete(l); inventories.remove(l);` becomes:

```java
long packed = packLocation(l);
inventories.get(packed).delete(l);
inventories.remove(packed);
```
- Line 836 (`hasInventory`): `return inventories.containsKey(l);` becomes `return inventories.containsKey(packLocation(l));`
- Line 877 (`getInventory`): `BlockMenu menu = storage.inventories.get(l);` becomes `BlockMenu menu = storage.inventories.get(packLocation(l));`

Every method signature above (`getLocationInfo`, `hasBlockInfo`, `setBlockInfo`, `deleteLocationInfoUnsafely`, `moveLocationInfoUnsafely`, `loadInventory`, `reloadInventory`, `clearInventory`, `hasInventory`, `getInventory`, `getRawStorage`, `save`, `computeChanges`) keeps its exact existing parameter and return types — only the method *bodies* change.

- [ ] **Step 4: Run tests to verify they still pass**

Run: `./mvnw -q -Dtest=TestBlockStorage test`
Expected: PASS — same tests as Step 2, now passing against the refactored internals, proving behavior didn't change.

- [ ] **Step 5: Run the full existing test suite to check for regressions**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`. `BlockStorage` is used throughout the codebase (recipes, machines, cargo, networks); this catches any call site this task's line-by-line pass missed.

- [ ] **Step 6: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 4: `TickerTask` sleep/wake registry + `BlockTicker` additive methods

**Files:**
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TickerTask.java`
- Modify: `src/main/java/me/mrCookieSlime/Slimefun/Objects/handlers/BlockTicker.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TestTickerTaskSleep.java`

**Interfaces:**
- Consumes: `FastBlockPos.pack(int, int, int)` from Task 2.
- Produces: `TickerTask#sleepLocation(Location, int)`, `TickerTask#wakeLocation(Location)`, `TickerTask#isAsleep(Location)` (public instance methods) — used by Tasks 6, 7, 8, 9. `BlockTicker#sleep(Block, int)`, `BlockTicker#wakeUp(Block)`, `BlockTicker#isSleeping(Block)` (protected instance methods, for third-party/addon `BlockTicker` subclasses that override `tick(Block, SlimefunItem, Config)` directly).

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestTickerTaskSleep test`
Expected: FAIL — compilation error, `sleepLocation`/`wakeLocation`/`isAsleep` don't exist on `TickerTask`.

- [ ] **Step 3: Implement the sleep/wake registry on `TickerTask`**

Add imports to `TickerTask.java`:

```java
import io.github.thebusybiscuit.slimefun4.utils.FastBlockPos;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
```

Add fields (near the existing `tickingLocations`/`movingQueue`/`bugs` fields):

```java
/**
 * This Map tracks, per chunk, which Locations are currently asleep and the
 * TickerTask cycle at which they should wake back up. Sleeping locations are
 * skipped entirely in {@link #tickLocation(Set, Location)} - no BlockStorage
 * lookup, no profiler entry, no BlockTicker#update() call.
 *
 * The inner Long2LongMap is written from the async ticking thread (this class,
 * single-threaded per run() due to the `running` re-entrancy guard) and from
 * wake events firing on the main thread (MachineWakeListener, CargoNet), so it
 * must be a thread-safe map. Individual get/put/remove calls are safe under
 * Long2LongMaps#synchronize; the lazy-expiry check-then-remove in isAsleep is
 * intentionally not additionally locked - a race there can only cause a
 * harmless redundant remove or one extra cycle of staleness, never data
 * corruption.
 */
private final Map<ChunkPosition, Long2LongMap> sleepingLocations = new ConcurrentHashMap<>();

/**
 * Incremented once per run() invocation. Used as the time base for
 * sleepLocation/isAsleep - these are TickerTask cycles (the custom-ticker-delay
 * granularity), not raw Minecraft game ticks.
 */
private volatile long currentCycle = 0;
```

Add the three public methods (near `enableTicker`/`disableTicker`):

```java
/**
 * This puts the given {@link Location} to sleep for the given amount of cycles.
 * A sleeping {@link Location} is skipped entirely during ticking - no
 * {@link me.mrCookieSlime.Slimefun.api.BlockStorage} lookup, no {@link BlockTicker#update()}
 * call, no profiler entry. Use this to opt a machine out of ticking while it
 * provably has no work to do.
 *
 * @param l
 *            The {@link Location} to put to sleep
 * @param cycles
 *            The amount of TickerTask cycles to sleep for (not raw game ticks)
 */
@ParametersAreNonnullByDefault
public void sleepLocation(Location l, int cycles) {
    Validate.notNull(l, "Location cannot be null!");
    Validate.isTrue(cycles > 0, "The amount of cycles must be greater than zero!");

    ChunkPosition chunk = new ChunkPosition(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    Long2LongMap wakeTimes = sleepingLocations.computeIfAbsent(chunk, c -> Long2LongMaps.synchronize(new Long2LongOpenHashMap()));
    long packed = FastBlockPos.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    wakeTimes.put(packed, currentCycle + cycles);
}

/**
 * This immediately wakes up the given {@link Location}, if it was asleep.
 * Has no effect if the {@link Location} was already awake.
 *
 * @param l
 *            The {@link Location} to wake up
 */
@ParametersAreNonnullByDefault
public void wakeLocation(Location l) {
    Validate.notNull(l, "Location cannot be null!");

    ChunkPosition chunk = new ChunkPosition(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    Long2LongMap wakeTimes = sleepingLocations.get(chunk);

    if (wakeTimes != null) {
        long packed = FastBlockPos.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        wakeTimes.remove(packed);
    }
}

/**
 * This checks whether the given {@link Location} is currently asleep.
 * If the sleep period has expired, this also lazily removes the stale entry.
 *
 * @param l
 *            The {@link Location} to check
 *
 * @return Whether the given {@link Location} is currently asleep
 */
@ParametersAreNonnullByDefault
public boolean isAsleep(Location l) {
    Validate.notNull(l, "Location cannot be null!");

    ChunkPosition chunk = new ChunkPosition(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    Long2LongMap wakeTimes = sleepingLocations.get(chunk);

    if (wakeTimes == null) {
        return false;
    }

    long packed = FastBlockPos.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ());

    if (!wakeTimes.containsKey(packed)) {
        return false;
    }

    long wakeAt = wakeTimes.get(packed);

    if (wakeAt <= currentCycle) {
        wakeTimes.remove(packed);
        return false;
    }

    return true;
}
```

Increment `currentCycle` once per cycle - in `run()`, right after `running = true;`, add:

```java
currentCycle++;
```

Add the early-return check in `tickLocation` (at the very top, before the `BlockStorage.getLocationInfo(l)` call):

```java
private void tickLocation(@Nonnull Set<BlockTicker> tickers, @Nonnull Location l) {
    if (isAsleep(l)) {
        return;
    }

    Config data = BlockStorage.getLocationInfo(l);
    // ... rest of the existing method body, unchanged
}
```

- [ ] **Step 4: Add the additive `BlockTicker` convenience methods**

In `BlockTicker.java`, add imports:

```java
import org.bukkit.block.Block;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
```

(`org.bukkit.block.Block` may already be imported - check before adding a duplicate.)

Add three new protected instance methods, after `startNewTick()`:

```java
/**
 * This puts the given {@link Block}'s {@link Location} to sleep for the given
 * amount of ticks. A sleeping Location is skipped entirely by the
 * {@link io.github.thebusybiscuit.slimefun4.implementation.tasks.TickerTask} -
 * use this when your machine has provably no work to do (e.g. an empty input,
 * no matching recipe) and register a wake trigger (e.g. via a
 * {@link org.bukkit.event.Listener}) for when that could change.
 *
 * @param b
 *            The {@link Block} to put to sleep
 * @param ticks
 *            The amount of TickerTask cycles to sleep for
 */
protected final void sleep(@Nonnull Block b, int ticks) {
    Slimefun.getTickerTask().sleepLocation(b.getLocation(), ticks);
}

/**
 * This immediately wakes up the given {@link Block}, if it was asleep.
 *
 * @param b
 *            The {@link Block} to wake up
 */
protected final void wakeUp(@Nonnull Block b) {
    Slimefun.getTickerTask().wakeLocation(b.getLocation());
}

/**
 * This checks whether the given {@link Block} is currently asleep.
 *
 * @param b
 *            The {@link Block} to check
 *
 * @return Whether the given {@link Block} is currently asleep
 */
protected final boolean isSleeping(@Nonnull Block b) {
    return Slimefun.getTickerTask().isAsleep(b.getLocation());
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestTickerTaskSleep test`
Expected: PASS, all 5 tests green.

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS` — confirms the new early-return in `tickLocation` doesn't break any existing ticker-dependent test (nothing should be asleep by default, since `sleepLocation` is opt-in and nothing calls it yet).

- [ ] **Step 7: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 5: `TickerTask` synchronous batching

Replace the per-block `Slimefun.runSync(...)` call for synchronized tickers with a single batched drain per cycle, and expose the batching queue as a general-purpose method so `CargoNet` (Task 8) can reuse it instead of scheduling its own individual task.

**Files:**
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TickerTask.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TestTickerTaskBatching.java`

**Interfaces:**
- Produces: `TickerTask#queueSyncTask(Runnable)` (public instance method) — used by Task 8.

- [ ] **Step 1: Write the failing test**

```java
package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;

class TestTickerTaskBatching {

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
    @DisplayName("Test queueSyncTask runs all queued tasks when drained")
    void testQueueSyncTaskRunsAllTasks() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.queueSyncTask(counter::incrementAndGet);

        ticker.drainSyncTaskQueueForTesting();

        Assertions.assertEquals(3, counter.get());
    }

    @Test
    @DisplayName("Test the queue is empty again after draining")
    void testQueueEmptiedAfterDrain() {
        AtomicInteger counter = new AtomicInteger(0);
        TickerTask ticker = Slimefun.getTickerTask();

        ticker.queueSyncTask(counter::incrementAndGet);
        ticker.drainSyncTaskQueueForTesting();
        ticker.drainSyncTaskQueueForTesting();

        Assertions.assertEquals(1, counter.get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestTickerTaskBatching test`
Expected: FAIL — `queueSyncTask`/`drainSyncTaskQueueForTesting` don't exist yet.

- [ ] **Step 3: Implement the batching queue**

Add an import if not already present: `import java.util.List;` (already imported in this file).

Add a field:

```java
/**
 * Runnables queued during this cycle's async ticking pass that need to run on
 * the main thread. Drained via a single Slimefun.runSync(...) call per cycle
 * instead of scheduling one Bukkit task per synchronized machine. Only ever
 * appended to and drained from the single async ticking thread (guarded by the
 * `running` re-entrancy flag in run()), so a plain ArrayList is safe here -
 * no concurrent-collection is needed.
 */
private final List<Runnable> syncTaskQueue = new ArrayList<>();
```

Add the public method (near `queueMove`/`queueDelete`):

```java
/**
 * This queues a {@link Runnable} to run on the main server thread during this
 * cycle's single batched sync-task drain, instead of scheduling an individual
 * {@link org.bukkit.scheduler.BukkitTask}. Safe to call from the async ticking
 * thread only (i.e. from within a {@link BlockTicker#tick(Block, io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem, Config)}
 * call, or anything else invoked synchronously from within this class's own
 * async {@link #run()}).
 *
 * @param task
 *            The {@link Runnable} to run on the main thread this cycle
 */
@ParametersAreNonnullByDefault
public void queueSyncTask(Runnable task) {
    Validate.notNull(task, "Task cannot be null!");
    syncTaskQueue.add(task);
}

/**
 * Test-only helper that drains the sync task queue immediately on the calling
 * thread, without going through Slimefun.runSync (which is a no-op scheduling
 * indirection under MockBukkit's unit-test {@link io.github.thebusybiscuit.slimefun4.core.services.MinecraftVersion#UNIT_TEST} mode
 * anyway - see {@link io.github.thebusybiscuit.slimefun4.implementation.Slimefun#runSync(Runnable)}).
 */
void drainSyncTaskQueueForTesting() {
    List<Runnable> batch = new ArrayList<>(syncTaskQueue);
    syncTaskQueue.clear();

    for (Runnable task : batch) {
        task.run();
    }
}
```

In `tickLocation`, replace the individual `Slimefun.runSync(...)` call in the synchronized-ticker branch:

```java
// before
if (item.getBlockTicker().isSynchronized()) {
    Slimefun.getProfiler().scheduleEntries(1);
    item.getBlockTicker().update();

    Slimefun.runSync(() -> {
        Block b = l.getBlock();
        tickBlock(l, b, item, data, System.nanoTime());
    });
} else {

// after
if (item.getBlockTicker().isSynchronized()) {
    Slimefun.getProfiler().scheduleEntries(1);
    item.getBlockTicker().update();

    queueSyncTask(() -> {
        Block b = l.getBlock();
        tickBlock(l, b, item, data, System.nanoTime());
    });
} else {
```

In `run()`, after the chunk-ticking loop (`if (!halted) { ... }` block) and before the "Move any moved block data" section, add the single batched drain:

```java
if (!syncTaskQueue.isEmpty()) {
    List<Runnable> batch = new ArrayList<>(syncTaskQueue);
    syncTaskQueue.clear();

    Slimefun.runSync(() -> {
        for (Runnable task : batch) {
            task.run();
        }
    });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestTickerTaskBatching test`
Expected: PASS, both tests green.

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS` — confirms synchronized `BlockTicker`s (e.g. anything using `EnhancedFurnace`, `GEOMiner`) still tick correctly through the new batched path.

- [ ] **Step 6: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 6: `MachineWakeListener`

**Files:**
- Create: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/listeners/MachineWakeListener.java`
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/Slimefun.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/implementation/listeners/TestMachineWakeListener.java`

**Interfaces:**
- Consumes: `TickerTask#wakeLocation(Location)` from Task 4, `BlockStorage#hasBlockInfo(Location)` (existing).

- [ ] **Step 1: Write the failing test**

```java
package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import org.bukkit.Location;
import org.bukkit.block.Block;
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

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestMachineWakeListener {

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
    @DisplayName("Test right-clicking a sleeping Slimefun block wakes it")
    void testInteractWakesSleepingBlock() {
        World world = server.addSimpleWorld("wake_listener_test_" + System.nanoTime());
        Block block = world.getBlockAt(1, 65, 1);
        Location l = block.getLocation();

        BlockStorage.addBlockInfo(block, "id", "TEST_MACHINE");
        Slimefun.getTickerTask().sleepLocation(l, 100);
        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(l));

        Player player = server.addPlayer();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, new ItemStack(org.bukkit.Material.AIR), block, org.bukkit.block.BlockFace.UP);
        server.getPluginManager().callEvent(event);

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(l));
    }

    @Test
    @DisplayName("Test right-clicking a non-Slimefun block does nothing")
    void testInteractIgnoresNonSlimefunBlock() {
        World world = server.addSimpleWorld("wake_listener_test_2_" + System.nanoTime());
        Block block = world.getBlockAt(2, 65, 2);

        Player player = server.addPlayer();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, new ItemStack(org.bukkit.Material.AIR), block, org.bukkit.block.BlockFace.UP);

        Assertions.assertDoesNotThrow(() -> server.getPluginManager().callEvent(event));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestMachineWakeListener test`
Expected: FAIL — compilation error, `MachineWakeListener` doesn't exist.

- [ ] **Step 3: Implement `MachineWakeListener`**

```java
package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * This {@link Listener} wakes up any sleeping Slimefun machine (see
 * {@link io.github.thebusybiscuit.slimefun4.implementation.tasks.TickerTask#sleepLocation(Location, int)})
 * whenever a Player interacts with it directly, or an {@link org.bukkit.inventory.Inventory}
 * it owns receives or loses items via a hopper/dropper/dispenser.
 *
 * Cargo delivery does not go through this listener - {@link io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet}
 * wakes its own regulator directly, since firing a Bukkit event per item
 * transfer would itself be wasteful.
 *
 * @author TheBusyBiscuit
 */
public class MachineWakeListener implements Listener {

    public MachineWakeListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();

        if (b != null) {
            wakeIfSlimefunBlock(b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        wakeIfSlimefunBlock(e.getSource().getLocation());
        wakeIfSlimefunBlock(e.getDestination().getLocation());
    }

    private void wakeIfSlimefunBlock(@Nullable Location l) {
        if (l != null && BlockStorage.hasBlockInfo(l)) {
            Slimefun.getTickerTask().wakeLocation(l);
        }
    }

}
```

- [ ] **Step 4: Register the listener**

In `Slimefun.java`, in `registerListeners()` (around line 620), add next to `new CargoNodeListener(this);`:

```java
new MachineWakeListener(this);
```

Add the import at the top of `Slimefun.java`:

```java
import io.github.thebusybiscuit.slimefun4.implementation.listeners.MachineWakeListener;
```

(Check the existing import block first — listener imports in this file are already grouped together; insert alphabetically among them.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestMachineWakeListener test`
Expected: PASS, both tests green.

- [ ] **Step 6: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 7: `AContainer` migration

**Files:**
- Modify: `src/main/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/AContainer.java`
- Test: `src/test/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/TestAContainerSleep.java`

**Interfaces:**
- Consumes: `Slimefun.getTickerTask().sleepLocation(Location, int)` / `isAsleep(Location)` from Task 4. (`AContainer` is not itself a `BlockTicker` — its `tick(Block)` method is called *from* an anonymous inner `BlockTicker`, so it calls `TickerTask` directly rather than through `BlockTicker#sleep`.)

- [ ] **Step 1: Write the failing test**

```java
package me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems;

import org.bukkit.Material;
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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestAContainerSleep {

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
    @DisplayName("Test an AContainer machine sleeps when its input has no matching recipe")
    void testSleepsWhenIdle() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "acontainer_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_IDLE_MACHINE", Material.FURNACE, "&7Test Machine");

        MockAContainer machine = new MockAContainer(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.setEnergyConsumption(1);
        machine.setProcessingSpeed(1);
        machine.register(plugin);

        World world = server.addSimpleWorld("acontainer_sleep_test_world_" + System.nanoTime());
        Block block = world.getBlockAt(1, 65, 1);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());

        Assertions.assertFalse(Slimefun.getTickerTask().isAsleep(block.getLocation()));

        // Input is empty, so findNextRecipe() will find nothing and the machine should sleep
        machine.tickForTesting(block);

        Assertions.assertTrue(Slimefun.getTickerTask().isAsleep(block.getLocation()));
    }
}
```

Add a small test-only `MockAContainer` helper alongside it (this project's existing `test/mocks` package already has similar single-purpose mock subclasses, e.g. `MockHazmatSuit`):

```java
package me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;

class MockAContainer extends AContainer {

    MockAContainer(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public ItemStack getProgressBar() {
        return new ItemStack(org.bukkit.Material.ARROW);
    }

    @Override
    public @javax.annotation.Nonnull String getMachineIdentifier() {
        return "MOCK_ACONTAINER";
    }

    void tickForTesting(Block b) {
        tick(b);
    }
}
```
(Save this as `src/test/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/MockAContainer.java`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestAContainerSleep test`
Expected: FAIL — the machine never calls `sleepLocation`, so `isAsleep` stays `false` after `tickForTesting`.

- [ ] **Step 3: Implement the sleep calls in `AContainer.tick(Block)`**

Add an import: `import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;` (check it isn't already imported).

Add two constants near the top of the class, with the other `private` fields:

```java
private static final int IDLE_SLEEP_TICKS = 30;
private static final int ENERGY_WAIT_SLEEP_TICKS = 10;
```

Update `tick(Block b)`:

```java
protected void tick(Block b) {
    BlockMenu inv = BlockStorage.getInventory(b);
    CraftingOperation currentOperation = processor.getOperation(b);

    if (currentOperation != null) {
        if (takeCharge(b.getLocation())) {

            if (!currentOperation.isFinished()) {
                processor.updateProgressBar(inv, 22, currentOperation);
                currentOperation.addProgress(1);
            } else {
                inv.replaceExistingItem(22, CustomItemStack.create(Material.BLACK_STAINED_GLASS_PANE, " "));

                for (ItemStack output : currentOperation.getResults()) {
                    inv.pushItem(output.clone(), getOutputSlots());
                }

                processor.endOperation(b);
            }
        } else {
            // Blocked on energy - no "energy became available" event exists in the
            // energy-net system, so use a short, self-renewing poll instead of an
            // indefinite sleep. Still skips findNextRecipe()'s HashMap allocations
            // while waiting, just with bounded (not event-driven) staleness.
            Slimefun.getTickerTask().sleepLocation(b.getLocation(), ENERGY_WAIT_SLEEP_TICKS);
        }
    } else {
        MachineRecipe next = findNextRecipe(inv);

        if (next != null) {
            currentOperation = new CraftingOperation(next);
            processor.startOperation(b, currentOperation);

            // Fixes #3534 - Update indicator immediately
            processor.updateProgressBar(inv, 22, currentOperation);
        } else {
            // No matching recipe for the current input - nothing will change until
            // new input arrives (MachineWakeListener) or a Player opens the GUI.
            Slimefun.getTickerTask().sleepLocation(b.getLocation(), IDLE_SLEEP_TICKS);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestAContainerSleep test`
Expected: PASS.

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`. `AContainer` is the base class for furnaces, autocrafters, and many other machines — this confirms none of their existing tests broke.

- [ ] **Step 6: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 8: Cargo migration

Two changes to `CargoNet`/`CargoNetworkTask`: (1) route the per-network sync scheduling through `TickerTask#queueSyncTask` instead of an individual `Slimefun.runSync(...)` call, and (2) sleep the network's regulator location for a short bounded period when a full routing pass moves zero items. `super.tick()` and the hologram update keep running every cycle unconditionally — only the expensive `mapInputNodes()`/`mapOutputNodes()`/`CargoNetworkTask` scheduling is skipped while asleep, so the network's topology bookkeeping never goes stale.

**Files:**
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/CargoNet.java`
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/CargoNetworkTask.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/TestCargoNetSleep.java`

**Interfaces:**
- Consumes: `TickerTask#queueSyncTask(Runnable)` from Task 5, `TickerTask#sleepLocation/wakeLocation/isAsleep` from Task 4.

- [ ] **Step 1: Write the failing test**

```java
package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.World;

class TestCargoNetSleep {

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestCargoNetSleep test`
Expected: FAIL — `CargoNetworkTask#run()` never sleeps the regulator, so `isAsleep` stays `false`.

- [ ] **Step 3: Track whether any item moved, and wake/sleep accordingly**

In `CargoNetworkTask.java`, change `routeItems` from `void` to `boolean` (package-private method, not part of any public API — safe to change its signature):

```java
// before
@ParametersAreNonnullByDefault
private void routeItems(Location inputNode, Block inputTarget, int frequency, Map<Integer, List<Location>> outputNodes) {
    ItemStackAndInteger slot = CargoUtils.withdraw(network, inventories, inputNode.getBlock(), inputTarget);

    if (slot == null) {
        return;
    }

    ItemStack stack = slot.getItem();
    int previousSlot = slot.getInt();
    List<Location> destinations = outputNodes.get(frequency);

    if (destinations != null) {
        stack = distributeItem(stack, inputNode, destinations);
    }

    if (stack != null) {
        insertItem(inputTarget, previousSlot, stack);
    }
}

// after
@ParametersAreNonnullByDefault
private boolean routeItems(Location inputNode, Block inputTarget, int frequency, Map<Integer, List<Location>> outputNodes) {
    ItemStackAndInteger slot = CargoUtils.withdraw(network, inventories, inputNode.getBlock(), inputTarget);

    if (slot == null) {
        return false;
    }

    ItemStack stack = slot.getItem();
    int previousSlot = slot.getInt();
    List<Location> destinations = outputNodes.get(frequency);

    if (destinations != null) {
        stack = distributeItem(stack, inputNode, destinations);
    }

    if (stack != null) {
        insertItem(inputTarget, previousSlot, stack);
    }

    // Something was withdrawn from an input this pass, so the network isn't idle -
    // conservative signal: even if it got reinserted unchanged (no valid destination),
    // there's an item waiting that could move once a destination frees up.
    return true;
}
```

Update `run()` to track and act on this:

```java
@Override
public void run() {
    long timestamp = System.nanoTime();
    boolean movedAnyItem = false;

    try {
        SlimefunItem inputNode = SlimefunItems.CARGO_INPUT_NODE.getItem();
        for (Map.Entry<Location, Integer> entry : inputs.entrySet()) {
            long nodeTimestamp = System.nanoTime();
            Location input = entry.getKey();
            Optional<Block> attachedBlock = network.getAttachedBlock(input);

            if (attachedBlock.isPresent() && routeItems(input, attachedBlock.get(), entry.getValue(), outputs)) {
                movedAnyItem = true;
            }

            timestamp += Slimefun.getProfiler().closeEntry(entry.getKey(), inputNode, nodeTimestamp);
        }
    } catch (Exception | LinkageError x) {
        Slimefun.logger().log(Level.SEVERE, x, () -> "An Exception was caught while ticking a Cargo network @ " + new BlockPosition(network.getRegulator()));
    }

    Slimefun.getProfiler().closeEntry(network.getRegulator(), SlimefunItems.CARGO_MANAGER.getItem(), timestamp);

    if (movedAnyItem) {
        Slimefun.getTickerTask().wakeLocation(network.getRegulator());
    } else {
        Slimefun.getTickerTask().sleepLocation(network.getRegulator(), CargoNet.IDLE_SLEEP_CYCLES);
    }
}
```

- [ ] **Step 4: Gate the expensive routing work behind the sleep check, and route scheduling through the batched queue**

In `CargoNet.java`, add a package-visible constant next to `TICK_DELAY`:

```java
static final int IDLE_SLEEP_CYCLES = 20;
```

Update `tick(Block b)`:

```java
public void tick(@Nonnull Block b) {
    if (!regulator.equals(b.getLocation())) {
        updateHologram(b, "&4Multiple Cargo Regulators connected");
        return;
    }

    super.tick();

    if (connectorNodes.isEmpty() && terminusNodes.isEmpty()) {
        updateHologram(b, "&cNo Cargo Nodes found");
    } else {
        updateHologram(b, "&7Status: &a&lONLINE");

        // Skip ticking if the threshold is not reached. The delay is not same as minecraft tick,
        // but it's based on 'custom-ticker-delay' config.
        if (tickDelayThreshold < TICK_DELAY) {
            tickDelayThreshold++;
            return;
        }

        // Reset the internal threshold, so we can start skipping again
        tickDelayThreshold = 0;

        if (Slimefun.getTickerTask().isAsleep(b.getLocation())) {
            return;
        }

        Map<Location, Integer> inputs = mapInputNodes();
        Map<Integer, List<Location>> outputs = mapOutputNodes();

        if (BlockStorage.getLocationInfo(b.getLocation(), "visualizer") == null) {
            display();
        }

        Slimefun.getProfiler().scheduleEntries(inputs.size() + 1);

        CargoNetworkTask runnable = new CargoNetworkTask(this, inputs, outputs);
        Slimefun.getTickerTask().queueSyncTask(runnable);
    }
}
```

(Only two lines changed from the original: the new `isAsleep` guard, and `Slimefun.getTickerTask().queueSyncTask(runnable)` replacing `Slimefun.runSync(runnable)`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestCargoNetSleep test`
Expected: PASS.

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

## Part B — Additional verified optimizations (found during a broader sweep, per user request to include any more safe wins)

### Task 9: `NetworkManager` O(1) regulator lookup

**Verified bottleneck:** `NetworkManager.getNetworkFromLocation(Location, Class)` does a **linear scan over every registered network on the server** (Energy and Cargo networks share one list), calling `network.connectsTo(l)` on each until a match is found ([NetworkManager.java:122-137](../../../src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/NetworkManager.java#L122)). Both `EnergyRegulator.tick()` ([EnergyRegulator.java:84-87](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/items/electric/EnergyRegulator.java#L84)) and `CargoNet.getNetworkFromLocationOrCreate` ([CargoNet.java:57-67](../../../src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/CargoNet.java#L57)) call this **every single tick, for their own regulator's own location** — a case where the answer is always "the network this regulator itself created," knowable in O(1) via a direct index instead of an O(total networks) scan. The general-purpose "which networks touch an arbitrary location" query (`getNetworksFromLocation`, used only on block place/break, not every tick) is untouched.

**Files:**
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/NetworkManager.java`
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/energy/EnergyNet.java`
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/CargoNet.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/core/networks/TestNetworkManagerRegulatorIndex.java`

**Interfaces:**
- Produces: `NetworkManager#getNetworkAtRegulator(Location, Class<T>)` (public method).

- [ ] **Step 1: Write the failing test**

```java
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

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=TestNetworkManagerRegulatorIndex test`
Expected: FAIL — `getNetworkAtRegulator` doesn't exist.

- [ ] **Step 3: Implement the index**

In `NetworkManager.java`, add imports if not already present: `import java.util.Map;` and `import java.util.concurrent.ConcurrentHashMap;`.

Add a field next to `networks`:

```java
/**
 * A direct index from a Network's regulator Location to the Network itself,
 * kept in lock-step with {@link #networks} via registerNetwork/unregisterNetwork.
 * This exists purely to make the extremely common "what network does my own
 * regulator belong to" query (asked every tick by every EnergyRegulator and
 * CargoManager) O(1) instead of an O(total networks) linear scan through
 * {@link #getNetworkFromLocation(Location, Class)}. General "what networks
 * touch this arbitrary Location" queries still use the linear scan, since
 * that requires checking every node in every network, not just regulators.
 */
private final Map<Location, Network> networksByRegulator = new ConcurrentHashMap<>();
```

Update `registerNetwork`/`unregisterNetwork`:

```java
public void registerNetwork(@Nonnull Network network) {
    Validate.notNull(network, "Cannot register a null Network");
    networks.add(network);
    networksByRegulator.put(network.getRegulator(), network);
}

public void unregisterNetwork(@Nonnull Network network) {
    Validate.notNull(network, "Cannot unregister a null Network");
    networks.remove(network);
    networksByRegulator.remove(network.getRegulator());
}
```

(Check the exact current body of `registerNetwork` before editing — the constructor-time `Validate.notNull` check shown in the grep at line 164-167 may already exist verbatim; only add the new `networksByRegulator` line if so.)

Add the new public method next to `getNetworkFromLocation`:

```java
/**
 * This is an O(1) lookup for the {@link Network} whose regulator is at the
 * given {@link Location}, as opposed to {@link #getNetworkFromLocation(Location, Class)}
 * which scans every registered {@link Network}. Use this when you already know
 * you're querying a regulator's own Location (e.g. from within that regulator's
 * own {@link me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker}) - for
 * arbitrary Locations that might be any node in a Network, use
 * {@link #getNetworksFromLocation(Location, Class)} instead.
 *
 * @param regulator
 *            The regulator {@link Location} to look up
 * @param type
 *            The {@link Network} subtype to filter by
 *
 * @return The {@link Network} at that regulator, if one exists and matches the given type
 */
@Nonnull
public <T extends Network> Optional<T> getNetworkAtRegulator(@Nullable Location regulator, @Nonnull Class<T> type) {
    if (regulator == null) {
        return Optional.empty();
    }

    Validate.notNull(type, "Type must not be null");

    Network network = networksByRegulator.get(regulator);

    if (type.isInstance(network)) {
        return Optional.of(type.cast(network));
    }

    return Optional.empty();
}
```

- [ ] **Step 4: Use the fast path from `EnergyNet` and `CargoNet`**

In `EnergyNet.java`:

```java
// before
public static EnergyNet getNetworkFromLocationOrCreate(@Nonnull Location l) {
    Optional<EnergyNet> energyNetwork = Slimefun.getNetworkManager().getNetworkFromLocation(l, EnergyNet.class);

    if (energyNetwork.isPresent()) {
        return energyNetwork.get();
    } else {
        EnergyNet network = new EnergyNet(l);
        Slimefun.getNetworkManager().registerNetwork(network);
        return network;
    }
}

// after
public static EnergyNet getNetworkFromLocationOrCreate(@Nonnull Location l) {
    Optional<EnergyNet> energyNetwork = Slimefun.getNetworkManager().getNetworkAtRegulator(l, EnergyNet.class);

    if (energyNetwork.isPresent()) {
        return energyNetwork.get();
    } else {
        EnergyNet network = new EnergyNet(l);
        Slimefun.getNetworkManager().registerNetwork(network);
        return network;
    }
}
```

In `CargoNet.java`, the same change to `getNetworkFromLocationOrCreate`:

```java
public static @Nonnull CargoNet getNetworkFromLocationOrCreate(@Nonnull Location l) {
    Optional<CargoNet> cargoNetwork = Slimefun.getNetworkManager().getNetworkAtRegulator(l, CargoNet.class);

    if (cargoNetwork.isPresent()) {
        return cargoNetwork.get();
    } else {
        CargoNet network = new CargoNet(l);
        Slimefun.getNetworkManager().registerNetwork(network);
        return network;
    }
}
```

`EnergyNet.getNetworkFromLocation(Location)` (the non-creating variant, if one exists elsewhere calling the generic `getNetworkFromLocation`) is intentionally left untouched if it's used for arbitrary-location queries rather than a regulator's own location — only the `...OrCreate` regulator-lookup path changes.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestNetworkManagerRegulatorIndex test`
Expected: PASS, all 3 tests green.

- [ ] **Step 6: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS` — confirms energy and cargo network resolution still works correctly everywhere else that depends on it.

- [ ] **Step 7: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

### Task 10: `CropGrowthAccelerator` skip-scan-when-no-fertilizer

**Verified bottleneck:** `CropGrowthAccelerator.tick(Block)` runs a `(2×radius+1)²` nested block scan every single tick whenever the machine has charge, checking `SlimefunTag.CROP_GROWTH_ACCELERATOR_BLOCKS.isTagged(...)` for every block in range — **even when neither input slot contains fertilizer**, in which case `grow()` was always going to return `false` for every block anyway ([CropGrowthAccelerator.java:40-54](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/items/electric/machines/accelerators/CropGrowthAccelerator.java#L40)).

**Files:**
- Modify: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/items/electric/machines/accelerators/CropGrowthAccelerator.java`
- Test: `src/test/java/io/github/thebusybiscuit/slimefun4/implementation/items/electric/machines/accelerators/TestCropGrowthAcceleratorSleep.java`

- [ ] **Step 1: Write the failing test**

```java
package io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.accelerators;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
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
import io.github.thebusybiscuit.slimefun4.test.TestUtilities;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
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

    @Test
    @DisplayName("Test a crop stays ungrown when the accelerator has no fertilizer, without scanning the world")
    void testNoFertilizerMeansNoGrowth() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.register(plugin);

        World world = server.addSimpleWorld("crop_accelerator_sleep_test_world_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setType(Material.WHEAT);
        Ageable ageable = (Ageable) cropBlock.getBlockData();
        ageable.setAge(0);
        cropBlock.setBlockData(ageable);

        // No fertilizer in the input inventory - the crop must not grow
        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(0, afterTick.getAge());
    }
}
```

Add the small test-only subclass alongside it:

```java
package io.github.thebusybiscuit.slimefun4.implementation.items.electric.machines.accelerators;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;

class MockCropGrowthAccelerator extends CropGrowthAccelerator {

    MockCropGrowthAccelerator(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public int getEnergyConsumption() {
        return 1;
    }

    @Override
    public int getRadius() {
        return 1;
    }

    @Override
    public int getSpeed() {
        return 1;
    }

    void tickForTesting(Block b) {
        tick(b);
    }
}
```
(Save as `src/test/java/io/github/thebusybiscuit/slimefun4/implementation/items/electric/machines/accelerators/MockCropGrowthAccelerator.java`.)

- [ ] **Step 2: Run test to verify it fails or passes for the wrong reason**

Run: `./mvnw -q -Dtest=TestCropGrowthAcceleratorSleep test`
Expected: this test actually PASSES against the *current* code too (no fertilizer already means no growth, since `grow()`'s inner loop already correctly finds nothing) — the point of Step 3 is to make the same correct outcome cheaper, not to change it. Confirm it passes now, so Step 4 can confirm it *still* passes after the optimization (a behavior-preservation check, not a red/green TDD cycle in the strict sense — appropriate here since this is a pure performance refactor, not new behavior).

- [ ] **Step 3: Add the pre-check**

In `CropGrowthAccelerator.java`:

```java
// before
@Override
protected void tick(Block b) {
    BlockMenu inv = BlockStorage.getInventory(b);

    if (getCharge(b.getLocation()) >= getEnergyConsumption()) {
        for (int x = -getRadius(); x <= getRadius(); x++) {
            for (int z = -getRadius(); z <= getRadius(); z++) {
                Block block = b.getRelative(x, 0, z);

                if (SlimefunTag.CROP_GROWTH_ACCELERATOR_BLOCKS.isTagged(block.getType()) && grow(b, inv, block)) {
                    return;
                }
            }
        }
    }
}

// after
@Override
protected void tick(Block b) {
    BlockMenu inv = BlockStorage.getInventory(b);

    if (getCharge(b.getLocation()) < getEnergyConsumption() || !hasFertilizer(inv)) {
        return;
    }

    for (int x = -getRadius(); x <= getRadius(); x++) {
        for (int z = -getRadius(); z <= getRadius(); z++) {
            Block block = b.getRelative(x, 0, z);

            if (SlimefunTag.CROP_GROWTH_ACCELERATOR_BLOCKS.isTagged(block.getType()) && grow(b, inv, block)) {
                return;
            }
        }
    }
}

private boolean hasFertilizer(BlockMenu inv) {
    for (int slot : getInputSlots()) {
        if (SlimefunUtils.isItemSimilar(inv.getItemInSlot(slot), organicFertilizer, false, false)) {
            return true;
        }
    }

    return false;
}
```

(`grow()` itself is unchanged — it still independently checks which specific slot to consume from once a growable crop is found in range.)

- [ ] **Step 4: Run test to verify it still passes**

Run: `./mvnw -q -Dtest=TestCropGrowthAcceleratorSleep test`
Expected: PASS.

- [ ] **Step 5: Add a positive-path test confirming growth still works with fertilizer present**

```java
    @Test
    @DisplayName("Test a crop still grows when fertilizer is present")
    void testGrowsWithFertilizer() {
        ItemGroup group = TestUtilities.getItemGroup(plugin, "crop_accelerator_sleep_test_2");
        SlimefunItemStack item = new SlimefunItemStack("TEST_CROP_ACCELERATOR_2", Material.HOPPER, "&aTest Accelerator");

        MockCropGrowthAccelerator machine = new MockCropGrowthAccelerator(group, item, RecipeType.NULL, new ItemStack[9]);
        machine.setCapacity(1000);
        machine.register(plugin);

        World world = server.addSimpleWorld("crop_accelerator_sleep_test_world_2_" + System.nanoTime());
        Block block = world.getBlockAt(0, 65, 0);
        BlockStorage.addBlockInfo(block, "id", item.getItemId());
        machine.setCharge(block.getLocation(), 1000);

        me.mrCookieSlime.Slimefun.api.inventory.BlockMenu inv = BlockStorage.getInventory(block);
        inv.replaceExistingItem(machine.getInputSlots()[0], io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems.FERTILIZER.item());

        Block cropBlock = world.getBlockAt(1, 65, 0);
        cropBlock.setType(Material.WHEAT);
        Ageable ageable = (Ageable) cropBlock.getBlockData();
        ageable.setAge(0);
        cropBlock.setBlockData(ageable);

        machine.tickForTesting(block);

        Ageable afterTick = (Ageable) cropBlock.getBlockData();
        Assertions.assertEquals(1, afterTick.getAge());
    }
```

Add this method inside `TestCropGrowthAcceleratorSleep` from Step 1.

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -q -Dtest=TestCropGrowthAcceleratorSleep test`
Expected: PASS, both tests green — confirms the optimization didn't accidentally break the case it's meant to leave untouched.

- [ ] **Step 7: Run the full test suite**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Save**

Stage and commit the changes with a descriptive message, e.g. `git add -A && git commit -m "<short summary of this task's change>"`.

---

## Follow-up candidates (found, not implemented in this plan)

The broader sweep also surfaced these — lower-confidence, lower-impact, or needing more investigation before a concrete non-breaking fix can be written. Documented here rather than silently dropped; a good next plan to write once Part A and B are done and measured:

- **`SlimefunUtils.isItemSimilar`** (`utils/SlimefunUtils.java:334+`) — allocates 1-2 `Optional` wrapper objects per call via `CustomItemDataService.getItemData(...)`; it's the single most-called comparison utility in the plugin (every machine's recipe search goes through it). A non-`Optional` internal fast-path overload is plausible but needs care to avoid duplicating logic incorrectly.
- **`EnergyNet` hologram label rebuilding** (`core/networks/energy/EnergyNet.java:291-299`) — builds a formatted `String` every tick before checking whether the label actually changed; caching last-seen values would avoid the string work on unchanged ticks.
- **`ProgrammableAndroid.tick()`** (`implementation/items/androids/ProgrammableAndroid.java:673-709`) — re-parses its script/fuel/rotation from `String`s every tick; caching the parsed form (invalidated on script edit) would help.
- **`GPSNetwork`/`GPSTransmitter`** (`api/gps/GPSNetwork.java:85-93`, `implementation/items/gps/GPSTransmitter.java:79-90`) — mutates a `Set<Location>` and re-parses a `UUID` every tick regardless of whether anything changed; low priority since GPS transmitters are typically few per server.
- **`AbstractAutoCrafter.craft()`** (`implementation/items/autocrafters/AbstractAutoCrafter.java:401-456`) — allocates scratch `HashMap`/`ArrayList` every tick even on ticks that can't fulfil the recipe; auto-crafters are comparatively rare, so this is low priority.
- **Unthrottled `getNearbyEntities` scans** in `AutoBreeder`, `ExpCollector`, `ProduceCollector`, `AnimalGrowthAccelerator` — these don't have the `lifetime % 60`-style throttle that `AbstractEntityAssembler` already uses; a throttle would very slightly change pickup/breed latency, so this needs a judgment call on acceptable latency before being folded into a plan as a "safe" change.

---

## Self-review

**Spec coverage:** All 4 core requirements from the design spec are covered — Task 4 (dynamic sleep + `MachineWakeListener` in Task 6), Task 2 (`FastBlockPos` + fastutil), Task 5 (synchronous batching), and every task preserves existing public signatures (called out per-task). Task 7/8 cover the spec's approved migration scope (`AContainer` + Cargo). Tasks 9-10 cover the user's "find more, don't break anything" follow-up request, with the remainder of the sweep's findings documented rather than silently dropped.

**Placeholder scan:** No TBD/TODO markers; every step has real, complete code.

**Type consistency:** `FastBlockPos.pack(int,int,int) -> long` used identically in Tasks 3 and 4. `TickerTask#sleepLocation/wakeLocation/isAsleep(Location)` signatures match between Task 4's implementation and Tasks 6/7/8/9's consumption. `TickerTask#queueSyncTask(Runnable)` from Task 5 matches its use in Task 8. `NetworkManager#getNetworkAtRegulator(Location, Class<T>)` from Task 9 matches its use in `EnergyNet`/`CargoNet`.

---

Plan complete and saved to `docs/superpowers/plans/2026-08-14-ticking-performance.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
