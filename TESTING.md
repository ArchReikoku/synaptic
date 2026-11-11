# Testing the patchwork-world fix and nearest-land spawn

Not committed, and `mod_version` not bumped. Server-side only, with no protocol change.

**The bug:** sometimes a new world had a rectangle of different terrain around spawn, cut off by sheer walls.

**Cause:** a new world was built on a folder that still held chunks from an earlier world of the same name. Those chunks loaded as they were, and everything around them generated from the new seed. Names got reused because `LobbyManager.begin` restarted the batch counter from its saved value. Worlds prepared during play (`prep_<batch>_<slot>`) before a restart were named again after it, and their folders were still on disk. Water made it more likely: on a wet seed, the old dry patch was what the spawn search landed on.

**Changes:**
- `LobbyManager.begin` claims a fresh batch number (`SessionStats.nextBatch()`), so names are never reused after a restart.
- New `RuntimeDimension.createFresh` deletes any leftover folder under the name before building. It's used for lobby candidates, for prepared worlds, in `startNextRun`, and for a new run's nether and end. `restore` and the lazily-built companions still use plain `create`, because they need what's on disk.
- **No more re-rolling wet seeds.** `buildCandidate` keeps the first seed. `findSpawn` now looks for the nearest dry land. It fully tries the 3×3 chunks around the origin. Beyond that, out to 64 chunks (about 1000 blocks), it uses `ChunkGenerator.getBaseHeight(OCEAN_FLOOR_WG)` to judge each chunk from the terrain noise without generating it, and generates at most 16 chunks that look dry to confirm one. If it finds nothing, it falls back to the origin as before.

## What I tested myself, and the results

| Check | Command / method | Result |
| --- | --- | --- |
| Shared code builds | `./gradlew :common:build` | ✅ Pass |
| Fabric and NeoForge compile | `:fabric:compileJava :neoforge:compileJava` | ✅ Pass |
| `ChunkGenerator.getBaseHeight`, `getSeaLevel`, `ServerChunkCache.randomState/getGenerator`, `PlayerSpawnFinder.getSpawnPosInChunk`, `ChunkPos.x()/getMiddleBlockX()` exist on both loaders | `javap` on Fabric's merged jar and NeoForge 102's patched jar | ✅ |
| Terrain noise is per-run | `javap -c ChunkMap` | ✅ `RandomState` is built from `level.getSeed()`, which `ServerLevelSeedMixin` overrides |

I did not test anything in-game.

**Noticed, not changed:** `ServerLevel`'s constructor gives `StructureCheck` the *save's* seed (`WorldGenSettings.options().seed()`), not the run's. That might affect structure lookups such as `/locate` or explorer maps in runs. It doesn't affect terrain, and it's a separate issue.

## How you should test

1. **The patchwork bug is gone.** This is the reproduction:
   1. Play a run for at least 10 seconds, so a few worlds get prepared in the background (one every 5 seconds).
   2. Quit to the title screen and reopen the world.
   3. Play another 10 seconds or so, then press Next run.
   4. Repeat a few times. Look at each new world from above in spectator mode.
   - **Pass:** no rectangles of mismatched terrain and no straight stone walls at chunk borders.
   - The log may show `already has files on disk from an earlier world, clearing them`. That's the safety net catching a leftover folder, not an error.
2. **Water seeds spawn on the nearest land.** Press Next run, then Recycle, many times.
   - **Pass:** you never start in water. When the origin is ocean, you start on the nearest shore or island.
   - Log lines like `seed … is all water near the origin, trying another` should no longer appear.
3. **Lobby speed.** Opening a new lobby with no prepared worlds should take about as long as before or less. Wet seeds no longer cost several whole extra worlds.
4. **Restart keeps the run.** Quit and reopen in the middle of a run.
   - **Pass:** you're back in the same world, and the terrain beyond what you explored joins up seamlessly. This checks that `restore` doesn't wipe anything.

---

# Testing the lobby lock (no movement, other players invisible)

Not committed, and `mod_version` not bumped. This is a client-side change, so the client needs the new jar. The network protocol is unchanged.

**What changed:** while the seed lobby holds a player (`LobbyClient.active()`, meaning touring or at the grid), four new client mixins apply:

