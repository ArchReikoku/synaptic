package com.synaptic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.synaptic.command.SynapticCommand;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.SynapticNetworking;
import com.synaptic.net.WipeReportPayload;
import com.synaptic.stats.SessionStats;
import com.synaptic.world.LobbyManager;
import com.synaptic.world.RunManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

/** Shared health, hunger, effects, experience, and inventory state contributed to by every online player. */
public final class SynapticMod implements ModInitializer {
    private static final int SHARED_INVENTORY_SLOTS = 41; // 36 inventory + 4 armor + offhand
    private static final int ENDER_CHEST_SLOTS = 27;
    private static final String HEART = "❤";
    /**
     * Damage types every player suffers at the same instant because the thing
     * causing them is shared: poison and instant harming arrive as "magic", the
     * wither effect as "wither", an empty shared hunger bar as "starve", and an
     * empty shared air bar as "drown" — two players holding the same breath run
     * out at the same instant, so the group pays for it once.
     */
    private static final Set<String> SHARED_CAUSE_DAMAGE = Set.of("magic", "wither", "starve", "drown");
    private static final Map<String, Long> sharedDamageTick = new HashMap<>();
    private static final Map<UUID, PlayerState> lastStates = new HashMap<>();
    private static final Map<UUID, InventorySnapshot> lastInventories = new HashMap<>();
    private static final Map<Holder<MobEffect>, MobEffectInstance> sharedEffects = new HashMap<>();
    private static final ItemStack[] sharedInventory = emptyStacks(SHARED_INVENTORY_SLOTS);
    private static final ItemStack[] sharedEnderChest = emptyStacks(ENDER_CHEST_SLOTS);
    private static final List<DamageReport> pendingDamage = new ArrayList<>();
    /**
     * The run's last few blows, kept for the death screen.
     * <p>
     * Recorded whether or not Damage Chat is switched on: the chat setting is
     * about what scrolls past while you play, and this is the report. Kept to
     * the same handful of lines the screen has room for.
     */
    private static final Deque<WipeReportPayload.Line> damageLog = new ArrayDeque<>();
    private static final int DAMAGE_LOG_KEPT = 10;
    /**
     * A death waiting to be reported.
     * <p>
     * Held rather than sent from the death event, because the blow that killed
     * is still sitting in {@code pendingDamage} at that moment and does not
     * reach the log until the reports go out at the end of the tick. Sending
     * from the event would publish a report missing its own last line.
     */
    private static PendingWipe pendingWipe;
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
    private static boolean cascadingDeath;
    private static boolean cascadingRespawn;
    private static long serverTick;
    private static boolean sharingAdvancement;
    private static MinecraftServer currentServer;

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

    /**
     * Kill a player without any of it counting.
     * <p>
     * For putting somebody back the way they were found. A player who opened the
     * lobby from the death screen was dead when it started, and cancelling has
     * to return them to that — but it is not a new death: it was never undone,
     * only stepped over so they could be toured. Counting it would credit them a
     * second death, stop the clock twice, and broadcast an obituary for a death
     * the group already watched.
     * <p>
     * The cascade flag is what buys all of that, since every one of those is
     * already written to skip a death that happens inside one. It also stops
     * this kill taking the rest of the group down with it, which matters because
     * the players being restored are restored one at a time.
     * <p>
     * Answers whether the player is now dead, which is not the same as whether
     * this was allowed to kill them. A player mid-teleport or one whose client
     * has not finished loading is invulnerable to everything —
     * {@code ServerPlayer.isInvulnerableTo} checks both before it ever looks at
     * the damage type, so not even a generic kill gets through — and refuses
     * silently, by returning false rather than by throwing. The caller is
     * expected to ask again on a later tick.
     */
    public static boolean killSilently(ServerPlayer player) {
        if (player.isDeadOrDying()) return true;
        boolean held = cascadingDeath;
        cascadingDeath = true;
        try {
            player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
        } finally {
            cascadingDeath = held;
        }
        return player.isDeadOrDying();
    }

