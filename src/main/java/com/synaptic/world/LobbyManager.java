package com.synaptic.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.CaptureFramePayload;
import com.synaptic.net.LobbyStatePayload;
import com.synaptic.net.SynapticNetworking;
import com.synaptic.stats.SessionStats;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seed lobby: several candidate worlds generated at once, looked at, and one
 * of them kept.
 * <p>
 * Nine live worlds cannot be shown side by side — a client renders one level —
 * so the grid is built from still frames. The server walks each player through
 * the candidates in turn, and their client photographs each one as they arrive.
 * The tour is per player rather than per lobby, which is what lets somebody who
 * joins halfway through be caught up on their own without disturbing anyone
 * already looking at the grid.
 * <p>
 * Every candidate is a real dimension with a real seed, generated for real. The
 * eight that are not chosen are deleted the moment one is.
 */
public final class LobbyManager {
    /** Ticks before the shutter is offered. The client may still say it is not ready. */
    private static final int SETTLE_TICKS = 4;
    /**
     * View distance while touring.
     * <p>
     * The single biggest cost in a tour is generating and sending chunks, and
     * chunk count grows with the square of the distance — so halving this is
     * roughly four times less work per candidate. A spawn preview does not need
     * to see the horizon, and the whole batch has to be generated from nothing.
     */
    private static final int TOUR_VIEW_DISTANCE = 7;
    /**
     * How long to wait for a client to report a tile photographed before giving
     * up on it and moving on. Generous: a slow first world is the normal case,
     * and a stuck tour is worse than a dark tile.
     */
    private static final int ACK_TIMEOUT = 400;
    private static final float VIEW_YAW = 0.0F;
    /** How many seeds to try for one tile before settling for whatever came last. */
    private static final int SEED_ATTEMPTS = 8;
    /**
     * Ticks between building one candidate ahead of time.
     * <p>
     * Building a world costs a noticeable pause, so they are made one at a time
     * with a gap between: spread over a run, a full grid is ready long before
     * anybody asks for it, and the cost is a hitch every few seconds rather than
     * ten seconds of frozen server the moment somebody presses the button.
     */
    private static final int PREPARE_INTERVAL = 100;

    private record Candidate(String name, long seed, BlockPos spawn) {}

    /** Where one player has got to in their walk through the candidates. */
    private static final class Tour {
        private int slot;
        private int ticks;
        private boolean shot;
        private GameType mode;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("synaptic");
    private static final List<Candidate> candidates = new ArrayList<>();
    private static final Map<UUID, Tour> tours = new LinkedHashMap<>();
    private static final Map<UUID, GameType> heldModes = new HashMap<>();
    /** Players who have seen the whole batch and are looking at the grid. */
    private static final Set<UUID> finished = new HashSet<>();
    /** Players already told their client cannot show them any of this. */
    private static final Set<UUID> warned = new HashSet<>();
    private static int grid = 2;
    private static boolean open;
    private static int heldViewDistance = 10;
    /** Bumped per batch so candidate names are never reused. */
    private static int batch;
    /** Whether the screens are already up for a lobby that has not been built yet. */
    private static boolean announced;
    /**
     * Worlds built during play, waiting for the next lobby to want them.
     * <p>
     * The whole reason a reset can be quick: generating nine worlds takes as
     * long as it takes, so it happens while the last run is still being played
     * rather than while everybody sits watching a progress bar. Borrowed from
     * SeedQueue, which keeps a queue of ready worlds for exactly this reason.
     */
    private static final List<Candidate> prepared = new ArrayList<>();
    private static int preparedGrid;
    private static int prepareTick;

    private LobbyManager() {
    }

    public static boolean isOpen() {
        return open;
    }

    public static int grid() {
        return grid;
    }

