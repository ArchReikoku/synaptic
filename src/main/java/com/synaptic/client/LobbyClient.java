package com.synaptic.client;

import com.synaptic.net.FrameReadyPayload;
import com.synaptic.net.LobbyStatePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * This client's side of the seed lobby.
 * <p>
 * Holds what the server last said and reacts to it: cover the tour with a
 * loading screen, take the photographs, then put the grid up once they are in.
 * <p>
 * The photographs are the awkward part. A capture takes whatever is on screen,
 * screens included — a death screen photographed nine times is what that looks
 * like when it goes wrong — so the loading screen is pulled down for a moment
 * around each shot and put straight back. The gaps either side are counted in
 * ticks rather than taken on trust: the frame being photographed is the last one
 * drawn, so the screen has to have been gone for a frame before the shutter, and
 * must not come back until the copy has been made.
 */
public final class LobbyClient {
    /**
     * Ticks with the screen down before the shot.
     * <p>
     * Long enough for the screen's background blur to fade as well as for the
     * screen itself to go: at two ticks the blur was still on the frame and
     * every tile came out washed out, with ghosts of the loading screen in it.
     */
    private static final int HIDE_TICKS = 6;
    /** Ticks before the screen returns, so it cannot land in the copy. */
    private static final int RESTORE_TICKS = 2;
    /** Ticks to wait for terrain before shooting anyway. A dark tile beats a stuck tour. */
    private static final int READY_TIMEOUT = 300;
    /**
     * Chunks either side of the player that must have arrived before the shot.
     * <p>
     * Waiting only for the one underfoot is what produced tiles with no land in
     * sight: the ground you are standing on is there, and everything you can
     * actually see is not.
     */
    private static final int READY_RADIUS = 3;
    /**
     * Ticks between the terrain arriving and the shutter.
     * <p>
     * Chunk data landing is not the same as chunk data being drawn — meshes are
     * still being built, and a shader pack has its own work to do after that.
     */
    private static final int GRACE_TICKS = 20;

    private static volatile int state = LobbyStatePayload.CLOSED;
    private static volatile int grid = 2;
    private static volatile int slots;
    private static volatile int session;
    private static volatile int run;
    private static volatile boolean mayPick;
    private static volatile int captured;

    /** Stages of photographing one tile. */
    private static final int WAITING = 0;
    private static final int SETTLING = 1;
    private static final int HIDING = 2;
    private static final int RESTORING = 3;

    private static int pendingSlot = -1;
    private static int phase = WAITING;
    private static int countdown;
    private static int waited;
    /** The player's own HUD setting, put back when the lobby lets go of it. */
    private static Boolean heldHideGui;

    private LobbyClient() {
    }

    public static int grid() {
        return grid;
    }

    public static int slots() {
        return slots;
    }

    public static int session() {
        return session;
    }

    public static int run() {
        return run;
    }

    public static boolean mayPick() {
        return mayPick;
    }

    /** How many tiles have been photographed, for the loading bar. */
    public static int captured() {
        return captured;
    }

    public static void accept(LobbyStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        state = payload.state();
        grid = payload.grid();
        slots = payload.slots();
        session = payload.session();
        run = payload.run();
        mayPick = payload.mayPick();

        switch (state) {
            case LobbyStatePayload.TOURING -> {
                // Frames are shot straight off the screen, so the HUD has to go
                // or every tile carries a hotbar across its bottom edge.
                hideHud(minecraft, true);
                PreviewFrames.clear();
                captured = 0;
                showTourScreen(minecraft);
            }
            case LobbyStatePayload.GRID -> {
                cancelCapture();
                hideHud(minecraft, false);
                if (!(minecraft.gui.screen() instanceof SeedGridScreen)) {
                    minecraft.setScreenAndShow(new SeedGridScreen());
                }
            }
            default -> {
                cancelCapture();
                hideHud(minecraft, false);
                PreviewFrames.clear();
                heldHideGui = null;
                // Only closes our own screens: a player who opened something else
                // in the meantime should keep it.
                if (minecraft.gui.screen() instanceof SeedGridScreen
                    || minecraft.gui.screen() instanceof TourScreen) {
                    minecraft.setScreenAndShow(null);
                }
            }
        }
    }

