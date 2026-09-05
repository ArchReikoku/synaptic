package com.synaptic.net;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the session totals for everyone who has joined, online or
 * not.
 * <p>
 * Ping is deliberately absent. The client already knows the latency of everyone
 * currently connected, and an offline player has none to report, so sending it
 * would be a number going stale in transit for no reason.
 */
public record StatsSyncPayload(int session, List<Row> rows) implements CustomPacketPayload {
    /** Damage and hunger stay as they were measured; the tab list rounds for display. */
    public record Row(UUID id, String name, float dealt, float taken,
                      int food, float hunger, int xp, int deaths, boolean online) {}

    public static final CustomPacketPayload.Type<StatsSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "stats_sync"));

    // Written by hand rather than composed: a row has more fields than the
    // composite helper takes, and the list needs a length in front of it either
    // way.
    public static final StreamCodec<ByteBuf, StatsSyncPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeVarInt(payload.session());
            out.writeVarInt(payload.rows().size());
            for (Row row : payload.rows()) {
                out.writeUUID(row.id());
                out.writeUtf(row.name());
                out.writeFloat(row.dealt());
                out.writeFloat(row.taken());
                out.writeVarInt(row.food());
                out.writeFloat(row.hunger());
                out.writeVarInt(row.xp());
                out.writeVarInt(row.deaths());
                out.writeBoolean(row.online());
            }
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            int session = in.readVarInt();
            int count = in.readVarInt();
            List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(new Row(in.readUUID(), in.readUtf(), in.readFloat(), in.readFloat(),
                    in.readVarInt(), in.readFloat(), in.readVarInt(), in.readVarInt(),
                    in.readBoolean()));
            }
            return new StatsSyncPayload(session, rows);
        });

    @Override
    public CustomPacketPayload.Type<StatsSyncPayload> type() {
        return TYPE;
    }
}
