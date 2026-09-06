package com.synaptic.client;

import java.util.List;

import com.synaptic.net.StatsSyncPayload;

/**
 * The client's copy of the session table, as last sent by the server.
 * <p>
 * Held statically because the tab overlay is reached through a mixin with
 * nowhere to hand state to, and read on the render thread while the network
 * thread replaces it — so the list is swapped whole rather than edited in place.
 */
public final class SessionTable {
    private static volatile List<StatsSyncPayload.Row> rows = List.of();
    private static volatile int session = 1;
    private static volatile int run;
    private static volatile int seconds;
    private static volatile int total;

    private SessionTable() {
    }

    public static List<StatsSyncPayload.Row> rows() {
        return rows;
    }

    /** Which session these totals belong to, for the heading. */
    public static int session() {
        return session;
    }

    /** Which run of that session is being played, for the death screen's heading. */
    public static int run() {
        return run;
    }

    /** How long this run has been played, in seconds. */
    public static int seconds() {
        return seconds;
    }

    /** Time on the current try. */
    public static String clock() {
        return format(seconds);
    }

    /** Time on this world across every try, which no reset clears. */
    public static String totalClock() {
        return format(total);
    }

    /** "12:34", or "1:02:33" once the count passes the hour. */
    public static String format(int count) {
        String body = String.format("%02d:%02d", (count / 60) % 60, count % 60);
        return count >= 3600 ? (count / 3600) + ":" + body : body;
    }

    public static void accept(StatsSyncPayload payload) {
        rows = List.copyOf(payload.rows());
        session = payload.session();
        run = payload.run();
        seconds = payload.seconds();
        total = payload.total();
    }
}
