package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: start the next run.
 * <p>
 * Carries nothing. The server picks the seed, owns the run number and decides
 * whether the asker is allowed — a client that could name its own seed could
 * also reroll until it found one it liked, and the run number is the server's to
 * count either way.
 */
public record NextRunPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<NextRunPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "next_run"));

    public static final StreamCodec<ByteBuf, NextRunPayload> CODEC =
        StreamCodec.unit(new NextRunPayload());

    @Override
    public CustomPacketPayload.Type<NextRunPayload> type() {
        return TYPE;
    }
}
