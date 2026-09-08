package com.synaptic.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: where the seed lobby has got to.
 * <p>
 * Sent to a player whenever their part in it changes, which includes joining
 * halfway through — a late arrival is told the same thing as everyone else and
 * walked through the same candidates, so they end up looking at the same grid.
 *
 * @param state    {@link #CLOSED}, {@link #TOURING} or {@link #GRID}
 * @param slots    how many candidates there are, which is the grid squared
 * @param mayPick  whether this player's keypresses count, ie. whether they host
 */
public record LobbyStatePayload(int state, int grid, int slots, int session, int run, boolean mayPick)
    implements CustomPacketPayload {

    /** No lobby: play on. */
    public static final int CLOSED = 0;
    /** Being walked through the candidates with the screen clear for capturing. */
    public static final int TOURING = 1;
    /** Frames are in; the grid is up and waiting on the host. */
    public static final int GRID = 2;

    public static final CustomPacketPayload.Type<LobbyStatePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "lobby_state"));

    public static final StreamCodec<ByteBuf, LobbyStatePayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeVarInt(payload.state());
            out.writeVarInt(payload.grid());
            out.writeVarInt(payload.slots());
            out.writeVarInt(payload.session());
            out.writeVarInt(payload.run());
            out.writeBoolean(payload.mayPick());
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            return new LobbyStatePayload(in.readVarInt(), in.readVarInt(), in.readVarInt(),
                in.readVarInt(), in.readVarInt(), in.readBoolean());
        });

    @Override
    public CustomPacketPayload.Type<LobbyStatePayload> type() {
        return TYPE;
    }
}