| Mixin | Target | Effect |
| --- | --- | --- |
| `LobbyInputMixin` | `KeyboardInput.tick` (TAIL) | No walking, flying, rising or sinking |
| `LobbyMouseMixin` | `MouseHandler.turnPlayer` (cancel) | The camera does not turn |
| `LobbyKeybindMixin` | `Minecraft.handleKeybinds` (cancel) | No hotbar or spectator teleport menu, no attacking into an entity's view, no F5, no chat or inventory keys |
| `LobbyPlayerRenderMixin` | `EntityRenderDispatcher.shouldRender` | Other players are not drawn: no body, head, name tag or shadow |

## What I tested myself, and the results

| Check | Command / method | Result |
| --- | --- | --- |
| Shared code builds | `./gradlew :common:build` | ✅ Pass |
| Fabric and NeoForge compile | `:fabric:compileJava :neoforge:compileJava` | ✅ Pass |
| All four target methods exist with these signatures | `javap -p` on Fabric's `minecraft-merged.jar` and NeoForge's `minecraft-patched-26.3.0.102-merged.jar` | ✅ Identical on both |
| Mouse deltas don't pile up while locked | `javap -c MouseHandler` | ✅ `turnPlayer` doesn't reset the accumulators; its caller does. So skipping it discards the movement, and the camera won't jump on unlock. |

I did not test anything in-game.

## How you should test

You need **two clients** on one world, because invisibility only shows with someone else there. Do it on Fabric and again on NeoForge.

1. **Mixins apply.** Join a world. **Pass:** no `Mixin apply failed` mentioning `Lobby*Mixin` in the log.
2. **Movement lock.** Press Next run. While the tour runs, hold W, Space and Shift and move the mouse, especially in the moment the loading screen drops for a shot.
   - **Pass:** the view never moves, and every tile faces the same way.
3. **Spectator escapes.** During the tour, and after closing the grid with Esc if it allows that, press 1–9, middle-click, left-click a mob, and press F5.
   - **Pass:** nothing happens. No spectator menu, no third person, no entity view.
4. **Invisibility.** With both clients touring, compare the tiles. Check the host's tiles in particular, since they're shot from a block above everyone else.
   - **Pass:** no heads, bodies or name tags in any tile, and none visible on the grid's backdrop.
5. **Released afterwards.** Pick a world, and separately cancel back to a run.
   - **Pass:** you can move, look and use keys at once. Other players are visible again. No key stays stuck down.
6. **Disconnect mid-tour.** Leave mid-tour and join another world.
   - **Pass:** you aren't frozen there. `LobbyClient.reset()` sets the state back to closed.

---

# Testing the loader update (NeoForge 26.3.0.102, Fabric Loader 0.20.1)

Minecraft stays on 26.3. Nothing is committed, and `mod_version` has not been bumped.

| | Old | New |
| --- | --- | --- |
| NeoForge | 26.3.0.16-beta | 26.3.0.102 (first stable 26.3) |
| ModDevGradle | 2.0.147 | 2.0.148 |
| Fabric Loader | 0.19.5 | 0.20.1 |
| Fabric API | 0.161.0+26.3 | 0.166.0+26.3 |
| Loom, NeoForm, Mixin | unchanged | Loom 1.18 still untried. NeoForge 102 still names neoform `26.3-1` and Mixin `0.17.4+mixin.0.8.7`. |

**Code change:** NeoForge 102 removed `MinecraftServer.markWorldsDirty()` along with the cached `worldArray` it invalidated. `tickChildren` now iterates `getAllLevels()`, the live map, as vanilla does. `NeoForgePlatform.levelLoaded/levelUnloaded` no longer call it, and they still post `LevelEvent.Load/Unload`.

**Compatibility:** `neoforge.mods.toml` takes its NeoForge range from the property, so the new jar requires NeoForge **26.3.0.102 or later**.

## What I tested myself, and the results

| Check | Command / method | Result |
| --- | --- | --- |
| Shared code builds | `./gradlew :common:build` | ✅ Pass |
| Fabric compiles | `./gradlew :fabric:compileJava` | ✅ Pass |
| NeoForge compiles | `./gradlew :neoforge:compileJava` | ❌ at first (`markWorldsDirty` gone), then ✅ after the fix above |
| Mixin target classes, old vs new NeoForge patched jar | `javap -p` diff | ✅ 16 classes identical. `MinecraftServer` and `PrimaryLevelData` changed, but the fields our accessors use (`levels`, `executor`, `storageSource`, `settings`) still exist. |
| How NeoForge 102 ticks levels | `javap -c MinecraftServer.tickChildren` | ✅ It iterates `getAllLevels()`. No cached array is left. |

