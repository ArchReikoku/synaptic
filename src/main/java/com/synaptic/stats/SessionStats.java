package com.synaptic.stats;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.synaptic.net.StatsSyncPayload;
import com.synaptic.net.WipeReportPayload;
import com.synaptic.report.SynapticLog;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What each player has done, counted twice: once for the run they are in and
 * once for the whole world.
 * <p>
 * A world is one save you sat down to play, however many runs it ends up
 * containing. Making a new world by hand starts a new one and the totals begin
 * at nothing; resetting to a new seed within it must not, which is why these
 * cannot simply live in the save. So they live in the config directory filed
 * under a number, and the save carries a marker naming which one it belongs to.
 * <p>
 * Everyone who has ever joined stays in the table whether or not they are online
 * now, because a world's story includes the player who left an hour ago.
 */
public final class SessionStats {
    private static final SynapticLog LOGGER = SynapticLog.get();
    private static final String DIRECTORY = "synaptic";
    private static final String MARKER = "synaptic-session.id";
    private static final String COUNTER = "next-session.txt";
    /** Reserved keys in a session file: not players. */
    private static final String RUN_KEY = "run";
    private static final String CLOCK_KEY = "runseconds";
    private static final String TOTAL_KEY = "totalseconds";
    private static final String ENDED_KEY = "runended";
    private static final String CREDITED_KEY = "advancements";
    /**
     * The run's dimension, remembered by name and seed.
     * <p>
     * The name cannot be worked out from the session and run numbers: a run
     * adopted from the lobby keeps the candidate's name, so run 3 of world 1 is
     * as likely to be called {@code prep_4_2} as anything else.
     */
    private static final String RUN_NAME_KEY = "run.dimension";
    private static final String RUN_SEED_KEY = "run.seed";
    /**
     * The lobby's batch counter, which names candidate dimensions.
     * <p>
     * Kept with the world rather than in memory because the names outlive the
     * server: a candidate's region files sit on disk until something deletes
     * them, so a counter that restarted at zero would hand out a name that
     * already has a world under it and quietly load that instead.
     */
    private static final String BATCH_KEY = "lobby.batch";
    /**
     * Where each player was before the lobby took them, one key per player.
     * <p>
     * Prefixed rather than filed under the bare UUID because the plain keys in
     * this file are the stats rows, and these are not rows.
     */
    private static final String HOME_PREFIX = "home.";
    /**
     * The run's obituary, kept with the world.
     * <p>
     * The death screen used to read a copy of chat the client happened to have,
     * which meant it showed whatever had been said — and showed nothing at all
     * to anyone who rejoined. The report is the world's, so it lives here.
     * Fields within a blow are tab separated; names and damage causes cannot
     * contain a tab, and Properties escapes them on the way out regardless.
     */
    private static final String WIPE_PREFIX = "wipe.";
    private static final String WIPE_NAME_KEY = WIPE_PREFIX + "name";
    private static final String WIPE_VICTIM_KEY = WIPE_PREFIX + "victim";
    private static final String WIPE_CAUSE_KEY = WIPE_PREFIX + "cause";
    private static final String WIPE_LINE_PREFIX = WIPE_PREFIX + "blow.";
    private static final String FIELD = "	";
    private static final String SEPARATOR = " ";
    /** How many numbers one tally writes, for reading rows back. */
    private static final int TALLY_FIELDS = 7;

    /** Insertion ordered, so ties in the table fall back on who arrived first. */
    private static final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /**
     * Advancements already counted for somebody this world.
     * <p>
     * An advancement is the group's to earn once. A player who joins later has
     * none of them and will work through the whole tree on their own, and
     * crediting those would pay a latecomer for ground the group covered hours
     * ago.
     */
    private static final Set<String> credited = new LinkedHashSet<>();

    private static boolean dirty;
    private static int session = 1;
    private static int run;
    private static int runSeconds;
    private static int totalSeconds;
    private static boolean runEnded;
    /** The dimension the current run is being played in, or null before the first one. */
    private static String runName;
    private static long runSeed;
    /** Never reused, never reset: see {@link #BATCH_KEY}. */
    private static int batch;
    /** Encoded {@code HomePoint}s, kept opaque here: this class stores them, it does not read them. */
    private static final Map<UUID, String> homes = new LinkedHashMap<>();
    /** The death this run ended on, or {@code none()} while it is still going. */
    private static WipeReportPayload wipe = WipeReportPayload.none();

