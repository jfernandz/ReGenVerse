package com.regenverse.core;

import com.regenverse.ReGenVerse;
import com.regenverse.config.ReGenVerseConfig;
import com.regenverse.network.CycleStatePayload;
import com.regenverse.state.ReGenVerseState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ThreadLocalRandom;

public final class ReGenVerseManager {
    private static final String CONFIG_FILE = "regenverse-server.json";
    private static final String STATE_FILE = "regenverse-state.json";
    private static final int CYCLE_VIEW_DISTANCE = 2;
    private static final int CYCLE_SIMULATION_DISTANCE = 2;

    private static ReGenVerseConfig config;
    private static ReGenVerseState state;
    private static Path statePath;
    private static int tickAccumulator;
    private static boolean cycleInProgress;

    private ReGenVerseManager() {
    }

    public static void onServerStarted(MinecraftServer server) {
        Path configPath = Path.of("config").resolve(CONFIG_FILE);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        statePath = worldRoot.resolve("data").resolve(STATE_FILE);

        config = ReGenVerseConfig.loadOrCreate(configPath);
        state = ReGenVerseState.loadOrCreate(statePath);

        long now = Instant.now().getEpochSecond();
        if (state.nextCycleEpochSeconds <= 0) {
            state.nextCycleEpochSeconds = now + config.cycleIntervalSeconds();
            state.activeSeed = server.overworld().getSeed();
            state.save(statePath);
        }

        ReGenVerse.LOGGER.info("ReGenVerse active. Next cycle at unix={} (epoch={})", state.nextCycleEpochSeconds, state.epoch);
    }

    public static void onServerTick(MinecraftServer server) {
        if (config == null || state == null || !config.enabled) {
            return;
        }

        tickAccumulator++;
        if (tickAccumulator < 20) {
            return;
        }
        tickAccumulator = 0;

        long now = Instant.now().getEpochSecond();
        if (now >= state.nextCycleEpochSeconds) {
            triggerCycle(server, false);
        }
    }

    public static void reloadConfig(MinecraftServer server) {
        Path configPath = Path.of("config").resolve(CONFIG_FILE);
        config = ReGenVerseConfig.loadOrCreate(configPath);
        ReGenVerse.LOGGER.info("ReGenVerse config reloaded");
    }

    public static void forceCycle(MinecraftServer server) {
        triggerCycle(server, true);
    }

