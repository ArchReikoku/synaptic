# Synaptic

**One life, shared.** A Fabric mod for Minecraft 26.2 that pools health, hunger, experience,
status effects, advancements, pets, the inventory and the ender chest across every player
online — and if one player dies, everyone dies, then comes back together.

Built for hardcore co-op: the group succeeds or fails together, and there is no quietly
banking your own supplies while someone else starves.

---

## What is shared

| | |
|---|---|
| **Life** | Health, golden hearts and damage are pooled. Everyone's hearts move together, and a hit on one is a hit on all. |
| **Death** | One death is everyone's death. When any player dies the rest are killed with them. |
| **Respawn** | One respawn is everyone's respawn. Whoever clicks Respawn first brings the whole group back at the same moment, so nobody is left sitting on the death screen. |
| **Air** | Shows everyone the bubbles of whoever is under water, so the group can watch the diver run out of breath. Only the submerged player drowns — but hearts are shared, so the group pays for it, once per breath rather than once per swimmer. Off by default. |
| **Hunger** | Food and saturation are pooled, so the group eats and starves as one. |
| **Inventory** | One inventory for everyone, armour and offhand included. Each player still holds their own slot and can use it freely. |
| **Ender Chest** | A second shared pool of 27 slots, separate from the main inventory. |
| **Effects** | A potion effect on anyone applies to everyone, at the strongest level and longest duration going. |
| **Experience** | Levels and XP are pooled. Orbs picked up by one player level the whole group. |
| **Advancements** | An advancement earned by one player is granted to everyone, including recipe unlocks. |
| **Pets** | Every tamed animal answers to everyone. Shared pets never turn on a player, and one that follows will follow whoever handled it last. |
| **Mob Anger** | Provoke a neutral mob and it turns on the whole group, not just whoever provoked it. Only players it could already see are drawn in — someone far away is never targeted. |

## Extras

| | |
|---|---|
| **Keep Inventory** | Forces the `keepInventory` gamerule on while inventories are shared. Without it every player drops a copy of the shared inventory on death, duplicating all of it across the floor. |
| **Split Hunger** | Each player's exertion costs only 1/N exhaustion, so four people sprinting drain the shared bar at one player's rate. |
| **Damage Chat** | Announces every hit with the damage, its cause and the hearts left: `Steve took 1.5❤ damage from fall (3.5❤ left)` |
| **Damage Sound** | Everyone hears a hurt sound when anyone is hit, however far apart you are. |
| **Solo Sleep** | One player in a bed skips the night. |
| **Next Run** | A Next Run button on the death screen. Builds a fresh world with a new seed, drops everyone on the same block facing the same way, wipes inventories and deletes the run you came from — without anyone disconnecting. Host only. |
| **Death Report** | Replaces the death screen with the run's obituary: whose death ended it and how, and the session totals for everyone. Vanilla's buttons move to a row in the corner. |
| **Session Tab** | Replaces the player list with session totals — damage dealt and taken, meals, hunger spent, XP earned, advancements unlocked and deaths caused — for everyone who has joined. Kept across world resets; offline players stay listed, dimmed. |
| **Tool Swap Mining** | Mining progress survives a change of held item — see the note below. |

Every one of these can be switched off individually.

## Configuring

Press **K** in game for the settings screen, or use the command:

```
/synaptic                          list every feature and its state
/synaptic <feature> <true|false>   turn one on or off
```

Settings apply to the whole server and are saved to `config/synaptic.properties`.
Reading them is open to anyone. Changing them is limited to **the host**: on a world you
opened yourself, singleplayer or over LAN, that is you and nobody else — no cheats
required, and guests do not get a say even if they are opped. On a dedicated server,
where there is no host sitting at the game, it is operators instead.

## Installing

- **Minecraft 26.2**, **Fabric Loader 0.19.3+**, **Fabric API**, **Java 25+**
- Drop the jar in `mods/`.

Almost everything runs server-side, so on a multiplayer server the mod only strictly needs
to be on the server. **Tool Swap Mining is the exception** — mining is driven by the client,
so that feature needs the mod installed client-side for each player who wants it. The
settings screen also needs it client-side; without it, use `/synaptic`.

## Two things worth knowing

**Tool Swap Mining is not just a convenience.** Vanilla restarts a block break whenever your
held stack changes. With a shared inventory that means another player picking up items can
reset your mining progress — or stop you breaking a block at all. Leaving it on is
recommended; the settings screen warns you if you switch it off.

**Toggling shared inventory mid-game costs you.** Turning it back on merges every player's
inventory into a single 36-slot one, and anything that does not fit is destroyed. The
settings screen warns before you commit, and announces it in chat afterwards.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Requires a JDK 25+.

## License

[MIT](LICENSE) — use it, change it, put it in a modpack. Not affiliated with Mojang Studios.
