# Slimefun4 Ticking Performance Overhaul — Design Spec

Date: 2026-08-14
Status: Approved, ready for implementation planning
Target: Paper 1.20.6 – 1.21+, Java 16+ (project compiles at source/target 16)

## Problem

Automated machine setups cause main-thread TPS drops. Three concrete, code-verified
bottlenecks:

1. **Unconditional ticking** — every registered machine's `BlockTicker#tick()` runs every
   cycle regardless of whether it has work to do. Verified in
   `AContainer.tick(Block)` ([AContainer.java:353](../../../src/main/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/AContainer.java#L353)):
   when idle, it still calls `findNextRecipe(inv)` every cycle, which allocates two new
   `HashMap`s per call ([AContainer.java:410-421](../../../src/main/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/AContainer.java#L410)).
2. **GC/allocation pressure** — `TickerTask` keys its hot structures by `Location`
   ([TickerTask.java:49](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TickerTask.java#L49)),
   and `BlockStorage` keys its per-block state by `Location` too
   ([BlockStorage.java:59-61](../../../src/main/java/me/mrCookieSlime/Slimefun/api/BlockStorage.java#L59)).
   `Location.hashCode()`/`equals()` are non-trivial and every lookup is a boxed object
   comparison.
3. **Main-thread hitching** — `TickerTask.tickLocation()` calls `Slimefun.runSync(...)`
   **individually per synchronized machine, per cycle**
   ([TickerTask.java:164](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/tasks/TickerTask.java#L164)),
   which is `scheduler.runTask(...)` under the hood
   ([Slimefun.java:1082](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/Slimefun.java#L1082)).
   N synchronized machines = N separate scheduler task submissions per cycle.

## Non-negotiable constraint: BlockTicker is a shared singleton

`BlockTicker` instances are registered once per `SlimefunItem` (item *type*), not once per
placed block. This is proven by the existing `unique`/`uniqueTick()` flag
([BlockTicker.java:16-23](../../../src/main/java/me/mrCookieSlime/Slimefun/Objects/handlers/BlockTicker.java#L16)),
which is instance state shared across every block of that type. Any sleep/wake API that
lives as no-argument instance state on `BlockTicker` (as originally proposed) would sleep
or wake *every block of that item type at once* — incorrect. Sleep state must be tracked
per-`Location`, external to the ticker instance.

## Architecture

```
Async: TickerTask.run()
  for each ChunkPosition -> Set<Location>:
    for each Location l:
      if isAsleep(l): skip entirely (no BlockStorage lookup, no profiler entry)
      data = BlockStorage.getLocationInfo(l)
      if ticker.isSynchronized():
        queue a Runnable into a local syncBatch list      <-- batched, not per-block runSync
      else:
        tick inline, on the async thread (unchanged)
  Slimefun.runSync(() -> drain syncBatch)                  <-- ONE scheduler call per cycle

Wake sources (main thread):
  PlayerInteractEvent, InventoryMoveItemEvent -> MachineWakeListener -> TickerTask.wakeLocation(l)
  CargoNet item delivery -> TickerTask.wakeLocation(l) (direct call, no event)
```

## Components

### 1. `FastBlockPos`
New file: `src/main/java/io/github/thebusybiscuit/slimefun4/utils/FastBlockPos.java`

Final utility class, no instances. Packs `(x, y, z)` into a primitive `long` using the
same bit layout as vanilla NMS `BlockPos.asLong()` (26 bits X, 12 bits Y offset by 2048,
26 bits Z) — a proven, collision-free scheme that comfortably covers the current
`-64..320` build-height range and any plausible future expansion, and the full vanilla
world border on X/Z.

```java
public static long pack(int x, int y, int z)
public static int unpackX(long packed)
public static int unpackY(long packed)
public static int unpackZ(long packed)
```

No world component — callers that need world-scoping (see `BlockStorage`, `TickerTask`
below) get it for free from context (one `BlockStorage` per world; `ChunkPosition` already
carries world identity).

### 2. Sleep/Wake infrastructure

**New state in `TickerTask`:**
```java
private final Map<ChunkPosition, Long2LongMap> sleepingLocations = new ConcurrentHashMap<>();
private volatile long currentCycle = 0; // incremented once per run(), NOT raw game ticks
```
Inner `Long2LongMap` values are created via `Long2LongMaps.synchronize(new Long2LongOpenHashMap())`
(written from the async tick thread and from wake events on the main thread — needs a
thread-safe inner map). Reusing `ChunkPosition` as the outer key means no new world-identity
scheme is needed and there is no cross-world collision risk, since two different worlds
never share a `ChunkPosition` map entry.

**New public methods on `TickerTask`:**
```java
public void sleepLocation(@Nonnull Location l, int cycles)
public void wakeLocation(@Nonnull Location l)
public boolean isAsleep(@Nonnull Location l)
```
`cycles` are `TickerTask` run cycles (the existing `custom-ticker-delay` granularity that
`tick()` methods already operate at), not raw 1/20s game ticks. `isAsleep` also lazily
removes expired entries (`wakeAtCycle <= currentCycle`) so the map doesn't grow unbounded.

**New additive methods on `BlockTicker`** (existing methods untouched):
```java
protected final void sleep(@Nonnull Block b, int ticks) {
    Slimefun.getTickerTask().sleepLocation(b.getLocation(), ticks);
}
protected final void wakeUp(@Nonnull Block b) {
    Slimefun.getTickerTask().wakeLocation(b.getLocation());
}
protected final boolean isSleeping(@Nonnull Block b) {
    return Slimefun.getTickerTask().isAsleep(b.getLocation());
}
```

**`TickerTask.tickLocation()` gains one early-return check**, before the `BlockStorage`
lookup and before the profiler entry — this is the actual CPU-saving mechanism:
```java
private void tickLocation(@Nonnull Set<BlockTicker> tickers, @Nonnull Location l) {
    if (isAsleep(l)) {
        return;
    }
    // ... existing logic unchanged
}
```

### 3. `MachineWakeListener`
New file: `src/main/java/io/github/thebusybiscuit/slimefun4/implementation/listeners/MachineWakeListener.java`,
registered in `Slimefun.registerListeners()` alongside the existing ~45 listeners.

- `PlayerInteractEvent` — right-clicking a sleeping Slimefun block wakes it immediately
  (so its GUI reflects live state rather than stale idle state).
- `InventoryMoveItemEvent` — a hopper/dispenser feeding a sleeping machine's inventory
  wakes it.
- Cargo delivery is **not** routed through this listener — `CargoNet`'s existing delivery
  code calls `TickerTask.wakeLocation()` directly, since firing a Bukkit event per item
  transfer would itself be wasteful.

### 4. `TickerTask` synchronous batching
`tickLocation()`'s synchronized-ticker branch currently calls `Slimefun.runSync(...)`
individually per block. Replace with: append a `Runnable` to a local `List<Runnable>`
built up during the async pass; after the full chunk iteration completes, issue exactly
one `Slimefun.runSync(...)` that drains the whole batch on the main thread. Unsynchronized
tickers are unaffected — they already run inline on the async thread.

### 5. `BlockStorage` internal storage swap
`BlockStorage` is already one instance per `World`
([BlockStorage.java:58](../../../src/main/java/me/mrCookieSlime/Slimefun/api/BlockStorage.java#L58)),
so its internal maps don't need world in the key:

```java
// before
private final Map<Location, Config> storage = new ConcurrentHashMap<>();
private final Map<Location, BlockMenu> inventories = new ConcurrentHashMap<>();

// after
private final Long2ObjectMap<Config> storage = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
private final Long2ObjectMap<BlockMenu> inventories = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
```
keyed by `FastBlockPos.pack(l.getBlockX(), l.getBlockY(), l.getBlockZ())`. All public
static methods (`getLocationInfo(Location)`, `addBlockInfo(...)`, `getInventory(...)`,
etc.) keep identical signatures and behavior — this is an internal-only swap. The
`blocksCache` map (keyed by `String`) is out of scope; it's not a per-tick hot path.

### 6. Migration: `AContainer`
[AContainer.java:353-384](../../../src/main/java/me/mrCookieSlime/Slimefun/Objects/SlimefunItem/abstractItems/AContainer.java#L353)
`tick(Block b)` changes:
- No current operation, and `findNextRecipe(inv)` finds nothing (empty/mismatched
  input): call `sleep(b, 30)` before returning. Woken by `InventoryMoveItemEvent` when
  new input arrives, or `PlayerInteractEvent` when a player opens the GUI.
- Current operation exists but `takeCharge()` fails (insufficient energy): call
  `sleep(b, 10)` — a short, self-renewing poll, **not** an indefinite sleep. There is no
  "energy became available" event in the energy-net system; inventing one is out of scope
  for this pass. This still removes most of the `findNextRecipe()`/`HashMap` allocation
  cost while correctly bounding staleness to ~10 cycles.
- Actively processing: unchanged, never enters sleep (tick() is skipped entirely while
  asleep, so no explicit wake call is needed on the "was already awake" path).

### 7. Migration: Cargo

> **Superseded during implementation (Task 8) — see below for what actually shipped.**
> The paragraph below was the original design intent. It turned out to be architecturally
> impossible given how `TickerTask`'s sleep gate works (see "What actually shipped").

`CargoManager`'s `BlockTicker.tick()` ([CargoManager.java:52-55](../../../src/main/java/io/github/thebusybiscuit/slimefun4/implementation/items/cargo/CargoManager.java#L52))
delegates every call to `CargoNet.getNetworkFromLocationOrCreate(...).tick(b)`. `CargoNet`
already self-throttles its network-wide routing via a `tickDelayThreshold`/`TICK_DELAY`
counter ([CargoNet.java:45-148](../../../src/main/java/io/github/thebusybiscuit/slimefun4/core/networks/cargo/CargoNet.java#L45)).
The original intent was for a sleeping regulator to still run `super.tick()`/hologram/
threshold bookkeeping every cycle, skipping only the expensive `mapInputNodes()`/
`mapOutputNodes()`/`CargoNetworkTask` scheduling.

**What actually shipped:** `TickerTask.tickLocation()`'s `isAsleep(l)` gate (Task 4) sits
*outside* and *before* any `BlockTicker.tick()` call, and is shared across every machine
type — it cannot be told "skip only part of my logic." Once a cargo regulator's `Location`
is asleep, `CargoNet.tick(Block)` (including `super.tick()`) is never invoked at all,
exactly like `AContainer`. The plan owner decided to accept this: cargo regulators sleep
their entire tick, bounded to `IDLE_SLEEP_CYCLES` (~20 cycles) or an earlier wake once
`CargoNetworkTask.run()` observes `movedAnyItem`. Topology/hologram staleness is bounded to
that same short window — the same trade-off already accepted for `AContainer`'s
energy-wait bounded polling.

## Dependency change

Add `it.unimi.dsi:fastutil-core` (not the full `fastutil`, which is ~20MB unshaded) to
`pom.xml`, relocated under `io.github.thebusybiscuit.slimefun4.libraries.fastutil` via the
existing shade-relocation pattern in the `maven-shade-plugin` config
([pom.xml:199-213](../../../pom.xml#L199)), consistent with how `dough`, `paperlib`, and
`commons-lang` are already relocated. This prevents classpath collisions with other
plugins that might bundle a different fastutil version.

## Backward compatibility

No existing public method signature in `BlockTicker`, `TickerTask`, or `BlockStorage`
changes — every change listed above is either an addition or an internal (private field)
swap. Addons compiled against the current API will neither throw `NoSuchMethodError` nor
`ClassNotFoundException`.

## Explicit trade-offs / risks

- Sleep state is **not persisted** across restarts. A restart re-evaluates every machine
  fresh on its next tick — correct and much simpler than persisting transient scheduling
  state.
- Energy-blocked `AContainer` machines use bounded polling (10 cycles), not event-driven
  wake, because no "energy available" event currently exists in the energy-net system.
- Cargo's idle signal requires implementation-time investigation into `CargoNet`.
- The inner `Long2LongMap` per chunk in `TickerTask.sleepingLocations` is written from
  both the async tick thread and the main thread (wake events), so it must use
  `Long2LongMaps.synchronize(...)`. Read-heavy, write-light — acceptable contention profile.

## Testing plan

- Unit tests for `FastBlockPos.pack`/`unpack` round-tripping across the full Y range
  (-2048..2047) and representative X/Z values including negative coordinates.
- Unit tests for `TickerTask.sleepLocation`/`wakeLocation`/`isAsleep`, including expiry
  and cross-`ChunkPosition` isolation (two different worlds/chunks with the same local
  coords don't interfere).
- `AContainer` idle-path test: a machine with empty input sleeps after one idle tick, and
  wakes when an item is inserted (existing MockBukkit test harness already used elsewhere
  in this project's `src/test` tree — reuse that pattern).
- `BlockStorage` regression tests: existing `getLocationInfo`/`addBlockInfo`/round-trip
  tests must pass unchanged against the new internal storage.
- Manual/server test: spin up a Paper 1.20.6+ test server with a large automated furnace
  farm, compare TPS/tick-profiler output before and after.

## Implementation phasing (for the implementation plan)

1. `FastBlockPos` + `pom.xml` dependency/relocation change (foundational, no behavior
   change, easiest to verify in isolation).
2. `BlockStorage` internal storage swap (internal-only, testable via existing
   `BlockStorage` tests).
3. `TickerTask` sleep/wake registry + `BlockTicker` additive methods + the
   `tickLocation()` early-return check (infrastructure, opt-in, no existing machine
   behavior changes yet).
4. `TickerTask` synchronous batching (independent of sleep/wake; can land before or after
   step 3).
5. `MachineWakeListener`.
6. `AContainer` migration.
7. Cargo migration (after investigating `CargoNet` internals per the open item above).
