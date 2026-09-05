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

`JAVA_HOME` is not set on this machine; Gradle needs a JDK 25+ (there is one at
`~/.jdks/jbr-25.0.3`). The jar is named from `mod_version` in `gradle.properties`.

## Things that bit us before

- **26.2 renamed a lot.** `GuiGraphics`, `Minecraft.setScreen`, and `hasPermissions(int)`
  are all gone; gamerules moved to `net.minecraft.world.level.gamerules`; permissions are a
  `PermissionSet`. Check the real signature against
  `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` with `javap` before writing
  against a remembered API.
- **Mixins apply when their target class first loads**, not at startup. A broken injection
  surfaces on world entry or when the relevant entity first spawns.
- **The settings screen lays out once.** `StringWidget` has no alignment methods in this
  version and reports its text width as its own, so text only centres if it is present when
  the grid runs. Changing a label afterwards leaves it where the empty widget sat.
- **Shared state multiplies.** Anything applied per player and then summed counts N times —
  this caused the poison, starvation, and wither damage bug, and the same trap is waiting in
  any new shared value.
