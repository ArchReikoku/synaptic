package com.synaptic.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.synaptic.mixin.MinecraftServerAccessor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creating, entering and destroying a dimension while the server runs.
 * <p>
 * A run of Synaptic is a dimension rather than a world, because that is the only
 * shape in which nobody is disconnected between runs. Swapping the world means
 * tearing the server down and every connection with it, and a dedicated server
 * cannot do it at all. Swapping the dimension is a portal trip — a loading
 * screen, and everyone is still connected on the other side.
 * <p>
 * None of this is supported by the game: it only ever builds levels for itself,
 * at startup, from the save's dimension list. Hence the accessor for the pieces
 * it keeps to itself, and ServerLevelSeedMixin for the per-run seed, which the
 * save would otherwise share out to every dimension in it.
 */
public final class RuntimeDimension {
    private static final Logger LOGGER = LoggerFactory.getLogger("synaptic");
    /** Read by ServerLevelSeedMixin during the level constructor. See there. */
    private static volatile Long pending;

    private RuntimeDimension() {
    }

    public static Long pendingSeed() {
        return pending;
    }

    public static ResourceKey<Level> key(String name) {
        return ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("synaptic", name));
    }

    /**
     * Build a level with its own seed and hand it to the server.
     * <p>
     * The overworld's dimension type and generator are reused deliberately: the
     * type is what the client is told to render with, and it already knows that
     * one, so a player without the mod can be sent here like any other
     * dimension. Only the seed is meant to differ.
     */
    public static ServerLevel create(MinecraftServer server, String name, long seed) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        ResourceKey<Level> key = key(name);
        ServerLevel existing = access.synaptic$levels().get(key);
        if (existing != null) return existing;

        LevelStem overworldStem = server.registryAccess()
            .lookupOrThrow(Registries.LEVEL_STEM)
            .getValueOrThrow(LevelStem.OVERWORLD);

        // Derived rather than its own: time, weather and the rest stay the
        // server's business, which is what a run wants anyway.
        DerivedLevelData data = new DerivedLevelData(
            server.getWorldData(), server.getWorldData().overworldData());

        pending = seed;
        ServerLevel level;
        try {
            level = new ServerLevel(
                server,
                access.synaptic$executor(),
                access.synaptic$storageSource(),
                data,
                key,
                overworldStem,
                false,
                seed,
                List.of(),
                false);
        } finally {
            pending = null;
        }
        ((SeededLevel) level).synaptic$setSeed(seed);

        access.synaptic$levels().put(key, level);
        // Mods that track levels hang off this.
        ServerLevelEvents.LOAD.invoker().onLevelLoad(server, level);
        LOGGER.info("created dimension {} with seed {}", key.identifier(), seed);
        return level;
    }

    /**
     * The one spot everybody starts from. Generated on demand, so the first call
     * for a run is the slow one.
     */
    public static BlockPos spawn(ServerLevel level) {
        // Forces the chunk through generation before the height is read; asking
        // an ungenerated column how tall it is answers zero.
        level.getChunk(0, 0);
        return new BlockPos(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0);
    }

    /**
     * Everyone lands on the same block facing the same way — the identical start
     * the runs are meant to have, and what makes one preview frame honest for
     * every player.
     */
    public static void send(ServerPlayer player, ServerLevel level, BlockPos pos, float yaw) {
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
            Set.<Relative>of(), yaw, 0.0F, true);
    }

    /**
     * Close a run and take its files with it.
     * <p>
     * Order matters: anyone still standing in it is moved out first — closing a
     * level with players in it is how a server ends up ticking an entity in a
     * world that no longer exists — then it leaves the level map so nothing new
     * can find it, then it is closed so the region files are flushed and
     * released, and only then deleted.
     */
    public static void destroy(MinecraftServer server, String name, ServerLevel fallback) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        ResourceKey<Level> key = key(name);
        Map<ResourceKey<Level>, ServerLevel> levels = access.synaptic$levels();
        ServerLevel level = levels.get(key);
        if (level == null) return;

        for (ServerPlayer player : List.copyOf(level.players())) {
            send(player, fallback, spawn(fallback), player.getYRot());
        }

        ServerLevelEvents.UNLOAD.invoker().onLevelUnload(server, level);
        levels.remove(key);
        try {
            level.close();
        } catch (IOException e) {
            LOGGER.warn("could not close {}", key.identifier(), e);
        }

        Path path = access.synaptic$storageSource().getDimensionPath(key);
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.delete(entry);
                } catch (IOException e) {
                    LOGGER.warn("could not delete {}", entry, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("could not walk {}", path, e);
        }
        LOGGER.info("destroyed dimension {}", key.identifier());
    }
}
