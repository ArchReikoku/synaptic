package com.synaptic.mixin;

import com.synaptic.SynapticMod;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Splits hunger across the group: with four players online, each one's exertion
 * costs a quarter of the usual exhaustion, so four people sprinting drain the
 * shared bar at the rate one person would.
 * <p>
 * This has to happen at the exhaustion level rather than on the resulting drop in
 * food/saturation. Vanilla spends exhaustion in whole points
 * ({@code saturationLevel = max(saturationLevel - 1, 0)}), so scaling the observed
 * drop instead means that once saturation falls below 1 the clamp makes each drop
 * smaller than the last: 1 &rarr; 0.5 &rarr; 0.25 &rarr; ... approaching zero but
 * never arriving. Saturation would then sit forever at an epsilon above zero, which
 * pins {@code FoodData.tick} in its fast-regen branch healing {@code min(sat,6)/6}
 * — effectively nothing — while its else-if locks out the slow branch that heals a
 * heart every 80 ticks, and food never drains because vanilla only decrements food
 * once saturation is exactly 0.
 * <p>
 * Scaling exhaustion keeps every drop a clean whole point, so saturation still
 * lands exactly on 0 and the vanilla cascade continues normally — just slower.
 * <p>
 * Natural regeneration is the group's, not each player's: see
 * {@link SynapticMod#healsForGroup}. Both regeneration branches in
 * {@code tick} are entered through {@code player.isHurt()}, so answering no
 * there for everyone but the healer switches off the heal and its hunger cost
 * together, with nothing of vanilla's arithmetic copied here.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @ModifyVariable(method = "addExhaustion", at = @At("HEAD"), argsOnly = true)
    private float synaptic$splitAcrossPlayers(float exhaustion) {
        if (!SynapticConfig.enabled(Feature.HUNGER_SPLIT)) return exhaustion;
        return exhaustion / SynapticMod.sharedPlayerCount();
    }

    @Redirect(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isHurt()Z"))
    private boolean synaptic$onlyTheHealerRegenerates(ServerPlayer player) {
        return player.isHurt() && SynapticMod.healsForGroup(player, false);
    }

    /**
     * The healer pays a whole player's price for healing, not a split share.
     * <p>
     * The only exhaustion {@code tick} adds is the cost of a heal it just made.
     * That heal is the group's single heal, so dividing its cost by the player
     * count would make healing N times cheaper than it is solo. Multiplied back
     * here so the split above cancels out.
     */
    @ModifyArg(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V"))
    private float synaptic$healingCostIsNotSplit(float exhaustion) {
        if (!SynapticConfig.enabled(Feature.HUNGER_SPLIT)) return exhaustion;
        return exhaustion * SynapticMod.sharedPlayerCount();
    }
}
