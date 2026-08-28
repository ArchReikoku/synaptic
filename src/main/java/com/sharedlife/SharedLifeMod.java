package com.sharedlife;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sharedlife.config.Feature;
import com.sharedlife.config.SharedLifeConfig;
import com.sharedlife.net.SharedLifeNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

/** Shared health, hunger, effects, experience, and inventory state contributed to by every online player. */
public final class SharedLifeMod implements ModInitializer {
    private static final int SHARED_INVENTORY_SLOTS = 41; // 36 inventory + 4 armor + offhand
    private static final String HEART = "❤";
    private static final Map<UUID, PlayerState> lastStates = new HashMap<>();
    private static final Map<UUID, InventorySnapshot> lastInventories = new HashMap<>();
    private static final Map<Holder<MobEffect>, MobEffectInstance> sharedEffects = new HashMap<>();
    private static final ItemStack[] sharedInventory = emptyInventory();
    private static final List<DamageReport> pendingDamage = new ArrayList<>();
    private static float sharedHealth;
    private static int sharedFood;
    private static float sharedSaturation;
    private static float sharedAbsorption;
    private static int sharedXpLevel;
    private static float sharedXpProgress;
    private static int sharedXpTotal;
    private static boolean initialized;
    private static int tick;
    private static volatile int playerCount = 1;

    /** How many ways the hunger cost of moving around is split. See FoodDataMixin. */
    public static int sharedPlayerCount() {
        return Math.max(1, playerCount);
    }

    /**
     * Re-seed every shared value from the players currently online, used when the
     * settings change: a feature that was switched off has a shared value frozen
     * at whatever it was, and snapping everyone back to that would be worse than
     * starting it fresh.
     */
    public static void reseed() {
        initialized = false;
    }

