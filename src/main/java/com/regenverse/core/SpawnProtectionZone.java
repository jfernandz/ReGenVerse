package com.regenverse.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class SpawnProtectionZone {
    private SpawnProtectionZone() {
    }

    public static boolean isProtected(ServerLevel world, BlockPos pos, int chunkRadius) {
        BlockPos spawn = world.getSharedSpawnPos();

        int spawnChunkX = spawn.getX() >> 4;
        int spawnChunkZ = spawn.getZ() >> 4;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;

        return Math.abs(chunkX - spawnChunkX) <= chunkRadius
            && Math.abs(chunkZ - spawnChunkZ) <= chunkRadius;
    }
}
