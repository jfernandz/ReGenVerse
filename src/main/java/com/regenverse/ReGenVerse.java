package com.regenverse;

import com.regenverse.command.ReGenVerseCommands;
import com.regenverse.core.ReGenVerseManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReGenVerse implements ModInitializer {
    public static final String MOD_ID = "regenverse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(ReGenVerseManager::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ReGenVerseManager::onServerTick);
        ReGenVerseCommands.register();
        LOGGER.info("ReGenVerse initialized");
    }
}
