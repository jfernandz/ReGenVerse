package com.regenverse.core;

import com.regenverse.ReGenVerse;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegionFileChunkResetter {
    private static final int LOCATION_TABLE_BYTES = 4096;
    private static final int TIMESTAMP_TABLE_BYTES = 4096;
    private static final int HEADER_BYTES = LOCATION_TABLE_BYTES + TIMESTAMP_TABLE_BYTES;
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionFileChunkResetter() {
    }

    public static ResetReport resetOverworldUnprotectedChunks(ServerLevel overworld, int protectedChunkRadius) {
        Path regionDir = overworld.getServer().getWorldPath(LevelResource.ROOT).resolve("region");
        ChunkPos spawnChunk = new ChunkPos(overworld.getSharedSpawnPos());

        if (Files.notExists(regionDir)) {
            return new ResetReport(0, 0, 0, 0);
        }

        int regionFiles = 0;
        int existingChunks = 0;
        int protectedChunks = 0;
        int removedChunks = 0;
        List<ChunkPos> chunksToRemove = new ArrayList<>();

        try (DirectoryStream<Path> regionFilesStream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path regionFile : regionFilesStream) {
                RegionCoordinates coords = parseRegionCoordinates(regionFile.getFileName().toString());
                if (coords == null) {
                    continue;
                }

                regionFiles++;

                try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
                    if (raf.length() < HEADER_BYTES) {
                        continue;
                    }

                    for (int index = 0; index < 1024; index++) {
                        long locationOffset = index * 4L;
                        raf.seek(locationOffset);
                        int locationEntry = raf.readInt();

                        if (locationEntry == 0) {
                            continue;
                        }

                        existingChunks++;

                        int localX = index & 31;
                        int localZ = index >> 5;
                        int chunkX = coords.regionX * 32 + localX;
                        int chunkZ = coords.regionZ * 32 + localZ;

                        if (isProtectedChunk(chunkX, chunkZ, spawnChunk.x, spawnChunk.z, protectedChunkRadius)) {
                            protectedChunks++;
                            continue;
                        }

                        chunksToRemove.add(new ChunkPos(chunkX, chunkZ));
                        removedChunks++;
                    }
                }
            }
        } catch (IOException e) {
            ReGenVerse.LOGGER.error("Failed to reset region files in {}", regionDir, e);
        }

        if (!chunksToRemove.isEmpty()) {
            try {
                List<CompletableFuture<Void>> writes = new ArrayList<>(chunksToRemove.size());
                for (ChunkPos chunkPos : chunksToRemove) {
                    writes.add(overworld.getChunkSource().chunkMap.write(chunkPos, (CompoundTag) null));
                }
                CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
                overworld.getChunkSource().chunkMap.flushWorker();
            } catch (Exception e) {
                ReGenVerse.LOGGER.error("Failed to delete chunk data via chunk storage worker for {} chunk(s).", chunksToRemove.size(), e);
            }
        }

        return new ResetReport(regionFiles, existingChunks, protectedChunks, removedChunks);
    }

    public static boolean hasOverworldChunkEntry(ServerLevel overworld, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        int localX = Math.floorMod(chunkX, 32);
        int localZ = Math.floorMod(chunkZ, 32);
        int index = localX + localZ * 32;

        Path regionDir = overworld.getServer().getWorldPath(LevelResource.ROOT).resolve("region");
        Path regionFile = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.notExists(regionFile)) {
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (raf.length() < HEADER_BYTES) {
                return false;
            }

            raf.seek(index * 4L);
            int locationEntry = raf.readInt();
            return locationEntry != 0;
        } catch (IOException e) {
            ReGenVerse.LOGGER.error("Failed to inspect chunk entry for ({},{}) in {}", chunkX, chunkZ, regionFile, e);
            return false;
        }
    }

    private static boolean isProtectedChunk(int chunkX, int chunkZ, int spawnChunkX, int spawnChunkZ, int protectedChunkRadius) {
        return Math.abs(chunkX - spawnChunkX) <= protectedChunkRadius
            && Math.abs(chunkZ - spawnChunkZ) <= protectedChunkRadius;
    }

    private static RegionCoordinates parseRegionCoordinates(String fileName) {
        Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }

        try {
            int rx = Integer.parseInt(matcher.group(1));
            int rz = Integer.parseInt(matcher.group(2));
            return new RegionCoordinates(rx, rz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ResetReport(int regionFilesScanned, int existingChunks, int protectedChunks, int removedChunks) {
    }

    private record RegionCoordinates(int regionX, int regionZ) {
    }
}
