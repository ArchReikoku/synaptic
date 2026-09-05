package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: what the host just pressed in the lobby.
 * <p>
 * The server checks who is asking on every one of these. A guest's client draws
 * the same grid and reads the same keys, and is simply told no.
 */
public record LobbyActionPayload(int action, int value) implements CustomPacketPayload {
    /** Take the candidate in the given slot and start the run. */
    public static final int PICK = 0;
    /** Throw this batch away and generate another. */
    public static final int RECYCLE = 1;
    /** Change the grid to the given size and generate that many. */
    public static final int RESIZE = 2;

    public static final CustomPacketPayload.Type<LobbyActionPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "lobby_action"));

    public static final StreamCodec<ByteBuf, LobbyActionPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeVarInt(payload.action());
            out.writeVarInt(payload.value());
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            return new LobbyActionPayload(in.readVarInt(), in.readVarInt());
        });

    @Override
    public CustomPacketPayload.Type<LobbyActionPayload> type() {
        return TYPE;
    }
}
