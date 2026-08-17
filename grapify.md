# Slimefun4 — Architecture & Connections

This document maps how Slimefun4's major subsystems connect to each other, and where each one lives
in the codebase. All diagrams are plain [Mermaid](https://mermaid.js.org/) — GitHub and VS Code
render them natively, no plugin needed.

A polished, illustrated version of the same material — hand-drawn connection diagrams styled as an
engineering schematic — is published separately as an HTML page (see the artifact link in chat).

---

## 1. Bootstrap — how the plugin wires itself together

`Slimefun` (the `JavaPlugin` subclass) constructs its core singletons at class-init, then
`onPluginStart()` wires them together in a fixed order.

```mermaid
flowchart TD
    A["Slimefun (JavaPlugin)"] -->|"constructs"| REG(("SlimefunRegistry"))
    A -->|"constructs"| TICK["TickerTask"]
    A -->|"constructs"| NETM["NetworkManager"]
    A -->|"constructs"| SVC["~15 Services<br/>(metrics, backup, permissions, sound...)"]
    A -->|"constructs"| INTG["IntegrationsManager"]

    A --> START["onPluginStart()"]
    START --> P1["1. registry.load(config)"]
    P1 --> REG
    START --> P2["2. SlimefunItemSetup.setup()<br/>→ every item.register()"]
    P2 -->|"writes items"| REG
    START --> P3["3. ResearchSetup.setupResearches()"]
    P3 -->|"writes researches"| REG
    START --> P4["4. registerListeners()<br/>(~45 listener classes)"]
    START --> P5["5. ticker.start(this)"]
    P5 --> TICK
    START --> P6["6. command.register() → /slimefun"]

    style REG fill:#7a3a1a,stroke:#ff7a3d,color:#fff2ea
```

**Reading it:** almost everything downstream of boot depends on step 1–3 having populated
`SlimefunRegistry` before listeners start firing and the ticker starts running.

---

## 2. The registry — the shared hub everything else reads or writes

`SlimefunRegistry` (`core/SlimefunRegistry.java`) has no interface and isn't passed around — every
subsystem reaches it through the static `Slimefun.getRegistry()`. Nothing in the plugin talks to
another subsystem directly; they talk to the registry.

```mermaid
flowchart LR
    subgraph writers["Writers"]
        direction TB
        SI["SlimefunItem.register()"]
        IG["ItemGroup.register()"]
        RS["Research.register()"]
        GD["SlimefunGuideImplementation<br/>(construction)"]
    end

    writers --> REG(("SlimefunRegistry<br/>slimefunItems · categories<br/>researches · tickers<br/>worlds · guides · profiles"))

    REG --> readers
    subgraph readers["Readers"]
        direction TB
        SG["SurvivalSlimefunGuide<br/>(categories, enabledItems)"]
        TT["TickerTask<br/>(tickers, worlds)"]
        BS["BlockStorage<br/>(one per world)"]
        NM["NetworkManager /<br/>NetworkListener"]
        CU["SlimefunItem.canUse()<br/>(research state)"]
    end

    style REG fill:#7a3a1a,stroke:#ff7a3d,color:#fff2ea
```

---

## 3. One block's runtime life — from placement to ticking

The path a single machine takes from being placed in the world to being ticked every cycle. This is
also where the sleep/wake optimization lives: a sleeping location skips `BlockStorage` and
`BlockTicker` resolution entirely until something wakes it.

```mermaid
flowchart TD
    PLACE["BlockPlaceEvent"] -->|"BlockListener"| ADD["BlockStorage.addBlockInfo(block, id, ...)"]
    ADD -->|"item.isTicking()"| ENABLE(["TickerTask.enableTicker(l)"])
    ENABLE --> QUEUE["tickingLocations"]

    RUN["TickerTask.run()<br/>async, every custom-ticker-delay"] --> B{{"isAsleep(l) ?"}}
    QUEUE --> RUN
    B -- "yes" --> SKIP["skip entirely<br/>0 lookups · 0 allocations"]
    B -- "no" --> LOC["BlockStorage.getLocationInfo(l)"]
    LOC --> TICK["item.getBlockTicker().tick(b, item, data)"]
    TICK -- "unsynchronized" --> INLINE["runs inline, async thread"]
    TICK -- "synchronized" --> SYNC["queueSyncTask → batched,<br/>ONE Slimefun.runSync() per cycle"]

    TICK -. "idle / energy-blocked" .-> SLEEP(["BlockTicker.sleep(l, N cycles)"])
    SLEEP -.-> B

    WAKE1["PlayerInteractEvent /<br/>InventoryMoveItemEvent"] -->|"MachineWakeListener"| WAKEUP(["TickerTask.wakeLocation(l)"])
    WAKE2["Cargo item delivery"] -->|"CargoNet, direct call"| WAKEUP
    WAKEUP -.-> B

    style SKIP fill:#2a5d63,stroke:#4fb0c2,color:#eaf6f7
    style SLEEP fill:#7a3a1a,stroke:#ff7a3d,color:#fff2ea
    style WAKEUP fill:#7a3a1a,stroke:#ff7a3d,color:#fff2ea
```

---

## 4. Two networks, one shared engine

`Network` (`api/network/Network.java`) is an abstract class, not an interface — it owns the shared
BFS-style discovery (`discoverStep()`) that classifies every queued location as a regulator,
connector, or terminus. `EnergyNet` and `CargoNet` both extend it and only supply their own
classification rules.

```mermaid
flowchart TD
    NET["Network (abstract)<br/>discoverStep() · regulator/connector/terminus"]
    NET --> EN["EnergyNet<br/>range 6 · generators/capacitors/consumers"]
    NET --> AIN["AbstractItemNetwork"]
    AIN --> CN["CargoNet<br/>+ CargoNetworkTask (moves items)<br/>+ CargoUtils / ItemFilter"]

    EVT["BlockBreakEvent /<br/>BlockPlaceEvent"] -->|"NetworkListener"| UPD["NetworkManager.updateAllNetworks(loc)"]
    UPD -->|"Network.markDirty(loc)"| NET

    style NET fill:#7a3a1a,stroke:#ff7a3d,color:#fff2ea
```

---

## 5. Where it all lives — package map

667 source files total: 646 under `io.github.thebusybiscuit.slimefun4`, plus 21 in the legacy
`me.mrCookieSlime` package — which is still load-bearing, not dead code (`BlockStorage` and
`BlockTicker` both live there).

```mermaid
flowchart TB
    subgraph pkg["io.github.thebusybiscuit.slimefun4 — 646 files"]
        api["api/ — 82 files<br/>SlimefunItem, Network, Research,<br/>PlayerProfile, custom events"]
        core["core/ — 153 files<br/>networks, machines, multiblocks,<br/>28 services, SlimefunRegistry"]
        impl["implementation/ — 366 files<br/>~257 concrete items, 59 listeners,<br/>guide, setup, tasks"]
        utils["utils/ — 35 files<br/>itemstack, tags, biomes helpers"]
        integ["integrations/ — 7 files<br/>WorldEdit, PlaceholderAPI, mcMMO..."]
        storage["storage/ — 3 files<br/>pluggable player-data backend"]
    end

    subgraph legacy["me.mrCookieSlime — 21 files (legacy core)"]
        bt["Objects/handlers/BlockTicker"]
        bs["api/BlockStorage"]
        menu["CSCoreLibPlugin —<br/>ChestMenu / BlockMenu GUI toolkit"]
    end
```

---

## Key classes reference

| Class | Path | Role |
|---|---|---|
| `Slimefun` | `implementation/Slimefun.java` | Main plugin class; constructs and wires every subsystem in `onPluginStart()` |
| `SlimefunRegistry` | `core/SlimefunRegistry.java` | Central in-memory hub every other subsystem reads/writes through |
| `SlimefunItem` | `api/items/SlimefunItem.java` | Base class for every item/machine; owns handler attachment and `canUse()` gating |
| `BlockStorage` | `me/mrCookieSlime/Slimefun/api/BlockStorage.java` | Per-world block-data persistence; one instance per loaded world |
| `BlockTicker` | `me/mrCookieSlime/Slimefun/Objects/handlers/BlockTicker.java` | Abstract handler for tickable machines; owns sleep/wake helpers |
| `TickerTask` | `implementation/tasks/TickerTask.java` | The async loop that ticks every registered machine location |
| `Network` | `api/network/Network.java` | Shared abstract engine (BFS discovery) behind both network types |
| `EnergyNet` / `CargoNet` | `core/networks/energy/` · `core/networks/cargo/` | The two concrete network implementations |
| `NetworkManager` | `core/networks/NetworkManager.java` | Indexes all active networks by location/regulator |
| `Research` | `api/researches/Research.java` | Unlock gate; checked from `SlimefunItem.canUse()` |
| `SlimefunGuideImplementation` | `core/guide/SlimefunGuideImplementation.java` | Interface behind the Survival and Cheat-Sheet guide GUIs |

No existing public method signature changes with any of this — it's a map of what's already there,
not a proposed change.