I did not test anything in-game, and I did not test the Fabric side beyond compiling it.

## How you should test

1. **Smoke test on both loaders:** `./gradlew :fabric:runClient` and `./gradlew :neoforge:runClient`. Create a survival world.
   - **Pass:** the world loads with no `Mixin apply failed` / `InvalidInjectionException` in the log.
2. **NeoForge: runtime dimensions still tick.** This is the one behaviour the code change touches.
   1. Start a run that creates a dimension during play.
   2. Inside it, run `/weather rain`, then `/weather clear`.
   3. Check that mobs move and a furnace smelts.
   - **Pass:** the weather follows, and mobs and furnaces work.
   - **Fail:** the run is frozen. Send me the log.
3. **NeoForge: destroying a run.** End or destroy the run, then keep playing for a minute.
   - **Pass:** no crash, and no exception about a closed level being ticked.

---

# Testing the six fixes

These are the six fixes from the code review. Nothing is committed, and `mod_version` has not been bumped yet.

> **Install the new jar on both ends.** `PROTOCOL` went from 1 to 2 because `ConfigSyncPayload` changed shape. An old jar on one end and a new one on the other will not work together.

---

## What I tested myself, and the results

I kept to cheap checks and did not launch the game.

| Check | Command / method | Result |
| --- | --- | --- |
| Shared code compiles against bare vanilla | `./gradlew :common:build` | ✅ Pass |
| Fabric project compiles | `./gradlew :fabric:compileJava` | ✅ Pass |
| NeoForge project compiles | `./gradlew :neoforge:compileJava` | ✅ Pass |
| New mixin targets exist in Fabric's 26.3 game jar | `javap -c` on `minecraft-merged.jar` | ✅ `FoodData.tick` has 2× `ServerPlayer.isHurt()` and 2× `addExhaustion(F)V`. `RegenerationMobEffect.applyEffectTick(ServerLevel, LivingEntity, int)` returns `boolean`. |
| Same targets in NeoForge's patched 26.3 jar | `javap -c` on `minecraft-patched-26.3.0.16-beta-merged.jar` | ✅ Same bytecode, so NeoForge does not patch these methods |
| Signatures used in new code | `javap` | ✅ `Entity.spawnAtLocation(ServerLevel, ItemStack)` and `Connection.disconnect(Component)` exist |

**What I did not test:** anything in-game. Compiling does not prove a mixin applies. Mixins apply when their target class first loads, so a broken one shows up on world entry. The first step below catches that.

---

## How you should test

### 0. Smoke test (do this first)
Run `./gradlew :fabric:runClient` and create a survival world. Then do the same with `./gradlew :neoforge:runClient`.

- **Pass:** the world loads and there is no `InvalidInjectionException` / `Mixin apply failed` in the log. Search the log for `RegenerationMobEffectMixin` and `FoodDataMixin`.
- **Fail:** a crash on world entry. Send me the stack trace.

For tests 1, 2 and 6 you need **two clients** on one world. Open a world to LAN and join it from a second dev client, for example a second `runClient` with a different username.

### 1. Inventory: same-slot changes in one tick (fix 1)
`SynapticMod.mergeSlots`

1. With Inventory shared, give the group one stack of 64 cobblestone in hotbar slot 1 (`/give @p cobblestone 64`).
2. Both players hold right-click and place blocks at the same moment for a few seconds.
3. Count the blocks placed plus the blocks left in the stack.
   - **Pass:** the total is exactly 64.
   - **Fail:** more than 64 (duplication). Before the fix this went over 64 almost every time.
4. **Loss case:** empty slot 1. Drop a stack of dirt in front of player A and a stack of sand in front of player B, and have both walk over theirs at the same moment.
   - **Pass:** you end up with both the dirt and the sand, in two slots, or one lands back on the floor if the inventory is full.
   - **Fail:** one of them is gone.
5. Ender chest: repeat step 2 with a stack placed from the shared ender chest. It uses the same code, so a quick check is enough.

### 2. Healing no longer scales with player count (fix 2)
`SynapticMod.healsForGroup`, `FoodDataMixin`, `RegenerationMobEffectMixin`

1. Life and Hunger shared, Split Hunger on, difficulty Easy or higher.
2. **Solo baseline:** one player online. Take some damage (for example `/damage @p 10`), keep food full (`/effect give @p saturation 1 10`, then clear it), and time how long it takes to heal back to full.
3. **Two players:** do the same with both online.
   - **Pass:** about the same time as solo.
   - **Fail:** roughly half the time, which is what happened before.