    /**
     * Throw away any batch in progress, generate a fresh one, and start
     * everybody walking through it.
     * <p>
     * Generation is done here and now rather than in the background: a candidate
     * is only a candidate once its spawn column exists, and a player sent to a
     * dimension that has not generated yet arrives inside the void.
     */
    public static void open(MinecraftServer server, int size) {
        grid = Math.clamp(size, 1, 3);
        discard(server);
        // Remembered once, on the way into the first lobby of a run: recycling
        // reopens and would otherwise record the tour's own reduced distance as
        // the value to go back to.
        if (!open) heldViewDistance = server.getPlayerList().getViewDistance();
        open = true;
        server.getPlayerList().setViewDistance(Math.min(heldViewDistance, TOUR_VIEW_DISTANCE));

        // Every batch gets names of its own. The chosen candidate keeps its name
        // as the run, so reusing "candidate_3" next time round handed the lobby
        // back the world everybody was already standing in — one tile of the new
        // grid was the current run, and picking anything else would have deleted
        // it out from under them.
        // Straight off the queue when there is a full set of the right size
        // waiting, which is the point of building them during play.
        if (preparedGrid == grid && prepared.size() == grid * grid) {
            candidates.addAll(prepared);
            prepared.clear();
            LOGGER.info("opened the picker from {} worlds prepared during play", candidates.size());
        } else {
            dropPrepared(server);
            batch++;
            for (int i = 0; i < grid * grid; i++) {
                addCandidate(server, i);
            }
        }
        // Nobody has seen this batch, including anyone who was looking at the
        // last one. The tick starts their walks on the next pass.
        finished.clear();
        warned.clear();
    }

    /**
     * Build one world ahead of time, if there is room and a reason.
     * <p>
     * Only while a run is actually being played: during a lobby the server has
     * enough to do, and before the first run there is nothing to hide the cost
     * behind. A grid's worth accumulates over a few minutes of play and is then
     * handed over whole the moment somebody wants the next run.
     */
    public static void prepare(MinecraftServer server) {
        if (!SynapticConfig.enabled(Feature.RUN_RESET)) return;
        if (open || RunManager.currentRun() == null) return;
        if (++prepareTick % PREPARE_INTERVAL != 0) return;

        // A grid resized since these were made is a queue for the wrong shape.
        if (preparedGrid != grid) dropPrepared(server);
        if (prepared.size() >= grid * grid) return;

        preparedGrid = grid;
        int slot = prepared.size();
        Candidate candidate = buildCandidate(server, "prep_" + batch + "_" + slot);
        if (candidate != null) prepared.add(candidate);
    }

    /** Throw away worlds queued for a grid nobody is going to ask for. */
    private static void dropPrepared(MinecraftServer server) {
        if (prepared.isEmpty()) return;
        ServerLevel fallback = RunManager.currentLevel(server);
        for (Candidate candidate : List.copyOf(prepared)) {
            RuntimeDimension.destroy(server, candidate.name(), fallback);
        }
        prepared.clear();
        batch++;
    }

    /**
     * Roll seeds for one tile until one of them has dry land to stand on.
     * <p>
     * A world whose origin is open ocean is not a world anybody wants to be
     * handed, so it is thrown away and another seed tried rather than dropped
     * into the sea — which is what the old fallback did, and why runs still
     * started in the water.
     * <p>
     * Each rejected world is deleted before the next is built, so a run of bad
     * luck does not leave a pile of abandoned oceans in the save. If every
     * attempt is wet the last is taken anyway: a soggy start beats a lobby with
     * a hole in it.
     */
    private static void addCandidate(MinecraftServer server, int slot) {
        Candidate candidate = buildCandidate(server, "candidate_" + batch + "_" + slot);
        if (candidate != null) candidates.add(candidate);
    }

    private static Candidate buildCandidate(MinecraftServer server, String base) {
        ServerLevel fallback = RunManager.currentLevel(server);
        for (int attempt = 0; attempt < SEED_ATTEMPTS; attempt++) {
            long seed = WorldOptions.randomSeed();
            String name = base + (attempt == 0 ? "" : "_r" + attempt);
            ServerLevel level = RuntimeDimension.create(server, name, seed);
            BlockPos spawn = RuntimeDimension.findSpawn(level);
            if (spawn != null || attempt == SEED_ATTEMPTS - 1) {
                return new Candidate(name, seed,
                    spawn != null ? spawn : RuntimeDimension.spawn(level));
            }
            LOGGER.info("seed {} is all water near the origin, trying another", seed);
            RuntimeDimension.destroy(server, name, fallback);
        }
        return null;
    }

