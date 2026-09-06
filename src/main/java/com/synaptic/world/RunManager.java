package com.synaptic.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.synaptic.SynapticMod;
import com.synaptic.mixin.MinecraftServerAccessor;
import com.synaptic.mixin.PrimaryLevelDataAccessor;
import com.synaptic.stats.SessionStats;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.PrimaryLevelData;

/**
 * Runs: one world after another inside a single save, without anybody leaving.
 * <p>
 * A run is a dimension of its own with its own seed, named for the session and
 * the run within it — {@code Synaptic 1.65} is the sixty-fifth run of the first
 * session. Starting the next one builds the new dimension, drops everybody on
 * the same block facing the same way, and deletes the one they came from.
 * <p>
 * Deliberately a clean slate: inventories, effects and experience are cleared
 * and the shared pools re-seeded. A run is a fresh attempt, and carrying the
 * last one's gear into it would make the reset pointless.
 */
public final class RunManager {
    /** Everyone starts facing due south, so one preview frame is honest for all of them. */
    private static final float START_YAW = 0.0F;

    private static String currentRun;
    /** The run's seed, so its nether and end can be built from the same one. */
    private static long currentSeed;
    /** Who has already been dropped into the current run since the server started. */
    private static final Set<UUID> placed = new HashSet<>();

    private RunManager() {
    }

    /** The dimension name for a run, which is also what the run is called. */
    private static String nameFor(int session, int run) {
        return "run_" + session + "_" + run;
    }

    /** "Synaptic 1.65" — the session and the run within it. */
    public static String label(int session, int run) {
        return "Synaptic " + session + "." + run;
    }

    /**
     * Build the next run and move everyone into it.
     * <p>
     * The old run is destroyed only after everybody is standing in the new one,
     * so there is never a moment where a player is in a level that is being
     * closed underneath them.
     */
    public static void startNextRun(MinecraftServer server, long seed) {
        int session = SessionStats.session();
        String name = nameFor(session, SessionStats.run() + 1);
        ServerLevel level = RuntimeDimension.create(server, name, seed);
        adopt(server, name, level, RuntimeDimension.spawn(level), seed);
    }

    /**
     * Make an already built dimension the current run and move everyone in.
     * <p>
     * Split out so the lobby can hand over a candidate it generated itself: by
     * the time a seed is chosen the world already exists and has been stood in,
     * and building it again would be both slow and a different world.
     */
    public static void adopt(MinecraftServer server, String name, ServerLevel level,
                             BlockPos spawn, long seed) {
        int session = SessionStats.session();
        int run = SessionStats.nextRun();
        String leaving = currentRun;

        // Point everyone's respawn at the new run first. A dead player is about
        // to come back through vanilla's own path, and this is what decides
        // where that path puts them.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pointRespawnAt(player, level, spawn);
        }

