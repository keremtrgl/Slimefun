# Slimefun 4
*Looking for the download link? [**Click here**](https://github.com/Slimefun/Slimefun4/blob/master/README.md#floppy_disk-download-slimefun-4)*

Slimefun is a plugin which aims to turn your Spigot Server into a modpack without ever installing a single mod. It offers everything you could possibly imagine. From Backpacks to Jetpacks! Slimefun lets every player decide on their own how much they want to dive into Magic or Tech.<br>
We got everything from magical wands to nuclear reactors.<br>
We feature a magical altar, an electric power grid and even item transport systems.

This project originally started back **in 2013** and has grown ever since.<br>
From one single person working on this plugin back then, we grew to a community of thousands of players and hundreds of contributors to this project.<br>
It currently adds over **500 new items and recipes** to Minecraft ([Read more about the history of this project](https://github.com/Slimefun/Slimefun4/wiki/Slimefun-in-a-nutshell)).

But it also comes with a lot of addons! Check out our [addons](https://github.com/Slimefun/Slimefun4/wiki/Addons), you may find exactly what you were looking for.

## :rocket: What's different in this fork

This is a modified fork of upstream Slimefun 4, focused on runtime performance, concurrency correctness, and staying compatible with current Paper builds. This section is an honest summary of what actually changed and why — no invented benchmark numbers, just the real work, with the full detail available in the [commit history](../../commits/main).

### :zap: Performance
* **Machines sleep instead of polling every tick.** Most machine types (furnaces, generators, reactors, androids, cargo/energy networks, crop accelerators, and more) used to re-scan their input slots, recipes, or surroundings on *every single game tick*, even when they had nothing to do. They now go to sleep when idle and wake up on the actual triggering event (a player interacting, an item arriving via hopper/cargo, fuel being restocked), falling back to a short bounded poll only where no real "wake" event exists.
* **O(1) network lookups.** Energy and Cargo networks used to find "which network is this regulator's own network" with a linear scan over every registered network on the server, every tick, for every regulator. This is now a direct index lookup, with the old linear scan kept only as a correctness fallback for the rare case of multiple regulators sharing one network.
* **Batched tick-thread scheduling.** Machines that need to touch the main thread from the async ticker used to each schedule their own individual Bukkit task per tick; this is now batched into a single scheduled task per tick cycle.
* **fastutil-backed block storage.** Slimefun's per-world block storage now keys its internal maps with a packed primitive `long` (via a zero-allocation coordinate-packing utility, same bit layout as vanilla Minecraft's own `BlockPos`) through fastutil's primitive collections, instead of boxing every coordinate into a `Location` object and hashing it the slow way.

### :repeat: Concurrency correctness
Several of the changes above touch code that runs across the main thread and Slimefun's async ticker thread at the same time. Along the way this surfaced (and fixed) some real concurrency bugs: unsynchronized iteration over a map that's still being mutated from another thread, a non-atomic check-then-set re-entrancy flag, and a sleep-state registry that could leak entries for the lifetime of the server if a machine was removed while asleep.

### :beetle: Bug fixes from a full code review
A systematic pass through the core item API, the multiblock framework, core services, listeners, commands, the player-profile/backpack/research/GPS systems, and a wide sweep of individual items turned up and fixed dozens of concrete bugs. Some highlights:
* A timed status effect (e.g. a temporary buff/debuff) built its expiry with string concatenation instead of arithmetic once one operand became a `String` — durations were silently ignored, making "temporary" effects effectively permanent.
* The Programmable Android's script-rating GUI displayed a 0–1 fraction as if it were already a 0–100 percentage (showing "0.75%" instead of "75%"), with the color tier always stuck at the lowest.
* An enchantment-conflict flag in the Auto Enchanter/Book Binder was declared outside its own loop instead of being reset per candidate, so the first conflict found silently dropped every later, unrelated enchantment from a merge.
* The Fluid Pump's bottle-filling logic ran for lava too, wasting energy and item on a guaranteed no-op with a chance to destroy the lava source for nothing.
* A hardcoded (and outdated) MockBukkit test-mode detection check meant plugin behavior could diverge between what was tested and what real servers ran.
* A recurring pattern across many files: unguarded `Integer.parseInt()` calls after only a regex format check, throwing uncaught exceptions on numeric overflow instead of showing the intended user-facing error.
* Several getters across the codebase returned live, mutable internal collections instead of defensive copies, letting external/addon code silently corrupt internal state (item groups, network topology, research data, and more).

### :package: Compatibility upgrades
* **Paper 1.21.11 support** (up from 1.21.1), including a fix for a shaded dependency (`dough-api`) whose `GameProfile` wrapper broke against the newer, immutable `GameProfile`/`PropertyMap` API shipped in this Paper build's `authlib`.
* **Migrated the test suite from MockBukkit 3.x to 4.x** (the old 3.x line is unmaintained and was blocking any Paper upgrade past 1.21.1) — around 105 test files updated, several genuine MockBukkit-4.x behavior changes accounted for, all 1842 tests passing.

### :earth_americas: Localization
* Completed and corrected the Turkish translation (`messages.yml`/`recipes.yml`/`researches.yml`) — filled in missing keys that were silently falling back to English, and fixed a number of existing entries with incorrect or awkward wording.

### Quick navigation
* **[:floppy_disk: Download Slimefun4](#floppy_disk-download-slimefun-4)**
* **[:framed_picture: Screenshots](#framed_picture-screenshots)**
* **[:headphones: Discord Support Server](#headphones-discord)**
* **[:beetle: Bug Tracker](https://github.com/Slimefun/Slimefun4/issues)**
* **[:open_book: Wiki](https://github.com/Slimefun/Slimefun4/wiki)**
* **[:interrobang: FAQ](https://github.com/Slimefun/Slimefun4/wiki/FAQ)**
* **[:handshake: How to contribute](https://github.com/Slimefun/Slimefun4/blob/master/CONTRIBUTING.md)**

## :floppy_disk: Download Slimefun 4
Slimefun requires your Minecraft Server to be running on [Spigot](https://spigotmc.org/), [Paper](https://papermc.io/) or on any fork of these.<br>
(See also: [How to install Slimefun](https://github.com/Slimefun/Slimefun4/wiki/Installing-Slimefun))

Slimefun 4 can be downloaded **for free** on our builds page.<br>
We currently provide two distinct versions of Slimefun, development builds and "stable" builds.<br>
Here is a full summary of the differences between the two different versions of Slimefun.

| | development (latest) | "stable" |
| ------------------ | -------- | -------- |
| **Minecraft version(s)** | :video_game: **1.16.\* - 1.20.\*** | :video_game: **1.16.\* - 1.20.\*** |
| **Java version** | :computer: **Java 16 (or higher)** | :computer: **Java 16 (or higher)** |
| **automatic updates** | :heavy_check_mark: | :heavy_check_mark: |
| **frequent updates** | :heavy_check_mark: | :x: |
| **latest content** | :heavy_check_mark: | :x: |
| **Discord support** | :heavy_check_mark: | :x: |
| **Bug Reports** | :heavy_check_mark: | :x: |
| **testing before release** | :x: | :heavy_check_mark: |
| **change logs** | :x: | :memo: **[change log](https://github.com/Slimefun/Slimefun4/blob/master/CHANGELOG.md)** |
| **Download links** | :floppy_disk: **[download latest](https://blob.build/project/Slimefun4/Dev)** | :floppy_disk: **[download "stable"](https://blob.build/project/Slimefun4/RC)** |

**:exclamation: We wholeheartedly recommend you to use _development builds_, they are the most recent version of Slimefun and also receive the most frequent updates! In fact, "stable" builds are so outdated that we won't accept bug reports from them at all.**
<details>
  <summary>Here's why...</summary>
  
"Stable" builds do not receive frequent updates or fast patches. As time goes on, bugs are fixed but it will take some time until these fixes make it into a "stable" build. We will also not accept or review any bug reports from "stable" builds. They are in fact just old development builds that seemed to run fine without any __major__ issues.

**:question: Why use a "stable" build then?**<br>
While "stable" builds most definitely contain more bugs than development builds due to their very slow update schedule. you can be sure that they will not include __game-breaking__ issues, but rest assured that development builds almost never contain such issues either. If your server or business however heavily depends on a version of Slimefun that does not change/update a lot, you are forgiven if you choose the "stable" branch. But development builds will bring you the best experience, both in terms of features and bug fixes.

**:question: What exactly are these "stable" builds then and why do you put them in quotes?**<br>
"Stable" builds are literally just outdated development builds that seemed to run fine without any __major__ issues. But they are far from bug-free hence why actually calling them stable would be hypocritical. However these builds can only really stay "stable" if there are enough people using development builds and report any bugs they come across. Otherwise potential issues may go unnoticed and slip into a "stable" build. Again, we really recommend you to choose the development builds. But since a few people really wanted "stable" builds, they are now an option too.

</details>

## :framed_picture: Screenshots
So what does Slimefun look like?<br>
Well, we asked some users on our [Discord server](#headphones-discord) to send us some screenshots, so see for yourself:
|                 Reactors and electricity                  |            Awesome factories             |          Magic and Altars           |
| :-------------------------------------------: | :--------------------------------------: | :----------------------------------------: |
| ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase1.png) | ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase6.png) | ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase5.png) |
| *Screenshot provided by HamtaBot#0001* | *Screenshot provided by Piͭxͪeͤl (mnb)#5049* | *Screenshot provided by Kilaruna#4981* |
| ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase4.png) | ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase3.png) | ![](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase2.png) |
| *Screenshot provided by GalaxyKat11#3816* | *Screenshot provided by TamThan#7987* | *Screenshot provided by Kilaruna#4981* |

## :headphones: Discord
You can find Slimefun's community on Discord and connect with **over 7000** users of this plugin from all over the world.<br>
Click the badge down below to join the server for suggestions/questions or other discussions about this plugin.<br>
We are also hosting a community event every so often, join us to find out more.<br>
**Important: We don't accept bug reports on discord, please use our [Issue Tracker](https://github.com/Slimefun/Slimefun4/issues) to submit bug reports!**

Due to the sheer size of this discord server, we need to enforce some [important rules](https://github.com/Slimefun/Slimefun4/wiki/Discord-Rules).<br>
Not following these rules can lead to a kick or even a ban from the server.

<p align="center">
  <a href="https://discord.gg/slimefun">
    <img src="https://discordapp.com/api/guilds/565557184348422174/widget.png?style=banner3" alt="Discord Invite"/>
  </a>
</p>

## :open_book: Wiki
Slimefun has a (detailed and well-maintained - *cough*) Wiki for new players, maybe also consider
expanding the wiki to help grow our community and help out new users of this plugin.
https://github.com/Slimefun/Slimefun4/wiki

#### :star: Highlighted Articles
* [What is Slimefun?](https://github.com/Slimefun/Slimefun4/wiki/Slimefun-in-a-nutshell)
* [How to install Slimefun](https://github.com/Slimefun/Slimefun4/wiki/Installing-Slimefun)
* [Addons for Slimefun 4](https://github.com/Slimefun/Slimefun4/wiki/Addons)
* [How to create an Addon for Slimefun 4](https://github.com/Slimefun/Slimefun4/wiki/Developer-Guide)
* [Getting Started](https://github.com/Slimefun/Slimefun4/wiki/Getting-Started)
* [Frequently Asked Questions](https://github.com/Slimefun/Slimefun4/wiki/FAQ)
* [Common issues](https://github.com/Slimefun/Slimefun4/wiki/Common-Issues)
* [Help us expand the Wiki!](https://github.com/Slimefun/Slimefun4/wiki/Expanding-the-Wiki)
* [Help us translate Slimefun!](https://github.com/Slimefun/Slimefun4/wiki/Translating-Slimefun)

The wiki is entirely community-run, so if you find an article missing, feel free to write one and share it with others.

## :handshake: Contributing to this project
Slimefun 4 is an Open-Source project and licensed under
[GNU GPLv3](https://github.com/Slimefun/Slimefun4/blob/master/LICENSE).<br>
**Over 200 people have already contributed to this amazing project. You guys are awesome! :heart:**<br>
Please consider helping us maintain this project too, your engagement keeps the project alive!

You can find more info on how to contribute to this project in our [CONTRIBUTING.md](https://github.com/Slimefun/Slimefun4/blob/master/CONTRIBUTING.md).

## :exclamation: Disclaimers
Slimefun4 uses various systems that collect usage information or download automatic updates as well as the latest information about the project.
We do not collect any personal information from you but there are some services that may gather or download some form of data.

You can opt-out of the Auto-Updater and stats collection at any time!

<details>
  <summary>Automatic updates</summary>
  
Slimefun4 uses an Auto-Updater which connects to https://thebusybiscuit.github.io/builds/ to check for and download updates.<br>
This behaviour is enabled by default but can be turned off under `/plugins/Slimefun/config.yml`.<br>
We highly recommend you to keep this on at any time though, as you could be missing out on important patches.
</details>

<details>
  <summary>Metrics and Statistics</summary>
  
Slimefun4 uses [bStats](https://bstats.org/plugin/bukkit/Slimefun/4574) to collect anonymous information about the usage of this plugin.<br>
This is solely for statistical purposes, as we are interested in how Servers/Players use this plugin.<br>
All available data is anonymous and aggregated, at no point can we see individual server or player information.<br>
All of the collected data is publicly accessible: https://bstats.org/plugin/bukkit/Slimefun/4574

You can also disable this behaviour under `/plugins/bStats/config.yml`.<br>
For more info see [bStats' Privacy Policy](https://bstats.org/privacy-policy)

Our [bStats Module](https://github.com/Slimefun/MetricsModule) is downloaded automatically when installing this Plugin, this module will automatically update on server starts independently from the main plugin. This way we can automatically roll out updates to the bStats module, in cases of severe performance issues for example where live data and insight into what is impacting performance can be crucial.
These updates can of course be disabled under `/plugins/Slimefun/config.yml`. To disable metrics collection as a whole, see the paragraph above.

---

Slimefun also uses its own analytics system to collect anonymous information about the performance of this plugin.<br>
This is solely for statistical purposes, as we are interested in how it's performing for all servers.<br>
All available data is anonymous and aggregated, at no point can we see individual server information.<br>

You can also disable this behaviour under `/plugins/Slimefun/config.yml`.<br>

</details>

<details>
  <summary>GitHub Integration</summary>
  
Lastly, Slimefun4 connects to https://api.github.com/ to gather information about this open-source project.<br>
No information about you or your Minecraft Server is sent to GitHub.

This information includes (but is not limited to)
* list of contributors, their username and profile link (from the repositories `Slimefun/Slimefun4`, `Slimefun/Slimefun-Wiki` and `Slimefun/Resourcepack`)
* amount of open issues in this repository
* amount of pending pull requests in this repository
* amount of stars in this repository
* amount of forks of this repository
* amount of code-bytes in this repository
* date of the last commit to this repository
</details>

Additionally the plugin connects to [textures.minecraft.net](https://www.minecraft.net/en-us) to retrieve the Minecraft skins of our contributors (if possible).<br>

*Note that Slimefun is not associated with `Mojang Studios` or `Minecraft`.*
