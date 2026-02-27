package com.regenverse.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class SpawnProtectionZone {
    private SpawnProtectionZone() {
    }

    public static boolean isProtected(ServerLevel world, BlockPos pos, int radius, int minY, int maxY) {
        BlockPos spawn = world.getSharedSpawnPos();

        int minX = spawn.getX() - radius;
        int maxX = spawn.getX() + radius;
        int minZ = spawn.getZ() - radius;
        int maxZ = spawn.getZ() + radius;

        boolean insideXZ = pos.getX() >= minX && pos.getX() <= maxX
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        boolean insideY = pos.getY() >= minY && pos.getY() <= maxY;

        return insideXZ && insideY;
    }
}
