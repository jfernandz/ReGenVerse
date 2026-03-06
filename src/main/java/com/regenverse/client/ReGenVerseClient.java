package com.regenverse.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.regenverse.ReGenVerse;
import com.regenverse.network.CycleStatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import org.lwjgl.glfw.GLFW;

public final class ReGenVerseClient implements ClientModInitializer {
    private static final String KEY_CATEGORY = "key.categories.regenverse";
    private static final String TOGGLE_KEY_ID = "key.regenverse.toggle_chunk_outline";
    private static final long POST_CYCLE_WIREFRAME_DELAY_MS = 4_000L;

    private static KeyMapping toggleChunkOutlineKey;
    private static boolean chunkOutlineEnabled;
    private static boolean cycleInProgress;
    private static long wireframeResumeAtMillis;

    @Override
    public void onInitializeClient() {
        toggleChunkOutlineKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            TOGGLE_KEY_ID,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            KEY_CATEGORY
        ));

        ClientPlayNetworking.registerGlobalReceiver(CycleStatePayload.TYPE, (payload, context) -> onCycleStateUpdate(payload.cycleInProgress()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            cycleInProgress = false;
            wireframeResumeAtMillis = 0L;
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
        WorldRenderEvents.LAST.register(this::onWorldRenderLast);

        ReGenVerse.LOGGER.info("ReGenVerse client initialized");
    }

    private static void onCycleStateUpdate(boolean nextCycleInProgress) {
        if (cycleInProgress && !nextCycleInProgress) {
            wireframeResumeAtMillis = System.currentTimeMillis() + POST_CYCLE_WIREFRAME_DELAY_MS;
        } else if (nextCycleInProgress) {
            wireframeResumeAtMillis = 0L;
        }

        cycleInProgress = nextCycleInProgress;
    }

    private void onEndClientTick(Minecraft client) {
        while (toggleChunkOutlineKey.consumeClick()) {
            chunkOutlineEnabled = !chunkOutlineEnabled;

            if (client.player != null) {
                String state = chunkOutlineEnabled ? "enabled" : "disabled";
                client.player.displayClientMessage(Component.literal("ReGenVerse chunk outline " + state), true);
            }
        }
    }

    private void onWorldRenderLast(WorldRenderContext context) {
        if (!chunkOutlineEnabled || cycleInProgress || System.currentTimeMillis() < wireframeResumeAtMillis) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || context.matrixStack() == null) {
            return;
        }

        ChunkPos targetChunk = new ChunkPos(client.player.blockPosition());

        double camX = context.camera().getPosition().x;
        double camY = context.camera().getPosition().y;
        double camZ = context.camera().getPosition().z;

        double minX = targetChunk.getMinBlockX() - camX;
        double minY = client.level.getMinBuildHeight() - camY;
        double minZ = targetChunk.getMinBlockZ() - camZ;
        double maxX = targetChunk.getMaxBlockX() + 1 - camX;
        double maxY = client.level.getMaxBuildHeight() - camY;
        double maxZ = targetChunk.getMaxBlockZ() + 1 - camZ;

        BlockPos spawn = client.level.getSharedSpawnPos();
        int spawnChunkX = spawn.getX() >> 4;
        int spawnChunkZ = spawn.getZ() >> 4;
        int spawnChunkRadiusRule = Math.max(0, client.level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS));
        int protectedChunkRadius = spawnChunkRadiusRule == 0 ? 0 : spawnChunkRadiusRule - 1;
        boolean protectedChunk = Math.abs(targetChunk.x - spawnChunkX) <= protectedChunkRadius
            && Math.abs(targetChunk.z - spawnChunkZ) <= protectedChunkRadius;

        float r = protectedChunk ? 0.10f : 1.00f;
        float g = protectedChunk ? 0.95f : 0.20f;
        float b = protectedChunk ? 0.25f : 0.20f;

        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        // Chunk prism color indicates protection state.
        LevelRenderer.renderLineBox(context.matrixStack(), lines, minX, minY, minZ, maxX, maxY, maxZ,
            r, g, b, 1.0f);

        // Extra connected loop near player feet for stronger border visibility.
        double markerY = client.player.getY() - camY;
        LevelRenderer.renderLineBox(context.matrixStack(), lines, minX, markerY, minZ, maxX, markerY + 0.01, maxZ,
            r, g, b, 1.0f);

        buffers.endBatch(RenderType.lines());
    }
}