    /**
     * Drop the last world's shared pools before this one starts.
     * <p>
     * The pools are static and the client is not restarted between worlds, so
     * without this the next save opens with the last one's health, hunger and
     * experience already loaded — and the first tick applies them to whoever is
     * standing there. The tick does clear itself when the player list empties,
     * but that relies on a tick happening with nobody on it, which a
     * single-player shutdown does not promise.
     * <p>
     * The cascade flags go too. Either one left set by a shutdown partway
     * through a group death would suppress the next world's deaths entirely.
     */
    public static void begin(MinecraftServer server) {
        // Read back off disk rather than trusted from memory. The settings are a
        // static too, and joining a server overwrites them with that server's —
        // so without this, opening your own world afterwards runs it on the last
        // server's settings, and changing one thing writes the whole borrowed
        // set over your own config file.
        SynapticConfig.load();
        sharedDamageTick.clear();
        lastStates.clear();
        lastInventories.clear();
        sharedEffects.clear();
        pendingDamage.clear();
        damageLog.clear();
        pendingWipe = null;
        sharedHealth = 0.0F;
        sharedFood = 0;
        sharedSaturation = 0.0F;
        sharedAbsorption = 0.0F;
        sharedXpLevel = 0;
        sharedXpProgress = 0.0F;
        sharedXpTotal = 0;
        initialized = false;
        playerCount = 1;
        cascadingDeath = false;
        cascadingRespawn = false;
        sharingAdvancement = false;
        serverTick = 0;
        tick = 0;
    }

