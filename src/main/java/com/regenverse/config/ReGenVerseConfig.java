package com.regenverse.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.regenverse.ReGenVerse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReGenVerseConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean enabled = true;
    public long cycleIntervalMinutes = 240;
    public boolean protectSpawnChunks = true;
    public int protectedChunkRadiusOverride = -1;
    public boolean dryRun = true;

    public static ReGenVerseConfig loadOrCreate(Path configPath) {
        try {
            if (Files.notExists(configPath)) {
                Files.createDirectories(configPath.getParent());
                ReGenVerseConfig defaults = new ReGenVerseConfig();
                Files.writeString(configPath, GSON.toJson(defaults));
                ReGenVerse.LOGGER.info("Created default config at {}", configPath);
                return defaults;
            }

            String raw = Files.readString(configPath);
            ReGenVerseConfig loaded = GSON.fromJson(raw, ReGenVerseConfig.class);
            return loaded != null ? loaded : new ReGenVerseConfig();
        } catch (IOException e) {
            ReGenVerse.LOGGER.error("Failed to load config {}. Using defaults.", configPath, e);
            return new ReGenVerseConfig();
        }
    }

    public long cycleIntervalSeconds() {
        return Math.max(1L, cycleIntervalMinutes) * 60L;
    }
}
