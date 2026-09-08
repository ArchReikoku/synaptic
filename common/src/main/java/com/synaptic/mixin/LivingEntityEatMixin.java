package com.synaptic.mixin;

import com.synaptic.stats.SessionStats;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts a meal for the session table.
 * <p>
 * Caught at the moment a use action finishes, and at HEAD specifically: the held
 * stack is still the thing being eaten there, and a tick later it is a crumb or
 * an empty bowl. Anything carrying food is counted — potions and milk go through
 * the same path but have no food component, so they are not meals.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEatMixin {
    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void synaptic$countMeal(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player
            && player.getUseItem().has(DataComponents.FOOD)) {
            SessionStats.foodEaten(player);
        }
    }
}
