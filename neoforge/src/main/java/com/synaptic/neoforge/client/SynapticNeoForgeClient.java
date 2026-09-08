package com.synaptic.neoforge.client;

import com.synaptic.client.SynapticClient;
import com.synaptic.neoforge.SynapticNeoForge;
import com.synaptic.platform.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The NeoForge client entrypoint.
 * <p>
 * A second {@code @Mod} class under the same id, marked {@code Dist.CLIENT}, so
 * a dedicated server never constructs it and never loads anything it touches.
 * Wiring only; see {@link SynapticClient}.
 */
@Mod(value = SynapticNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class SynapticNeoForgeClient {
    public SynapticNeoForgeClient(IEventBus modBus, ModContainer container) {
        ClientPlatform.set(new NeoForgeClientPlatform());

        modBus.addListener(SynapticNeoForgeClient::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(SynapticNeoForgeClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(SynapticNeoForgeClient::onLoggingOut);
    }

    /** Built in common, registered here — NeoForge collects mappings by event. */
    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SynapticClient.OPEN_SETTINGS);
    }

    /**
     * The event carries no client, unlike Fabric's, so it is fetched. There is
     * exactly one and this only ever runs on its thread.
     */
    private static void onClientTick(ClientTickEvent.Post event) {
        SynapticClient.endClientTick(Minecraft.getInstance());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SynapticClient.disconnected();
    }
}
