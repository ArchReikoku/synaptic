package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps mining progress when the held item changes mid-break.
 * <p>
 * This is load-bearing for the shared inventory, not just a convenience for
 * swapping tools: any change to the held stack counts, so another player picking
 * up items can reset a break that is already underway, or leave a block
 * unbreakable while they keep collecting.
 * <p>
 * Vanilla decides whether you are still working on the same block with both the
 * position AND the held stack:
 * <pre>pos.equals(destroyBlockPos) &amp;&amp; ItemStack.isSameItemSameComponents(held, destroyingItem)</pre>
 * Swapping tools fails the second half, so {@code startDestroyBlock} aborts the
 * old break and {@code continueDestroyBlock} restarts the progress bar from zero.
 * Comparing the position alone lets the accumulated progress stand; the per-tick
 * increment is recalculated from the currently held item anyway, so the new tool's
 * speed takes over immediately from wherever the old one left off.
 * <p>
 * {@code destroyingItem} is read nowhere else in the class, so leaving it stale
 * has no other effect.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Shadow
    private BlockPos destroyBlockPos;

    @Inject(method = "sameDestroyTarget", at = @At("HEAD"), cancellable = true)
    private void synaptic$ignoreToolSwap(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!SynapticConfig.enabled(Feature.KEEP_MINING_PROGRESS)) return;
        cir.setReturnValue(pos.equals(this.destroyBlockPos));
    }
}
