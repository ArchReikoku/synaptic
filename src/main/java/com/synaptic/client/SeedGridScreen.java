package com.synaptic.client;

import com.synaptic.net.LobbyActionPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The candidate worlds, side by side, one keypress each.
 * <p>
 * Every player sees this — the same candidates, photographed from the same spot
 * facing the same way, so the picture in tile four is what all of them will open
 * their eyes to. Only the host's keys do anything; a guest's are read and
 * ignored, and the footer says so rather than leaving them wondering.
 */
public final class SeedGridScreen extends Screen {
    private static final int FACE = 0xFFEDEDED;
    private static final int DROP = 0xFF1E1E1E;
    private static final int DIM = 0xFFB0B0B8;
    private static final int DISABLED = 0xFF5A5A64;
    private static final int ACCENT = 0xFF5AC8FA;
    private static final int SCRIM = 0xC0000000;
    /** Radians. Enough of a lift to read as cut stone, well short of the logo's slant. */
    private static final float TILT = -0.045F;

    /** What the arrows have wound the grid to, before Enter commits it. */
    private int pendingGrid = LobbyClient.grid();

    public SeedGridScreen() {
        super(Component.literal("Next run"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int slots = Math.max(1, LobbyClient.slots());
        int columns = LobbyClient.grid();
        int rows = (int) Math.ceil((double) slots / columns);

        for (int i = 0; i < slots; i++) {
            int column = i % columns;
            int row = i / columns;
            int left = column * this.width / columns;
            int right = (column + 1) * this.width / columns;
            int top = row * this.height / rows;
            int bottom = (row + 1) * this.height / rows;
            drawTile(graphics, i, left, top, right, bottom);
        }
        drawFooter(graphics);
    }

    /**
     * One candidate, filling its tile.
     * <p>
     * Where a frame and its tile are not the same shape the overhang is trimmed
     * off the texture rather than the picture being squashed into place — a
     * preview that lies about the shape of the world is worse than one missing a
     * sliver at the edges.
     */
    private void drawTile(GuiGraphicsExtractor graphics, int slot, int left, int top, int right, int bottom) {
        PreviewFrames.Frame frame = PreviewFrames.get(slot);
        if (frame == null) {
            // The shot for this tile has not come back yet.
            graphics.fill(left, top, right, bottom, SCRIM);
            graphics.centeredText(this.font, Component.literal("..."),
                (left + right) / 2, (top + bottom) / 2, DIM);
        } else {
            float tileAspect = (float) (right - left) / (bottom - top);
            float frameAspect = (float) frame.width() / frame.height();
            float u = 0.0F;
            float v = 0.0F;
            if (frameAspect > tileAspect) u = (1.0F - tileAspect / frameAspect) / 2.0F;
            else v = (1.0F - frameAspect / tileAspect) / 2.0F;
            // The last two ints are the right and bottom EDGES, not a width and
            // a height — the method hands them straight to innerBlit as x1/y1.
            graphics.blit(frame.id(), left, top, right, bottom, u, 1.0F - u, v, 1.0F - v);
        }

        float scale = Math.clamp((bottom - top) / 42.0F, 2.0F, 14.0F);
        drawNumber(graphics, slot + 1, left + Math.round(scale * 2), top + Math.round(scale), scale);
    }

    /** A slot number: one dark copy behind a pale face, tilted slightly. */
    private void drawNumber(GuiGraphicsExtractor graphics, int slot, int x, int y, float scale) {
        String label = String.valueOf(slot);
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        pose.rotate(TILT);
        graphics.text(this.font, label, 1, 1, DROP, false);
        graphics.text(this.font, label, 0, 0, FACE, false);
        pose.popMatrix();
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        int top = this.height - 20;
        graphics.fill(0, top, this.width, this.height, SCRIM);
        graphics.text(this.font, "Synaptic " + LobbyClient.session() + "." + LobbyClient.run(),
            8, top + 6, FACE, false);

        if (!LobbyClient.mayPick()) {
            String waiting = "The host is choosing";
            graphics.text(this.font, waiting, this.width - 8 - this.font.width(waiting), top + 6, DIM, false);
            return;
        }

        // Right to left, so the grid control keeps the corner whatever else is
        // in the row.
        int x = this.width - 8;
        boolean changed = pendingGrid != LobbyClient.grid();
        String enter = "[Enter]";
        // Lit only when pressing it would do something. Greyed on the current
        // size says "this is already what you are looking at" without a word.
        x -= this.font.width(enter);
        graphics.text(this.font, enter, x, top + 6, changed ? ACCENT : DISABLED, false);

        String size = pendingGrid + "x" + pendingGrid;
        x -= this.font.width(size) + 6;
        graphics.text(this.font, size, x, top + 6, changed ? FACE : DIM, false);

        String rest = "[1-" + Math.max(1, LobbyClient.slots()) + "] pick    [R] new worlds    [↑ ↓]";
        x -= this.font.width(rest) + 10;
        graphics.text(this.font, rest, x, top + 6, DIM, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!LobbyClient.mayPick()) return super.keyPressed(event);
        int key = event.key();
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            int slot = key - GLFW.GLFW_KEY_1;
            if (slot < LobbyClient.slots()) send(LobbyActionPayload.PICK, slot);
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_R -> {
                send(LobbyActionPayload.RECYCLE, 0);
                return true;
            }
            // The arrows only move the number in the corner. Changing the grid
            // throws away every world on screen and generates a new batch, which
            // is far too much to happen on a stray arrow key.
            case GLFW.GLFW_KEY_UP -> {
                pendingGrid = Math.min(3, pendingGrid + 1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                pendingGrid = Math.max(1, pendingGrid - 1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (pendingGrid != LobbyClient.grid()) send(LobbyActionPayload.RESIZE, pendingGrid);
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    private static void send(int action, int value) {
        ClientPlayNetworking.send(new LobbyActionPayload(action, value));
    }

    /** A run has to be chosen; there is nothing to go back to. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
