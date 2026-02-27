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
- `/regenverse cycle` -> triggers a cycle immediately.

## Config File
`config/regenverse-server.json`

```json
{
  "enabled": true,
  "cycleIntervalMinutes": 240,
  "protectedRadiusBlocks": 256,
  "protectedMinY": -64,
  "protectedMaxY": 320,
  "dryRun": true
}
```

`dryRun=true` means cycles are logged and persisted but no chunk data is touched yet.

## Runtime State File
`<world>/data/regenverse-state.json`

Stores:
- `epoch`
- `activeSeed`
- `nextCycleEpochSeconds`

## Implementation Blueprint (Next Phases)
1. Chunk Index + Epoch Tagging
- Persist per-chunk epoch metadata (dimension + chunk pos -> last generated epoch).
- Keep spawn-zone chunks permanently pinned to epoch 0.

2. Regeneration Engine
- For chunks outside protection zone: detect stale chunks and rebuild terrain with cycle seed.
- Never mutate currently occupied chunks (player-safe queue).

3. Data Preservation Rules
- Decide what survives resets (structures, claims, inventories, entities, block entities).
- Add rule-based whitelist/blacklist policies.

4. Safety + Operations
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