    public static void syncCycleState(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, CycleStatePayload.TYPE)) {
            ServerPlayNetworking.send(player, new CycleStatePayload(cycleInProgress));
        }
    }

    public static String statusLine(MinecraftServer server) {
        if (config == null || state == null) {
            return "ReGenVerse is not initialized yet.";
        }

        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int protectedChunkRadius = resolveProtectedChunkRadius(overworld);
        int spawnChunkRadiusRule = Math.max(0, overworld.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
        int loadedOverworldChunks = overworld.getChunkSource().getLoadedChunksCount();

        return "enabled=" + config.enabled
            + ", dryRun=" + config.dryRun
            + ", cycleInProgress=" + cycleInProgress
            + ", epoch=" + state.epoch
            + ", activeSeed=" + state.activeSeed
            + ", nextCycleUnix=" + state.nextCycleEpochSeconds
            + ", protectSpawnChunks=" + config.protectSpawnChunks
            + ", spawnChunkRadiusRule=" + spawnChunkRadiusRule
            + ", protectedChunkRadius=" + protectedChunkRadius
            + ", loadedOverworldChunks=" + loadedOverworldChunks
            + ", spawnZoneChunks=[x,z +-" + protectedChunkRadius + "] around "
            + "(" + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ() + ")";
    }

    private static void triggerCycle(MinecraftServer server, boolean manual) {
        long now = Instant.now().getEpochSecond();
        long newSeed = ThreadLocalRandom.current().nextLong();
        ServerLevel overworld = server.overworld();
        int protectedChunkRadius = resolveProtectedChunkRadius(overworld);
        long nextEpoch = state.epoch + 1;

        if (config.dryRun) {
            state.epoch = nextEpoch;
            state.activeSeed = newSeed;
            state.nextCycleEpochSeconds = now + config.cycleIntervalSeconds();
            state.save(statePath);
            ReGenVerse.LOGGER.warn("[DRY RUN] ReGenVerse cycle {} triggered (manual={}) with seed {}", state.epoch, manual, newSeed);
            return;
        }

        if (cycleInProgress) {
            ReGenVerse.LOGGER.warn("Skipped ReGenVerse cycle trigger because another cycle is already in progress.");
            return;
        }

        cycleInProgress = true;
        broadcastCycleState(server, true);

        try {
            evacuatePlayersToSpawn(server, overworld, nextEpoch);
            PlayerList playerList = server.getPlayerList();
            int originalViewDistance = playerList.getViewDistance();
            int originalSimulationDistance = playerList.getSimulationDistance();
            boolean distancesAdjusted = false;
            if (server.getPlayerCount() > 0) {
                if (originalViewDistance != CYCLE_VIEW_DISTANCE) {
                    playerList.setViewDistance(CYCLE_VIEW_DISTANCE);
                    distancesAdjusted = true;
                }
                if (originalSimulationDistance != CYCLE_SIMULATION_DISTANCE) {
                    playerList.setSimulationDistance(CYCLE_SIMULATION_DISTANCE);
                    distancesAdjusted = true;
                }
                if (distancesAdjusted) {
                    ReGenVerse.LOGGER.info(
                        "ReGenVerse cycle {} applied temporary distances: view {}->{} simulation {}->{}.",
                        nextEpoch,
                        originalViewDistance,
                        playerList.getViewDistance(),
                        originalSimulationDistance,
                        playerList.getSimulationDistance()
                    );
                }
            }

            List<ServerPlayer> ticketSuspendedPlayers = suspendOverworldPlayerTickets(overworld, server);
            state.epoch = nextEpoch;
            state.activeSeed = newSeed;
            state.nextCycleEpochSeconds = now + config.cycleIntervalSeconds();
            state.save(statePath);

            int originalSpawnChunkRadiusRule = disableSpawnChunkTickets(overworld, server);
            int loadedBefore = overworld.getChunkSource().getLoadedChunksCount();
            try {
                int droppedItemsRemoved = clearUnprotectedDroppedItems(overworld, protectedChunkRadius);
                int unloadedChunksBeforeWipe = unloadUnprotectedLoadedChunks(overworld, protectedChunkRadius);
                drainOverworldChunkTasks(overworld);
                server.saveEverything(true, true, true);

                RegionFileChunkResetter.ResetReport report = RegionFileChunkResetter.resetOverworldUnprotectedChunks(overworld, protectedChunkRadius);
                int unloadedChunksAfterWipe = unloadUnprotectedLoadedChunks(overworld, protectedChunkRadius);
                drainOverworldChunkTasks(overworld);
                int loadedAfter = overworld.getChunkSource().getLoadedChunksCount();

                ReGenVerse.LOGGER.info(
                    "ReGenVerse cycle {} triggered (manual={}) with seed {}. Unprotected chunk removal report: files={}, existingChunks={}, protectedChunks={}, removedChunks={}, droppedItemsRemoved={}, unloadedBeforeWipe={}, unloadedAfterWipe={}, loadedBefore={}, loadedAfter={}",
                    nextEpoch,
                    manual,
                    newSeed,
                    report.regionFilesScanned(),
                    report.existingChunks(),
                    report.protectedChunks(),
                    report.removedChunks(),
                    droppedItemsRemoved,
                    unloadedChunksBeforeWipe,
                    unloadedChunksAfterWipe,
                    loadedBefore,
                    loadedAfter
                );
                ReGenVerse.LOGGER.info("Removed chunks regenerate on next load using the current world seed.");
            } finally {
                restoreSpawnChunkTickets(overworld, server, originalSpawnChunkRadiusRule);
                if (!ticketSuspendedPlayers.isEmpty()) {
                    resumeOverworldPlayerTickets(overworld, ticketSuspendedPlayers);
                }
                if (distancesAdjusted) {
                    playerList.setViewDistance(originalViewDistance);
                    playerList.setSimulationDistance(originalSimulationDistance);
                    ReGenVerse.LOGGER.info(
                        "ReGenVerse cycle {} restored server distances: view={} simulation={}.",
                        nextEpoch,
                        originalViewDistance,
                        originalSimulationDistance
                    );
                }
            }
        } finally {
            cycleInProgress = false;
            broadcastCycleState(server, false);
        }
    }

    private static void broadcastCycleState(MinecraftServer server, boolean active) {
        CycleStatePayload payload = new CycleStatePayload(active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, CycleStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static void evacuatePlayersToSpawn(MinecraftServer server, ServerLevel overworld, long nextEpoch) {
        if (server.getPlayerCount() == 0) {
            return;
        }

        BlockPos spawn = overworld.getSharedSpawnPos();
        double tpX = spawn.getX() + 0.5D;
        double tpY = spawn.getY();
        double tpZ = spawn.getZ() + 0.5D;

        int movedPlayers = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.teleportTo(overworld, tpX, tpY, tpZ, player.getYRot(), player.getXRot());
            movedPlayers++;
        }

        ReGenVerse.LOGGER.info(
            "ReGenVerse cycle {} moved {} player(s) to spawn before wipe.",
            nextEpoch,
            movedPlayers
        );
    }

    private static List<ServerPlayer> suspendOverworldPlayerTickets(ServerLevel overworld, MinecraftServer server) {
        List<ServerPlayer> suspended = new ArrayList<>();
        try {
            Object chunkMap = overworld.getChunkSource().chunkMap;
            Method updatePlayerStatusMethod = chunkMap.getClass().getDeclaredMethod("updatePlayerStatus", ServerPlayer.class, boolean.class);
            updatePlayerStatusMethod.setAccessible(true);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.serverLevel() != overworld) {
                    continue;
                }

                updatePlayerStatusMethod.invoke(chunkMap, player, false);
                suspended.add(player);
            }

            if (!suspended.isEmpty()) {
                ReGenVerse.LOGGER.info("ReGenVerse temporarily suspended overworld chunk tickets for {} player(s).", suspended.size());
            }
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to suspend overworld player chunk tickets before wipe.", e);
        }

        return suspended;
    }

    private static void resumeOverworldPlayerTickets(ServerLevel overworld, List<ServerPlayer> suspendedPlayers) {
        try {
            Object chunkMap = overworld.getChunkSource().chunkMap;
            Method updatePlayerStatusMethod = chunkMap.getClass().getDeclaredMethod("updatePlayerStatus", ServerPlayer.class, boolean.class);
            updatePlayerStatusMethod.setAccessible(true);

            int resumed = 0;
            for (ServerPlayer player : suspendedPlayers) {
                if (player.isRemoved() || !player.connection.isAcceptingMessages()) {
                    continue;
                }
                if (player.serverLevel() != overworld) {
                    continue;
                }

                updatePlayerStatusMethod.invoke(chunkMap, player, true);
                // Force chunk tracking refresh for this player after ticket restoration.
                overworld.getChunkSource().move(player);
                resumed++;
            }

            if (resumed > 0) {
                ReGenVerse.LOGGER.info("ReGenVerse restored overworld chunk tickets for {} player(s).", resumed);
            }
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to restore overworld player chunk tickets after wipe.", e);
        }
    }

    public static String playerChunkStatus(ServerPlayer player) {
        if (config == null || state == null) {
            return "ReGenVerse is not initialized yet.";
        }

        ServerLevel level = player.serverLevel();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int spawnChunkX = level.getSharedSpawnPos().getX() >> 4;
        int spawnChunkZ = level.getSharedSpawnPos().getZ() >> 4;
        int protectedChunkRadius = resolveProtectedChunkRadius(level);
        boolean protectedChunk = isProtectedChunk(chunkX, chunkZ, spawnChunkX, spawnChunkZ, protectedChunkRadius);
        boolean hasRegionEntry = level == player.server.overworld()
            && RegionFileChunkResetter.hasOverworldChunkEntry(level, chunkX, chunkZ);

        return "dimension=" + level.dimension().location()
            + ", chunk=(" + chunkX + "," + chunkZ + ")"
            + ", protected=" + protectedChunk
            + ", spawnChunk=(" + spawnChunkX + "," + spawnChunkZ + ")"
            + ", protectedChunkRadius=" + protectedChunkRadius
            + ", hasRegionEntry=" + hasRegionEntry;
    }

    private static int unloadUnprotectedLoadedChunks(ServerLevel overworld, int protectedChunkRadius) {
        try {
            Object chunkMap = overworld.getChunkSource().chunkMap;
            Method getChunksMethod = chunkMap.getClass().getDeclaredMethod("getChunks");
            getChunksMethod.setAccessible(true);
            Method scheduleUnloadMethod = chunkMap.getClass().getDeclaredMethod("scheduleUnload", long.class, ChunkHolder.class);
            scheduleUnloadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Iterable<ChunkHolder> holders = (Iterable<ChunkHolder>) getChunksMethod.invoke(chunkMap);
            if (holders == null) {
                return 0;
            }

            int spawnChunkX = overworld.getSharedSpawnPos().getX() >> 4;
            int spawnChunkZ = overworld.getSharedSpawnPos().getZ() >> 4;

            List<ChunkHolder> toUnload = new ArrayList<>();
            for (ChunkHolder holder : holders) {
                if (holder == null) {
                    continue;
                }

                int chunkX = holder.getPos().x;
                int chunkZ = holder.getPos().z;
                if (isProtectedChunk(chunkX, chunkZ, spawnChunkX, spawnChunkZ, protectedChunkRadius)) {
                    continue;
                }

                if (holder.getLatestChunk() instanceof LevelChunk levelChunk) {
                    // Prevent stale chunk content from being persisted during this cycle.
                    levelChunk.setUnsaved(false);
                }
                toUnload.add(holder);
            }

            for (ChunkHolder holder : toUnload) {
                scheduleUnloadMethod.invoke(chunkMap, holder.getPos().toLong(), holder);
            }

            return toUnload.size();
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to unload non-protected loaded chunks before cycle.", e);
            return 0;
        }
    }

    private static int clearUnprotectedDroppedItems(ServerLevel overworld, int protectedChunkRadius) {
        int spawnChunkX = overworld.getSharedSpawnPos().getX() >> 4;
        int spawnChunkZ = overworld.getSharedSpawnPos().getZ() >> 4;

        List<ItemEntity> toDiscard = new ArrayList<>();
        for (var entity : overworld.getAllEntities()) {
            if (!(entity instanceof ItemEntity itemEntity) || itemEntity.isRemoved()) {
                continue;
            }

            int chunkX = itemEntity.blockPosition().getX() >> 4;
            int chunkZ = itemEntity.blockPosition().getZ() >> 4;
            if (isProtectedChunk(chunkX, chunkZ, spawnChunkX, spawnChunkZ, protectedChunkRadius)) {
                continue;
            }

            toDiscard.add(itemEntity);
        }

        for (ItemEntity itemEntity : toDiscard) {
            itemEntity.discard();
        }

        return toDiscard.size();
    }

    private static void drainOverworldChunkTasks(ServerLevel overworld) {
        int rounds = 0;
        while (rounds < 32) {
            rounds++;
            overworld.getChunkSource().tick(() -> true, true);
            runDistanceManagerUpdates(overworld);
            promoteChunkMap(overworld);
            processChunkMapUnloads(overworld);
            tickChunkMap(overworld);

            int thisRoundPolled = 0;
            while (thisRoundPolled < 10_000 && overworld.getChunkSource().pollTask()) {
                thisRoundPolled++;
            }

            if (thisRoundPolled == 0) {
                break;
            }
        }
    }

    private static void promoteChunkMap(ServerLevel overworld) {
        try {
            Method method = overworld.getChunkSource().chunkMap.getClass().getDeclaredMethod("promoteChunkMap");
            method.setAccessible(true);
            method.invoke(overworld.getChunkSource().chunkMap);
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to promote chunk map during cycle.", e);
        }
    }

    private static void processChunkMapUnloads(ServerLevel overworld) {
        try {
            Method method = overworld.getChunkSource().chunkMap.getClass().getDeclaredMethod("processUnloads", BooleanSupplier.class);
            method.setAccessible(true);
            method.invoke(overworld.getChunkSource().chunkMap, (BooleanSupplier) () -> true);
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to process chunk unloads during cycle.", e);
        }
    }

    private static int disableSpawnChunkTickets(ServerLevel overworld, MinecraftServer server) {
        GameRules.IntegerValue spawnChunkRadiusRule = overworld.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        int original = Math.max(0, spawnChunkRadiusRule.get());
        if (original > 0) {
            spawnChunkRadiusRule.set(0, server);
            ReGenVerse.LOGGER.info("ReGenVerse cycle: temporarily set spawnChunkRadius {} -> 0 to allow unprotected chunk unload.", original);
        }
        return original;
    }

    private static void restoreSpawnChunkTickets(ServerLevel overworld, MinecraftServer server, int originalSpawnChunkRadiusRule) {
        if (originalSpawnChunkRadiusRule <= 0) {
            return;
        }

        GameRules.IntegerValue spawnChunkRadiusRule = overworld.getGameRules().getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS);
        if (spawnChunkRadiusRule.get() != originalSpawnChunkRadiusRule) {
            spawnChunkRadiusRule.set(originalSpawnChunkRadiusRule, server);
            ReGenVerse.LOGGER.info("ReGenVerse cycle: restored spawnChunkRadius to {}.", originalSpawnChunkRadiusRule);
        }
    }

    private static void runDistanceManagerUpdates(ServerLevel overworld) {
        try {
            Method method = overworld.getChunkSource().getClass().getDeclaredMethod("runDistanceManagerUpdates");
            method.setAccessible(true);

            int updates = 0;
            while (updates < 512 && (boolean) method.invoke(overworld.getChunkSource())) {
                updates++;
            }
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to run distance manager updates during cycle.", e);
        }
    }

    private static void tickChunkMap(ServerLevel overworld) {
        try {
            Method method = overworld.getChunkSource().chunkMap.getClass().getDeclaredMethod("tick");
            method.setAccessible(true);
            method.invoke(overworld.getChunkSource().chunkMap);
        } catch (ReflectiveOperationException e) {
            ReGenVerse.LOGGER.error("Failed to tick chunk map during cycle.", e);
        }
    }

    private static boolean isProtectedChunk(int chunkX, int chunkZ, int spawnChunkX, int spawnChunkZ, int protectedChunkRadius) {
        return Math.abs(chunkX - spawnChunkX) <= protectedChunkRadius
            && Math.abs(chunkZ - spawnChunkZ) <= protectedChunkRadius;
    }

    private static int resolveProtectedChunkRadius(ServerLevel overworld) {
        if (!config.protectSpawnChunks) {
            return Math.max(0, config.protectedChunkRadiusOverride);
        }

        if (config.protectedChunkRadiusOverride >= 0) {
            return config.protectedChunkRadiusOverride;
        }

        int spawnChunkRadiusRule = Math.max(0, overworld.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
        // Mojang's gamerule 2 means a 3x3 spawn-chunk square, so convert to per-axis chunk distance.
        return spawnChunkRadiusRule == 0 ? 0 : spawnChunkRadiusRule - 1;
    }
}
