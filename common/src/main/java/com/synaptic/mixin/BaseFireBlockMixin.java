package com.synaptic.mixin;

import com.synaptic.world.RunManager;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a portal be lit inside a run.
 * <p>
 * Fire only becomes a portal in a dimension vanilla recognises, and it decides
 * that by name: {@code OVERWORLD} or {@code NETHER}, nothing else. A run is
 * neither — it is a dimension of its own — so a frame lit inside one simply
 * burned, which is what a portal that "does not work" looks like from the
 * player's side.
 * <p>
 * The answer is widened to a run and its own nether, and to nothing else. A
 * run's end is deliberately left out, exactly as the real end is: portals are
 * not lit there.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
    @Inject(method = "inPortalDimension", at = @At("RETURN"), cancellable = true)
    private static void synaptic$runsCountToo(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && RunManager.allowsPortals(level)) {
            cir.setReturnValue(true);
        }
    }
}