    private SessionStats() {
    }

    /** One player's running totals over some span. Mutable: added to constantly. */
    private static final class Tally {
        private float dealt;
        private float taken;
        private int food;
        private float hunger;
        private int xp;
        private int unlocks;
        private int deaths;

        private void clear() {
            dealt = 0.0F;
            taken = 0.0F;
            food = 0;
            hunger = 0.0F;
            xp = 0;
            unlocks = 0;
            deaths = 0;
        }

        private StatsSyncPayload.Tally wire() {
            return new StatsSyncPayload.Tally(dealt, taken, food, hunger, xp, unlocks, deaths);
        }

        private String write() {
            return String.join(SEPARATOR, String.valueOf(dealt), String.valueOf(taken),
                String.valueOf(food), String.valueOf(hunger), String.valueOf(xp),
                String.valueOf(unlocks), String.valueOf(deaths));
        }

        private void read(String[] parts, int from) {
            dealt = Float.parseFloat(parts[from]);
            taken = Float.parseFloat(parts[from + 1]);
            food = Integer.parseInt(parts[from + 2]);
            hunger = Float.parseFloat(parts[from + 3]);
            xp = Integer.parseInt(parts[from + 4]);
            unlocks = Integer.parseInt(parts[from + 5]);
            deaths = Integer.parseInt(parts[from + 6]);
        }
    }

    private static final class Entry {
        private String name;
        private boolean online;
        private final Tally run = new Tally();
        private final Tally all = new Tally();

        private Entry(String name) {
            this.name = name;
        }
    }

    private static Entry entryFor(ServerPlayer player) {
        return entries.computeIfAbsent(player.getUUID(),
            id -> new Entry(player.getGameProfile().name()));
    }

    public static void damageDealt(ServerPlayer player, float amount) {
        Entry entry = entryFor(player);
        entry.run.dealt += amount;
        entry.all.dealt += amount;
        dirty = true;
    }

    public static void damageTaken(ServerPlayer player, float amount) {
        Entry entry = entryFor(player);
        entry.run.taken += amount;
        entry.all.taken += amount;
        dirty = true;
    }

    /**
     * A death of this player's that took the whole group with it.
     * <p>
     * Only the death that started the cascade counts. Everyone else dying is the
     * rule working, not a mistake of theirs, so counting those would give every
     * player the same number and say nothing.
     */
    public static void causedDeath(ServerPlayer player) {
        Entry entry = entryFor(player);
        entry.run.deaths++;
        entry.all.deaths++;
        dirty = true;
    }

    /**
     * An advancement finished for the first time this world.
     * <p>
     * Answers false if somebody has already had it, so the caller can leave it
     * uncounted rather than paying a latecomer for old ground.
     */
    public static boolean advancementEarned(ServerPlayer player, String id) {
        if (!credited.add(id)) return false;
        Entry entry = entryFor(player);
        entry.run.unlocks++;
        entry.all.unlocks++;
        dirty = true;
        return true;
    }

    public static void foodEaten(ServerPlayer player) {
        Entry entry = entryFor(player);
        entry.run.food++;
        entry.all.food++;
        dirty = true;
    }

    public static void exhaustionSpent(ServerPlayer player, float amount) {
        Entry entry = entryFor(player);
        entry.run.hunger += amount;
        entry.all.hunger += amount;
        dirty = true;
    }

    public static void experienceGained(ServerPlayer player, int points) {
        // Levelling costs are spent as negative points; a total of what was
        // earned should not be walked backwards by spending it.
        if (points <= 0) return;
        Entry entry = entryFor(player);
        entry.run.xp += points;
        entry.all.xp += points;
        dirty = true;
    }

