package com.synaptic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The candidate worlds as pictures, one slot per grid tile.
 * <p>
 * Nine live worlds cannot be rendered at once — a client holds one level — so
 * the grid is nine frames captured one at a time while the server walks the
 * player through the candidates, and kept until the batch is thrown away.
 * <p>
 * The readback is vanilla's own screenshot path, which hands the pixels back
 * asynchronously once the GPU is done with them. A slot therefore fills a frame
 * or two after it is asked for, and the grid has to cope with a hole until it
 * does.
 */
public final class PreviewFrames {
    /** Size travels with the picture: the grid letterboxes rather than stretches. */
    public record Frame(Identifier id, int width, int height) {}

    private static final int MAX = 9;
    private static final Frame[] frames = new Frame[MAX];
    private static int generation;

    private PreviewFrames() {
    }

    public static Frame get(int slot) {
        return slot >= 0 && slot < MAX ? frames[slot] : null;
    }

    /** Drop the batch. Textures are released so a long lobby does not leak them. */
    public static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        for (int i = 0; i < MAX; i++) {
            if (frames[i] != null) minecraft.getTextureManager().release(frames[i].id());
            frames[i] = null;
        }
        generation++;
    }

    /**
     * Take what is on screen now and keep it as the given tile.
     * <p>
     * The batch is stamped so a shot still in flight when the batch is thrown
     * away is dropped on arrival rather than landing in the new grid.
     */
    public static void capture(int slot) {
        if (slot < 0 || slot >= MAX) return;
        Minecraft minecraft = Minecraft.getInstance();
        int batch = generation;
        Identifier id = Identifier.fromNamespaceAndPath("synaptic", "preview_" + slot + "_" + batch);
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> {
            if (batch != generation) {
                image.close();
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            // The texture takes ownership of the image, so its size is read first.
            minecraft.getTextureManager().register(id, new DynamicTexture(id::toString, image));
            frames[slot] = new Frame(id, width, height);
        });
    }
}