    /**
     * Let go of everything when the connection does.
     * <p>
     * All of this is static and the client is not restarted between worlds, so a
     * player who leaves mid-tour keeps {@code TOURING} and comes back to a
     * loading screen the new server has no idea it is showing: nothing sends the
     * lobby state to a client that is not being toured, so the bar sits at "3 of
     * 4" forever over a world that is perfectly playable underneath it.
     * <p>
     * The HUD matters as much as the screen. It is hidden for the photographs,
     * and left hidden it would follow the player into the next world with no way
     * back short of a keybind they have no reason to press.
     */
    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        cancelCapture();
        // Straight back to what the player had, rather than through hideHud:
        // with the lobby over, the held value is the whole answer.
        if (heldHideGui != null) {
            if (minecraft.gui.hud.isHidden() != heldHideGui) minecraft.gui.hud.toggle();
            heldHideGui = null;
        }
        PreviewFrames.clear();
        state = LobbyStatePayload.CLOSED;
        captured = 0;
        slots = 0;
        mayPick = false;
        if (minecraft.gui.screen() instanceof SeedGridScreen
            || minecraft.gui.screen() instanceof TourScreen) {
            minecraft.setScreenAndShow(null);
        }
    }

    /** Opened locally the moment Next run is pressed, before the server has answered. */
    public static void showTourScreen(Minecraft minecraft) {
        if (!(minecraft.gui.screen() instanceof TourScreen)) {
            minecraft.setScreenAndShow(new TourScreen());
        }
    }

    /** The server offers the shutter. The wait for the world starts here. */
    public static void requestCapture(int slot) {
        pendingSlot = slot;
        phase = WAITING;
        countdown = 0;
        waited = 0;
    }

    /**
     * Walks one tile through waiting, settling, hiding, shooting and restoring.
     * <p>
     * The loading screen stays up for all of it bar the shot itself, so the
     * player sees a progress bar rather than worlds flickering past.
     */
    public static void clientTick(Minecraft minecraft) {
        if (pendingSlot < 0) return;
        if (countdown > 0) {
            countdown--;
            return;
        }
        switch (phase) {
            case WAITING -> {
                // The shutter waits on the world, not on a stopwatch.
                if (ready(minecraft)) {
                    phase = SETTLING;
                    countdown = GRACE_TICKS;
                } else if (++waited >= READY_TIMEOUT) {
                    phase = SETTLING;
                }
            }
            case SETTLING -> {
                if (minecraft.gui.screen() instanceof TourScreen) minecraft.setScreenAndShow(null);
                phase = HIDING;
                countdown = HIDE_TICKS;
            }
            case HIDING -> {
                PreviewFrames.capture(pendingSlot);
                captured = Math.max(captured, pendingSlot + 1);
                phase = RESTORING;
                countdown = RESTORE_TICKS;
            }
            default -> {
                int slot = pendingSlot;
                pendingSlot = -1;
                phase = WAITING;
                if (state == LobbyStatePayload.TOURING) showTourScreen(minecraft);
                // Only now: the server holds this candidate until it hears back,
                // so answering early would move the world out from under the
                // tile still being developed.
                ClientPlayNetworking.send(new FrameReadyPayload(slot));
            }
        }
    }

    /**
     * Whether there is a world here to photograph yet.
     * <p>
     * A ring of chunks rather than the one underfoot: the ground you are
     * standing on arrives first and everything you can actually see arrives
     * after, which is why waiting on that one chunk produced tiles with no land
     * in sight. Any screen other than the tour's own also means no — that is
     * how "Loading terrain" got into a tile.
     */
    private static boolean ready(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) return false;
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof TourScreen)) {
            return false;
        }
        int centreX = minecraft.player.getBlockX() >> 4;
        int centreZ = minecraft.player.getBlockZ() >> 4;
        for (int x = -READY_RADIUS; x <= READY_RADIUS; x++) {
            for (int z = -READY_RADIUS; z <= READY_RADIUS; z++) {
                if (!minecraft.level.getChunkSource().hasChunk(centreX + x, centreZ + z)) return false;
            }
        }
        return true;
    }

    private static void cancelCapture() {
        pendingSlot = -1;
        phase = WAITING;
        countdown = 0;
        waited = 0;
    }

    /**
     * The HUD is a toggle rather than a setting, so what it was is remembered on
     * the way in — a player who plays with it already hidden should still have
     * it hidden when the lobby lets go.
     */
    private static void hideHud(Minecraft minecraft, boolean hidden) {
        if (heldHideGui == null) heldHideGui = minecraft.gui.hud.isHidden();
        boolean wanted = hidden || heldHideGui;
        if (minecraft.gui.hud.isHidden() != wanted) minecraft.gui.hud.toggle();
    }
}
