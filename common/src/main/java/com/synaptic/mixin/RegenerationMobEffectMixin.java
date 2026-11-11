package com.synaptic.mixin;

import com.synaptic.SynapticMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A shared Regeneration effect heals the group once, not once per player.
 * <p>
 * With effects shared, everyone carries the same Regeneration and it ticks on
 * every one of them in the same game tick; with health pooled, every one of
 * those heals is summed. Everyone but the healer (see
 * {@link SynapticMod#healsForGroup}) skips the heal and keeps the effect —
 * answering true is what keeps it running.
 * <p>
 * Named by string because the class is package-private.
 */
@Mixin(targets = "net.minecraft.world.effect.RegenerationMobEffect")
public abstract class RegenerationMobEffectMixin {
    @Inject(method = "applyEffectTick", at = @At("HEAD"), cancellable = true)
    private void synaptic$healOnceForTheGroup(ServerLevel level, LivingEntity entity, int amplifier,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof ServerPlayer player && !SynapticMod.healsForGroup(player, true)) {
            cir.setReturnValue(true);
        }
    }
}
