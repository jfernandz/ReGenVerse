package com.regenverse;

import com.regenverse.command.ReGenVerseCommands;
import com.regenverse.core.ReGenVerseManager;
import com.regenverse.network.CycleStatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReGenVerse implements ModInitializer {
    public static final String MOD_ID = "regenverse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(CycleStatePayload.TYPE, CycleStatePayload.STREAM_CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(ReGenVerseManager::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(ReGenVerseManager::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ReGenVerseManager.syncCycleState(handler.getPlayer()));
        ReGenVerseCommands.register();
        LOGGER.info("ReGenVerse initialized");
    }
}
