package com.synaptic.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether this world has had its Synaptic settings looked at yet.
 * <p>
 * Kept as a marker file in the world folder rather than alongside the settings,
 * because the settings live in the installation-wide config directory: a flag
 * there would fire once ever, when what is wanted is once per new world (and,
 * on a dedicated server, once for that server).
 */
public final class SetupState {
    private static final Logger LOGGER = LoggerFactory.getLogger("synaptic");
    private static final String MARKER = "synaptic-setup.done";

    private SetupState() {
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
    }

    public static boolean isDone(MinecraftServer server) {
        return Files.exists(marker(server));
    }

    /**
     * Marked as soon as the prompt goes out, not when it is answered — closing the
     * screen with Cancel is an answer too, and re-asking on every join would be
     * nagging. The screen is always a keypress away afterwards.
     */
    public static void markDone(MinecraftServer server) {
        Path path = marker(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "Synaptic showed its first-run settings for this world.\n");
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", path, e);
        }
    }
}
