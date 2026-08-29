package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A shared pet never turns on a player.
 * <p>
 * Treating everyone as the owner stops a pet defending itself against its own
 * side, but not every route to a player: a wolf still answers a stray hit, and
 * still joins in when the player it is following swings at someone. Refusing
 * the target outright is the one choke point all of those pass through.
 * <p>
 * Only tamed animals, and only players — a wolf will still go for a skeleton,
 * and a wild one will still go for you.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void synaptic$neverTargetPlayers(LivingEntity target, CallbackInfo ci) {
        if (target instanceof Player
            && (Object) this instanceof TamableAnimal pet && pet.isTame()
            && SynapticConfig.enabled(Feature.PETS)) {
            ci.cancel();
        }
    }
}
