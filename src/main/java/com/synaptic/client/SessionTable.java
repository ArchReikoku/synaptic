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

    private SessionTable() {
    }

    public static List<StatsSyncPayload.Row> rows() {
        return rows;
    }

    /** Which session these totals belong to, for the heading. */
    public static int session() {
        return session;
    }

    public static void accept(StatsSyncPayload payload) {
        rows = List.copyOf(payload.rows());
        session = payload.session();
    }
}
