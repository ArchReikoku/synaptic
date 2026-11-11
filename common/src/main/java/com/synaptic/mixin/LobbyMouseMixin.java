package com.synaptic.mixin;

import com.synaptic.client.LobbyClient;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The camera stays where the server pointed it while the seed lobby holds the
 * player. Every tile is shot facing the same way, so a nudge of the mouse in the
 * gap before the shutter would leave one tile looking somewhere else.
 * <p>
 * The accumulated deltas are cleared by the caller, not here. Skipping the turn
 * throws the movement away instead of saving it up, so the view does not jump
 * when the lobby lets go.
 */
@Mixin(MouseHandler.class)
public abstract class LobbyMouseMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void synaptic$lockDuringLobby(double movementTime, CallbackInfo ci) {
        if (LobbyClient.active()) ci.cancel();
    }
}
