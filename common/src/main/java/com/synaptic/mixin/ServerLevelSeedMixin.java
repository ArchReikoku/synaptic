package com.synaptic.mixin;

import com.synaptic.world.RuntimeDimension;
import com.synaptic.world.SeededLevel;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a level carry a seed of its own instead of the save's.
 * <p>
 * A world file holds exactly one seed, and every dimension in it reads that one
 * value — the nether and the end are different because their generator settings
 * differ, not their seed. Terrain generation asks the level for it, so this is
 * the single point where a per-run seed can be introduced, and everything
 * downstream (noise, biomes, structure placement) follows from the answer.
 * <p>
 * The pending value exists because the chunk source is built inside the level's
 * own constructor and asks this question while it runs — before there is a
 * level object to have stored anything on. {@link RuntimeDimension} parks the
 * seed for the length of that call and stamps it onto the finished level.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeedMixin implements SeededLevel {
    @Unique
    private Long synaptic$seed;

    @Override
    public void synaptic$setSeed(long seed) {
        this.synaptic$seed = seed;
    }

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void synaptic$useRunSeed(CallbackInfoReturnable<Long> cir) {
        Long seed = this.synaptic$seed != null ? this.synaptic$seed : RuntimeDimension.pendingSeed();
        if (seed != null) cir.setReturnValue(seed);
    }
}
