package com.synaptic.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What you look at while the candidate worlds are being built and photographed.
 * <p>
 * It covers the tour rather than showing it: without this the screen cycles
 * through half-loaded worlds for the better part of a minute with nothing to
 * say why. It steps aside for a moment around each shot — see
 * {@link LobbyClient} — because the capture photographs whatever is on screen,
 * this screen included.
 * <p>
 * Never a pause screen. In singleplayer a pausing screen stops the integrated
 * server, and a stopped server never advances the tour that this screen is
 * waiting for.
 */
public final class TourScreen extends Screen {
    private static final int BACKDROP = 0xE8000000;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int SUBTITLE = 0xFF9A9AA6;
    private static final int TRACK = 0xFF2A2A32;
    private static final int FILL = 0xFF5AC8FA;

    private static final int BAR_WIDTH = 220;
    private static final int BAR_HEIGHT = 4;

    public TourScreen() {
        super(Component.literal("Building worlds"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, BACKDROP);

        int total = Math.max(1, LobbyClient.slots());
        int done = Math.min(LobbyClient.captured(), total);
        int middle = this.height / 2;

        graphics.centeredText(this.font, Component.literal("Building worlds"),
            this.width / 2, middle - 26, TITLE);
        graphics.centeredText(this.font,
            Component.literal("Synaptic " + LobbyClient.session() + "." + LobbyClient.run()),
            this.width / 2, middle - 14, SUBTITLE);

        int left = (this.width - BAR_WIDTH) / 2;
        int top = middle + 4;
        graphics.fill(left, top, left + BAR_WIDTH, top + BAR_HEIGHT, TRACK);
        graphics.fill(left, top, left + BAR_WIDTH * done / total, top + BAR_HEIGHT, FILL);

        graphics.centeredText(this.font, Component.literal(done + " of " + total),
            this.width / 2, top + 12, SUBTITLE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
