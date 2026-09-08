package com.synaptic.platform;

import java.nio.file.Path;
import java.util.List;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The handful of things the shared code cannot do for itself.
 * <p>
 * Everything in {@code common} compiles against bare vanilla Minecraft — no
 * Fabric, no NeoForge — which is deliberate: the compiler, not a review, is what
 * stops a loader import creeping into shared code. The cost of that is this
 * interface. Anything a loader has to answer differently lives here, is
 * implemented once per loader, and is handed over at startup before the mod does
 * anything else.
 * <p>
 * It stays small on purpose. Every method added is a thing that has to be
 * written twice and can disagree twice, so the test for a new one is whether the
 * two loaders genuinely cannot be made to look the same from up here — not
 * whether routing through it would be convenient.
 * <p>
 * Client-only calls do not belong here. A dedicated server loads this class, and
 * an implementation that so much as mentions {@code Minecraft} would take the
 * server down when it did. See {@link ClientPlatform}.
 */
public interface Platform {
    /** Where {@code synaptic.properties} and the session files live. */
    Path configDir();

    /** The game directory, which is where bug reports are written. */
    Path gameDir();

    /**
     * A mod's version by id, or {@code "(not installed)"} — which, in a bug
     * report, is itself a useful answer.
     */
    String modVersion(String modId);

    /**
     * The loader's own rows for a bug report: {@code "Fabric Loader"} and
     * {@code "Fabric API"} on one side, {@code "NeoForge"} on the other.
     * <p>
     * Returned rather than printed because the report is assembled in two
     * different shapes — a fixed-column block for the saved file and a bulleted
     * list for the issue URL — and neither of those is the loader's business.
     * Labels are padded by the caller, so give it the bare label.
     */
    List<Row> loaderRows();

    /** Send a payload to one player. */
    void send(ServerPlayer player, CustomPacketPayload payload);

    /**
     * Whether this player's client has said it can receive this payload.
     * <p>
     * The answer is no for a vanilla client, and no for a modded one that has
     * not finished negotiating yet, so it is asked rather than assumed
     * everywhere a client-rendered thing is about to be pushed out.
     */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    /**
     * Announce a level that was created after the server started.
     * <p>
     * Both loaders fire an event when a level loads, and every mod that keeps
     * per-level state is listening to it. A runtime dimension that skipped it
     * would exist as far as the game is concerned and not exist as far as
     * everyone else is concerned, which is the sort of bug that surfaces in
     * somebody else's mod a week later.
     */
    void levelLoaded(MinecraftServer server, ServerLevel level);

    /** The other half of {@link #levelLoaded}, for a dimension being thrown away. */
    void levelUnloaded(MinecraftServer server, ServerLevel level);

    /** One labelled version, for {@link #loaderRows()}. */
    record Row(String label, String version) {
    }

    static Platform get() {
        Platform platform = Holder.instance;
        if (platform == null) {
            throw new IllegalStateException(
                "Synaptic's platform was never installed — the loader entrypoint must call "
                    + "Platform.set() before anything else runs.");
        }
        return platform;
    }

    /** Called once, first thing, by each loader's entrypoint. */
    static void set(Platform platform) {
        Holder.instance = platform;
    }

    /** Interfaces cannot hold mutable state; this is the field that would have been one. */
    final class Holder {
        private static Platform instance;

        private Holder() {
        }
    }
}
