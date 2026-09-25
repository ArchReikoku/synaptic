# Working agreement

## Ask, don't act

Do not do any of the following on your own. Ask first, and wait for a clear yes:

- **Do not build.** No `./gradlew build`, no `runClient`, no compiling of any kind.
- **Do not commit.** No `git commit`, no `git push`, no tagging.
- **Do not test.** No launching the game, no automating the game window, no screenshots
  of it, no summoning or command-sending to verify behaviour.

Editing files, reading code, and searching are fine — those need no permission.

## Testing is mine to run

When a change needs verifying, **explain how to test it** and let me do it. Say what to
run, what to look at, and what a pass looks like versus a failure. Do not run it yourself
and report back.

A change being "unverified" is the expected state when handing work over. Say so plainly
rather than reaching for the build or the game to settle it.

## Why

I want to see the changes land myself, and I want control over when the game launches and
what gets written to the repo's history.

---

# Project notes

**Synaptic** — a Fabric mod for Minecraft 26.3. One life shared across every player.
Published at https://github.com/ArchReikoku/synaptic (MIT).

## Three projects, two jars

**Synaptic builds for Fabric and NeoForge from one codebase.**

| Where | What is in it |
| --- | --- |
| `common/` | Everything that only needs vanilla Minecraft — nearly all of it. Compiled against a plain NeoForm game jar with **no loader on the classpath at all**. |
| `fabric/` | Loom. `SynapticFabric`, `FabricPlatform`, the client pair, `fabric.mod.json`. |
| `neoforge/` | ModDevGradle. `SynapticNeoForge`, `NeoForgePlatform`, the client pair, `neoforge.mods.toml`. |

The loader projects do not depend on `common`'s jar; they add its source directories to
their own source sets, so the shared code is compiled again against each loader's game jar
and lands directly in each mod jar. Nothing is shadowed and no mixin config is buried in a
dependency.

**`common` may not import `net.fabricmc` or `net.neoforged`, and cannot** — it has neither
on its class path. Anything a loader answers differently goes through
`com.synaptic.platform.Platform` (or `ClientPlatform` for the client half), which is
implemented once per loader and installed by that loader's entrypoint before anything else
runs. Keep it small: every method on it is a thing written twice that can disagree twice.

**Loader modules are wiring only.** If a decision about the game is being made in
`SynapticFabric` or `SynapticNeoForge`, it is in the wrong file — the other one has to make
the same decision and the two will drift. Put it in `common` and call it from both.

## Building, when asked

```
./gradlew collectJars        # both jars, gathered into build/libs/
./gradlew :fabric:build      # fabric/build/libs/
./gradlew :neoforge:build    # neoforge/build/libs/
./gradlew :common:build      # compiles the shared code against bare vanilla
./gradlew :fabric:runClient      # dev client, Fabric
./gradlew :neoforge:runClient    # dev client, NeoForge
```

The jars are named from `mod_version` in `gradle.properties` and carry the loader in the
name — `synaptic-fabric-3.0.0.jar`, `synaptic-neoforge-3.0.0.jar`.

`:common:build` is the cheap check worth running first. A loader import that has crept into
shared code fails there, in seconds, instead of in whichever of the two jars happens to be
built second.

### One JDK: Temurin 25

**Everyone on this project builds on Eclipse Temurin 25 (LTS). Do not build on anything
else, and do not raise or lower the number on your own machine.** Four places already
agree on 25 and have to keep agreeing:

| Where | Setting |
| --- | --- |
| `build.gradle` (root, `subprojects`) | `java.toolchain.languageVersion = 25` |
| `build.gradle` (root, `subprojects`) | `options.release = 25` |
| `common/src/main/resources/synaptic.mixins.json` | `"compatibilityLevel": "JAVA_25"` |
| `fabric/src/main/resources/fabric.mod.json` | `"java": ">=25"` |

Still four, not five: the first two moved to the root `subprojects` block, where they cover
all three projects at once, and `neoforge.mods.toml` has no Java field to disagree with
them. Changing one without the others is how you get a jar that compiles here and refuses
to load there, so a JDK change is a four-file change plus a word to whoever else is working
on the mod — never a quiet local edit. Vendor is a convention, not a constraint: the
toolchain pins the *version*, not Temurin specifically, so another 25 will build. We
still all say Temurin so that "works on my machine" means the same machine everywhere.

