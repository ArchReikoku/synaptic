package com.synaptic.net;

import com.synaptic.SynapticMod;
import com.synaptic.config.Feature;
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
        PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            // Settings apply to everyone, so the client is never trusted on this.
            // A rejected client is sent the real values back so its screen cannot
            // drift out of step with the server.
            if (!canConfigure(player)) {
                ServerPlayNetworking.send(player, syncFor(player));
                player.sendSystemMessage(Component.literal("Only the host can change Synaptic settings.")
                    .withStyle(ChatFormatting.RED));
                return;
            }
            applyChange(context.server(), payload.bits());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            ServerPlayNetworking.send(player, syncFor(player));
            // Everyone gets the hint, not just whoever can change things: the
            // screen is worth opening to read what the world is running under.
            player.sendSystemMessage(Component.literal("Press ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal("\"K\"").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" to configure ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Synaptic").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" - Shared Life").withStyle(ChatFormatting.AQUA)));
        });
    }

    /**
     * The one way settings change, whether they came from the screen or from
     * /synaptic. Saves, re-seeds, announces, and pushes the result to every
     * client — leaving any of that to the caller is how the two paths drift.
     */
    public static void applyChange(MinecraftServer server, int bits) {
        int before = SynapticConfig.bits();
        if (bits == before) return;
        SynapticConfig.apply(bits);
        SynapticConfig.save();
        // A feature that was off has a stale shared value; re-seed from the
        // players who are actually here rather than snapping them to it.
        SynapticMod.reseed();
        announceInventoryChange(server, before, bits);
        broadcast(server);
    }

    /**
     * Who may change the settings.
     * <p>
     * On a world someone opened themselves — singleplayer, or singleplayer opened
     * to LAN — that is the host and nobody else. Not operator permission: cheats
     * are off by default, which would lock a player out of their own world, and
     * opening to LAN with cheats on hands everyone who joins the same authority
     * the host has. The host owns the run either way.
     * <p>
     * A dedicated server has no host sitting at it, so there it is operators, the
     * same bar as /gamerule.
     */
    public static boolean canConfigure(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return false;
        if (!server.isDedicatedServer()) return server.isSingleplayerOwner(player.nameAndId());
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
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

    /** Built per player: the settings are the same for everyone, the permission is not. */
    private static ConfigSyncPayload syncFor(ServerPlayer player) {
        return new ConfigSyncPayload(SynapticConfig.bits(), canConfigure(player));
    }

    private static void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, syncFor(player));
        }
    }
}