    @Override
    public void onInitialize() {
        SynapticConfig.load();
        SynapticNetworking.register();
        SynapticCommand.register();
        // Which session this world belongs to can only be asked once the save is
        // open, so it waits for the server rather than happening at load.
        // First of the three: the shared pools must be empty before anything
        // else looks at them.
        ServerLifecycleEvents.SERVER_STARTED.register(SynapticMod::begin);
        ServerLifecycleEvents.SERVER_STARTED.register(SessionStats::begin);
        // Strictly after the stats, which is where the run's dimension is
        // recorded, and strictly before anyone logs in — rebuilding the run is
        // what stops a reconnecting player being dumped in the save's own
        // overworld instead of the world they were playing.
        ServerLifecycleEvents.SERVER_STARTED.register(RunManager::restore);
        // Same reason, for the lobby's own leftovers: its candidate lists are
        // static and outlive the server that filled them.
        ServerLifecycleEvents.SERVER_STARTED.register(LobbyManager::begin);
        // The timed save covers a crash; this covers a clean shutdown, where the
        // last few seconds of the session would otherwise be lost.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SessionStats.save());
        ServerTickEvents.END_SERVER_TICK.register(SynapticMod::tick);
        // Queued rather than broadcast right here: the hearts-left figure in the
        // message is only settled once this tick's damage has been merged into the
        // shared bar, which happens at END_SERVER_TICK.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0.0F) return;
            // The cascade below is bookkeeping, not something that happened to
            // anyone: reporting it would bury the real death under a wall of
            // "took 1000 damage" lines, and it would flatter whoever's blow
            // triggered it with everybody else's health as damage dealt.
            if (cascadingDeath) return;
            // Counted against any victim, not just players: hitting a zombie is
            // damage dealt too.
            if (source.getEntity() instanceof ServerPlayer attacker && attacker != entity) {
                SessionStats.damageDealt(attacker, taken);
            }
            if (!(entity instanceof ServerPlayer player)) return;
            SessionStats.damageTaken(player, taken);
            pendingDamage.add(new DamageReport(player.getUUID(),
                player.getGameProfile().name(), taken, describeSource(source)));
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SynapticMod::allowSharedSourceDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer player)) return;
            // Counted before the cascade rather than inside it, and only for a
            // death that was not itself part of one: the whole group dies every
            // time, so what is worth counting is whose death it was.
            if (!cascadingDeath) {
                SessionStats.causedDeath(player);
                // The survival clock stops here, not when the next run starts:
                // the time spent staring at the report is not time survived.
                SessionStats.endRun();
                // The sentence is taken here, while the combat tracker still
                // describes the death that actually happened rather than the
                // generic kill used to finish everybody else off. Publishing
                // waits for the end of the tick; see PendingWipe.
                pendingWipe = new PendingWipe(player.getUUID(), player.getGameProfile().name(),
                    source.getLocalizedDeathMessage(player).getString());
            }
            killEveryoneElse(player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register(SynapticMod::respawnEveryoneElse);
        // A shared pet follows whoever handled it last. Ownership is what the
        // follow goals read, so handing it over is the whole mechanism — and it
        // is why this is a real transfer rather than a temporary loan: switch the
        // feature off afterwards and each pet stays with whoever touched it last.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            // Against the real owner, not isOwnedBy: the mixin answers yes to
            // every player, so asking that would never find a pet to hand over.
            if (!level.isClientSide() && SynapticConfig.enabled(Feature.PETS)
                && entity instanceof TamableAnimal pet && pet.isTame() && pet.getOwner() != player) {
                pet.setOwner(player);
            }
            return InteractionResult.PASS;
        });
    }

    private static void tick(MinecraftServer server) {
        currentServer = server;
        // Bumped before anything else reads it. Damage events fire during entity
        // ticking, which is finished by the time this runs, so every hit within
        // one game tick carries the same stamp — and the stamp keeps moving, or
        // the first poison tick would be the only one that ever landed.
        serverTick++;
        sharedDamageTick.values().removeIf(stamp -> stamp < serverTick - 1);
        allowSoloSleep(server);
        LobbyManager.tick(server);

        // Kept above the lobby check, not below it. The session table has to
        // carry on arriving while the worlds are being looked at, and when this
        // sat under the early return a lobby that opened with nobody in it took
        // the tab list down with it.
        tick++;
        if (tick % 20 == 0) {
            SynapticNetworking.broadcastStats(server);
            SynapticNetworking.checkClients(server);
        }
        // Not on the sync timer: writing the file every second would be constant
        // disk churn for numbers that only matter if the game stops unexpectedly.
        if (tick % 600 == 0) SessionStats.save();

        // Nobody is playing while the worlds are being looked at, so the shared
        // pools are left alone rather than being fed a tour's worth of nonsense.
        if (LobbyManager.isOpen()) return;
        // Worlds for the next reset are built while this one is being played,
        // so pressing the button does not start a wait.
        LobbyManager.prepare(server);
        // The clock runs on the run, not on the server: time spent picking a
        // world is not time spent playing it.
        if (tick % 20 == 0 && RunManager.currentRun() != null) SessionStats.tickClock();
        // A run is a dimension the save does not know about, so anyone who has
        // just joined is standing in the original overworld instead of with
        // everybody else.
        RunManager.placeJoiners(server);
        // After placing, and every tick: a death owed to somebody just restored
        // cannot be delivered on the tick they arrive.
        RunManager.settleDeaths(server);
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
            initializeSharedEnderChest(players);
            initializeSharedEffects(players);
            initialized = true;
        } else {
            applyBarChangesFromEveryone(players);
            if (SynapticConfig.enabled(Feature.INVENTORY)) applyInventoryChangesFromEveryone(players);
            if (SynapticConfig.enabled(Feature.ENDER_CHEST)) applyEnderChestChangesFromEveryone(players);
            if (SynapticConfig.enabled(Feature.EFFECTS)) mergeEffectsFromEveryone(players);
        }

        broadcastDamageReports(server);
        // After the reports, so the blow that ended it is in the log it carries.
        settleWipe(server);

        shareAir(players);

        lastStates.clear();
        lastInventories.clear();
        for (ServerPlayer player : players) {
            applySharedState(player);
            lastStates.put(player.getUUID(), new PlayerState(
                sharedHealth, sharedFood, sharedSaturation, sharedAbsorption,
                sharedXpLevel, sharedXpProgress, sharedXpTotal));
            lastInventories.put(player.getUUID(), snapshot(player));
        }

        if (tick % 20 == 0) {
            for (ServerPlayer player : players) {
                player.containerMenu.broadcastChanges();
            }
        }
    }

    /**
     * Damage whose cause is itself shared lands on the group once, not once per
     * player.
     * <p>
     * Poison is the clear case. The effect is shared, so every player is poisoned
     * at once, every one of them takes the tick, and the health merge sums all of
     * it — four players poisoned drain the shared bar four times as fast as one,
     * which kills a full group from a single spider. Starving does the same thing
     * through the shared hunger bar, and wither through its shared effect.
     * <p>
     * The first player to take such a hit in a tick pays it and the rest are
     * waved through, so the bar moves by what one player suffered. Damage with a
     * real source behind it — a mob, a fall, fire — is untouched: those genuinely
     * happened to each player separately and should still stack.
     */
    private static boolean allowSharedSourceDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer) || !SynapticConfig.enabled(Feature.HEALTH)) return true;
        if (!SHARED_CAUSE_DAMAGE.contains(source.getMsgId())) return true;
        // Keyed on the amount as well, so two different doses in one tick are not
        // mistaken for the same event arriving twice.
        String signature = source.getMsgId() + ":" + amount;
        Long alreadyPaid = sharedDamageTick.get(signature);
        if (alreadyPaid != null && alreadyPaid == serverTick) return false;
        sharedDamageTick.put(signature, serverTick);
        return true;
    }

    /**
     * Everyone watches the bubbles of whoever is under water.
     * <p>
     * The lowest air of anyone submerged is mirrored onto everyone who is not,
     * so a player standing on the shore sees the diver running out of breath.
     * Anyone dry is regenerating every tick anyway and simply has that
     * overwritten, so being shown somebody else's empty bar costs them nothing.
     * <p>
     * Divers are never written to, only read from. Vanilla does not drown a
     * player at zero air: it carries on counting down to -20 and deals the
     * damage at that exact value. An earlier version wrote the shared figure
     * onto everybody, which reset that countdown every tick and left a player
     * sitting at no bubbles under water taking nothing at all, indefinitely.
     * <p>
     * The drowning itself stays vanilla's, which only hurts a player whose eyes
     * are actually under. The group still pays for it: "drown" is a shared cause
     * (see SHARED_CAUSE_DAMAGE), so it comes off the pooled bar once per breath
     * rather than once per swimmer.
     * <p>
     * Nobody under water means nobody is holding their breath, and the write
     * stops — vanilla then refills everyone at the same rate, together.
     */
    private static void shareAir(List<ServerPlayer> players) {
        if (!SynapticConfig.enabled(Feature.AIR)) return;
        int lowest = Integer.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (player.isUnderWater()) lowest = Math.min(lowest, player.getAirSupply());
        }
        if (lowest == Integer.MAX_VALUE) return;
        // Shown at zero rather than at the sentinel below it, which is bookkeeping
        // and not a number anybody should be shown.
        int shown = Math.max(0, lowest);
        for (ServerPlayer player : players) {
            // Anyone actually under water is left entirely alone. Vanilla does not
            // drown a player at zero air — it keeps counting down to -20, and that
            // exact value is what deals the damage. Writing their bar back to zero
            // each tick reset that countdown before it ever arrived, which is how a
            // player sat at no bubbles under water indefinitely and took nothing.
            if (player.isUnderWater()) continue;
            if (player.getAirSupply() != shown) player.setAirSupply(shown);
        }
    }

    /**
     * One death is everyone's death. Health is pooled, so a fatal blow already
     * empties everyone's bar — but only the player actually struck goes through
     * vanilla's death path. The rest would sit at zero hearts in a half-dead
     * state, with no death message and nothing dropped. This finishes the job
     * properly for them.
     * <p>
     * Only the player whose death it was drops anything. That is what lets
     * keepInventory stay the server's own setting rather than something this
     * mod switches on behind your back: with one inventory shared between
     * everyone, every player dropping means one copy of the group's gear per
     * player, and the pile grows with the player count.
     */
    private static void killEveryoneElse(ServerPlayer dead) {
        if (cascadingDeath || !SynapticConfig.enabled(Feature.DEATH)) return;
        MinecraftServer server = dead.level().getServer();
        if (server == null) return;
        cascadingDeath = true;
        try {
            boolean shared = SynapticConfig.enabled(Feature.INVENTORY);
            boolean keeping = Boolean.TRUE.equals(
                server.getGameRules().get(GameRules.KEEP_INVENTORY));
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (player == dead || player.isDeadOrDying() || player.isSpectator()) continue;
                // Emptied before the blow, so nothing of theirs reaches the floor.
                // What they are carrying is not theirs — it is the group's one
                // inventory, and the player whose death this was has already
                // dropped it. Letting the rest drop too would put a copy on the
                // ground for every player online. Emptying rather than keeping,
                // because a kept copy would flow straight back into the shared
                // pool on the next tick and duplicate it that way instead.
                if (shared && !keeping) player.getInventory().clearContent();
                player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
            }
        } finally {
            cascadingDeath = false;
        }
    }

    /**
     * One respawn is everyone's respawn. Shared death puts the whole group on the
     * death screen at the same instant, and vanilla then makes each of them click
     * their way out of it separately — the last one to notice keeps everybody
     * else standing around at spawn. Whoever clicks first now clicks for all of
     * them.
     * <p>
     * Done by handing the server the respawn request it would have received from
     * each of those clients anyway, rather than by respawning them directly:
     * vanilla's handler owns bookkeeping beyond the respawn itself — swapping the
     * connection over to the new player object among it — and routing through it
     * means a forced respawn is indistinguishable from a clicked one. It is also
     * why this needs nothing on the client: the death screen closes on the
     * respawn packet that comes back, mod or no mod.
     *
     * @param alive true when the player was not actually dead, which is the walk
     *              back from the End. Nobody else is waiting on a death screen
     *              for that, so it is left alone.
     */
    private static void respawnEveryoneElse(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (alive || cascadingRespawn || !SynapticConfig.enabled(Feature.RESPAWN)) return;
        MinecraftServer server = newPlayer.level().getServer();
        if (server == null) return;
        cascadingRespawn = true;
        try {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                // By UUID, because the player who did the clicking is in this list
                // as a brand new object and the one this event handed us may be
                // either half of that swap.
                if (player.getUUID().equals(newPlayer.getUUID())) continue;
                // Only players actually waiting to come back. Somebody who is up
                // and walking around has no death screen to be let out of, and
                // sending this for them would drag them to their spawn point.
                if (player.isSpectator() || !player.isDeadOrDying()) continue;
                player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
        } finally {
            cascadingRespawn = false;
        }
    }

    /**
     * Hand an advancement one player just earned to everybody else. Called from
     * PlayerAdvancementsMixin for each criterion as it is granted, so partially
     * completed advancements travel too rather than only finished ones.
     * <p>
     * The re-entrancy guard matters: awarding to the others runs this again from
     * inside itself, once per player, which without it recurses until the stack
     * gives out.
     */
    /**
     * Count an advancement against whoever actually finished it.
     * <p>
     * Called before the sharing pass and refused during one, so the credit lands
     * on the player who did the work rather than on everyone it is handed to.
     * Only advancements with something to show for them count: recipe unlocks go
     * through the same machinery in their hundreds, and counting those would
     * drown the figure they are meant to sit beside.
     */
    public static void advancementEarned(ServerPlayer player, AdvancementHolder advancement) {
        if (sharingAdvancement || player == null) return;
        if (advancement.value().display().isEmpty()) return;
        if (!player.getAdvancements().getOrStartProgress(advancement).isDone()) return;
        // Counted once for the world, not once per player. Somebody joining an
        // hour in starts with none of them and will work through the whole tree
        // on their own; paying them for ground the group already covered would
        // put a latecomer top of the table by morning.
        SessionStats.advancementEarned(player, advancement.id().toString());
    }

    public static void shareAdvancement(AdvancementHolder advancement, String criterion) {
        if (sharingAdvancement || currentServer == null
            || !SynapticConfig.enabled(Feature.ADVANCEMENTS)) {
            return;
        }
        sharingAdvancement = true;
        try {
            for (ServerPlayer player : List.copyOf(currentServer.getPlayerList().getPlayers())) {
                player.getAdvancements().award(advancement, criterion);
            }
        } finally {
            sharingAdvancement = false;
        }
    }

    /**
     * One player in a bed is enough to pass the night: the sleeper count vanilla
     * derives from this percentage floors at one, so any value this low means
     * "whoever lies down first".
     */
    private static void allowSoloSleep(MinecraftServer server) {
        int wanted = SynapticConfig.enabled(Feature.SOLO_SLEEP) ? 1 : 100;
        GameRules rules = server.getGameRules();
        Integer required = rules.get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (required == null || required != wanted) {
            rules.set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, wanted, server);
        }
    }

    /**
     * Every player carries the same shared inventory, so without keepInventory a
     * shared death drops one copy per player — the whole inventory duplicated
     * across the floor while the shared pool is wiped.
     * <p>
     * Only ever forced ON. Forcing it back off when the toggle is cleared would
     * overrule a preference the world may hold for its own reasons, and unlike
     * the on case there is no bug being prevented.
     */

    /** "Steve took 1.5(heart) damage from fall (3.5(heart) left)" for the whole server. */
    /**
     * Publish the death once the tick's damage has been written down.
     * <p>
     * Saved as well as sent: the report is what the death screen shows, and a
     * player who leaves and comes back is still looking at the same dead run.
     * Without it on file they came back to an empty screen — or worse, to
     * whatever had been said in chat since.
     */
    private static void settleWipe(MinecraftServer server) {
        if (pendingWipe == null) return;
        WipeReportPayload report = new WipeReportPayload(true, pendingWipe.victim(),
            pendingWipe.name(), pendingWipe.cause(), List.copyOf(damageLog));
        pendingWipe = null;
        SessionStats.setWipe(report);
        SynapticNetworking.broadcastWipe(server, report);
    }

    /**
     * Start a run with nothing to report: no death, and no blows leading up to
     * one. Called when a run is adopted, so the new run's screen cannot open on
     * the last run's obituary.
     */
    public static void clearWipe(MinecraftServer server) {
        damageLog.clear();
        pendingWipe = null;
        SessionStats.clearWipe();
        SynapticNetworking.broadcastWipe(server, WipeReportPayload.none());
    }

    private static void broadcastDamageReports(MinecraftServer server) {
        if (pendingDamage.isEmpty()) return;
        boolean announce = SynapticConfig.enabled(Feature.DAMAGE_MESSAGES);
        boolean alert = SynapticConfig.enabled(Feature.DAMAGE_SOUND);
        for (DamageReport report : pendingDamage) {
            damageLog.addLast(new WipeReportPayload.Line(
                report.name(), report.damage(), report.cause(), sharedHealth));
            while (damageLog.size() > DAMAGE_LOG_KEPT) damageLog.removeFirst();
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
        if (SynapticConfig.enabled(Feature.HEALTH)) {
            // Capped at a full bar, not just at zero. Respawning restores each
            // player to full, and every one of those counts as a gain here — so a
            // four-player death would otherwise bank four bars' worth of hidden
            // health that has to be chewed through before a heart moves again.
            float fullBar = 20.0F;
            for (ServerPlayer player : players) fullBar = Math.max(fullBar, player.getMaxHealth());
            sharedHealth = Math.max(0.0F, Math.min(fullBar, sharedHealth + healthDelta));
        }
        if (SynapticConfig.enabled(Feature.HUNGER)) {
            sharedFood = Math.max(0, Math.min(20, sharedFood + foodDelta));
            sharedSaturation = Math.max(0.0F, Math.min(sharedFood, sharedSaturation + saturationDelta));
        }
        // Golden hearts ride along with Life: they are hearts too, and splitting
        // them out would let absorption drift away from the health it buffers.
        if (SynapticConfig.enabled(Feature.HEALTH)) {
            sharedAbsorption = Math.max(0.0F, sharedAbsorption + absorptionDelta);
        }
        if (SynapticConfig.enabled(Feature.EXPERIENCE)) {
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

    /**
     * Same pooling as the main inventory, in its own 27 slots. Players carrying an
     * identical copy are skipped for the same reason: after a settings change
     * everyone is usually holding the same shared chest, and merging those copies
     * would multiply every stack by the player count.
     */
    private static void initializeSharedEnderChest(List<ServerPlayer> players) {
        Arrays.fill(sharedEnderChest, ItemStack.EMPTY);
        List<ServerPlayer> merged = new ArrayList<>();
        for (ServerPlayer player : players) {
            if (merged.stream().anyMatch(other -> sameEnderChest(other, player))) continue;
            merged.add(player);
            for (int slot = 0; slot < ENDER_CHEST_SLOTS; slot++) {
                addToPool(sharedEnderChest, ENDER_CHEST_SLOTS, player.getEnderChestInventory().getItem(slot));
            }
        }
    }

    private static void applyEnderChestChangesFromEveryone(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            InventorySnapshot previous = lastInventories.get(player.getUUID());
            if (previous == null) continue;
            for (int slot = 0; slot < ENDER_CHEST_SLOTS; slot++) {
                ItemStack current = player.getEnderChestInventory().getItem(slot);
                if (!ItemStack.matches(current, previous.enderChest[slot])) {
                    sharedEnderChest[slot] = current.copy();
                }
            }
        }
    }

    private static boolean sameEnderChest(ServerPlayer left, ServerPlayer right) {
        for (int slot = 0; slot < ENDER_CHEST_SLOTS; slot++) {
            if (!ItemStack.matches(left.getEnderChestInventory().getItem(slot),
                right.getEnderChestInventory().getItem(slot))) {
                return false;
            }
        }
        return true;
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
        if (SynapticConfig.enabled(Feature.HEALTH)) {
            player.setHealth(Math.min(sharedHealth, player.getMaxHealth()));
        }
        if (SynapticConfig.enabled(Feature.HUNGER)) {
            player.getFoodData().setFoodLevel(sharedFood);
            player.getFoodData().setSaturation(sharedSaturation);
        }
        if (SynapticConfig.enabled(Feature.EFFECTS)) {
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
        if (SynapticConfig.enabled(Feature.HEALTH)) {
            player.setAbsorptionAmount(sharedAbsorption);
        }
        // Only on a real change: the client is resent the bar whenever
        // totalExperience moves, so writing every tick would be packet spam.
        if (SynapticConfig.enabled(Feature.EXPERIENCE)
            && (player.experienceLevel != sharedXpLevel
                || player.totalExperience != sharedXpTotal
                || Math.abs(player.experienceProgress - sharedXpProgress) > 1.0E-4F)) {
            player.experienceLevel = sharedXpLevel;
            player.experienceProgress = sharedXpProgress;
            player.totalExperience = sharedXpTotal;
        }
        if (SynapticConfig.enabled(Feature.INVENTORY)) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < SHARED_INVENTORY_SLOTS; slot++) {
                if (!ItemStack.matches(inventory.getItem(slot), sharedInventory[slot])) {
                    inventory.setItem(slot, sharedInventory[slot].copy());
                }
            }
        }
        if (SynapticConfig.enabled(Feature.ENDER_CHEST)) {
            for (int slot = 0; slot < ENDER_CHEST_SLOTS; slot++) {
                if (!ItemStack.matches(player.getEnderChestInventory().getItem(slot), sharedEnderChest[slot])) {
                    player.getEnderChestInventory().setItem(slot, sharedEnderChest[slot].copy());
                }
            }
        }
    }

    private static void addToSharedPool(ItemStack incoming) {
        addToPool(sharedInventory, 36, incoming);
    }

    /** Merge a stack into a pool, combining with matching stacks before taking a free slot. */
    private static void addToPool(ItemStack[] pool, int slots, ItemStack incoming) {
        if (incoming.isEmpty()) return;
        ItemStack remaining = incoming.copy();
        for (int slot = 0; slot < slots && !remaining.isEmpty(); slot++) {
            ItemStack existing = pool[slot];
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                int moved = Math.min(room, remaining.getCount());
                existing.grow(moved);
                remaining.shrink(moved);
            }
        }
        for (int slot = 0; slot < slots && !remaining.isEmpty(); slot++) {
            if (pool[slot].isEmpty()) {
                int moved = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                pool[slot] = remaining.copyWithCount(moved);
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
        ItemStack[] ender = new ItemStack[ENDER_CHEST_SLOTS];
        for (int slot = 0; slot < ENDER_CHEST_SLOTS; slot++) {
            ender[slot] = player.getEnderChestInventory().getItem(slot).copy();
        }
        return new InventorySnapshot(items, ender);
    }

    private static ItemStack[] emptyStacks(int size) {
        ItemStack[] items = new ItemStack[size];
        Arrays.fill(items, ItemStack.EMPTY);
        return items;
    }

    private record PlayerState(float health, int food, float saturation, float absorption,
                               int xpLevel, float xpProgress, int xpTotal) {}
    private record InventorySnapshot(ItemStack[] items, ItemStack[] enderChest) {}
    private record DamageReport(UUID victim, String name, float damage, String cause) {}

    /** A death recorded, waiting for the tick to finish before it is published. */
    private record PendingWipe(UUID victim, String name, String cause) {}
}