        // Remembered before the respawn below, which is free to change it: a
        // hardcore world turns anyone who comes back into a spectator, and a run
        // reset is meant to hand them a new world to play rather than a ghost's
        // view of one.
        Map<UUID, GameType> modes = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            modes.put(player.getUUID(), player.gameMode());
        }

        // Then bring back anyone sitting on a death screen. This is the whole
        // reason a reset cannot simply teleport: a dead player has no business
        // being moved, and moving them would leave them staring at Game Over in
        // a world they cannot see.
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!player.isDeadOrDying()) continue;
            player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }

        // Respawning builds a new player object for everyone it touched, so the
        // list is read again rather than reused — the copy taken above is stale
        // for exactly the players that mattered.
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            restoreMode(player, modes.get(player.getUUID()));
            reset(player);
            RuntimeDimension.send(player, level, spawn, START_YAW);
            pointRespawnAt(player, level, spawn);
        }
        currentRun = name;
        currentSeed = seed;
        // Built now rather than when somebody first lights a portal. Making a
        // level costs almost nothing until its chunks are asked for, and adding
        // one to the server's map from inside a block tick means editing the
        // collection the server is in the middle of ticking.
        RuntimeDimension.create(server, name + "_nether", seed, LevelStem.NETHER);
        RuntimeDimension.create(server, name + "_end", seed, LevelStem.END);
        renameSave(server, session, run);

        if (leaving != null) destroyRun(server, leaving, level);
        // The shared pools still hold the last run's numbers; a fresh start has
        // to be re-seeded from the players actually standing here.
        SynapticMod.reseed();

        server.getPlayerList().broadcastSystemMessage(Component.literal(label(session, run))
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
            .append(Component.literal(" — seed ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(seed)).withStyle(ChatFormatting.WHITE)), false);
    }

    public static void startNextRun(MinecraftServer server) {
        startNextRun(server, WorldOptions.randomSeed());
    }

    /** Which run everyone is currently in, or null before the first one is started. */
    public static String currentRun() {
        return currentRun;
    }

    /**
     * The run's own nether or end, built the first time somebody goes looking.
     * <p>
     * Without these every run shares the save's originals, so a dragon killed in
     * one run is still dead in the next and a nether stripped bare stays
     * stripped. They are made on demand rather than up front because most runs
     * end long before anyone lights a portal, and generating two more worlds for
     * every reset would cost seconds nobody asked for.
     * <p>
     * Both are built from the run's own seed, the way one seed makes all three
     * dimensions of an ordinary world.
     */
    public static ServerLevel companion(MinecraftServer server, ResourceKey<Level> asked) {
        if (currentRun == null) return null;
        if (asked == Level.OVERWORLD) return currentLevel(server);

        ResourceKey<LevelStem> flavour;
        String suffix;
        if (asked == Level.NETHER) {
            flavour = LevelStem.NETHER;
            suffix = "_nether";
        } else if (asked == Level.END) {
            flavour = LevelStem.END;
            suffix = "_end";
        } else {
            return null;
        }
        return RuntimeDimension.create(server, currentRun + suffix, currentSeed, flavour);
    }

    /**
     * What this level would be called in an ordinary world.
     * <p>
     * A portal works out which way you are going by comparing the dimension you
     * are standing in against {@code NETHER} or {@code END}. A run's are named
     * for the run, so every one of those comparisons is false and every portal
     * concludes you are on your way in — which is how walking into the end's
     * exit portal put people back on the obsidian platform they arrived on.
     * <p>
     * Answering with the name vanilla expects lets its own logic run correctly,
     * and the destination it then asks for is redirected back to the run.
     */
    public static ResourceKey<Level> canonical(Level level) {
        ResourceKey<Level> here = level.dimension();
        if (currentRun == null) return here;
        if (here.equals(RuntimeDimension.key(currentRun))) return Level.OVERWORLD;
        if (here.equals(RuntimeDimension.key(currentRun + "_nether"))) return Level.NETHER;
        if (here.equals(RuntimeDimension.key(currentRun + "_end"))) return Level.END;
        return here;
    }

    /**
     * Whether a portal may be lit here.
     * <p>
     * Vanilla asks this of the dimension by name and only accepts its own two.
     * A run and the nether belonging to it stand in for those; a run's end does
     * not, for the same reason the real end does not.
     */
    public static boolean allowsPortals(Level level) {
        if (currentRun == null) return false;
        ResourceKey<Level> here = level.dimension();
        return here.equals(RuntimeDimension.key(currentRun))
            || here.equals(RuntimeDimension.key(currentRun + "_nether"));
    }

    /** Take a run down along with the nether and end that belonged to it. */
    public static void destroyRun(MinecraftServer server, String name, ServerLevel fallback) {
        RuntimeDimension.destroy(server, name + "_nether", fallback);
        RuntimeDimension.destroy(server, name + "_end", fallback);
        RuntimeDimension.destroy(server, name, fallback);
    }

    /**
     * Somewhere safe to put a player being moved out of a level about to be
     * deleted. The current run if there is one, the save's own overworld if the
     * first run has not been chosen yet.
     */
    public static ServerLevel currentLevel(MinecraftServer server) {
        if (currentRun == null) return server.overworld();
        ServerLevel level = server.getLevel(RuntimeDimension.key(currentRun));
        return level != null ? level : server.overworld();
    }

    /**
     * Put a player back in the mode they were playing in.
     * <p>
     * Spectator is never restored. In a hardcore world it is what respawning
     * just turned them into rather than anything they chose, and a run they can
     * only watch is not a run — so anyone arriving as a ghost is handed survival
     * instead. Creative and adventure are left alone.
     */
    private static void restoreMode(ServerPlayer player, GameType before) {
        GameType wanted = before == null || before == GameType.SPECTATOR ? GameType.SURVIVAL : before;
        if (player.gameMode() != wanted) player.setGameMode(wanted);
    }

    /**
     * Put a player who has just arrived into the run everyone else is in.
     * <p>
     * A run is a dimension the save knows nothing about, so the game drops a
     * joining player into the save's own overworld — an empty world nobody is
     * playing, left over from before the first run was chosen.
     * <p>
     * Only someone standing in that original overworld is moved. Somebody who
     * logged out in the nether or the end belongs where they left off, and
     * hauling them to spawn on every login would be worse than the bug.
     */
    public static void placeJoiners(MinecraftServer server) {
        if (currentRun == null) return;
        ServerLevel level = server.getLevel(RuntimeDimension.key(currentRun));
        if (level == null) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> here = new HashSet<>();
        for (ServerPlayer player : players) here.add(player.getUUID());
        placed.retainAll(here);

        for (ServerPlayer player : List.copyOf(players)) {
            if (!placed.add(player.getUUID())) continue;
            if (player.level() != server.overworld()) continue;
            BlockPos spawn = RuntimeDimension.spawn(level);
            RuntimeDimension.send(player, level, spawn, START_YAW);
            pointRespawnAt(player, level, spawn);
        }
    }

    /**
     * Name the save after the world and the try, so a folder full of runs does
     * not read as a folder full of "New World".
     * <p>
     * The name in the world list lives in the save's settings, which are held
     * privately and normally only changed from the menu with the world closed.
     * The whole record is swapped for one carrying the new name, because that is
     * what gets written on the next save — setting it anywhere else is undone by
     * the first autosave. The folder on disk keeps whatever name it was created
     * with: its files are open and locked, and renaming a directory out from
     * under a running server is a good way to lose one.
     */
    private static void renameSave(MinecraftServer server, int session, int run) {
        if (!(server.getWorldData() instanceof PrimaryLevelData data)) return;
        PrimaryLevelDataAccessor access = (PrimaryLevelDataAccessor) data;
        LevelSettings settings = access.synaptic$settings();
        String wanted = label(session, run);
        if (wanted.equals(settings.levelName())) return;
        access.synaptic$setSettings(new LevelSettings(wanted, settings.gameType(),
            settings.difficultySettings(), settings.allowCommands(), settings.dataConfiguration()));
        // Written now rather than at the next autosave, so quitting straight
        // after a reset still leaves the right name in the list.
        ((MinecraftServerAccessor) server).synaptic$storageSource().saveDataTag(data);
    }

    /** Dying returns you to this run's start, not to the save's original overworld. */
    private static void pointRespawnAt(ServerPlayer player, ServerLevel level, BlockPos spawn) {
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(
            LevelData.RespawnData.of(level.dimension(), spawn, START_YAW, 0.0F), true), false);
    }

    /**
     * Put a player back to how they would start a brand new world.
     * <p>
     * Health and hunger are left to the shared pools, which are re-seeded once
     * everybody has landed — setting them here would only be overwritten a tick
     * later by whatever the pool decided.
     */
    private static void reset(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        for (MobEffectInstance effect : List.copyOf(player.getActiveEffects())) {
            player.removeEffect(effect.getEffect());
        }
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.setRemainingFireTicks(0);
        player.fallDistance = 0.0;
    }
}