4. **Regeneration effect:** with Effects shared, `/effect give <one player> regeneration 10 1`. Time the heal with 1 player and with 2.
   - **Pass:** about the same.
5. **Hunger cost:** watch the hunger bar during step 3.
   - **Pass:** it drops about as fast per heart as when solo, not N times slower.
6. **Healer death:** kill the first player who joined (`/kill`) while Death sharing is off.
   - **Pass:** the other player keeps regenerating, because the healer moves to them.

### 3. Atomic file writes (fix 3)
`report/AtomicFiles`, `SessionStats.save`, `SynapticConfig.save`

- **Normal case:** play a run for 1 minute, quit, and reopen the world.
  - **Pass:** you are back in the same run with your session totals.
  - Look in `run/config/`: there should be **no** leftover `*.tmp` files next to the Synaptic files.
- **Crash case** (optional): during a run, kill the game from Task Manager right after an autosave (every 30 s), then reopen.
  - **Pass:** you are back in the run. The worst case is losing up to 30 s of totals, never the run.

### 4. Picker can no longer get stuck (fix 4)
`LobbyManager.tick` / `greet`, `SynapticNetworking.onNextRun`

1. With Next Run on, open the picker using the Next Run button.
2. While the picker is up, open settings (K) and switch **Next Run off**. If K is unreachable during the picker, type `/synaptic run_reset false` in chat instead.
   - **Pass:** the tour keeps going, and you can still pick a tile or cancel. Afterwards the game is normal and no new picker opens.
   - **Fail (old behaviour):** everyone frozen in spectator, with health and hunger not updating.
3. With Next Run **off**, check that the Next Run button is not shown.

### 5. Locale-safe config keys (fix 5)
`SynapticConfig.key`

- Open `run/config/synaptic.properties` and check that the keys are plain ASCII: `hunger_split`, `keep_mining_progress`, `death_report` and so on.
- Optional full test: add `-Duser.language=tr -Duser.country=TR` to the run config's JVM args and start once.
  - **Pass:** the same ASCII keys, and your saved toggles are kept.
  - **Fail (old behaviour):** keys like `hunger_splıt` and settings reset to defaults.

### 6. Protocol check on both loaders (fix 6)
`SynapticNetworking.PROTOCOL`, `ConfigSyncPayload`, `SynapticClient.onConfigSync`

- **Matching jars:** join a LAN world from a second client.
  - **Pass:** you join normally and the K screen shows the host's settings.
- **Mismatch** (optional): temporarily change `PROTOCOL` to `3` in a second build, then join the normal world with it.
  - **Pass on Fabric:** disconnected with *"Synaptic version mismatch: the server speaks protocol 2, this client speaks 3…"*.
  - **Pass on NeoForge:** the loader refuses the connection during the handshake, before that message is ever needed.
  - Change `PROTOCOL` back afterwards.

---

## Known limits (deliberately not fixed)

- **Tools:** two players wearing down the *same tool* in one tick costs it one use instead of two. Durability is a component, so the changed copies count as different items and the last one wins.
- **Moving one stack:** two players moving the *same stack* out of one slot into two *different* slots in one tick still duplicates it. Fixing that needs tracking across slots.
- **Hunger and Saturation effects** (shared via Effects) still apply once per player. Only Regeneration was in scope.
- **Version number:** `mod_version` is still 3.0.0. These are bugfixes plus a protocol bump, so per the versioning rules the next release is at least 3.0.1. Whether it is 3.0.1 or higher is your call.

## Files touched
- `common/.../SynapticMod.java`: `mergeSlots`, `capped`, `Spill`, `healsForGroup`, `chooseHealer`; `addToPool` now returns what did not fit
- `common/.../mixin/FoodDataMixin.java`: healer-only regeneration, healing cost not split
- `common/.../mixin/RegenerationMobEffectMixin.java` (new) and `synaptic.mixins.json`
- `common/.../report/AtomicFiles.java` (new); `stats/SessionStats.java`, `config/SynapticConfig.java`
- `common/.../world/LobbyManager.java`, `common/.../net/SynapticNetworking.java`
- `common/.../net/ConfigSyncPayload.java`, `common/.../client/SynapticClient.java`
- `neoforge/.../SynapticNeoForge.java`: `PROTOCOL` now read from common
- `CLAUDE.md`: the protocol paragraph updated to match
