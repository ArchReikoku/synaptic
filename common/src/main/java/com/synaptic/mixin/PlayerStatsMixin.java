package com.synaptic.mixin;

import com.synaptic.stats.SessionStats;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts hunger spent and experience earned, per player, for the session table.
 * <p>
 * Both are taken at the player rather than further down: {@code FoodData} has no
 * idea whose bar it is, which with a shared hunger bar would make every point of
 * exertion unattributable. Exhaustion is counted before Split Hunger divides it
 * (see FoodDataMixin), so the figure is what that player actually exerted rather
 * than the share the group ended up paying.
 */
@Mixin(Player.class)
public abstract class PlayerStatsMixin {
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"))
    private void synaptic$countExhaustion(float exhaustion, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            SessionStats.exhaustionSpent(player, exhaustion);
        }
    }

    @Inject(method = "giveExperiencePoints", at = @At("HEAD"))
    private void synaptic$countExperience(int points, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) {
            SessionStats.experienceGained(player, points);
        }
    }
}
