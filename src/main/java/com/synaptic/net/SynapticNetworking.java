package com.synaptic.net;

import com.synaptic.SynapticMod;
import com.synaptic.config.Feature;
import com.synaptic.config.SetupState;
import com.synaptic.config.SynapticConfig;

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
public final class SynapticNetworking {
    private SynapticNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenSettingsPayload.TYPE, OpenSettingsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            // Settings apply to everyone on the server, so they need the same
            // authority as /gamerule. A rejected client is sent the real values
            // back so its screen cannot drift out of step with the server.
            if (!canConfigure(player)) {
                ServerPlayNetworking.send(player, new ConfigSyncPayload(SynapticConfig.bits()));
                player.sendSystemMessage(Component.literal("Only operators can change Synaptic settings.")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            int before = SynapticConfig.bits();
            if (payload.bits() == before) return;
            SynapticConfig.apply(payload.bits());
            SynapticConfig.save();
            // A feature that was off has a stale shared value; re-seed from the
            // players who are actually here rather than snapping them to it.
            SynapticMod.reseed();
            announceInventoryChange(context.server(), before, payload.bits());
            broadcast(context.server());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            ServerPlayNetworking.send(player, new ConfigSyncPayload(SynapticConfig.bits()));
            // First arrival in this world: put the settings in front of whoever
            // can actually change them, while the inventory toggle is still free
            // to move. Marked done on send so nobody gets nagged every join.
            if (!SetupState.isDone(server) && canConfigure(player)) {
                SetupState.markDone(server);
                ServerPlayNetworking.send(player, OpenSettingsPayload.INSTANCE);
            }
        });
    }

    /**
     * Operators, plus whoever opened a singleplayer world — that player owns the
     * world outright, and would otherwise be locked out of their own settings
     * whenever cheats are off.
     */
    public static boolean canConfigure(ServerPlayer player) {
        if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) return true;
        MinecraftServer server = player.level().getServer();
        return server != null && !server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId());
    }

    /** Turning shared inventory back on pools everything; that deserves saying out loud. */
    private static void announceInventoryChange(MinecraftServer server, int before, int after) {
        boolean was = (before & Feature.INVENTORY.bit()) != 0;
        boolean now = (after & Feature.INVENTORY.bit()) != 0;
        if (was == now) return;
        Component message = now
            ? Component.literal("Shared inventory ON — everyone's inventories have been pooled into one. "
                + "Anything past 36 slots did not survive the merge.").withStyle(ChatFormatting.RED)
            : Component.literal("Shared inventory OFF — you each keep what you are carrying now, "
                + "and your inventories will drift apart from here.").withStyle(ChatFormatting.YELLOW);
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static void broadcast(MinecraftServer server) {
        ConfigSyncPayload payload = new ConfigSyncPayload(SynapticConfig.bits());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
