package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the settings everyone is playing under, and whether this
 * particular player is allowed to change them.
 * <p>
 * The permission travels with the settings rather than being worked out again on
 * the client. The two ends had drifted once already — the server let the owner of
 * a singleplayer world configure it, while the client greyed the buttons out
 * because it only asked about operator permission — and a rule written twice is a
 * rule that disagrees with itself eventually.
 * <p>
 * Carries the server's {@link SynapticNetworking#PROTOCOL} first, ahead of
 * anything whose shape could change, so that a client of any later version can
 * still read it and refuse a server it cannot understand. Fabric has no loader
 * check of its own; this is it.
 */
public record ConfigSyncPayload(int protocol, int bits, boolean editable) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfigSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "config_sync"));

    public static final StreamCodec<ByteBuf, ConfigSyncPayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, ConfigSyncPayload::protocol,
        ByteBufCodecs.VAR_INT, ConfigSyncPayload::bits,
        ByteBufCodecs.BOOL, ConfigSyncPayload::editable,
        ConfigSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<ConfigSyncPayload> type() {
        return TYPE;
    }
}
