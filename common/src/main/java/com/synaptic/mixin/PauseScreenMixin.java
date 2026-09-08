package com.synaptic.mixin;

import java.util.ArrayList;
import java.util.List;

import com.synaptic.client.SynapticSettingsScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts one Synaptic button at the top of the pause menu, above Back to Game.
 * <p>
 * One button, not a row of them. Everything the mod offers is on the other side
 * of it, and the pause menu is somewhere a player already goes when they want
 * the game to stop and something to change — it needs to say Synaptic is here,
 * not list what Synaptic does.
 * <p>
 * Positioned from what vanilla laid out rather than from numbers of our own.
 * Vanilla gives its title a 50-pixel bottom padding, so there is a band of empty
 * screen between the title and Back to Game that is already the right height for
 * a button; sitting in it costs nothing and moves nothing. Measuring the topmost
 * button to find that band means the arithmetic survives vanilla re-spacing its
 * own menu, which a hard-coded y would not.
 * <p>
 * Nothing is inserted into vanilla's grid. That grid is built and arranged in
 * {@code init} before this runs, and adding a cell to a layout that has already
 * run means re-running it. An earlier version dodged that by halving Back to
 * Game and taking the other half, which shrinks the most-pressed button in the
 * game to make room.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    private static final int GAP = 4;

    private PauseScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void synaptic$addSynapticButton(CallbackInfo ci) {
        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) buttons.add(button);
        }
        // No buttons means no pause menu — the screen is up for something else.
        if (buttons.isEmpty()) return;

        // The widest is Back to Game, which vanilla gives a row to itself at full
        // width while everything below it sits in pairs; the topmost is where the
        // menu starts, and so where the band above it ends.
        Button widest = buttons.get(0);
        Button topmost = buttons.get(0);
        for (Button button : buttons) {
            if (button.getWidth() > widest.getWidth()) widest = button;
            if (button.getY() < topmost.getY()) topmost = button;
        }

        // Half the menu's width, centred: wide enough to read as a way in,
        // narrow enough not to read as another row of the menu itself.
        int height = widest.getHeight();
        int width = widest.getWidth() / 2;
        int y = Math.max(GAP, topmost.getY() - height - GAP);

        // Red, so the one button on this screen that vanilla did not put there
        // does not read as one that vanilla did. The colour is on the label
        // rather than on the widget: a style colour wins over the shade the
        // button would otherwise draw its message in, which is the only way to
        // recolour stock button text without drawing the button ourselves.
        //
        // Handed this screen to come back to, so the pause menu is still there
        // afterwards rather than dropping the player into the game.
        this.addRenderableWidget(Button.builder(
            Component.literal("Synaptic").withStyle(ChatFormatting.RED),
            button -> Minecraft.getInstance().setScreenAndShow(new SynapticSettingsScreen(this)))
            .bounds((this.width - width) / 2, y, width, height).build());
    }
}
