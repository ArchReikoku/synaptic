package com.synaptic.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The client half of {@link Platform}, kept apart from it for one reason: a
 * dedicated server loads {@code Platform} and must never load an implementation
 * that mentions {@code Minecraft} or a client-side networking class. Folding
 * {@code sendToServer} into the other interface would put exactly that on the
 * server's class path, and it would not fail until somebody ran a server.
 * <p>
 * Installed by each loader's client entrypoint, and never installed at all on a
 * server — which is safe, because everything that calls it is a screen or a
 * client tick.
 */
public interface ClientPlatform {
    /** Send a payload to the server this client is connected to. */
    void sendToServer(CustomPacketPayload payload);

    static ClientPlatform get() {
        ClientPlatform platform = Holder.instance;
        if (platform == null) {
            throw new IllegalStateException(
                "Synaptic's client platform was never installed — the loader's client "
                    + "entrypoint must call ClientPlatform.set() before anything else runs.");
        }
        return platform;
    }

    /** Called once, first thing, by each loader's client entrypoint. */
    static void set(ClientPlatform platform) {
        Holder.instance = platform;
    }

    /** Convenience for the call sites, all of which are one-liners. */
    static void send(CustomPacketPayload payload) {
        get().sendToServer(payload);
    }

    /** Interfaces cannot hold mutable state; this is the field that would have been one. */
    final class Holder {
        private static ClientPlatform instance;

        private Holder() {
        }
    }
}
