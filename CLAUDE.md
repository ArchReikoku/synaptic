# Working agreement

## Testing

If the test is going to take a lot of time, don't do it in order to save my tokens.
write in TESTING.md what should be tested manually and how, also write what you have tested und results.

---

# Project notes

**Synaptic** is mod for Minecraft.
Published at https://github.com/ArchReikoku/synaptic (MIT).

### One JDK: Temurin 25

**Everyone on this project builds on Eclipse Temurin 25 (LTS). Do not build on anything
else, and do not raise or lower the number on your own machine.**
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
