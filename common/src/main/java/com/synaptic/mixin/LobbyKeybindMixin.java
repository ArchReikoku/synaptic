package com.synaptic.mixin;

import com.synaptic.client.LobbyClient;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No gameplay keybinds while the seed lobby holds the player.
 * <p>
 * Movement keys alone are not enough to keep a spectator still. The hotbar keys
 * open the spectator menu, which teleports to other players. Attacking an
 * entity enters its view. And the perspective key puts the camera behind the
 * player's head for the next shot.
 * <p>
 * Escape, F1 and screen input do not go through here, so the pause menu and the
 * picker's own buttons still work.
 */
@Mixin(Minecraft.class)
public abstract class LobbyKeybindMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void synaptic$lockDuringLobby(CallbackInfo ci) {
        if (LobbyClient.active()) ci.cancel();
    }
}
