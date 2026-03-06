package com.regenverse.network;

import com.regenverse.ReGenVerse;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CycleStatePayload(boolean cycleInProgress) implements CustomPacketPayload {
    public static final Type<CycleStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ReGenVerse.MOD_ID, "cycle_state"));
    public static final StreamCodec<FriendlyByteBuf, CycleStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
        (payload, buf) -> buf.writeBoolean(payload.cycleInProgress()),
        buf -> new CycleStatePayload(buf.readBoolean())
    );

    @Override
    public Type<CycleStatePayload> type() {
        return TYPE;
    }
}
