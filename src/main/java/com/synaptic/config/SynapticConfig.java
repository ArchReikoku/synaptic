package com.synaptic.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which features are switched on, as a bitmask of {@link Feature} ordinals.
 * <p>
 * The server owns the value and persists it; clients hold a copy pushed to them
 * on join and whenever it changes, so client-side features (and the settings
 * screen) can read the same state. Stored as a single volatile int rather than a
 * set because it is read from the server thread and written from the network and
 * client threads.
 */
public final class SynapticConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("synaptic");
    private static final String FILE_NAME = "synaptic.properties";

    private static volatile int bits = defaults();
    /** Client-side only: what the server said this player may do. */
    private static volatile boolean editable;

    private SynapticConfig() {
    }

    private static int defaults() {
        int all = 0;
        for (Feature feature : Feature.values()) {
            if (feature.defaultOn()) all |= feature.bit();
        }
        return all;
    }

    public static boolean enabled(Feature feature) {
        return (bits & feature.bit()) != 0;
    }

    public static int bits() {
        return bits;
    }

    /**
     * Whether this client's player may change the settings, as decided by the
     * server and sent with them. Meaningless server-side, where the question is
     * asked per player rather than once for the game.
     */
    public static boolean editable() {
        return editable;
    }

    public static void setEditable(boolean allowed) {
        editable = allowed;
    }

    public static void apply(int newBits) {
        bits = newBits;
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Reads the saved settings; anything missing keeps its default of "on". */
    public static void load() {
        Path path = configFile();
        if (!Files.exists(path)) {
            save();
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}, using defaults", path, e);
            return;
        }
        int loaded = 0;
        for (Feature feature : Feature.values()) {
            String value = properties.getProperty(feature.name().toLowerCase());
            // A key the file has never heard of falls back to the feature's own
            // default rather than to "on", so a feature added later does not
            // switch itself on in somebody's running world.
            boolean on = value == null ? feature.defaultOn() : Boolean.parseBoolean(value);
            if (on) loaded |= feature.bit();
        }
        bits = loaded;
    }

    public static void save() {
        Properties properties = new Properties();
        for (Feature feature : Feature.values()) {
            properties.setProperty(feature.name().toLowerCase(), String.valueOf(enabled(feature)));
        }
        Path path = configFile();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "Synaptic — feature toggles (in-game: press K)");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", path, e);
        }
    }
}
