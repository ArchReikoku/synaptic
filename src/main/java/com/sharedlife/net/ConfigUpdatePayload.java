package com.sharedlife.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: "please apply these settings". Refused unless the sender is an operator. */
public record ConfigUpdatePayload(int bits) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfigUpdatePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("shared_life", "config_update"));

    public static final StreamCodec<ByteBuf, ConfigUpdatePayload> CODEC =
        ByteBufCodecs.VAR_INT.map(ConfigUpdatePayload::new, ConfigUpdatePayload::bits);

    @Override
    public CustomPacketPayload.Type<ConfigUpdatePayload> type() {
        return TYPE;
    }
}
