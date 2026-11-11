package com.synaptic.world;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Where a player was, and how, before the lobby took them away.
 * <p>
 * Touring is destructive to a player's situation: they are put in spectator and
 * walked through worlds that are about to be deleted. That is fine while the
 * lobby is going to finish, because picking a run replaces all of it anyway.
 * It is not fine when the lobby does not finish — the host cancels, or somebody
 * closes the game partway through — and the only record of what they were doing
 * lived in a field on the server that is now gone.
 * <p>
 * So it is written down instead, and written down where it survives a restart.
 * A player who logged out mid-tour comes back to a candidate dimension that no
 * longer exists, in spectator, with nothing to say what they were before. That
 * was exactly the bug: alive, touring, quit, and back as a ghost.
 *
 * @param dimension the run they were playing, by name
 * @param dead      whether the run was already over for them — a lobby opened
 *                  from the death screen is the ordinary case, and putting them
 *                  back means putting them back on that screen
 */
public record HomePoint(String dimension, double x, double y, double z,
                        float yaw, float pitch, GameType mode, boolean dead) {

    private static final String SEPARATOR = " ";

    /** Where this player is standing now, to be gone back to later. */
    public static HomePoint of(ServerPlayer player, String dimension) {
        return new HomePoint(dimension, player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot(), player.gameMode(), player.isDeadOrDying());
    }

    public String encode() {
        return String.join(SEPARATOR, dimension, String.valueOf(x), String.valueOf(y),
            String.valueOf(z), String.valueOf(yaw), String.valueOf(pitch),
            mode.getName(), String.valueOf(dead));
    }

    /** Null for anything unreadable: a bad line means a lost position, not a broken load. */
    public static HomePoint decode(String line) {
        if (line == null) return null;
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length < 8) return null;
        try {
            GameType mode = GameType.byName(parts[6], GameType.SURVIVAL);
            return new HomePoint(parts[0], Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]), Float.parseFloat(parts[5]),
                mode, Boolean.parseBoolean(parts[7]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
