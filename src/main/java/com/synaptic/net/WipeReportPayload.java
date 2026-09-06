package com.synaptic.net;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: who ended the run, and how.
 * <p>
 * Sent on the death that starts a cascade rather than on every death in it. The
 * rest of the group dying is the rule working; what the death screen has to say
 * is whose death it was.
 * <p>
 * The cause travels as the finished sentence rather than as a damage type, so
 * every screen shows the same words the chat did — "fell from a high place",
 * "was slain by Zombie" — instead of each client inventing its own phrasing.
 */
public record WipeReportPayload(UUID victim, String name, String cause) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WipeReportPayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("synaptic", "wipe_report"));

    public static final StreamCodec<ByteBuf, WipeReportPayload> CODEC = StreamCodec.of(
        (buffer, payload) -> {
            FriendlyByteBuf out = new FriendlyByteBuf(buffer);
            out.writeUUID(payload.victim());
            out.writeUtf(payload.name());
            out.writeUtf(payload.cause());
        },
        buffer -> {
            FriendlyByteBuf in = new FriendlyByteBuf(buffer);
            return new WipeReportPayload(in.readUUID(), in.readUtf(), in.readUtf());
        });

    @Override
    public CustomPacketPayload.Type<WipeReportPayload> type() {
        return TYPE;
    }
}