Getting one:

- **IntelliJ** — Project Structure → SDKs → `+` → Download JDK → version 25, vendor
  Eclipse Temurin. Then set it as the Project SDK, and as the Gradle JVM under
  Settings → Build Tools → Gradle.
- **Command line** — Gradle needs a JVM before it can read the build, so `JAVA_HOME`
  must be set or `java` must be on `PATH`; a bare `./gradlew` with neither dies at the
  launcher. On this machine:
  `$env:JAVA_HOME = "C:\Users\magom\.jdks\temurin-25.0.4.1"` (your path will differ).
- **Neither** — the foojay resolver in `settings.gradle` downloads a matching JDK for the
  *compile* toolchain by itself, but it cannot help the launcher. You still need a JVM to
  start Gradle with.

After switching JDKs, run `./gradlew --stop` before building. Gradle reuses a live daemon
regardless of which JVM it was started on, which will happily hide the fact that your new
JDK was never exercised.

**The Gradle and Loom versions are a matched pair — do not bump one alone.** Loom 1.17.21
declares `org.gradle.plugin.api-version` 9.5.0. Gradle 9.6 removed
`JvmVendorSpec.IBM_SEMERU`, which Loom still references, so 9.6+ dies during configuration
before reading a single source file; Gradle 8.x is rejected by Loom's variant metadata. The
wrapper is pinned to 9.5.0 for exactly that window.

**That window now has to hold ModDevGradle too.** MDG 2.0.147 declares no
`org.gradle.plugin.api-version` of its own, so it will not refuse 9.5 on metadata grounds —
but it is the newer of the two plugins and Loom is the one with the hard ceiling, so if the
pair ever stops working the wrapper stays where it is and MDG moves. Pin MDG to a release
for the same reason Loom is pinned: snapshots are mutable and revalidated on a TTL.

