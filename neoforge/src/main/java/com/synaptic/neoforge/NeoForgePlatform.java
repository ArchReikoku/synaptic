package com.synaptic.neoforge;

import java.nio.file.Path;
import java.util.List;

import com.synaptic.platform.Platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** {@link Platform}, answered by FML and NeoForge. */
public final class NeoForgePlatform implements Platform {
    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
            .map(ModContainer::getModInfo)
            .map(info -> info.getVersion().toString())
            .orElse("(not installed)");
    }

    @Override
    public List<Row> loaderRows() {
        // One row, not two. NeoForge is loader and API in the same artifact, so
        // there is no second version to disagree with the first.
        return List.of(new Row("NeoForge", modVersion("neoforge")));
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        // Asked of the connection rather than of the player: this is the same
        // negotiated channel list NeoForge itself checks before sending its own
        // optional payloads, and it answers no for a vanilla client.
        return player.connection.hasChannel(type);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Up to 26.3.0.16-beta NeoForge ticked a cached copy of the level map and
     * needed {@code markWorldsDirty()} before a run added during play was
     * ticked at all. 26.3.0.102 removed both: {@code tickChildren} now loops
     * over {@code getAllLevels()}, the live map, as vanilla and Fabric do, so
     * nothing has to be invalidated any more. If a run stops following
     * {@code /weather} again, look here first.
     */
    @Override
    public void levelLoaded(MinecraftServer server, ServerLevel level) {
        // Posted rather than handed to a listener list, because on NeoForge the
        // event bus IS the listener list — a runtime dimension that skipped this
        // would be invisible to every other mod keeping per-level state.
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    /**
     * {@inheritDoc}
     * <p>
     * The tick loop reads the live map (see {@link #levelLoaded}), so a run
     * leaves it as soon as it is removed from the map.
     */
    @Override
    public void levelUnloaded(MinecraftServer server, ServerLevel level) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
    }
}
