package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: "this world has not been set up yet — show the settings".
 * Sent once, to whoever can actually change them.
 */
public record OpenSettingsPayload() implements CustomPacketPayload {
    public static final OpenSettingsPayload INSTANCE = new OpenSettingsPayload();

    public static final CustomPacketPayload.Type<OpenSettingsPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "open_settings"));

    public static final StreamCodec<ByteBuf, OpenSettingsPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<OpenSettingsPayload> type() {
        return TYPE;
    }
}
