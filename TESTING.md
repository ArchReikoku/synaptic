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
