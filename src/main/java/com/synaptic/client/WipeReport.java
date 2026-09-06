package com.synaptic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.synaptic.net.WipeReportPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * The run's obituary as this client last heard it.
 * <p>
 * Kept after the screen closes rather than cleared with it: the run is over
 * until somebody starts the next one, and the report is what that death screen
 * is about however many times it is reopened.
 * <p>
 * The blows arrive as figures and are worded here, so the report says the same
 * thing on every screen and says it again after a rejoin. It used to be drawn
 * from a copy of chat, which meant it showed whatever had been said — including
 * the join hint, and nothing at all to a player who reconnected.
 */
public final class WipeReport {
    private static final String HEART = "❤";

    private static volatile UUID victim;
    private static volatile String name = "";
    private static volatile String cause = "";
    private static volatile List<Component> lines = List.of();

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

    /**
     * The blows that led to the death, oldest first, with the death itself last.
     * Empty when there is nothing to show — a death by command leaves no damage
     * behind it — and the screen draws no panel at all in that case.
     */
    public static List<Component> lines() {
        return lines;
    }

    public static boolean present() {
        return victim != null;
    }

    /**
     * Forget the death on the way out of a world. Kept across screens, but not
     * across worlds — a report is about the run it belongs to, and the next
     * world's first death screen should not open on the last world's obituary.
     */
    public static void clear() {
        victim = null;
        name = "";
        cause = "";
        lines = List.of();
    }

    public static void accept(WipeReportPayload payload) {
        if (!payload.present()) {
            clear();
            return;
        }
        victim = payload.victim();
        name = payload.name();
        cause = payload.cause();

        List<Component> built = new ArrayList<>(payload.lines().size() + 1);
        for (WipeReportPayload.Line blow : payload.lines()) {
            built.add(describe(blow));
        }
        // The death itself closes the list, plainly: the lines above are what
        // happened to the bar, and this is the one that ended it.
        if (!payload.cause().isEmpty()) {
            built.add(Component.literal(payload.cause()).withStyle(ChatFormatting.WHITE));
        }
        lines = List.copyOf(built);
    }

    /** Worded and coloured exactly as the damage chat words it, so the two match. */
    private static Component describe(WipeReportPayload.Line blow) {
        return Component.literal(blow.name()).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" took ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(hearts(blow.damage()) + HEART).withStyle(ChatFormatting.RED))
            .append(Component.literal(" damage from ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(blow.cause()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(hearts(blow.left()) + HEART).withStyle(ChatFormatting.RED))
            .append(Component.literal(" left)").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Damage is counted in half-hearts internally; the report speaks in hearts. */
    private static String hearts(float healthPoints) {
        float value = Math.round(healthPoints / 2.0F * 10.0F) / 10.0F;
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }
}
