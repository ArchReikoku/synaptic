package com.synaptic.report;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * The mod's logger, keeping its last few hundred lines in memory so a bug
 * report can carry them without asking anyone to find {@code latest.log}.
 * <p>
 * The methods are named and shaped exactly like the slf4j ones they replaced,
 * so a call site reads the same as it always did and swapping a class back out
 * costs one line per file. Everything still goes to the real log as well — this
 * is a tee, not a diversion, and a report is not a reason to lose a line from
 * the file an operator actually reads.
 * <p>
 * <b>This JVM only.</b> Most of the mod's logging happens server-side, so the
 * buffer a client can see holds everything in singleplayer and on a LAN host
 * (where the integrated server shares this JVM) and client-side lines alone
 * when connected to a dedicated server. That is a limit of where the report is
 * written from, not an oversight: the server's lines are in the server's log.
 * <p>
 * Throwables are recorded as their type and message, not their stack trace. A
 * trace is worth many lines of a small buffer and is already in {@code
 * latest.log} in full; what a report needs is the shape of what went wrong and
 * what led up to it.
 */
public final class SynapticLog {
    /** Roughly a session's worth of a mod this quiet, and a bounded paste. */
    private static final int CAPACITY = 200;
    private static final Logger DELEGATE = LoggerFactory.getLogger("synaptic");
    private static final SynapticLog INSTANCE = new SynapticLog();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> RECENT = new ArrayDeque<>(CAPACITY);

    private SynapticLog() {
    }

    public static SynapticLog get() {
        return INSTANCE;
    }

    public void info(String format, Object... arguments) {
        DELEGATE.info(format, arguments);
        record("INFO", format, arguments);
    }

    public void warn(String format, Object... arguments) {
        DELEGATE.warn(format, arguments);
        record("WARN", format, arguments);
    }

    public void error(String format, Object... arguments) {
        DELEGATE.error(format, arguments);
        record("ERROR", format, arguments);
    }

    /** The buffer, oldest first. A copy, so the caller cannot be caught mid-write. */
    public static List<String> recent() {
        synchronized (RECENT) {
            return new ArrayList<>(RECENT);
        }
    }

    private static void record(String level, String format, Object[] arguments) {
        // The same renderer slf4j uses, so the buffered line reads exactly like
        // the one in latest.log — including its rule that a trailing Throwable
        // is the exception rather than a value for the last {}.
        FormattingTuple rendered = MessageFormatter.arrayFormat(format, arguments);
        StringBuilder line = new StringBuilder()
            .append('[').append(LocalTime.now().format(CLOCK)).append(' ').append(level).append("] ")
            .append(rendered.getMessage());
        Throwable thrown = rendered.getThrowable();
        if (thrown != null) {
            line.append(" — ").append(thrown.getClass().getSimpleName());
            if (thrown.getMessage() != null) line.append(": ").append(thrown.getMessage());
        }
        String text = line.toString();
        // Written from the server thread, the client thread and the network
        // threads, and read from whichever one has the screen open.
        synchronized (RECENT) {
            if (RECENT.size() == CAPACITY) RECENT.removeFirst();
            RECENT.addLast(text);
        }
    }
}
