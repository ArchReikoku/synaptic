package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: that tile is photographed, move me on.
 * <p>
 * The tour used to run on a stopwatch, which meant guessing how long a world
 * takes to arrive and being wrong about the first one — it was still drawing
 * "Loading terrain" when its picture was taken. Only the client knows when its
 * chunks are actually there, so it says so and the server waits to be told.
 */
public record FrameReadyPayload(int slot) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FrameReadyPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "frame_ready"));

    public static final StreamCodec<ByteBuf, FrameReadyPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, FrameReadyPayload::slot,
        FrameReadyPayload::new);

    @Override
    public CustomPacketPayload.Type<FrameReadyPayload> type() {
        return TYPE;
    }
}