The three version numbers that have to agree about the game are `minecraft_version` (26.3),
`neoforge_version` (26.3.0.x — NeoForge's line tracks the game) and `neoform_version`, which
is what `common` compiles against. Do not guess the last one: read it out of the NeoForge
build's own `neoforge-<version>-moddev-config.json` on maven, whose `mcp` field names it
(`net.neoforged:neoform:26.3-1@zip` for 26.3.0.16-beta). Taken from anywhere else, `common`
and `neoforge` end up compiled against two different Minecrafts. The same file lists the
`sponge-mixin` artifact that `mixin_version` has to match.

## Versioning

Semantic versioning, `MAJOR.MINOR.PATCH`, set by `mod_version` in `gradle.properties`.

- **PATCH** — `1.2.3` to `1.2.4`. A bugfix, nothing new. The poison-damage fix was one.
- **MINOR** — `1.2.3` to `1.3.0`. A feature added, nothing existing broken. Shared pets,
  shared mob anger.
- **MAJOR** — `1.2.3` to `2.0.0`. Existing worlds or setups need attention: a Minecraft
  version jump, a change to the config file's format, or a feature whose meaning changed
  under players who were relying on the old behaviour.

**A bump resets everything to its right.** `1.2.3` plus a feature is `1.3.0`, not `1.3.3`;
plus a breaking change it is `2.0.0`, not `2.2.3`. Without that the numbers stop carrying
information.

**Bump once per release, not once per commit.** The version marks a jar someone could
install, so five commits shipping together are one bump. Documentation-only changes are not
a release at all.

**Any change to the `Feature` enum is a MINOR bump at least, and jars must never be mixed
across one.** Settings travel between client and server as a bitmask of enum ordinals, so a
jar whose `Feature` order differs reads the other side's settings as nonsense. The config
file is keyed by name and survives reordering; the wire format does not.

On NeoForge the loader will now catch that for you: `SynapticNeoForge.PROTOCOL` is compared
between the two ends and a mismatch refuses the connection outright, which is a far better
failure than a garbled bitmask. **Bump `PROTOCOL` whenever a payload's shape changes,
`Feature` included.** Fabric has no equivalent and still relies on the rule above, so the
two jars are only as safe as the weaker of them — the bump is not optional just because one
side would have noticed.

## Things that bit us before

- **26.2 renamed a lot.** `GuiGraphics`, `Minecraft.setScreen`, and `hasPermissions(int)`
  are all gone; gamerules moved to `net.minecraft.world.level.gamerules`; permissions are a
  `PermissionSet`. Check the real signature against
  `~/.gradle/caches/fabric-loom/<minecraft_version>/minecraft-merged.jar` with `javap`
  before writing against a remembered API.
- **More 26.2 renames, found the hard way.** `ResourceLocation` is now
  `net.minecraft.resources.Identifier`; `ResourceKey.location()` is `identifier()`;
  `ChunkProgressListener*` is the `LevelLoadListener` family and the `ServerLevel`
  constructor no longer takes one (nor a `RandomSequences`); `ServerLevel.getSharedSpawnPos`
  is gone, replaced by `getRespawnData()` returning a `LevelData.RespawnData` record that
  carries yaw and pitch as well as the position. On the Fabric side `ServerWorldEvents` is
  now `ServerLevelEvents`, with `onLevelLoad` / `onLevelUnload`.
- **26.3 moved the render layer out from under blaze3d.** The pipeline, texture and
  sampler types are now `com.mojang.renderpearl.api.*`, and the desktop openers left
  `Util.OS`: `Util.getPlatform().openPath(...)` / `openUri(...)` are the static
  `com.mojang.blaze3d.Blaze3D.openPath` / `openUri`. `ServerClockManager.moveToTimeMarker`
  returns a `MoveResult` instead of a `boolean`. Every mixin target the mod uses came
  through 26.3 unchanged.
- **26.3 dropped GLFW for SDL3.** `org.lwjgl.glfw` is no longer on the class path, and key
  codes are SDL scancodes (`1` is 30, not 49). Use `InputConstants.KEY_*` — never a raw
  number or a `GLFW_KEY_*` — so the codes follow whichever library the game is on.
- **`GuiGraphicsExtractor.blit` takes edges, not sizes.** The nine-argument
  `blit(Identifier, int, int, int, int, float, float, float, float)` is
  `(id, x0, y0, x1, y1, u0, u1, v0, v1)` — the bytecode reorders them to
  `innerBlit(x0, x1, y0, y1, ...)`. Pass a width and a height and anything not drawn at
  the top-left corner comes out mirrored or upside down, because its edges end up
  crossed. A tile at (4, 4) draws correctly either way, which is how the mistake hides.
  Frames from `Screenshot.takeScreenshot` are already the right way up — it writes the
  rows in order rather than flipping afterwards — so `v0 = 0, v1 = 1` is correct.
- **Never rewrite a source file through PowerShell.** `Get-Content`/`Set-Content` on PS 5.1
  reads UTF-8 as ANSI (every em-dash becomes mojibake) and `-Encoding utf8` writes a BOM,
  which javac rejects outright with `illegal character: '﻿'`. Both happened. Edit
  files with the editing tools, not the shell.
- **The 26.2 client GUI moved again.** Screens live on `Minecraft.gui`
  (`gui.screen()` / `setScreen`), not on `Minecraft` itself; the HUD is `gui.hud` with
  `toggle()` / `isHidden()` and there is no `Options.hideGui`; and `Screen.keyPressed`
  takes a single `net.minecraft.client.input.KeyEvent` rather than three ints.
- **NeoForge ticks a cached copy of the level map.** `MinecraftServer.getWorldArray()` is
  rebuilt only after `markWorldsDirty()`, which NeoForge itself never calls. A level put
  into the map at runtime is never ticked on NeoForge — no weather cycle, no mobs — while
  Fabric, on vanilla's live loop, works fine. `NeoForgePlatform.levelLoaded` /
  `levelUnloaded` mark it dirty, so every runtime level must go through them.
- **Mixins apply when their target class first loads**, not at startup. A broken injection
  surfaces on world entry or when the relevant entity first spawns.
- **The settings screen lays out once.** `StringWidget` has no alignment methods in this
  version and reports its text width as its own, so text only centres if it is present when
  the grid runs. Changing a label afterwards leaves it where the empty widget sat.
- **Shared state multiplies.** Anything applied per player and then summed counts N times —
  this caused the poison, starvation, and wither damage bug, and the same trap is waiting in
  any new shared value.
