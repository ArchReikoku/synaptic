package com.synaptic.fabric;

import java.nio.file.Path;
import java.util.List;

import com.synaptic.platform.Platform;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** {@link Platform}, answered by Fabric Loader and the Fabric API. */
public final class FabricPlatform implements Platform {
    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(not installed)");
    }

    @Override
    public List<Row> loaderRows() {
        // Fabric API earns its own row because half of what this mod does goes
        // through it, and "which Fabric API" is the first question about any
        // report that mentions networking or events.
        return List.of(
            new Row("Fabric Loader", modVersion("fabricloader")),
            new Row("Fabric API", modVersion("fabric-api")));
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void levelLoaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
    }

    @Override
    public void levelUnloaded(MinecraftServer server, ServerLevel level) {
        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
    }
}
