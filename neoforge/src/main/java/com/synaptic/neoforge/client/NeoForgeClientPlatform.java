package com.synaptic.neoforge.client;

import com.synaptic.platform.ClientPlatform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** {@link ClientPlatform}, answered by NeoForge's client packet distributor. */
public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
