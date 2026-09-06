package com.synaptic.client;

import com.synaptic.config.SynapticConfig;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.WipeReportPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class SynapticClient implements ClientModInitializer {
    public static final KeyMapping OPEN_SETTINGS = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key.synaptic.settings", GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

    @Override
    public void onInitializeClient() {
        // The server pushes this on join and after every change, so the screen and
        // the client-side features always read the values actually in force.
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.TYPE,
            (payload, context) -> {
                SynapticConfig.apply(payload.bits());
                SynapticConfig.setEditable(payload.editable());
            });

        ClientPlayNetworking.registerGlobalReceiver(StatsSyncPayload.TYPE,
            (payload, context) -> SessionTable.accept(payload));

        ClientPlayNetworking.registerGlobalReceiver(WipeReportPayload.TYPE,
            (payload, context) -> WipeReport.accept(payload));

        ClientPlayNetworking.registerGlobalReceiver(LobbyStatePayload.TYPE,
            (payload, context) -> LobbyClient.accept(payload));

        ClientPlayNetworking.registerGlobalReceiver(CaptureFramePayload.TYPE,
            (payload, context) -> LobbyClient.requestCapture(payload.slot()));

        // Everything the client holds about a world has to go with the world.
        // None of it is per-connection state as far as the JVM is concerned, and
        // carrying it into the next one shows the last world's numbers, the last
        // world's death, and — worst of the three — the last world's loading
        // screen over a world that has finished loading.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LobbyClient.reset();
            SessionTable.clear();
            WipeReport.clear();
            // Host-only controls are hidden again until the next server says
            // otherwise, so its answer is never assumed from the last one.
            SynapticConfig.setEditable(false);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Drives the duck-shoot-restore around each preview capture.
            LobbyClient.clientTick(client);
            while (OPEN_SETTINGS.consumeClick()) {
                // Vanilla only fires keybinds while no screen is open, so reaching
                // here already means the game is the active view.
                if (client.player != null) client.setScreenAndShow(new SynapticSettingsScreen());
            }
        });
    }
}
