package com.synaptic.client;

import java.util.UUID;

import com.synaptic.net.WipeReportPayload;

/**
 * The last death that ended a run, as this client was told it.
 * <p>
 * Kept after the screen closes rather than cleared with it: the run is over
 * until somebody starts the next one, and the report is what that death screen
 * is about however many times it is reopened.
 */
public final class WipeReport {
    private static volatile UUID victim;
    private static volatile String name = "";
    private static volatile String cause = "";

    private WipeReport() {
    }

    public static UUID victim() {
        return victim;
    }

    public static String name() {
        return name;
    }

    /** The finished sentence, as the game itself would have written it in chat. */
    public static String cause() {
        return cause;
    }

    public static boolean present() {
        return victim != null;
    }

    public static void accept(WipeReportPayload payload) {
        victim = payload.victim();
        name = payload.name();
        cause = payload.cause();
    }
}