    @Override
    public void onInitialize() {
        SharedLifeConfig.load();
        SharedLifeNetworking.register();
        ServerTickEvents.END_SERVER_TICK.register(SharedLifeMod::tick);
        // Queued rather than broadcast right here: the hearts-left figure in the
        // message is only settled once this tick's damage has been merged into the
        // shared bar, which happens at END_SERVER_TICK.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayer player) || taken <= 0.0F) return;
            pendingDamage.add(new DamageReport(player.getUUID(),
                player.getGameProfile().name(), taken, describeSource(source)));
        });
    }

    private static void tick(MinecraftServer server) {
        allowSoloSleep(server);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        playerCount = players.size();
        if (players.isEmpty()) {
            lastStates.clear();
            lastInventories.clear();
            sharedEffects.clear();
            pendingDamage.clear();
            initialized = false;
            return;
        }

        if (!initialized) {
            initializeSharedBars(players);
            initializeSharedInventory(players);
            initializeSharedEffects(players);
            initialized = true;
        } else {
            applyBarChangesFromEveryone(players);
            if (SharedLifeConfig.enabled(Feature.INVENTORY)) applyInventoryChangesFromEveryone(players);
            if (SharedLifeConfig.enabled(Feature.EFFECTS)) mergeEffectsFromEveryone(players);
        }

        broadcastDamageReports(server);

        lastStates.clear();
        lastInventories.clear();
        for (ServerPlayer player : players) {
            applySharedState(player);
            lastStates.put(player.getUUID(), new PlayerState(
                sharedHealth, sharedFood, sharedSaturation, sharedAbsorption,
                sharedXpLevel, sharedXpProgress, sharedXpTotal));
            lastInventories.put(player.getUUID(), snapshot(player));
        }

        if (++tick % 20 == 0) {
            for (ServerPlayer player : players) {
                player.containerMenu.broadcastChanges();
            }
        }
    }

    /**
     * One player in a bed is enough to pass the night: the sleeper count vanilla
     * derives from this percentage floors at one, so any value this low means
     * "whoever lies down first".
     */
    private static void allowSoloSleep(MinecraftServer server) {
        int wanted = SharedLifeConfig.enabled(Feature.SOLO_SLEEP) ? 1 : 100;
        GameRules rules = server.getGameRules();
        Integer required = rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (required == null || required != wanted) {
            rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, wanted, server);
        }
    }

    /** "Steve took 1.5(heart) damage from fall (3.5(heart) left)" for the whole server. */
    private static void broadcastDamageReports(MinecraftServer server) {
        if (pendingDamage.isEmpty()) return;
        boolean announce = SharedLifeConfig.enabled(Feature.DAMAGE_MESSAGES);
        boolean alert = SharedLifeConfig.enabled(Feature.DAMAGE_SOUND);
        for (DamageReport report : pendingDamage) {
            if (announce) server.getPlayerList().broadcastSystemMessage(Component.literal(report.name())
                .withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" took ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(hearts(report.damage()) + HEART).withStyle(ChatFormatting.RED))
                .append(Component.literal(" damage from ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(report.cause()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(hearts(sharedHealth) + HEART).withStyle(ChatFormatting.RED))
                .append(Component.literal(" left)").withStyle(ChatFormatting.DARK_GRAY)), false);
            if (alert) playHurtSoundForEveryone(server, report.victim());
        }
        pendingDamage.clear();
    }

    /**
     * The hit lands on the whole group, so the whole group hears it. Sent as a
     * packet aimed at each listener's own position rather than played into the
     * world, so it carries no matter how far apart everyone is. The victim is
     * skipped because vanilla already played them their own hurt sound.
     */
    private static void playHurtSoundForEveryone(MinecraftServer server, UUID victim) {
        for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
            if (listener.getUUID().equals(victim)) continue;
            listener.connection.send(new ClientboundSoundPacket(
                Holder.direct(SoundEvents.PLAYER_HURT), SoundSource.PLAYERS,
                listener.getX(), listener.getY(), listener.getZ(),
                1.0F, 1.0F, listener.getRandom().nextLong()));
        }
    }

    /** The attacker's name when something hit you, otherwise the damage type: fall, lava, cactus. */
    private static String describeSource(DamageSource source) {
        Entity attacker = source.getEntity();
        return attacker != null ? attacker.getName().getString() : source.getMsgId();
    }

    /** Health is counted in half-heart points internally; chat speaks in hearts. */
    private static String hearts(float healthPoints) {
        float value = Math.round(healthPoints / 2.0F * 10.0F) / 10.0F;
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    private static void initializeSharedBars(List<ServerPlayer> players) {
        float healthTotal = 0.0F;
        int foodTotal = 0;
        float saturationTotal = 0.0F;
        float absorptionTotal = 0.0F;
        int xpLevelTotal = 0;
        float xpProgressTotal = 0.0F;
        int xpPointTotal = 0;
        for (ServerPlayer player : players) {
            healthTotal += player.getHealth();
            foodTotal += player.getFoodData().getFoodLevel();
            saturationTotal += player.getFoodData().getSaturationLevel();
            absorptionTotal += player.getAbsorptionAmount();
            xpLevelTotal += player.experienceLevel;
            xpProgressTotal += player.experienceProgress;
            xpPointTotal += player.totalExperience;
        }
        sharedHealth = healthTotal / players.size();
        sharedFood = Math.round((float) foodTotal / players.size());
        sharedSaturation = saturationTotal / players.size();
        sharedAbsorption = absorptionTotal / players.size();
        sharedXpLevel = xpLevelTotal / players.size();
        sharedXpProgress = xpProgressTotal / players.size();
        sharedXpTotal = xpPointTotal / players.size();
        normalizeSharedXp();
    }

    private static void applyBarChangesFromEveryone(List<ServerPlayer> players) {
        float healthDelta = 0.0F;
        int foodDelta = 0;
        float saturationDelta = 0.0F;
        float absorptionDelta = 0.0F;
        int xpLevelDelta = 0;
        float xpProgressDelta = 0.0F;
        int xpPointDelta = 0;
        for (ServerPlayer player : players) {
            PlayerState previous = lastStates.get(player.getUUID());
            if (previous == null) continue;
            healthDelta += player.getHealth() - previous.health;
            foodDelta += player.getFoodData().getFoodLevel() - previous.food;
            saturationDelta += player.getFoodData().getSaturationLevel() - previous.saturation;
            absorptionDelta += player.getAbsorptionAmount() - previous.absorption;
            xpLevelDelta += player.experienceLevel - previous.xpLevel;
            xpProgressDelta += player.experienceProgress - previous.xpProgress;
            xpPointDelta += player.totalExperience - previous.xpTotal;
        }
        // Drops arrive here already divided by the player count, because the split
        // happens upstream on exhaustion (FoodDataMixin) rather than on the drop
        // itself. So these are summed at full weight: a whole point spent is a
        // whole point off the shared bar.
        if (SharedLifeConfig.enabled(Feature.HEALTH)) {
            sharedHealth = Math.max(0.0F, sharedHealth + healthDelta);
        }
        if (SharedLifeConfig.enabled(Feature.HUNGER)) {
            sharedFood = Math.max(0, Math.min(20, sharedFood + foodDelta));
            sharedSaturation = Math.max(0.0F, Math.min(sharedFood, sharedSaturation + saturationDelta));
        }
        if (SharedLifeConfig.enabled(Feature.ABSORPTION)) {
            sharedAbsorption = Math.max(0.0F, sharedAbsorption + absorptionDelta);
        }
        if (SharedLifeConfig.enabled(Feature.EXPERIENCE)) {
            sharedXpLevel += xpLevelDelta;
            sharedXpProgress += xpProgressDelta;
            sharedXpTotal = Math.max(0, sharedXpTotal + xpPointDelta);
            normalizeSharedXp();
        }
    }

    /** Carry a bar that ran off either end of the level into the level count itself. */
    private static void normalizeSharedXp() {
        while (sharedXpProgress >= 1.0F) {
            sharedXpProgress -= 1.0F;
            sharedXpLevel++;
        }
        while (sharedXpProgress < 0.0F && sharedXpLevel > 0) {
            sharedXpProgress += 1.0F;
            sharedXpLevel--;
        }
        sharedXpLevel = Math.max(0, sharedXpLevel);
        sharedXpProgress = Math.max(0.0F, Math.min(0.9999F, sharedXpProgress));
    }

    private static void initializeSharedInventory(List<ServerPlayer> players) {
        Arrays.fill(sharedInventory, ItemStack.EMPTY);
        // Merge every player's normal inventory into the shared pool. Matching
        // stacks combine; conflicting equipment uses the first occupied slot.
        //
        // Players still carrying an identical copy of someone already merged are
        // skipped. Re-seeding happens whenever the settings change, and by then
        // everyone is usually holding the same shared inventory — pooling those
        // copies would multiply every stack by the player count, which is a dupe
        // exploit one toggle away.
        List<ServerPlayer> merged = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (merged.stream().anyMatch(other -> sameInventory(other, player))) continue;
            merged.add(player);
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < 36; slot++) addToSharedPool(inventory.getItem(slot));
            for (int slot = 36; slot < SHARED_INVENTORY_SLOTS; slot++) {
                if (sharedInventory[slot].isEmpty() && !inventory.getItem(slot).isEmpty()) {
                    sharedInventory[slot] = inventory.getItem(slot).copy();
                }
            }
        }
    }

    private static void applyInventoryChangesFromEveryone(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            InventorySnapshot previous = lastInventories.get(player.getUUID());
            if (previous == null) continue; // joining players receive the shared state
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < SHARED_INVENTORY_SLOTS; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (!ItemStack.matches(current, previous.items[slot])) {
                    sharedInventory[slot] = current.copy();
                }
            }
        }
    }

    private static void initializeSharedEffects(List<ServerPlayer> players) {
        sharedEffects.clear();
        mergeEffectsFromEveryone(players);
    }

    /**
     * Effects are an all-player pool: an effect present on any player is shared
     * by everyone. When players contribute different strengths or durations,
     * the strongest/longest instance wins so no player's contribution is lost.
     */
    private static void mergeEffectsFromEveryone(List<ServerPlayer> players) {
        Map<Holder<MobEffect>, MobEffectInstance> merged = new HashMap<>();
        for (ServerPlayer player : players) {
            for (MobEffectInstance effect : player.getActiveEffects()) {
                Holder<MobEffect> type = effect.getEffect();
                MobEffectInstance current = merged.get(type);
                if (current == null || isStronger(effect, current)) {
                    merged.put(type, new MobEffectInstance(effect));
                }
            }
        }
        sharedEffects.clear();
        sharedEffects.putAll(merged);
    }

    private static boolean isStronger(MobEffectInstance candidate, MobEffectInstance current) {
        if (candidate.getAmplifier() != current.getAmplifier()) {
            return candidate.getAmplifier() > current.getAmplifier();
        }
        return candidate.getDuration() > current.getDuration();
    }

    private static void applySharedState(ServerPlayer player) {
        if (SharedLifeConfig.enabled(Feature.HEALTH)) {
            player.setHealth(Math.min(sharedHealth, player.getMaxHealth()));
        }
        if (SharedLifeConfig.enabled(Feature.HUNGER)) {
            player.getFoodData().setFoodLevel(sharedFood);
            player.getFoodData().setSaturation(sharedSaturation);
        }
        if (SharedLifeConfig.enabled(Feature.EFFECTS)) {
            for (MobEffectInstance active : List.copyOf(player.getActiveEffects())) {
                if (!sharedEffects.containsKey(active.getEffect())) {
                    player.removeEffect(active.getEffect());
                }
            }
            for (MobEffectInstance shared : sharedEffects.values()) {
                MobEffectInstance current = player.getEffect(shared.getEffect());
                if (current == null || !current.equals(shared)) {
                    player.addEffect(new MobEffectInstance(shared));
                }
            }
        }
        // Golden hearts, written AFTER the effects above: re-applying a shared
        // Absorption effect tops the amount up on every apply, so the shared value
        // has to be the last word or absorption would inflate tick after tick.
        if (SharedLifeConfig.enabled(Feature.ABSORPTION)) {
            player.setAbsorptionAmount(sharedAbsorption);
        }
        // Only on a real change: the client is resent the bar whenever
        // totalExperience moves, so writing every tick would be packet spam.
        if (SharedLifeConfig.enabled(Feature.EXPERIENCE)
            && (player.experienceLevel != sharedXpLevel
                || player.totalExperience != sharedXpTotal
                || Math.abs(player.experienceProgress - sharedXpProgress) > 1.0E-4F)) {
            player.experienceLevel = sharedXpLevel;
            player.experienceProgress = sharedXpProgress;
            player.totalExperience = sharedXpTotal;
        }
        if (SharedLifeConfig.enabled(Feature.INVENTORY)) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < SHARED_INVENTORY_SLOTS; slot++) {
                if (!ItemStack.matches(inventory.getItem(slot), sharedInventory[slot])) {
                    inventory.setItem(slot, sharedInventory[slot].copy());
                }
            }
        }
    }

    private static void addToSharedPool(ItemStack incoming) {
        if (incoming.isEmpty()) return;
        ItemStack remaining = incoming.copy();
        for (int slot = 0; slot < 36 && !remaining.isEmpty(); slot++) {
            ItemStack existing = sharedInventory[slot];
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int moved = Math.min(room, remaining.getCount());
                existing.grow(moved);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < 36 && !remaining.isEmpty(); slot++) {
            if (sharedInventory[slot].isEmpty()) {
                int moved = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                sharedInventory[slot] = remaining.copyWithCount(moved);
                remaining.shrink(moved);
            }
        }
    }

    private static boolean sameInventory(ServerPlayer left, ServerPlayer right) {
        for (int slot = 0; slot < SHARED_INVENTORY_SLOTS; slot++) {
            if (!ItemStack.matches(left.getInventory().getItem(slot), right.getInventory().getItem(slot))) {
                return false;
            }
        }
        return true;
    }

    private static InventorySnapshot snapshot(ServerPlayer player) {
        ItemStack[] items = new ItemStack[SHARED_INVENTORY_SLOTS];
        for (int slot = 0; slot < SHARED_INVENTORY_SLOTS; slot++) {
            items[slot] = player.getInventory().getItem(slot).copy();
        }
        return new InventorySnapshot(items);
    }

    private static ItemStack[] emptyInventory() {
        ItemStack[] items = new ItemStack[SHARED_INVENTORY_SLOTS];
        Arrays.fill(items, ItemStack.EMPTY);
        return items;
    }

    private record PlayerState(float health, int food, float saturation, float absorption,
                               int xpLevel, float xpProgress, int xpTotal) {}
    private record InventorySnapshot(ItemStack[] items) {}
    private record DamageReport(UUID victim, String name, float damage, String cause) {}
}
