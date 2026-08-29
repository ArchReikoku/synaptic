package com.synaptic.client;

import com.synaptic.config.SynapticConfig;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.OpenSettingsPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
            (payload, context) -> SynapticConfig.apply(payload.bits()));

        // First join in a fresh world: the server asks us to put the settings up,
        // while the inventory toggle is still free to move without cost.
        ClientPlayNetworking.registerGlobalReceiver(OpenSettingsPayload.TYPE, (payload, context) ->
            context.client().setScreenAndShow(new SynapticSettingsScreen(true)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_SETTINGS.consumeClick()) {
                // Vanilla only fires keybinds while no screen is open, so reaching
                // here already means the game is the active view.
                if (client.player != null) client.setScreenAndShow(new SynapticSettingsScreen(false));
            }
        });
    }
}
