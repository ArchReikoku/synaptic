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
 * Server to client: who ended the run, how, and the damage that led to it.
 * <p>
 * Sent on the death that starts a cascade rather than on every death in it. The
 * rest of the group dying is the rule working; what the death screen has to say
 * is whose death it was.
 * <p>
 * The cause travels as the finished sentence rather than as a damage type, so
 * every screen shows the same words the chat did — "fell from a high place",
 * "was slain by Zombie" — instead of each client inventing its own phrasing.
 * <p>
 * The blows travel as figures rather than as sentences, and for a reason: this
 * report is the death screen's only source now, and it has to survive being
 * written to a file and read back after a restart. Numbers do that; a rendered
 * line with colours in it does not. The wording and the colouring are the
 * client's, applied identically to every row.
 *
 * @param present whether there is a death to report at all. A run that has just
 *                started has none, and saying so explicitly is what clears the
 *                last run's report off everybody's screen.
 */
public record WipeReportPayload(boolean present, UUID victim, String name, String cause,
                                List<Line> lines) implements CustomPacketPayload {

    /** One blow: who took it, how much, what caused it, and what was left after. */
    public record Line(String name, float damage, String cause, float left) {}

    /** The sentinel for "nothing to report", so the client can clear what it holds. */
    public static WipeReportPayload none() {
        return new WipeReportPayload(false, new UUID(0L, 0L), "", "", List.of());
    }

    public static final CustomPacketPayload.Type<WipeReportPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "wipe_report"));

    public static final StreamCodec<ByteBuf, WipeReportPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeBoolean(payload.present());
            out.writeUUID(payload.victim());
            out.writeUtf(payload.name());
            out.writeUtf(payload.cause());
            out.writeVarInt(payload.lines().size());
            for (Line line : payload.lines()) {
                out.writeUtf(line.name());
                out.writeFloat(line.damage());
                out.writeUtf(line.cause());
                out.writeFloat(line.left());
            }
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            boolean present = in.readBoolean();
            UUID victim = in.readUUID();
            String name = in.readUtf();
            String cause = in.readUtf();
            int count = in.readVarInt();
            List<Line> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(new Line(in.readUtf(), in.readFloat(), in.readUtf(), in.readFloat()));
            }
            return new WipeReportPayload(present, victim, name, cause, List.copyOf(lines));
        });

    @Override
    public CustomPacketPayload.Type<WipeReportPayload> type() {
        return TYPE;
    }
}
