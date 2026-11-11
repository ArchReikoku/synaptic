package com.synaptic.neoforge.client;

import com.synaptic.client.SynapticClient;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.ConfigSyncPayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.WipeReportPayload;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The clientbound handlers, in a class that exists only to hold them.
 * <p>
 * A dedicated server does register these payloads — registration is common to
 * both sides — so it loads this class and never calls anything in it. That is
 * safe, and precisely why the class is here. Nothing client-only appears in a
 * signature: the parameters are payload records and a NeoForge interface, all of
 * which a server has. {@link SynapticClient} is named only inside the bodies,
 * and the JVM does not resolve those until a body actually runs — which on a
 * server it never does.
 * <p>
 * That matters because {@code SynapticClient} cannot be loaded on a server at
 * all: it builds a {@code KeyMapping} in its static initialiser, and that is a
 * client class. Inline these five bodies into the registration and
 * {@code SynapticNeoForge} becomes the class that mentions it — which a server
 * loads on the way to registering anything.
 * <p>
 * Every one of them runs on the main client thread — the registrar's default
 * handling thread is MAIN, and none of what they touch is safe off it.
 */
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {
    }

    public static void configSync(ConfigSyncPayload payload, IPayloadContext context) {
        SynapticClient.onConfigSync(payload);
    }

    public static void stats(StatsSyncPayload payload, IPayloadContext context) {
        SynapticClient.onStats(payload);
    }

    public static void wipeReport(WipeReportPayload payload, IPayloadContext context) {
        SynapticClient.onWipeReport(payload);
    }

    public static void lobbyState(LobbyStatePayload payload, IPayloadContext context) {
        SynapticClient.onLobbyState(payload);
    }

    public static void captureFrame(CaptureFramePayload payload, IPayloadContext context) {
        SynapticClient.onCaptureFrame(payload);
    }
}
