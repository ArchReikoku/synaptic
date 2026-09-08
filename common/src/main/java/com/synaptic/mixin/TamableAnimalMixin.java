package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Everyone's pets belong to everyone.
 * <p>
 * Vanilla funnels almost every owner-only behaviour through
 * {@code isOwnedBy} — sitting and standing, collar dyeing, wolf armour,
 * whether a pet will take food, and which mobs its goals refuse to fight.
 * Answering yes for any player is therefore the whole of "interact with someone
 * else's pet as if it were yours", in one place, rather than a patch per
 * interaction.
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {
    @Shadow
    public abstract boolean isTame();

    @Inject(method = "isOwnedBy", at = @At("HEAD"), cancellable = true)
    private void synaptic$sharePet(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Player && isTame() && SynapticConfig.enabled(Feature.PETS)) {
            cir.setReturnValue(true);
        }
    }
}
