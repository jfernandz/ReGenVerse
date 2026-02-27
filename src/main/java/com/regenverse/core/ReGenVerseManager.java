package com.regenverse.core;

import com.regenverse.ReGenVerse;
import com.regenverse.config.ReGenVerseConfig;
import com.regenverse.state.ReGenVerseState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class ReGenVerseManager {
    private static final String CONFIG_FILE = "regenverse-server.json";
    private static final String STATE_FILE = "regenverse-state.json";

    private static ReGenVerseConfig config;
    private static ReGenVerseState state;
    private static Path statePath;
    private static int tickAccumulator;

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

    public static String statusLine(MinecraftServer server) {
        if (config == null || state == null) {
            return "ReGenVerse is not initialized yet.";
        }

        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();

        return "enabled=" + config.enabled
            + ", dryRun=" + config.dryRun
            + ", epoch=" + state.epoch
            + ", activeSeed=" + state.activeSeed
            + ", nextCycleUnix=" + state.nextCycleEpochSeconds
            + ", spawnZone=[x,z +-" + config.protectedRadiusBlocks + ", y "
            + config.protectedMinY + ".." + config.protectedMaxY + "] around "
            + "(" + spawn.getX() + "," + spawn.getY() + "," + spawn.getZ() + ")";
    }

    private static void triggerCycle(MinecraftServer server, boolean manual) {
        long now = Instant.now().getEpochSecond();
        long newSeed = ThreadLocalRandom.current().nextLong();

        state.epoch += 1;
        state.activeSeed = newSeed;
        state.nextCycleEpochSeconds = now + config.cycleIntervalSeconds();
        state.save(statePath);

        if (config.dryRun) {
            ReGenVerse.LOGGER.warn("[DRY RUN] ReGenVerse cycle {} triggered (manual={}) with seed {}", state.epoch, manual, newSeed);
            return;
        }

        ReGenVerse.LOGGER.info("ReGenVerse cycle {} triggered (manual={}) with seed {}", state.epoch, manual, newSeed);

        // Blueprint phase: this is where chunk regeneration is executed.
        // Proposed production strategy:
        // 1) Mark all chunks outside protected spawn zone as stale for the new epoch.
        // 2) On chunk load, if stale and currently unloaded by players, replace data and force re-generation.
        // 3) Keep protected zone chunk data untouched.
    }
}
