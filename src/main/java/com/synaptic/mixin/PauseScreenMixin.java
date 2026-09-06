package com.synaptic.mixin;

import java.util.ArrayList;
import java.util.List;

import com.synaptic.client.LobbyClient;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.NextRunPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
 * Puts New run in the pause menu, beside Back to Game.
 * <p>
 * The death screen already offers it, but a run is worth abandoning long before
 * it kills anybody — a spawn with no wood in sight is a lost run, and the only
 * way out was to die on purpose first.
 * <p>
 * Back to Game is halved and the new button takes the other half, rather than
 * pushing the whole menu down a row. Vanilla's own layout is a grid built in
 * {@code init}, and inserting into it means fighting a layout that has already
 * run; resizing the one button it puts on its own row does not.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    private PauseScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void synaptic$addNextRun(CallbackInfo ci) {
        if (!SynapticConfig.enabled(Feature.RUN_RESET)) return;
        // Host only, like every other way of starting a run: it throws away the
        // world everybody is playing. The server refuses it regardless.
        if (!SynapticConfig.editable()) return;

        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) buttons.add(button);
        }
        if (buttons.isEmpty()) return;

        // The widest one is Back to Game: vanilla gives it a row to itself at
        // full width while everything below it sits in pairs.
        Button widest = buttons.get(0);
        for (Button button : buttons) {
            if (button.getWidth() > widest.getWidth()) widest = button;
        }

        int gap = 4;
        int full = widest.getWidth();
        int half = (full - gap) / 2;
        int left = widest.getX();
        int y = widest.getY();
        widest.setWidth(half);

        Button next = Button.builder(Component.literal("New run"), button -> {
            ClientPlayNetworking.send(new NextRunPayload());
            // Straight to the loading screen: generating candidates takes
            // seconds, and a button that does nothing visible for that long
            // reads as broken.
            LobbyClient.showTourScreen(Minecraft.getInstance());
        }).bounds(left + half + gap, y, full - half - gap, widest.getHeight()).build();
        this.addRenderableWidget(next);
    }
}
