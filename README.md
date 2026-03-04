# ReGenVerse (Fabric 1.21.1)

ReGenVerse is a world lifecycle mod concept for Minecraft Fabric 1.21.1.

## Goal
- Protect a fixed 3D spawn zone forever.
- Periodically regenerate the rest of the world using a different seed each cycle.

## Feasibility
Yes, this is possible, but it is a medium/high-complexity server-side systems mod. The hard part is not config/scheduling, it is safe chunk replacement while players are online.

This blueprint already gives you:
- Fabric + Loom project setup similar to `Locks-Unofficial` (`runClient` / `runServer`).
- Server config and persistent state files.
- Cycle scheduler and admin commands.
- Explicit extension points for implementing chunk regeneration.

## Current Commands
- `/regenverse status` -> shows runtime config/state.
- `/regenverse reload` -> reloads `config/regenverse-server.json`.
- `/regenverse here` -> shows server-side chunk status at your current position.
- `/regenverse cycle` -> triggers a cycle immediately.

## Config File
`config/regenverse-server.json`

```json
{
  "enabled": true,
  "cycleIntervalMinutes": 240,
  "protectSpawnChunks": true,
  "protectedChunkRadiusOverride": -1,
  "dryRun": true
}
```

When `protectSpawnChunks=true`, protection uses the overworld `spawnChunkRadius` gamerule.
Set `protectedChunkRadiusOverride` to a non-negative number to force a custom chunk radius.
With default gamerule `spawnChunkRadius=2`, ReGenVerse protects a `3x3` chunk area (9 chunks).

`dryRun=true` means cycles are logged and persisted but no chunk data is touched yet.

When `dryRun=false`, ReGenVerse now performs a first regeneration phase:
- teleports online players to overworld spawn
- temporarily suspends overworld player chunk tickets
- temporarily sets server `viewDistance` and `simulationDistance` to `2`
- temporarily sets gamerule `spawnChunkRadius` to `0`
- unloads non-protected loaded overworld chunks
- flushes world save data
- scans overworld chunk entries
- preserves protected spawn chunks
- deletes unprotected chunks through Minecraft's chunk storage worker
- restores player chunk tickets, server distances, and original `spawnChunkRadius`

Those removed chunks regenerate the next time they are loaded.

## Runtime State File
`<world>/data/regenverse-state.json`

Stores:
- `epoch`
- `activeSeed`
- `nextCycleEpochSeconds`

## Implementation Blueprint (Next Phases)
1. Epoch Seed Wiring
- Route worldgen calls to use per-epoch seed instead of only the original world seed.

2. Data Preservation Rules
- Decide what survives resets (structures, claims, inventories, entities, block entities).
- Add rule-based whitelist/blacklist policies.

3. Safety + Operations
- Add pre-cycle warning broadcasts and grace period.
- Add backups and rollback hooks.
- Add rate limits to avoid TPS spikes.

## Dev Workflow
Same as standard Fabric Loom:

```bash
./gradlew runClient
./gradlew runServer
```

## Important Technical Note
Minecraft does not provide a single high-level API to "reseed existing world data" live. In practice, ReGenVerse must own chunk replacement logic (or region-file surgery plus controlled re-generation) and coordinate that with chunk loading rules.
