package com.synaptic.client;

import com.synaptic.config.SynapticConfig;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.WipeReportPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Everything the client does that is not a screen: what to do with each payload
 * the server sends, what to forget when the connection drops, and the one
 * keybind.
 * <p>
 * None of it registers itself. Each loader's client entrypoint hands the payload
 * types, the tick and the disconnect to these methods, because that is the part
 * the two of them have no call in common for. What happens once a payload has
 * arrived is the same on both, so it is written once, here.
 */
public final class SynapticClient {
    /**
     * Constructed here, registered by the loader.
     * <p>
     * Fabric wants the mapping handed to a helper during client init; NeoForge
     * wants it handed to an event later on. Both want the same object, and
     * building it at class-init means whichever of them asks, asks for one that
     * already exists.
     */
    public static final KeyMapping OPEN_SETTINGS =
        new KeyMapping("key.synaptic.settings", GLFW.GLFW_KEY_K, KeyMapping.Category.MISC);

    private SynapticClient() {
    }

    /**
     * The server pushes this on join and after every change, so the screen and
     * the client-side features always read the values actually in force.
     */
    public static void onConfigSync(ConfigSyncPayload payload) {
        SynapticConfig.apply(payload.bits());
        SynapticConfig.setEditable(payload.editable());
    }

    public static void onStats(StatsSyncPayload payload) {
        SessionTable.accept(payload);
    }

    public static void onWipeReport(WipeReportPayload payload) {
        WipeReport.accept(payload);
    }

    public static void onLobbyState(LobbyStatePayload payload) {
        LobbyClient.accept(payload);
    }

    public static void onCaptureFrame(CaptureFramePayload payload) {
        LobbyClient.requestCapture(payload.slot());
    }

    /**
     * Everything the client holds about a world has to go with the world.
     * <p>
     * None of it is per-connection state as far as the JVM is concerned, and
     * carrying it into the next one shows the last world's numbers, the last
     * world's death, and — worst of the three — the last world's loading screen
     * over a world that has finished loading.
     */
    public static void disconnected() {
        LobbyClient.reset();
        SessionTable.clear();
        WipeReport.clear();
        // Host-only controls are hidden again until the next server says
        // otherwise, so its answer is never assumed from the last one.
        SynapticConfig.setEditable(false);
    }

    public static void endClientTick(Minecraft client) {
        // Drives the duck-shoot-restore around each preview capture.
        LobbyClient.clientTick(client);
        while (OPEN_SETTINGS.consumeClick()) {
            // Vanilla only fires keybinds while no screen is open, so reaching
            // here already means the game is the active view.
            if (client.player != null) client.setScreenAndShow(new SynapticSettingsScreen());
        }
    }
}
