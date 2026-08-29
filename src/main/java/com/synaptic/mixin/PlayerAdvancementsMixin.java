package com.synaptic.mixin;

import com.synaptic.SynapticMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Passes an advancement one player earns on to everyone else.
 * <p>
 * Hooked at the criterion level rather than on completion, so progress towards a
 * multi-step advancement is shared as it happens instead of only the finished
 * article. Only a genuinely new grant propagates: award() returns false when the
 * player already had that criterion, which is also what stops the pass-along
 * from bouncing back and forth.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Inject(method = "award", at = @At("RETURN"))
    private void synaptic$shareWithEveryone(AdvancementHolder advancement, String criterion,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            SynapticMod.shareAdvancement(advancement, criterion);
        }
    }
}
