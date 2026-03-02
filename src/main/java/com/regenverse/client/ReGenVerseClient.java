package com.regenverse.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.regenverse.ReGenVerse;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.glfw.GLFW;

public final class ReGenVerseClient implements ClientModInitializer {
    private static final String KEY_CATEGORY = "key.categories.regenverse";
    private static final String TOGGLE_KEY_ID = "key.regenverse.toggle_chunk_outline";

    private static KeyMapping toggleChunkOutlineKey;
    private static boolean chunkOutlineEnabled;

    @Override
    public void onInitializeClient() {
        toggleChunkOutlineKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            TOGGLE_KEY_ID,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
        WorldRenderEvents.LAST.register(this::onWorldRenderLast);

        ReGenVerse.LOGGER.info("ReGenVerse client initialized");
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
        if (!chunkOutlineEnabled) {
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

        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        // High-contrast orange chunk prism.
        LevelRenderer.renderLineBox(context.matrixStack(), lines, minX, minY, minZ, maxX, maxY, maxZ,
            1.0f, 0.45f, 0.0f, 1.0f);

        // Extra connected loop near player feet so chunk borders are visible at ground level.
        double markerY = client.player.getY() - camY;
        LevelRenderer.renderLineBox(context.matrixStack(), lines, minX, markerY, minZ, maxX, markerY + 0.01, maxZ,
            1.0f, 0.85f, 0.2f, 1.0f);

        buffers.endBatch(RenderType.lines());
    }
}
