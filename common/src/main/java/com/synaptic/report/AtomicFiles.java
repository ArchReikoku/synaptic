package com.synaptic.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Writes a file so that it is either the old version or the new one, never half
 * of each.
 * <p>
 * Overwriting in place truncates the file first and fills it after, so a crash
 * or a power cut in between leaves it empty or cut short. For the session totals
 * that is not just lost numbers: the file names the dimension the run is being
 * played in, and without it a reconnecting group lands in the save's own
 * overworld. So the new text goes to a sibling file, and that is moved over the
 * old one in a single step once it is complete.
 */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void writeString(Path path, String text) throws IOException {
        Path temp = temp(path);
        Files.writeString(temp, text);
        replace(temp, path);
    }

    public static void writeProperties(Path path, Properties properties, String comment) throws IOException {
        Path temp = temp(path);
        try (Writer writer = Files.newBufferedWriter(temp)) {
            properties.store(writer, comment);
        }
        replace(temp, path);
    }

    private static Path temp(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        return path.resolveSibling(path.getFileName() + ".tmp");
    }

    private static void replace(Path temp, Path path) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some file systems cannot do it in one step. A plain replace still
            // never truncates the old file before the new one is complete.
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
