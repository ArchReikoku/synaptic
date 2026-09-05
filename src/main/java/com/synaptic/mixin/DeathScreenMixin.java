package com.synaptic.mixin;

import com.synaptic.client.LobbyClient;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.NextRunPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds "Next run" to the death screen, beneath vanilla's own buttons.
 * <p>
 * Shown to everyone rather than only the host: the server refuses the request
 * from anyone else and says so, and a button that vanishes for some players is
 * more confusing than one that explains itself when pressed.
 * <p>
 * Placed by measuring the screen rather than by tracking vanilla's layout,
 * because the death screen builds its buttons in {@code init} and their
 * positions are not exposed. It sits low enough to clear both of them at the
 * sizes this version uses.
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {
    private DeathScreenMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void synaptic$addNextRun(CallbackInfo ci) {
        if (!SynapticConfig.enabled(Feature.RUN_RESET)) return;
        this.addRenderableWidget(Button.builder(
            Component.literal("Next run").withStyle(ChatFormatting.AQUA),
            button -> {
                ClientPlayNetworking.send(new NextRunPayload());
                // Straight to the loading screen rather than waiting for the
                // server to answer: generating the candidates takes seconds, and
                // a button that does nothing visible for that long reads as
                // broken.
                LobbyClient.showTourScreen(Minecraft.getInstance());
            })
            .bounds(this.width / 2 - 100, this.height / 4 + 120, 200, 20)
            .build());
    }
}
