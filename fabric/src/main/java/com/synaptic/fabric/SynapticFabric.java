package com.synaptic.fabric;

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
import com.synaptic.platform.Platform;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionResult;

/**
 * The Fabric entrypoint: wiring, and nothing else.
 * <p>
 * Every line here either installs the platform, declares a packet, or forwards
 * an event to a method in {@code common}. If a decision about the game is being
 * made in this file, it is in the wrong file — the NeoForge entrypoint has to
 * make the same one, and the two will drift.
 */
public final class SynapticFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // First, before anything reads a config path or sends a packet.
        Platform.set(new FabricPlatform());
        SynapticMod.init();

        registerPayloads();
        registerReceivers();

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            SynapticCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(SynapticMod::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(SynapticMod::serverStopping);
        ServerTickEvents.END_SERVER_TICK.register(SynapticMod::endServerTick);

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) ->
            SynapticMod.afterDamage(entity, source, taken));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SynapticMod::allowSharedSourceDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(SynapticMod::afterDeath);
        // The old player is dropped on the way through: nothing in the shared
        // code needs it, and NeoForge's respawn event does not offer one.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            SynapticMod.afterRespawn(newPlayer, alive));

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            SynapticMod.entityInteract(player, entity);
            // Always PASS: handing a pet over is a side effect of the
            // interaction, never a replacement for it.
            return InteractionResult.PASS;
        });
    }

    /**
     * Both directions are declared on both sides. A payload the server can send
     * but the client has not declared is a disconnect, not a dropped packet.
     */
    private void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatsSyncPayload.TYPE, StatsSyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WipeReportPayload.TYPE, WipeReportPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LobbyStatePayload.TYPE, LobbyStatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CaptureFramePayload.TYPE, CaptureFramePayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(ConfigUpdatePayload.TYPE, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NextRunPayload.TYPE, NextRunPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(LobbyActionPayload.TYPE, LobbyActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FrameReadyPayload.TYPE, FrameReadyPayload.CODEC);
    }

    private void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(FrameReadyPayload.TYPE, (payload, context) ->
            SynapticNetworking.onFrameReady(context.server(), context.player(), payload.slot()));
        ServerPlayNetworking.registerGlobalReceiver(LobbyActionPayload.TYPE, (payload, context) ->
            SynapticNetworking.onLobbyAction(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(NextRunPayload.TYPE, (payload, context) ->
            SynapticNetworking.onNextRun(context.server(), context.player()));
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) ->
            SynapticNetworking.onConfigUpdate(context.server(), context.player(), payload));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            SynapticNetworking.onJoin(handler.player));
    }
}
