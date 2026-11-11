package com.synaptic.fabric.client;

import com.synaptic.platform.ClientPlatform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** {@link ClientPlatform}, answered by the Fabric API's client networking. */
public final class FabricClientPlatform implements ClientPlatform {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
