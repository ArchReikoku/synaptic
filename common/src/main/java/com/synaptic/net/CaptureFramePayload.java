package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: photograph what you are looking at, and file it as this
 * tile.
 * <p>
 * The server decides when because only the server knows the player has been
 * standing in that candidate long enough for its terrain to have arrived.
 */
public record CaptureFramePayload(int slot) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CaptureFramePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "capture_frame"));

    public static final StreamCodec<ByteBuf, CaptureFramePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, CaptureFramePayload::slot,
        CaptureFramePayload::new);

    @Override
    public CustomPacketPayload.Type<CaptureFramePayload> type() {
        return TYPE;
    }
}
