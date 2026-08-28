package com.sharedlife.net;

import com.sharedlife.SharedLifeMod;
import com.sharedlife.config.SharedLifeConfig;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Keeps every client's copy of the settings in step with the server's, which is
 * the only authoritative one. Clients may ask for a change; the server decides.
 */
public final class SharedLifeNetworking {
    private SharedLifeNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            // Settings apply to everyone on the server, so they need the same
            // authority as /gamerule. A rejected client is sent the real values
            // back so its screen cannot drift out of step with the server.
            if (!player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                ServerPlayNetworking.send(player, new ConfigSyncPayload(SharedLifeConfig.bits()));
                player.sendSystemMessage(Component.literal("Only operators can change Shared Life settings.")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            if (payload.bits() == SharedLifeConfig.bits()) return;
            SharedLifeConfig.apply(payload.bits());
            SharedLifeConfig.save();
            // A feature that was off has a stale shared value; re-seed from the
            // players who are actually here rather than snapping them to it.
            SharedLifeMod.reseed();
            broadcast(context.server());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            ServerPlayNetworking.send(handler.player, new ConfigSyncPayload(SharedLifeConfig.bits())));
    }

    private static void broadcast(MinecraftServer server) {
        ConfigSyncPayload payload = new ConfigSyncPayload(SharedLifeConfig.bits());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
