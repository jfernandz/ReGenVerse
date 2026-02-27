package com.regenverse.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.regenverse.ReGenVerse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReGenVerseState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public long epoch = 0;
    public long activeSeed = 0;
    public long nextCycleEpochSeconds = 0;

    public static ReGenVerseState loadOrCreate(Path statePath) {
        try {
            if (Files.notExists(statePath)) {
                Files.createDirectories(statePath.getParent());
                ReGenVerseState defaults = new ReGenVerseState();
                Files.writeString(statePath, GSON.toJson(defaults));
                return defaults;
            }

            String raw = Files.readString(statePath);
            ReGenVerseState loaded = GSON.fromJson(raw, ReGenVerseState.class);
            return loaded != null ? loaded : new ReGenVerseState();
        } catch (IOException e) {
            ReGenVerse.LOGGER.error("Failed to load state {}. Using defaults.", statePath, e);
            return new ReGenVerseState();
        }
    }

    public void save(Path statePath) {
        try {
            Files.createDirectories(statePath.getParent());
            Files.writeString(statePath, GSON.toJson(this));
        } catch (IOException e) {
            ReGenVerse.LOGGER.error("Failed to save state {}", statePath, e);
        }
    }
}
