package com.synaptic.neoforge;

import com.synaptic.SynapticMod;
import com.synaptic.command.SynapticCommand;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.ConfigUpdatePayload;
import com.synaptic.net.FrameReadyPayload;
import com.synaptic.net.LobbyActionPayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.NextRunPayload;
import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.SynapticNetworking;
import com.synaptic.net.WipeReportPayload;
import com.synaptic.neoforge.client.ClientPayloadHandlers;
import com.synaptic.platform.Platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The NeoForge entrypoint: wiring, and nothing else.
 * <p>
 * Every line here either installs the platform, declares a packet, or forwards
 * an event to a method in {@code common}. If a decision about the game is being
 * made in this file, it is in the wrong file — the Fabric entrypoint has to make
 * the same one, and the two will drift.
 */
@Mod(SynapticNeoForge.MOD_ID)
public final class SynapticNeoForge {
    public static final String MOD_ID = "synaptic";

    /**
     * The protocol version, bumped when a payload's shape changes.
     * <p>
     * Between two modded ends NeoForge compares this and refuses a connection
     * that disagrees, which is a better failure than a garbled packet. It is the
     * same promise the CLAUDE.md note about the {@code Feature} enum makes — the
     * wire format is not allowed to drift silently — enforced here by the loader
     * rather than by remembering.
     */
    private static final String PROTOCOL = "1";

    public SynapticNeoForge(IEventBus modBus, ModContainer container) {
        // First, before anything reads a config path or sends a packet.
        Platform.set(new NeoForgePlatform());
        SynapticMod.init();

        modBus.addListener(SynapticNeoForge::registerPayloads);

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(SynapticNeoForge::onRegisterCommands);
        gameBus.addListener(SynapticNeoForge::onServerStarted);
        gameBus.addListener(SynapticNeoForge::onServerStopping);
        gameBus.addListener(SynapticNeoForge::onServerTick);
        gameBus.addListener(SynapticNeoForge::onIncomingDamage);
        gameBus.addListener(SynapticNeoForge::onDamaged);
        gameBus.addListener(SynapticNeoForge::onDeath);
        gameBus.addListener(SynapticNeoForge::onRespawn);
        gameBus.addListener(SynapticNeoForge::onEntityInteract);
        gameBus.addListener(SynapticNeoForge::onLoggedIn);
    }

    /**
     * Both directions in one place, because NeoForge registers them together.
     * <p>
     * The clientbound handlers are method references into a class that exists
     * only to hold them, and that is not tidiness. A dedicated server runs this
     * method, so whatever it names, it loads. {@link ClientPayloadHandlers} is
     * safe to load — no client type appears in any of its signatures — while
     * {@code SynapticClient}, which its bodies reach, is not: that one builds a
     * KeyMapping in its static initialiser. Folding those bodies in here would
     * put a client class in this class's constant pool and take the server down
     * with it.
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional(), and it is not a detail. Without it NeoForge treats every
        // one of these channels as required and refuses the connection of any
        // client that has not got them — which is every vanilla client. This mod
        // is deliberately playable without the client half installed: the tab
        // table, the world picker and the Next run button simply do not appear,
        // and SynapticNetworking.checkClients tells that player so. Fabric's play
        // channels are optional by nature, so this is what keeps the two jars
        // agreeing about who is allowed to join.
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();

        registrar.playToClient(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC,
            ClientPayloadHandlers::configSync);
        registrar.playToClient(StatsSyncPayload.TYPE, StatsSyncPayload.CODEC,
            ClientPayloadHandlers::stats);
        registrar.playToClient(WipeReportPayload.TYPE, WipeReportPayload.CODEC,
            ClientPayloadHandlers::wipeReport);
        registrar.playToClient(LobbyStatePayload.TYPE, LobbyStatePayload.CODEC,
            ClientPayloadHandlers::lobbyState);
        registrar.playToClient(CaptureFramePayload.TYPE, CaptureFramePayload.CODEC,
            ClientPayloadHandlers::captureFrame);

        // context.player() is the sending player, and on a serverbound payload
        // that is a ServerPlayer. The cast is the loader's own contract; if it
        // ever fails, something has gone wrong far below this mod.
        registrar.playToServer(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC,
            (payload, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                SynapticNetworking.onConfigUpdate(player.level().getServer(), player, payload);
            });
        registrar.playToServer(NextRunPayload.TYPE, NextRunPayload.CODEC,
            (payload, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                SynapticNetworking.onNextRun(player.level().getServer(), player);
            });
        registrar.playToServer(LobbyActionPayload.TYPE, LobbyActionPayload.CODEC,
            (payload, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                SynapticNetworking.onLobbyAction(player.level().getServer(), player, payload);
            });
        registrar.playToServer(FrameReadyPayload.TYPE, FrameReadyPayload.CODEC,
            (payload, context) -> {
                ServerPlayer player = (ServerPlayer) context.player();
                SynapticNetworking.onFrameReady(player.level().getServer(), player, payload.slot());
            });
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        SynapticCommand.register(event.getDispatcher());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        SynapticMod.serverStarted(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        SynapticMod.serverStopping(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        SynapticMod.endServerTick(event.getServer());
    }

    /**
     * The shared-cause check, which answers "should this land at all".
     * <p>
     * Fabric's ALLOW_DAMAGE returns false to stop the damage; here the same
     * answer is a cancel. This event is the last point at which the hit can be
     * called off, which is where that decision has to be made — by the time the
     * Post event runs, the health is already gone.
     */
    private static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!SynapticMod.allowSharedSourceDamage(event.getEntity(), event.getSource(), event.getAmount())) {
            event.setCanceled(true);
        }
    }

    /**
     * A blow that has landed.
     * <p>
     * getInflictedDamage() rather than getHealthDamage(): the figure this feeds
     * is what the blow did to the player, and absorption is shared too, so a hit
     * soaked by a golden apple still happened to everybody. getHealthDamage()
     * would report it as nothing.
     */
    private static void onDamaged(LivingDamageEvent.Post event) {
        SynapticMod.afterDamage(event.getEntity(), event.getSource(), event.getInflictedDamage());
    }

    /**
     * NOTE ON TIMING, and the one real behavioural difference between the two
     * jars: Fabric's AFTER_DEATH fires once {@code die()} has finished, whereas
     * NeoForge's LivingDeathEvent fires at the top of it. So on NeoForge the
     * group death cascades from inside the first player's own death rather than
     * after it. The re-entrancy guard the cascade already carries is what makes
     * that survivable — every death raised inside a cascade is skipped — and the
     * first player's {@code die()} carries on and completes normally once the
     * cascade returns.
     * <p>
     * Never cancelled. This mod has an opinion about what a death means, not
     * about whether it happened.
     */
    private static void onDeath(LivingDeathEvent event) {
        SynapticMod.afterDeath(event.getEntity(), event.getSource());
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // isEndConquered is NeoForge's name for Fabric's `alive`: the player was
        // never dead, they walked back out of the End. Nobody else is sitting on
        // a death screen waiting for that.
        SynapticMod.afterRespawn(player, event.isEndConquered());
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        SynapticMod.entityInteract(event.getEntity(), event.getTarget());
    }

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // Fires on the client too, for the local player. Only the server owes
        // anybody a settings sync.
        if (event.getEntity() instanceof ServerPlayer player) {
            SynapticNetworking.onJoin(player);
        }
    }
}
