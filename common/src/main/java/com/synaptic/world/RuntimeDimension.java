package com.synaptic.world;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.synaptic.mixin.MinecraftServerAccessor;
import com.synaptic.platform.Platform;
import com.synaptic.report.SynapticLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.DerivedLevelData;

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
    private static final SynapticLog LOGGER = SynapticLog.get();
    /**
     * Rings of chunks around the origin that are always tried in full,
     * regardless of what the noise says. A small island or a riverbank can fall
     * between the noise samples, and these chunks get generated anyway the
     * moment anybody arrives.
     */
    private static final int SPAWN_NEAR_RINGS = 1;
    /**
     * How far out to look for dry land, in chunks. The noise check is cheap, so
     * this can be wide. 64 chunks is a thousand blocks, past the far shore of
     * all but the biggest oceans.
     */
    private static final int SPAWN_SEARCH_CHUNKS = 64;
    /**
     * At most this many chunks are generated in full to confirm what the noise
     * suggested. Generating is the expensive part, and the noise is rarely wrong
     * by more than a few chunks.
     */
    private static final int SPAWN_GENERATE_LIMIT = 16;
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
        return create(server, name, seed, LevelStem.OVERWORLD);
    }

    /**
     * Build a brand-new level, clearing out anything left on disk under its name
     * first.
     * <p>
     * {@link #create} opens whatever folder the name points at. That is right
     * when rebuilding a run after a restart, and wrong for a new world. Chunks
     * left there by an earlier world of the same name are loaded as they are,
     * and only the chunks around them are generated from the new seed. The
     * result is a patch of one world set into another, with sheer walls along
     * the chunk borders where the two meet. That happened whenever a name was
     * handed out a second time, for example a world prepared before a restart.
     */
    public static ServerLevel createFresh(MinecraftServer server, String name, long seed) {
        return createFresh(server, name, seed, LevelStem.OVERWORLD);
    }

    public static ServerLevel createFresh(MinecraftServer server, String name, long seed,
                                          ResourceKey<LevelStem> flavour) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        ResourceKey<Level> key = key(name);
        // A live level under this name is never wiped: its files are in use, and
        // create hands it back unchanged anyway. Callers check for this first.
        if (!access.synaptic$levels().containsKey(key)) {
            Path path = access.synaptic$storageSource().getDimensionPath(key);
            if (Files.exists(path)) {
                LOGGER.warn("{} already has files on disk from an earlier world, clearing them",
                    key.identifier());
                deleteFiles(path);
            }
        }
        return create(server, name, seed, flavour);
    }

    /**
     * Build a level of the given flavour with its own seed.
     * <p>
     * The stem is the game's own recipe for a dimension — its type and its
     * generator — so asking for the nether's gives a real nether and the end's a
     * real end. Only the seed differs from the save's own, which is what makes
     * these a run's rather than a copy of it.
     */
    public static ServerLevel create(MinecraftServer server, String name, long seed,
                                     ResourceKey<LevelStem> flavour) {
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        ResourceKey<Level> key = key(name);
        ServerLevel existing = access.synaptic$levels().get(key);
        if (existing != null) return existing;

        LevelStem overworldStem = server.registryAccess()
            .lookupOrThrow(Registries.LEVEL_STEM)
            .getValueOrThrow(flavour);

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
        Platform.get().levelLoaded(server, level);
        LOGGER.info("created dimension {} with seed {}", key.identifier(), seed);
        return level;
    }

    /**
     * The one spot everybody starts from.
     * <p>
     * Asked of the game's own world-spawn search rather than worked out here.
     * Taking the surface height at the origin put runs in the middle of oceans
     * and standing in rivers, because the height of a water column is the top of
     * the water — vanilla never spawns a new world that way, and neither should
     * a run.
     * <p>
     * A seed whose origin is water is kept, and the spawn moves to the nearest
     * dry land instead. See {@link #findSpawn}. Only if there is no land within
     * a thousand blocks is the origin used regardless: a wet start beats a run
     * that will not begin.
     */
    public static BlockPos spawn(ServerLevel level) {
        BlockPos found = findSpawn(level);
        if (found != null) return found;
        LOGGER.warn("no dry spawn within {} chunks of {}, starting at the origin",
            SPAWN_SEARCH_CHUNKS, level.dimension().identifier());
        level.getChunk(0, 0);
        return new BlockPos(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0);
    }

    /**
     * The nearest dry land to the origin, or nothing.
     * <p>
     * Each spot is confirmed by the game's own search, the one that places a new
     * world's spawn and refuses anything standing in liquid. That search
     * generates the chunk it looks at, and generating every chunk across an ocean
     * is what made a wet seed slow. So beyond the first ring, chunks are judged
     * by the terrain noise first. That says how high the ground will be without
     * building anything, and only chunks whose ground clears sea level are
     * generated to be checked properly.
     * <p>
     * Chunks are tried nearest ring first, so the first one that passes is the
     * nearest dry land, or close to it.
     */
    public static BlockPos findSpawn(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState noise = level.getChunkSource().randomState();
        int seaLevel = generator.getSeaLevel();
        int generated = 0;
        for (ChunkPos chunk : spiral(SPAWN_SEARCH_CHUNKS)) {
            boolean near = Math.max(Math.abs(chunk.x()), Math.abs(chunk.z())) <= SPAWN_NEAR_RINGS;
            if (!near) {
                // OCEAN_FLOOR_WG ignores water, so ground above sea level here
                // means no water standing on it.
                int ground = generator.getBaseHeight(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ(),
                    Heightmap.Types.OCEAN_FLOOR_WG, level, noise);
                if (ground <= seaLevel) continue;
                if (generated >= SPAWN_GENERATE_LIMIT) break;
                generated++;
            }
            BlockPos found = PlayerSpawnFinder.getSpawnPosInChunk(level, chunk);
            if (found != null) return found;
        }
        return null;
    }

    /** Chunk positions outward from the origin, nearest rings first. */
    private static List<ChunkPos> spiral(int rings) {
        List<ChunkPos> out = new ArrayList<>();
        out.add(new ChunkPos(0, 0));
        for (int r = 1; r <= rings; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    // Only the new ring, not the filled square: everything inside
                    // has already been tried on an earlier pass.
                    if (Math.max(Math.abs(x), Math.abs(z)) == r) out.add(new ChunkPos(x, z));
                }
            }
        }
        return out;
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

        Platform.get().levelUnloaded(server, level);
        levels.remove(key);
        try {
            level.close();
        } catch (IOException e) {
            LOGGER.warn("could not close {}", key.identifier(), e);
        }

        deleteFiles(access.synaptic$storageSource().getDimensionPath(key));
        LOGGER.info("destroyed dimension {}", key.identifier());
    }

    /** Delete a dimension's folder and everything in it. A folder that is not there is fine. */
    private static void deleteFiles(Path path) {
        if (!Files.exists(path)) return;
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
    }
}
