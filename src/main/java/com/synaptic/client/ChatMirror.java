package com.synaptic.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.network.chat.Component;

/**
 * A copy of the last few things said, kept for the death screen.
 * <p>
 * The real chat is drawn by the HUD, which sits behind every screen, so a death
 * screen either buries it or has to leave a hole in itself to let it through.
 * Keeping our own copy means the report can lay chat out where it belongs —
 * under the table, in the middle, in the report's own styling — instead of
 * working around wherever the HUD happened to put it.
 * <p>
 * Messages arrive with their formatting intact and are drawn that way, so the
 * damage lines keep their colours here exactly as they had them in chat.
 */
public final class ChatMirror {
    /** Enough to read the run's last moments; more would crowd the report. */
    private static final int KEPT = 10;

    private static final Deque<Component> lines = new ArrayDeque<>();

    private ChatMirror() {
    }

    public static synchronized void record(Component line) {
        if (line == null) return;
        lines.addLast(line);
        while (lines.size() > KEPT) lines.removeFirst();
    }

    /** Oldest first, so the newest reads at the bottom the way chat does. */
    public static synchronized List<Component> lines() {
        return new ArrayList<>(lines);
    }

    /** Dropped on disconnect: the next world's report should not open with the last one's. */
    public static synchronized void clear() {
        lines.clear();
    }
}
