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
 * Server to client: what everyone who has joined has done, online or not.
 * <p>
 * Every player carries two sets of the same figures — one for the run in
 * progress and one for the whole world. Both travel every time rather than the
 * client being told which to care about, because the death screen offers the
 * choice and switching between them should not wait on a packet.
 * <p>
 * Ping is deliberately absent. The client already knows the latency of everyone
 * connected, and an offline player has none to report.
 */
public record StatsSyncPayload(int session, int run, int seconds, int total, List<Row> rows)
    implements CustomPacketPayload {

    /** One span of a player's figures. Damage stays in half-hearts; the tables round. */
    public record Tally(float dealt, float taken, int food, float hunger,
                        int xp, int unlocks, int deaths) {}

    public record Row(UUID id, String name, Tally run, Tally all, boolean online) {}

    public static final CustomPacketPayload.Type<StatsSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "stats_sync"));

    // Written by hand rather than composed: a row has far more fields than the
    // composite helper takes, and the list needs a length in front of it anyway.
    public static final StreamCodec<ByteBuf, StatsSyncPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeVarInt(payload.session());
            out.writeVarInt(payload.run());
            out.writeVarInt(payload.seconds());
            out.writeVarInt(payload.total());
            out.writeVarInt(payload.rows().size());
            for (Row row : payload.rows()) {
                out.writeUUID(row.id());
                out.writeUtf(row.name());
                writeTally(out, row.run());
                writeTally(out, row.all());
                out.writeBoolean(row.online());
            }
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            int session = in.readVarInt();
            int run = in.readVarInt();
            int seconds = in.readVarInt();
            int total = in.readVarInt();
            int count = in.readVarInt();
            List<Row> rows = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                rows.add(new Row(in.readUUID(), in.readUtf(),
                    readTally(in), readTally(in), in.readBoolean()));
            }
            return new StatsSyncPayload(session, run, seconds, total, rows);
        });

    private static void writeTally(FriendlyByteBuf out, Tally tally) {
        out.writeFloat(tally.dealt());
        out.writeFloat(tally.taken());
        out.writeVarInt(tally.food());
        out.writeFloat(tally.hunger());
        out.writeVarInt(tally.xp());
        out.writeVarInt(tally.unlocks());
        out.writeVarInt(tally.deaths());
    }

    private static Tally readTally(FriendlyByteBuf in) {
        return new Tally(in.readFloat(), in.readFloat(), in.readVarInt(), in.readFloat(),
            in.readVarInt(), in.readVarInt(), in.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<StatsSyncPayload> type() {
        return TYPE;
    }
}
