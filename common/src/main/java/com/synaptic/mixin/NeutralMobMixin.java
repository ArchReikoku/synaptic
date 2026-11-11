package com.synaptic.mixin;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.player.Player;
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
 * villager you hit, a bee whose nest you broke. Answering yes for any mob angry
 * at a player covers all of them, however it came to be angry.
 * <p>
 * <b>Range takes care of itself.</b> Every caller of {@code isAngryAt} — Bee,
 * IronGolem, PolarBear, Wolf, EnderMan, ZombifiedPiglin — asks it from inside a
 * targeting goal's predicate, and those goals only ever look within the mob's own
 * follow range. So this widens <em>who counts</em> among the players a mob is
 * already considering; it never widens how far it looks. A player across the map
 * is not spared by a distance check here, they are simply never asked about.
 * <p>
 * <b>Only anger a player earned.</b> Being angry is not by itself the group's
 * business — a mob's persistent anger target is set from whatever it is
 * currently fighting, player or not, so an enderman the ender dragon swatted is
 * "angry" in exactly the same sense as one somebody stared at. Answering yes for
 * every angry mob turned the dragon's own collateral onto the players standing
 * near it. The question this has to ask is not "is it angry" but "is it angry at
 * one of us".
 * <p>
 * An interface mixin, because everything about persistent anger lives in default
 * methods on {@link NeutralMob} — there is no shared class underneath the six
 * mobs that implement it.
 */
@Mixin(NeutralMob.class)
public interface NeutralMobMixin {
    @Inject(method = "isAngryAtAllPlayers", at = @At("HEAD"), cancellable = true)
    private void synaptic$angerIsShared(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
        if (!SynapticConfig.enabled(Feature.MOB_ANGER)) return;
        NeutralMob self = (NeutralMob) this;
        if (!self.isAngry()) return;

        // Who the anger is aimed at. Null covers two cases and both should
        // spread: a mob angry at nobody in particular is vanilla's own universal
        // anger, which is only ever reached by a player provoking it, and a
        // target that will not resolve is one we cannot judge — that is the
        // player who logged out mid-fight, and leaving the group off the hook
        // for them would be the more surprising answer.
        //
        // Anything else that resolves to something which is not a player is a
        // quarrel between mobs. The dragon breaks the endermen it hits and they
        // go for the dragon; that is theirs to settle.
        LivingEntity provoker = EntityReference.getLivingEntity(self.getPersistentAngerTarget(), level);
        if (provoker != null && !(provoker instanceof Player)) return;

        cir.setReturnValue(true);
    }
}
