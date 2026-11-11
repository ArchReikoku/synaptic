package com.synaptic.mixin;

import com.synaptic.client.LobbyClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Other players are not drawn at all while the seed lobby holds this one. That
 * covers body, head, name tag and shadow.
 * <p>
 * Everyone is in spectator during the lobby, and spectators see each other as
 * floating heads. Everyone is also parked on the same block of every
 * candidate, so without this every photograph has the other players in it.
 * <p>
 * Done on the client, as a render decision. Server-side invisibility does not
 * hide a spectator from another spectator, and it would still leave the name
 * tag.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class LobbyPlayerRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void synaptic$hidePlayersDuringLobby(E entity, Frustum frustum,
            double camX, double camY, double camZ, float partialTick, CallbackInfoReturnable<Boolean> cir) {
        if (!LobbyClient.active()) return;
        if (entity instanceof Player && entity != Minecraft.getInstance().player) {
            cir.setReturnValue(false);
        }
    }
}
