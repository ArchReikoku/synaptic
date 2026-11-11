package com.synaptic.mixin;

import com.synaptic.client.LobbyClient;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No walking, flying, rising or sinking while the seed lobby holds the player.
 * <p>
 * Done after vanilla has read the keys rather than instead of it, so the keys
 * themselves stay untouched. When the lobby lets go, the next tick reads them
 * fresh, and nothing is left stuck down.
 */
@Mixin(KeyboardInput.class)
public abstract class LobbyInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void synaptic$lockDuringLobby(CallbackInfo ci) {
        if (!LobbyClient.active()) return;
        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
