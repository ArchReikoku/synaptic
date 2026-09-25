package com.synaptic.world;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * The sky and the clock, which belong to the server rather than to any run.
 * <p>
 * <b>Neither of these belongs to the run's dimension, which is why building a
 * new one does not reset them.</b> Since 26.2 weather is a single server-wide
 * {@code WeatherData} and the time of day a single server-wide
 * {@code ServerClockManager} — that is why the save keeps one
 * {@code weather.dat} and one {@code world_clocks.dat} rather than a pair per
 * dimension. Left alone, a brand new run opens under whatever weather and
 * whatever hour the last one ended in, which is the one thing that most gives
 * away that it is not a new world.
 * <p>
 * The overworld clock covers the run's overworld and its nether; the End runs
 * on a clock of its own that has no day to move to.
 */
public final class Skies {
    /**
     * Everything needed to put the sky back exactly as it was: the weather's
     * timers, how far into a rain or a storm each level had faded, and the hour.
     */
    public record Snapshot(int clearTime, int rainTime, int thunderTime,
                           boolean raining, boolean thundering,
                           float rainLevel, float thunderLevel, long ticks) {}

    private Skies() {
    }

    /**
     * Morning, and a clear sky — at once, not over the next few seconds.
     * <p>
     * The weather is set through the same call {@code /weather clear} makes and
     * the clear spell is sampled the way vanilla samples it, so rain resumes
     * naturally later rather than the run being permanently sunny. What
     * {@code /weather clear} does not do is stop the rain now: the server fades
     * each level's rain out by a hundredth a tick, five seconds of it falling
     * over the first world anybody is shown. So the fade is skipped and the
     * clients are told the finished result directly.
     * <p>
     * The clock goes through {@code moveToTimeMarker}, which is what
     * {@code /time set day} uses, so the new hour reaches every client with it.
     */
    public static void fresh(MinecraftServer server) {
        server.setWeatherParameters(
            ServerLevel.RAIN_DELAY.sample(server.overworld().getRandom()), 0, false, false);
        settle(server, false, 0.0F, 0.0F);
        server.clockManager().moveToTimeMarker(overworldClock(server), ClockTimeMarkers.DAY);
    }

    /**
     * Write down the sky as it is now, so that {@link #restore} can bring it back.
     * <p>
     * The fade levels are read from the level everyone is playing in: every
     * level with weather follows the same {@code WeatherData}, so they agree to
     * within a tick of each other and one of them speaks for all.
     */
    public static Snapshot take(MinecraftServer server, ServerLevel playing) {
        WeatherData weather = server.getWeatherData();
        return new Snapshot(weather.getClearWeatherTime(), weather.getRainTime(),
            weather.getThunderTime(), weather.isRaining(), weather.isThundering(),
            playing.getRainLevel(1.0F), playing.getThunderLevel(1.0F),
            server.clockManager().getInstance(overworldClock(server)).totalTicks());
    }

    /**
     * Put the sky back as {@link #take} found it: the same storm at the same
     * strength, and the same hour.
     * <p>
     * Written straight into {@code WeatherData} rather than through
     * {@code setWeatherParameters}, which takes one timer for both rain and
     * thunder and would lose whichever of them differed.
     */
    public static void restore(MinecraftServer server, Snapshot sky) {
        WeatherData weather = server.getWeatherData();
        weather.setClearWeatherTime(sky.clearTime());
        weather.setRainTime(sky.rainTime());
        weather.setThunderTime(sky.thunderTime());
        weather.setRaining(sky.raining());
        weather.setThundering(sky.thundering());
        settle(server, sky.raining(), sky.rainLevel(), sky.thunderLevel());
        server.clockManager().setTotalTicks(overworldClock(server), sky.ticks());
    }

    /**
     * Jump every level with weather straight to the given strength of rain and
     * thunder, and tell the players in each one.
     * <p>
     * Setting the level's own values is what stops the server fading towards
     * them; it would otherwise also be the reason clients never heard, since the
     * server only sends a level when it sees one change. So the packets are sent
     * here, per dimension as vanilla sends them — a player in the nether is not
     * told about rain it cannot have. START or STOP comes first because the
     * client answers each by forcing its own level to an end, and the level
     * packets that follow are what leave it where it belongs.
     */
    private static void settle(MinecraftServer server, boolean raining, float rain, float thunder) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.canHaveWeather()) continue;
            level.setRainLevel(rain);
            level.setThunderLevel(thunder);
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(raining
                ? ClientboundGameEventPacket.START_RAINING
                : ClientboundGameEventPacket.STOP_RAINING, 0.0F), level.dimension());
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain), level.dimension());
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder), level.dimension());
        }
    }

    private static Holder<WorldClock> overworldClock(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
            .getOrThrow(WorldClocks.OVERWORLD);
    }
}
