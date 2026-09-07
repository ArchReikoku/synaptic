package com.synaptic.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.report.SynapticLog;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Gathers what a maintainer needs in order to act on a bug, writes it to a file
 * the player owns, and — only if the player asks — builds the GitHub issue link
 * to paste it into.
 * <p>
 * Nothing here uploads anything. The file lands in the game folder, the player
 * can read every line of it before anyone else does, and opening the browser is
 * a second, separate button behind the game's own "are you sure" prompt. A mod
 * that phoned home the moment something went wrong would collect better reports
 * and deserve none of them.
 * <p>
 * Two sizes of the same report, deliberately. The <b>file</b> is complete: every
 * setting, and the recent log. The <b>link</b> carries a summary, because a URL
 * long enough to hold a log is a URL that browsers and Windows both truncate
 * without saying so — the player attaches the file for the rest.
 * <p>
 * What is left out is as considered as what is in. The server address is not
 * recorded: a bug report is a public document, and where somebody plays is
 * nobody's business but theirs. Player names and UUIDs are out for the same
 * reason; a count answers "was this a group thing?" without naming the group.
 */
public final class BugReport {
    private static final String ISSUE_URL = "https://github.com/ArchReikoku/synaptic/issues/new";
    private static final String FOLDER = "synaptic-reports";
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter WRITTEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * How much of the body the link will carry. GitHub itself would take several
     * times this, but the trip there is through a browser address bar and, on
     * Windows, a command line — both of which give up long before GitHub does,
     * and neither of which warns you that they did.
     */
    private static final int LINK_BUDGET = 3500;
    private static final int TITLE_LENGTH = 70;

    private BugReport() {
    }

    /** The whole report, as it is written to disk. */
    public static String compose(String description) {
        StringBuilder out = new StringBuilder();
        out.append("Synaptic bug report\n");
        out.append("===================\n");
        out.append("Written ").append(LocalDateTime.now().format(WRITTEN)).append("\n\n");

        out.append("What happened\n-------------\n");
        out.append(description.isBlank() ? "(nothing written)" : description.strip()).append("\n\n");

        out.append("Versions\n--------\n");
        out.append("Synaptic       ").append(version("synaptic")).append('\n');
        out.append("Minecraft      ").append(version("minecraft")).append('\n');
        out.append("Fabric Loader  ").append(version("fabricloader")).append('\n');
        out.append("Fabric API     ").append(version("fabric-api")).append('\n');
        out.append("Java           ").append(System.getProperty("java.version"))
            .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        out.append("OS             ").append(System.getProperty("os.name"))
            .append(' ').append(System.getProperty("os.version"))
            .append(" (").append(System.getProperty("os.arch")).append(")\n\n");

        out.append("Game\n----\n");
        out.append(game()).append('\n');

        out.append("Settings\n--------\n");
        for (Feature.Group group : Feature.Group.values()) {
            out.append(group.title()).append('\n');
            for (Feature feature : Feature.of(group)) {
                boolean on = SynapticConfig.enabled(feature);
                out.append("  ").append(on ? "ON  " : "OFF ").append(feature.label());
                // Called out rather than left to be worked out from the enum,
                // because the first question about any report is what this
                // player was running that everybody else was not.
                if (on != feature.enabledByDefault()) out.append("   (not the default)");
                out.append('\n');
            }
        }
        out.append('\n');

        out.append("Recent Synaptic log — this game, oldest first\n");
        out.append("--------------------------------------------\n");
        List<String> lines = SynapticLog.recent();
        if (lines.isEmpty()) {
            out.append("(the mod has logged nothing this session)\n");
        } else {
            for (String line : lines) out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Writes the report and hands back where it went. Named for the moment
     * rather than the run, so two reports in one session cannot land on top of
     * each other and the newest is always last in the folder.
     */
    public static Path write(String report) throws IOException {
        Path file = ensureFolder()
            .resolve("synaptic-report-" + LocalDateTime.now().format(FILE_STAMP) + ".txt");
        Files.writeString(file, report, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Where reports go, made if it is not there yet. Reports are written here
     * rather than beside the log so that handing somebody the folder hands them
     * only what they wrote and meant to share.
     */
    public static Path ensureFolder() throws IOException {
        Path folder = FabricLoader.getInstance().getGameDir().resolve(FOLDER);
        Files.createDirectories(folder);
        return folder;
    }

    /**
     * The New Issue page, filled in as far as a link reasonably can be. The
     * saved file is named in the body so the player knows what to attach and
     * where to find it.
     */
    public static URI issueUri(String description, Path saved) {
        StringBuilder body = new StringBuilder();
        body.append("### What happened\n\n");
        body.append(description.isBlank() ? "_(describe the bug here)_" : description.strip()).append("\n\n");
        body.append("### Versions\n\n");
        body.append("- Synaptic ").append(version("synaptic")).append('\n');
        body.append("- Minecraft ").append(version("minecraft")).append('\n');
        body.append("- Fabric Loader ").append(version("fabricloader")).append('\n');
        body.append("- Fabric API ").append(version("fabric-api")).append('\n');
        body.append("- Java ").append(System.getProperty("java.version"))
            .append(", ").append(System.getProperty("os.name")).append('\n');
        body.append('\n');
        body.append("### Game\n\n").append(game()).append('\n');

        List<String> changed = changedSettings();
        body.append("### Settings away from their defaults\n\n");
        body.append(changed.isEmpty() ? "None — everything is as it ships.\n" : String.join("\n", changed) + "\n");
        body.append('\n');

        if (saved != null) {
            body.append("### Full report\n\n");
            body.append("Saved locally, with the recent log — **please attach this file**:\n\n");
            body.append("`").append(saved).append("`\n");
        }

        String text = body.toString();
        if (text.length() > LINK_BUDGET) {
            text = text.substring(0, LINK_BUDGET) + "\n\n_(cut short by the length a link can carry — "
                + "the saved file has all of it)_\n";
        }
        return URI.create(ISSUE_URL + "?title=" + encode(title(description)) + "&body=" + encode(text));
    }

    /** A first line for the issue, taken from what the player actually wrote. */
    private static String title(String description) {
        String first = description.strip().lines().findFirst().orElse("").strip();
        if (first.isEmpty()) return "[Bug] ";
        if (first.length() > TITLE_LENGTH) first = first.substring(0, TITLE_LENGTH).strip() + "…";
        return "[Bug] " + first;
    }

    private static List<String> changedSettings() {
        List<String> changed = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            boolean on = SynapticConfig.enabled(feature);
            if (on != feature.enabledByDefault()) {
                changed.add("- " + feature.label() + ": " + (on ? "ON" : "OFF"));
            }
        }
        return changed;
    }

    /**
     * The shape of the game around the bug. Deliberately vague about the server:
     * whether it was shared at all changes how a bug reproduces, but which
     * server it was does not.
     */
    private static String game() {
        Minecraft minecraft = Minecraft.getInstance();
        StringBuilder out = new StringBuilder();
        boolean local = minecraft.isLocalServer();
        out.append("Playing        ").append(local ? "singleplayer or LAN host" : "on a server").append('\n');
        int players = minecraft.getConnection() == null
            ? 0 : minecraft.getConnection().getOnlinePlayers().size();
        out.append("Players online ").append(players).append('\n');
        // Shared features behave differently for the one player allowed to change
        // them, and "it works for the host" is half of every report about them.
        out.append("This player    ").append(SynapticConfig.editable()
            ? "may change the settings" : "may not change the settings").append('\n');
        return out.toString();
    }

    /** A mod's version, or a note that it is not there — which is itself a clue. */
    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("(not installed)");
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
