package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: the settings everyone is playing under. */
public record ConfigSyncPayload(int bits) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfigSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "config_sync"));

    public static final StreamCodec<ByteBuf, ConfigSyncPayload> CODEC =
        ByteBufCodecs.VAR_INT.map(ConfigSyncPayload::new, ConfigSyncPayload::bits);

    @Override
    public CustomPacketPayload.Type<ConfigSyncPayload> type() {
        return TYPE;
    }
}