    private static void beginTour(MinecraftServer server, ServerPlayer player) {
        Tour tour = new Tour();
        tour.mode = player.gameMode();
        tours.put(player.getUUID(), tour);
        heldModes.putIfAbsent(player.getUUID(), tour.mode);
        // Spectator for the duration: no falling, no drowning and no mob deciding
        // the tour for them, and the frames come out clean.
        if (player.gameMode() != GameType.SPECTATOR) player.setGameMode(GameType.SPECTATOR);
        sendState(server, player, LobbyStatePayload.TOURING);
        place(server, player, 0);
    }

    /**
     * Open the lobby when it is due, catch up anyone who has not seen it, and
     * advance every tour by a tick.
     * <p>
     * All of this lives on the tick rather than on the join event on purpose.
     * A joining player is not reliably in the player list at the moment that
     * event fires, so opening the lobby there produced a lobby with nobody in
     * it — the world opened, the tour never started, and the player simply
     * spawned into the save's original world. Asking every tick instead means
     * the question is answered once the player is genuinely there, and asking
     * twice costs nothing.
     */
    public static void tick(MinecraftServer server) {
        if (!SynapticConfig.enabled(Feature.RUN_RESET)) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        // A session nobody has chosen a world for yet starts at the picker
        // rather than in whatever seed the save was made with.
        if (!open && SessionStats.run() == 0) {
            // Two passes on purpose. Building the candidates blocks the server
            // for seconds, so the screens go up on this tick and the generating
            // happens on the next — otherwise the host stares at whatever world
            // they spawned into, then gets yanked through four of them with no
            // explanation.
            if (!announced) {
                announced = true;
                broadcast(server, LobbyStatePayload.TOURING);
                return;
            }
            LOGGER.info("opening the seed lobby for session {}", SessionStats.session());
            open(server, grid);
            return;
        }
        if (!open) return;

        // Anyone online who is neither touring nor already at the grid needs
        // walking through — which covers both a fresh lobby and a player who
        // arrived halfway through one.
        Set<UUID> here = new HashSet<>();
        for (ServerPlayer player : players) here.add(player.getUUID());
        // A player who left has to be walked through again on their return: the
        // frames they captured went with their client.
        finished.retainAll(here);
        for (ServerPlayer player : List.copyOf(players)) {
            UUID id = player.getUUID();
            if (tours.containsKey(id) || finished.contains(id)) continue;
            // A player without the mod has nothing to show them: the grid, the
            // loading screen and the hidden HUD are all client side. Touring one
            // walks them blindly through nine worlds in spectator with no idea
            // why. They wait where they are until a run is chosen.
            if (!ServerPlayNetworking.canSend(player, LobbyStatePayload.TYPE)) {
                warnUnmodded(player);
                continue;
            }
            // A dead player cannot be moved and cannot see. Touring one walks a
            // corpse through the candidates and photographs their death screen
            // four times over, which is exactly what it looked like. Bring them
            // back first and pick them up next tick — respawning replaces the
            // player object, so this one is already stale.
            if (player.isDeadOrDying()) {
                player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                continue;
            }
            beginTour(server, player);
        }

        if (tours.isEmpty()) return;
        for (Map.Entry<UUID, Tour> pair : List.copyOf(tours.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(pair.getKey());
            if (player == null) {
                tours.remove(pair.getKey());
                continue;
            }
            Tour tour = pair.getValue();
            tour.ticks++;
            if (!tour.shot && tour.ticks >= SETTLE_TICKS) {
                // Offered, not commanded: the client shoots once its chunks are
                // there and answers with a FrameReadyPayload.
                ServerPlayNetworking.send(player, new CaptureFramePayload(tour.slot));
                tour.shot = true;
            } else if (tour.shot && tour.ticks >= SETTLE_TICKS + ACK_TIMEOUT) {
                advance(server, player, tour);
            }
        }
    }

    /**
     * A client reports the tile photographed. Moves them straight on rather than
     * sitting out the rest of a timer nobody needs.
     */
    public static void acknowledge(MinecraftServer server, ServerPlayer player, int slot) {
        Tour tour = tours.get(player.getUUID());
        // Ignored unless it is the shot actually being waited on: a late answer
        // for a tile already left behind would skip the one after it.
        if (tour == null || !tour.shot || tour.slot != slot) return;
        advance(server, player, tour);
    }

    private static void advance(MinecraftServer server, ServerPlayer player, Tour tour) {
        tour.slot++;
        tour.ticks = 0;
        tour.shot = false;
        if (tour.slot >= candidates.size()) finishTour(server, player, tour);
        else place(server, player, tour.slot);
    }

    private static void finishTour(MinecraftServer server, ServerPlayer player, Tour tour) {
        tours.remove(player.getUUID());
        finished.add(player.getUUID());
        // Parked in the first candidate while the grid is up, so there is a world
        // behind the screen rather than a void.
        place(server, player, 0);
        sendState(server, player, LobbyStatePayload.GRID);
    }

    private static void place(MinecraftServer server, ServerPlayer player, int slot) {
        Candidate candidate = candidates.get(slot);
        ServerLevel level = server.getLevel(RuntimeDimension.key(candidate.name()));
        if (level == null) return;
        BlockPos spawn = candidate.spawn();
        // Everyone lands on the same block, so the host would otherwise be
        // looking at the backs of everybody else's heads in every photograph.
        // A block up clears them, and it is the host's shot that gets picked
        // from.
        if (SynapticNetworking.canConfigure(player)) spawn = spawn.above();
        RuntimeDimension.send(player, level, spawn, VIEW_YAW);
    }

    /** Said once per lobby, to the player it concerns rather than to everyone. */
    private static void warnUnmodded(ServerPlayer player) {
        if (!warned.add(player.getUUID())) return;
        player.sendSystemMessage(Component.literal("Synaptic is not installed on your client, "
            + "so you cannot see the world picker. Waiting for the host to choose.")
            .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Take the candidate in this slot and start the run on it.
     * <p>
     * The chosen one is handed to {@link RunManager} before the others are
     * deleted, so nobody is ever standing in a level being closed.
     */
    public static void pick(MinecraftServer server, int slot) {
        if (!open || slot < 0 || slot >= candidates.size()) return;
        Candidate chosen = candidates.get(slot);
        ServerLevel level = server.getLevel(RuntimeDimension.key(chosen.name()));
        if (level == null) return;

        open = false;
        announced = false;
        tours.clear();
        finished.clear();
        // The run itself is played at whatever distance the player chose.
        server.getPlayerList().setViewDistance(heldViewDistance);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameType held = heldModes.get(player.getUUID());
            if (held != null && held != GameType.SPECTATOR) player.setGameMode(held);
            sendState(server, player, LobbyStatePayload.CLOSED);
        }
        heldModes.clear();

        RunManager.adopt(server, chosen.name(), level, chosen.spawn(), chosen.seed());

        for (Candidate candidate : List.copyOf(candidates)) {
            if (candidate != chosen) RuntimeDimension.destroy(server, candidate.name(), level);
        }
        candidates.clear();
    }

    /** Same grid, new worlds. */
    public static void recycle(MinecraftServer server) {
        if (open) open(server, grid);
    }

    /** A different grid means a different number of candidates, so a fresh batch. */
    public static void resize(MinecraftServer server, int size) {
        if (open) open(server, size);
    }

    /** Delete every candidate. Used before regenerating, and when giving up on a lobby. */
    private static void discard(MinecraftServer server) {
        ServerLevel fallback = RunManager.currentLevel(server);
        for (Candidate candidate : List.copyOf(candidates)) {
            RuntimeDimension.destroy(server, candidate.name(), fallback);
        }
        candidates.clear();
        tours.clear();
    }

    private static void sendState(MinecraftServer server, ServerPlayer player, int state) {
        ServerPlayNetworking.send(player, new LobbyStatePayload(state, grid, candidates.size(),
            SessionStats.session(), SessionStats.run() + 1,
            SynapticNetworking.canConfigure(player)));
    }

    /** Everyone in the lobby is told the same thing at the same time. */
    public static void broadcast(MinecraftServer server, int state) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendState(server, player, state);
        }
    }
}
