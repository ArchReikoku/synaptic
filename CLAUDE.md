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

**Synaptic** — a Fabric mod for Minecraft 26.2. One life shared across every player.
Published at https://github.com/ArchReikoku/synaptic (MIT).

## Building, when asked

```
./gradlew build          # jar lands in build/libs/
./gradlew runClient      # dev client
```

`JAVA_HOME` points at temurin-21, which is too old. Build with
`$env:JAVA_HOME = "C:\Users\om\.jdks\openjdk-26.0.1"` — that is the only JDK 25+ on the
machine, which is why the toolchain asks for 26 while `options.release` still targets 25.
The jar is named from `mod_version` in `gradle.properties`.

**The Gradle and Loom versions are a matched pair — do not bump one alone.** Loom 1.17.20
declares `org.gradle.plugin.api-version` 9.5.0. Gradle 9.6 removed
`JvmVendorSpec.IBM_SEMERU`, which Loom still references, so 9.6+ dies during configuration
before reading a single source file; Gradle 8.x is rejected by Loom's variant metadata. The
wrapper is pinned to 9.5.0 for exactly that window.

## Things that bit us before

- **26.2 renamed a lot.** `GuiGraphics`, `Minecraft.setScreen`, and `hasPermissions(int)`
  are all gone; gamerules moved to `net.minecraft.world.level.gamerules`; permissions are a
  `PermissionSet`. Check the real signature against
  `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` with `javap` before writing
  against a remembered API.
- **More 26.2 renames, found the hard way.** `ResourceLocation` is now
  `net.minecraft.resources.Identifier`; `ResourceKey.location()` is `identifier()`;
  `ChunkProgressListener*` is the `LevelLoadListener` family and the `ServerLevel`
  constructor no longer takes one (nor a `RandomSequences`); `ServerLevel.getSharedSpawnPos`
  is gone, replaced by `getRespawnData()` returning a `LevelData.RespawnData` record that
  carries yaw and pitch as well as the position. On the Fabric side `ServerWorldEvents` is
  now `ServerLevelEvents`, with `onLevelLoad` / `onLevelUnload`.
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
- **Mixins apply when their target class first loads**, not at startup. A broken injection
  surfaces on world entry or when the relevant entity first spawns.
- **The settings screen lays out once.** `StringWidget` has no alignment methods in this
  version and reports its text width as its own, so text only centres if it is present when
  the grid runs. Changing a label afterwards leaves it where the empty widget sat.
- **Shared state multiplies.** Anything applied per player and then summed counts N times —
  this caused the poison, starvation, and wither damage bug, and the same trap is waiting in
  any new shared value.
