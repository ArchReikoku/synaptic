package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.NeutralMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * One player provokes a neutral mob, the whole group is fair game.
 * <p>
 * Vanilla already has a shape for this — {@code isAngryAt} answers yes to any
 * player when {@code isAngryAtAllPlayers} does — but reaching it normally needs
 * the universalAnger gamerule <em>and</em> the mob to have been hurt by a player
 * specifically, which is the only case ResetUniversalAngerTargetGoal fires on.
 * That misses every other way a mob turns on you: an iron golem answering for a
 * villager you hit, a bee whose nest you broke. Answering yes for any angry mob
 * covers all of them, whatever made it angry.
 * <p>
 * <b>Range takes care of itself.</b> Every caller of {@code isAngryAt} — Bee,
 * IronGolem, PolarBear, Wolf, EnderMan, ZombifiedPiglin — asks it from inside a
 * targeting goal's predicate, and those goals only ever look within the mob's own
 * follow range. So this widens <em>who counts</em> among the players a mob is
 * already considering; it never widens how far it looks. A player across the map
 * is not spared by a distance check here, they are simply never asked about.
 * <p>
 * An interface mixin, because everything about persistent anger lives in default
 * methods on {@link NeutralMob} — there is no shared class underneath the six
 * mobs that implement it.
 */
@Mixin(NeutralMob.class)
public interface NeutralMobMixin {
    @Inject(method = "isAngryAtAllPlayers", at = @At("HEAD"), cancellable = true)
    private void synaptic$angerIsShared(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (SynapticConfig.enabled(Feature.MOB_ANGER) && ((NeutralMob) this).isAngry()) {
            cir.setReturnValue(true);
        }
    }
}
