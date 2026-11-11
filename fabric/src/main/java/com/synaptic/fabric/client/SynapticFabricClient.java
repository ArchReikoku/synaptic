package com.synaptic.fabric.client;

import com.synaptic.client.SynapticClient;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.WipeReportPayload;
import com.synaptic.platform.ClientPlatform;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The Fabric client entrypoint. Wiring only; see {@link SynapticClient}. */
public final class SynapticFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlatform.set(new FabricClientPlatform());

        // Built in common, registered here — the mapping has to exist before the
        // controls screen is first laid out, which client init is early enough for.
        KeyMappingHelper.registerKeyMapping(SynapticClient.OPEN_SETTINGS);

        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.TYPE,
            (payload, context) -> SynapticClient.onConfigSync(payload));
        ClientPlayNetworking.registerGlobalReceiver(StatsSyncPayload.TYPE,
            (payload, context) -> SynapticClient.onStats(payload));
        ClientPlayNetworking.registerGlobalReceiver(WipeReportPayload.TYPE,
            (payload, context) -> SynapticClient.onWipeReport(payload));
        ClientPlayNetworking.registerGlobalReceiver(LobbyStatePayload.TYPE,
            (payload, context) -> SynapticClient.onLobbyState(payload));
        ClientPlayNetworking.registerGlobalReceiver(CaptureFramePayload.TYPE,
            (payload, context) -> SynapticClient.onCaptureFrame(payload));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SynapticClient.disconnected());
        ClientTickEvents.END_CLIENT_TICK.register(SynapticClient::endClientTick);
    }
}
