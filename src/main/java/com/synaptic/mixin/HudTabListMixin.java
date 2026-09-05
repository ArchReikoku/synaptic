package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Shows the player list in singleplayer.
 * <p>
 * Vanilla hides it when the game is a local server with nobody else listed and
 * no scoreboard objective — reasonable when the list would say only your own
 * name, but the session table hangs off that same render and has plenty to say
 * on your own. The condition reads
 * {@code !isLocalServer() || listed > 1 || objective != null}, so answering "not
 * local" satisfies it outright.
 * <p>
 * Redirecting the question rather than rewriting the branch keeps this to the
 * one call and leaves the other two reasons vanilla might show the list intact.
 */
@Mixin(Hud.class)
public abstract class HudTabListMixin {
    @Redirect(method = "extractTabList",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z"))
    private boolean synaptic$showTabAlone(Minecraft minecraft) {
        if (SynapticConfig.enabled(Feature.SESSION_TAB)) return false;
        return minecraft.isLocalServer();
    }
}
