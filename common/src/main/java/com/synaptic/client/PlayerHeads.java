package com.synaptic.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/**
 * A player's face, taken off their skin.
 * <p>
 * A skin sheet is 64 by 64 with the face at (8,8) and the hat layer over it at
 * (40,8), so the coordinates below are those pixels as fractions of the whole
 * sheet. Anyone this client has never met — everyone offline — falls back to the
 * skin their id would have been given by default, so no row is ever headless.
 */
public final class PlayerHeads {
    private static final float FACE_MIN = 8 / 64.0F;
    private static final float FACE_MAX = 16 / 64.0F;
    private static final float HAT_MIN = 40 / 64.0F;
    private static final float HAT_MAX = 48 / 64.0F;

    private PlayerHeads() {
    }

    /**
     * @param veil colour laid over the face afterwards, or zero for none. Used to
     *             grey out a player who is not here.
     */
    public static void draw(GuiGraphicsExtractor graphics, UUID id, int x, int y, int size, int veil) {
        Minecraft minecraft = Minecraft.getInstance();
        PlayerInfo info = minecraft.getConnection() == null
            ? null : minecraft.getConnection().getPlayerInfo(id);
        Identifier skin = info != null
            ? info.getSkin().body().texturePath()
            : DefaultPlayerSkin.get(id).body().texturePath();

        int right = x + size;
        int bottom = y + size;
        graphics.blit(skin, x, y, right, bottom, FACE_MIN, FACE_MAX, FACE_MIN, FACE_MAX);
        graphics.blit(skin, x, y, right, bottom, HAT_MIN, HAT_MAX, FACE_MIN, FACE_MAX);
        if (veil != 0) graphics.fill(x, y, right, bottom, veil);
    }
}
