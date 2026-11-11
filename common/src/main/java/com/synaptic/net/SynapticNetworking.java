package com.synaptic.net;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.synaptic.SynapticMod;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.platform.Platform;
import com.synaptic.stats.SessionStats;
import com.synaptic.world.LobbyManager;
import com.synaptic.world.RunManager;

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
    /**
     * The wire format's version. <b>Bump it whenever a payload's shape changes,
     * the {@code Feature} enum included</b> — settings travel as a bitmask of its
     * ordinals.
     * <p>
     * Checked twice: NeoForge compares it in the loader's own handshake and
     * refuses the connection, and on both loaders the client compares it against
     * the one sent in {@link ConfigSyncPayload} and disconnects itself. Kept here
     * rather than in either loader so the two jars cannot carry different numbers.
     */
    public static final int PROTOCOL = 2;

    /** Seconds each player has been connected, for the unmodded check below. */
    private static final Map<UUID, Integer> connectedFor = new HashMap<>();
    private static final Set<UUID> warnedUnmodded = new HashSet<>();

    private SynapticNetworking() {
    }

    // The handlers below are handlers, not registrations. Declaring the payload
    // types and hanging a receiver off each one is the one part of networking
    // the two loaders have no call in common for, so it lives in each loader's
    // own module and ends up here — which keeps the decisions about who may do
    // what in one file rather than two.
    //
    // Every one of them runs on the server thread. Both loaders are asked to
    // hand payloads to the main thread, and none of this is safe anywhere else.

    /**
     * A client reporting that its tour of one world has been drawn.
     * <p>
     * Not permission checked: this is a client reporting on its own tour, and
     * the worst a forged one can do is give that player a dark tile.
     */
    public static void onFrameReady(MinecraftServer server, ServerPlayer player, int slot) {
        LobbyManager.acknowledge(server, player, slot);
    }

    public static void onLobbyAction(MinecraftServer server, ServerPlayer player, LobbyActionPayload payload) {
        if (!canConfigure(player)) {
            player.sendSystemMessage(Component.literal("Only the host can choose the world.")
                .withStyle(ChatFormatting.RED));
            return;
        }
        switch (payload.action()) {
            case LobbyActionPayload.PICK -> LobbyManager.pick(server, payload.value());
            case LobbyActionPayload.RECYCLE -> LobbyManager.recycle(server);
            case LobbyActionPayload.RESIZE -> LobbyManager.resize(server, payload.value());
            case LobbyActionPayload.CANCEL -> LobbyManager.cancel(server);
            default -> { }
        }
    }

    public static void onNextRun(MinecraftServer server, ServerPlayer player) {
        // Same bar as the settings: a run reset throws away everyone's world,
        // so it is the host's call and nobody else's.
        if (!canConfigure(player)) {
            player.sendSystemMessage(Component.literal("Only the host can start the next run.")
                .withStyle(ChatFormatting.RED));
            return;
        }
        // The button is hidden while the feature is off, but the server is the
        // one that has to say no: a request that arrives anyway would open a
        // picker the setting says should not exist.
        if (!SynapticConfig.enabled(Feature.RUN_RESET)) {
            player.sendSystemMessage(Component.literal("Next Run is switched off in the Synaptic settings.")
                .withStyle(ChatFormatting.RED));
            return;
        }
        // The button opens the picker rather than dropping straight into a
        // random world: choosing the next one is the point of a next run.
        LobbyManager.open(server, LobbyManager.grid());
    }

    public static void onConfigUpdate(MinecraftServer server, ServerPlayer player, ConfigUpdatePayload payload) {
        // Settings apply to everyone, so the client is never trusted on this.
        // A rejected client is sent the real values back so its screen cannot
        // drift out of step with the server.
        if (!canConfigure(player)) {
            Platform.get().send(player, syncFor(player));
            player.sendSystemMessage(Component.literal("Only the host can change Synaptic settings.")
                .withStyle(ChatFormatting.RED));
            return;
        }
        applyChange(server, payload.bits());
    }

    /** Everything a player is owed the moment they finish connecting. */
    public static void onJoin(ServerPlayer player) {
        Platform.get().send(player, syncFor(player));
        // Before anything else it might show: a client that left during a
        // lobby still has the loading screen up and will keep it there until
        // something says otherwise.
        LobbyManager.greet(player);
        // The death screen's whole content, restated for a client that has
        // just arrived with nothing in it. A player who leaves a dead run
        // and comes back is looking at the same dead run.
        if (Platform.get().canSend(player, WipeReportPayload.TYPE)) {
            Platform.get().send(player, SessionStats.wipe());
        }
        // Everyone gets the hint, not just whoever can change things: the
        // screen is worth opening to read what the world is running under.
        player.sendSystemMessage(Component.literal("Press ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("\"K\"").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(Component.literal(" to configure ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("Synaptic").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
            .append(Component.literal(" - Shared Life").withStyle(ChatFormatting.AQUA)));
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

    /**
     * Tell anyone playing without the mod what they are missing.
     * <p>
     * The tab table, the world picker and the death screen button are all drawn
     * client side, so on a vanilla client they simply are not there — and
     * nothing says why. Worth one line each, once.
     * <p>
     * Not asked at join: the channels a client can receive are not known that
     * early, and everybody would be told they were unmodded. A few seconds of
     * being connected settles it.
     */
    public static void checkClients(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> here = new HashSet<>();
        for (ServerPlayer player : players) here.add(player.getUUID());
        connectedFor.keySet().retainAll(here);
        warnedUnmodded.retainAll(here);

        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            int seconds = connectedFor.merge(id, 1, Integer::sum);
            if (seconds != 5 || warnedUnmodded.contains(id)) continue;
            if (Platform.get().canSend(player, ConfigSyncPayload.TYPE)) continue;
            warnedUnmodded.add(id);
            player.sendSystemMessage(Component.literal("Synaptic is not installed on your client. ")
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("The session tab, the world picker and the Next run "
                    + "button will not appear until it is.").withStyle(ChatFormatting.GRAY)));
        }
    }

    /**
     * Tell everyone whose death ended the run, in the words the game itself
     * would have used for it.
     */
    /**
     * Send the run's obituary to everyone who can render it.
     * <p>
     * Takes the finished report rather than a death, because it is assembled at
     * the end of the tick once the blow that killed is in the log — and because
     * the same call publishes "there is nothing to report" at the start of a new
     * run, which is what clears the last one off every screen.
     */
    public static void broadcastWipe(MinecraftServer server, WipeReportPayload report) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Platform.get().canSend(player, WipeReportPayload.TYPE)) {
                Platform.get().send(player, report);
            }
        }
    }

    /** The session table, to everyone at once — it is the same table for all of them. */
    public static void broadcastStats(MinecraftServer server) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        StatsSyncPayload payload = SessionStats.snapshot(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Platform.get().send(player, payload);
        }
    }

    /** Built per player: the settings are the same for everyone, the permission is not. */
    private static ConfigSyncPayload syncFor(ServerPlayer player) {
        return new ConfigSyncPayload(PROTOCOL, SynapticConfig.bits(), canConfigure(player));
    }

    private static void broadcast(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Platform.get().send(player, syncFor(player));
        }
    }
}