    /**
     * Refresh who is here and pick up any name changes, then hand back the table
     * to be sent out. Called every sync rather than on join and quit alone, so a
     * player who drops without a clean disconnect still greys out.
     */
    public static StatsSyncPayload snapshot(MinecraftServer server) {
        for (Entry entry : entries.values()) entry.online = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Entry entry = entryFor(player);
            entry.name = player.getGameProfile().name();
            entry.online = true;
        }
        List<StatsSyncPayload.Row> rows = new ArrayList<>(entries.size());
        for (Map.Entry<UUID, Entry> pair : entries.entrySet()) {
            Entry entry = pair.getValue();
            rows.add(new StatsSyncPayload.Row(pair.getKey(), entry.name,
                entry.run.wire(), entry.all.wire(), entry.online));
        }
        return new StatsSyncPayload(session, run, runSeconds, totalSeconds, rows);
    }

    public static int session() {
        return session;
    }

    public static int run() {
        return run;
    }

    /** The dimension the current run lives in, or null if no run has been started. */
    public static String runName() {
        return runName;
    }

    /** The seed that dimension was built from, meaningless unless {@link #runName()} is set. */
    public static long runSeed() {
        return runSeed;
    }

    /** The run's obituary as it stands. Never null: absent is a report saying so. */
    public static WipeReportPayload wipe() {
        return wipe;
    }

    /** Record the death that ended the run, and write it out at once. */
    public static void setWipe(WipeReportPayload report) {
        wipe = report;
        dirty = true;
        save();
    }

    /** A new run has nothing to report yet. */
    public static void clearWipe() {
        if (!wipe.present()) return;
        wipe = WipeReportPayload.none();
        dirty = true;
        save();
    }

    /** Where this player was before the lobby, encoded, or null if nothing was recorded. */
    public static String home(UUID id) {
        return homes.get(id);
    }

    /**
     * Remember where a player was. Not written out on its own: a whole lobby's
     * worth is recorded at once, and the caller saves when it has them all.
     */
    public static void setHome(UUID id, String encoded) {
        homes.put(id, encoded);
        dirty = true;
    }

    /** Forget one player's, once they have been put back. */
    public static void clearHome(UUID id) {
        if (homes.remove(id) != null) {
            dirty = true;
            save();
        }
    }

    /**
     * Forget all of them, once there is nothing to go back to — a run has been
     * adopted and the world they were recorded in has been deleted.
     */
    public static void clearHomes() {
        if (homes.isEmpty()) return;
        homes.clear();
        dirty = true;
        save();
    }

    /** The batch number the lobby is currently naming worlds from. */
    public static int batch() {
        return batch;
    }

    /**
     * Claim a batch number nothing has used before, in this world or any run of
     * it. Written out at once, because a name handed out and then forgotten is
     * exactly the collision this counter exists to prevent.
     */
    public static int nextBatch() {
        batch++;
        dirty = true;
        save();
        return batch;
    }

    /**
     * Record which dimension the run is being played in.
     * <p>
     * Written out immediately rather than left to the next save: this is what a
     * restart reads to rebuild the run, and a crash between here and shutdown
     * would otherwise strand everyone in the save's own overworld with the run
     * still sitting on disk under a name nothing remembers.
     */
    public static void setRunDimension(String name, long seed) {
        runName = name;
        runSeed = seed;
        dirty = true;
        save();
    }

    public static int runSeconds() {
        return runSeconds;
    }

    public static int totalSeconds() {
        return totalSeconds;
    }

    /**
     * Another second on the clocks.
     * <p>
     * Both stop once the group is down: nobody is playing while the report is on
     * screen, and a world clock that counts time spent staring at a death screen
     * is measuring the wrong thing. Counted in seconds rather than kept as a
     * start time so they survive the server stopping.
     */
    public static void tickClock() {
        if (runEnded) return;
        runSeconds++;
        // The world total is deliberately not a sum of anything: runs are
        // deleted, so a figure rebuilt from what survives would shrink as the
        // evening goes on. It is its own count, cleared only by a new world.
        totalSeconds++;
        dirty = true;
    }

    /** The group is down. Freeze both clocks until a new run starts. */
    public static void endRun() {
        if (runEnded) return;
        runEnded = true;
        dirty = true;
        save();
    }

    /**
     * Claim the next run number, and wipe what belonged to the run just ended.
     * <p>
     * Only the per-run tallies are cleared. The world totals carry on, which is
     * the whole reason there are two of them.
     */
    public static int nextRun() {
        run++;
        runSeconds = 0;
        runEnded = false;
        for (Entry entry : entries.values()) entry.run.clear();
        dirty = true;
        save();
        return run;
    }

    /**
     * Work out which world this save is and load its totals.
     * <p>
     * Called once the save is open, because until then there is nothing to ask.
     * A marker already in it means picking up where that world left off; no
     * marker means it was made by hand, so it takes the next number and starts
     * empty.
     */
    public static void begin(MinecraftServer server) {
        Path marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
        Integer existing = readMarker(marker);
        if (existing != null) {
            session = existing;
        } else {
            session = allocate();
            try {
                Files.writeString(marker, Integer.toString(session));
            } catch (IOException e) {
                // Not fatal, but worth saying: without the marker this save will
                // be handed a fresh number every time it loads.
                LOGGER.warn("Could not mark {} as world {}", marker, session, e);
            }
        }
        entries.clear();
        credited.clear();
        run = 0;
        runSeconds = 0;
        totalSeconds = 0;
        runEnded = false;
        runName = null;
        runSeed = 0;
        batch = 0;
        homes.clear();
        wipe = WipeReportPayload.none();
        dirty = false;
        load();
        LOGGER.info("Synaptic world {}, run {}", session, run);
    }

    private static Integer readMarker(Path marker) {
        if (!Files.exists(marker)) return null;
        try {
            return Integer.parseInt(Files.readString(marker).trim());
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("Unreadable marker at {}, starting a new world", marker);
            return null;
        }
    }

    /** The next world number going, bumped and kept for the next new save. */
    private static int allocate() {
        Path counter = directory().resolve(COUNTER);
        int next = 1;
        if (Files.exists(counter)) {
            try {
                next = Math.max(1, Integer.parseInt(Files.readString(counter).trim()));
            } catch (IOException | NumberFormatException e) {
                LOGGER.warn("Unreadable counter at {}, starting from 1", counter);
            }
        }
        try {
            Files.createDirectories(counter.getParent());
            Files.writeString(counter, Integer.toString(next + 1));
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", counter, e);
        }
        return next;
    }

    private static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve(DIRECTORY);
    }

    private static Path file() {
        return directory().resolve("session-" + session + ".properties");
    }

    private static void load() {
        Path path = file();
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}, starting fresh", path, e);
            return;
        }
        run = readInt(properties, RUN_KEY);
        runSeconds = readInt(properties, CLOCK_KEY);
        batch = readInt(properties, BATCH_KEY);
        loadWipe(properties);
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(HOME_PREFIX)) continue;
            try {
                homes.put(UUID.fromString(key.substring(HOME_PREFIX.length())),
                    properties.getProperty(key));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping unreadable home for {}", key);
            }
        }
        totalSeconds = readInt(properties, TOTAL_KEY);
        // Kept across a restart too: a group that logs off dead should not find
        // the run they lost quietly accruing survival time.
        runEnded = Boolean.parseBoolean(properties.getProperty(ENDED_KEY, "false"));
        // Blank counts as absent: a file written before runs were remembered by
        // name has no key here, and neither has a world still in its lobby.
        String name = properties.getProperty(RUN_NAME_KEY, "").trim();
        runName = name.isEmpty() ? null : name;
        try {
            runSeed = Long.parseLong(properties.getProperty(RUN_SEED_KEY, "0").trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Unreadable run seed in {}, rebuilding the run from a fresh one", path);
            runSeed = 0;
        }
        for (String id : properties.getProperty(CREDITED_KEY, "").split(",")) {
            if (!id.isBlank()) credited.add(id.trim());
        }

        for (String key : properties.stringPropertyNames()) {
            if (key.equals(RUN_KEY) || key.equals(CLOCK_KEY) || key.equals(TOTAL_KEY)
                || key.equals(ENDED_KEY) || key.equals(CREDITED_KEY)
                || key.equals(RUN_NAME_KEY) || key.equals(RUN_SEED_KEY)
                || key.equals(BATCH_KEY) || key.startsWith(HOME_PREFIX)
                || key.startsWith(WIPE_PREFIX)) {
                continue;
            }
            String[] parts = properties.getProperty(key).split(SEPARATOR, -1);
            try {
                Entry entry = new Entry(parts[0]);
                // One tally in the row is a file written before runs were counted
                // apart: those figures are the world's, and the run starts at
                // nothing.
                if (parts.length >= 1 + TALLY_FIELDS * 2) {
                    entry.run.read(parts, 1);
                    entry.all.read(parts, 1 + TALLY_FIELDS);
                } else if (parts.length >= 1 + TALLY_FIELDS) {
                    entry.all.read(parts, 1);
                } else {
                    continue;
                }
                entries.put(UUID.fromString(key), entry);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                LOGGER.warn("Skipping unreadable row for {}", key);
            }
        }
    }

    /**
     * Read the obituary back. Anything unreadable leaves the report absent
     * rather than half built: a death screen with the wrong figures on it would
     * be worse than one with none.
     */
    private static void loadWipe(Properties properties) {
        String victim = properties.getProperty(WIPE_VICTIM_KEY);
        if (victim == null) return;
        try {
            UUID id = UUID.fromString(victim.trim());
            List<WipeReportPayload.Line> blows = new ArrayList<>();
            for (int i = 0; ; i++) {
                String raw = properties.getProperty(WIPE_LINE_PREFIX + i);
                if (raw == null) break;
                String[] parts = raw.split(FIELD, -1);
                if (parts.length < 4) continue;
                blows.add(new WipeReportPayload.Line(parts[0], Float.parseFloat(parts[1]),
                    parts[2], Float.parseFloat(parts[3])));
            }
            wipe = new WipeReportPayload(true, id, properties.getProperty(WIPE_NAME_KEY, ""),
                properties.getProperty(WIPE_CAUSE_KEY, ""), List.copyOf(blows));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unreadable death report in the session file, leaving it out");
            wipe = WipeReportPayload.none();
        }
    }

    private static int readInt(Properties properties, String key) {
        try {
            return Integer.parseInt(properties.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Only writes when something actually changed; this is called on a timer. */
    public static void save() {
        if (!dirty) return;
        Properties properties = new Properties();
        properties.setProperty(RUN_KEY, String.valueOf(run));
        properties.setProperty(CLOCK_KEY, String.valueOf(runSeconds));
        properties.setProperty(TOTAL_KEY, String.valueOf(totalSeconds));
        properties.setProperty(ENDED_KEY, String.valueOf(runEnded));
        properties.setProperty(CREDITED_KEY, String.join(",", credited));
        properties.setProperty(BATCH_KEY, String.valueOf(batch));
        for (Map.Entry<UUID, String> pair : homes.entrySet()) {
            properties.setProperty(HOME_PREFIX + pair.getKey(), pair.getValue());
        }
        if (wipe.present()) {
            properties.setProperty(WIPE_VICTIM_KEY, wipe.victim().toString());
            properties.setProperty(WIPE_NAME_KEY, wipe.name());
            properties.setProperty(WIPE_CAUSE_KEY, wipe.cause());
            List<WipeReportPayload.Line> blows = wipe.lines();
            for (int i = 0; i < blows.size(); i++) {
                WipeReportPayload.Line blow = blows.get(i);
                properties.setProperty(WIPE_LINE_PREFIX + i, String.join(FIELD, blow.name(),
                    String.valueOf(blow.damage()), blow.cause(), String.valueOf(blow.left())));
            }
        }
        if (runName != null) {
            properties.setProperty(RUN_NAME_KEY, runName);
            properties.setProperty(RUN_SEED_KEY, String.valueOf(runSeed));
        }
        for (Map.Entry<UUID, Entry> pair : entries.entrySet()) {
            Entry entry = pair.getValue();
            properties.setProperty(pair.getKey().toString(),
                entry.name + SEPARATOR + entry.run.write() + SEPARATOR + entry.all.write());
        }
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                properties.store(writer, "Synaptic — totals for world " + session);
            }
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", path, e);
        }
    }
}
