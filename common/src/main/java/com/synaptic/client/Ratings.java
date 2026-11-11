package com.synaptic.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import com.synaptic.net.StatsSyncPayload;

/**
 * One number per player, and the order it puts them in.
 * <p>
 * The table carries seven figures, which is enough to argue about and too many
 * to rank by. This folds them into a single score, weighted by what a shared run
 * actually costs and rewards.
 * <p>
 * The first attempt at this scored damage taken at a flat penalty and left
 * everybody negative, because in a shared-health world getting hit is not a
 * mistake — it is Tuesday. What matters is the balance: the shared bar is a
 * pool, and a player is worth having if they put more into it than they take
 * out. So damage taken is charged lightly and eating pays well, since food is
 * the only thing that fills the bar back up. Somebody who tanks a fight and then
 * feeds the group comes out ahead of somebody who did neither, which is the
 * behaviour worth encouraging.
 * <p>
 * Deliberately arithmetic anyone can follow. A score nobody can explain is a
 * score nobody trusts.
 */
public final class Ratings {
    /** Per heart of damage dealt: the run only moves forward if something dies. */
    private static final int DEALT = 2;
    /** Per heart taken. Charged, but lightly — everyone bleeds in a shared pool. */
    private static final int TAKEN = -1;
    /** Per meal. Food is the only thing that refills the bar, so it pays properly. */
    private static final int FOOD = 8;
    /** Per twenty exhaustion: the legwork nobody else wants to do. */
    private static final int HUNGER_PER = 20;
    /** Per ten experience gathered. */
    private static final int XP_PER = 10;
    /** Per advancement finished first for the group. */
    private static final int UNLOCK = 25;
    /** Per death that ended a run for everybody. */
    private static final int DEATH = -25;

    /** Gold, silver, bronze, then nothing but a number. */
    private static final int GOLD = 0xFFFFD24A;
    private static final int SILVER = 0xFFC9D2DC;
    private static final int BRONZE = 0xFFC8813C;
    private static final int PLAIN = 0xFF6E6E7A;

    /** A player, the figures being ranked, their score, and where it puts them. */
    public record Ranked(StatsSyncPayload.Row row, StatsSyncPayload.Tally tally,
                         int rank, int score) {}

    private Ratings() {
    }

    /** Damage arrives in half-hearts; the score counts hearts like the tables do. */
    public static int score(StatsSyncPayload.Tally tally) {
        return Math.round(tally.dealt() / 2.0F) * DEALT
            + Math.round(tally.taken() / 2.0F) * TAKEN
            + tally.food() * FOOD
            + Math.round(tally.hunger()) / HUNGER_PER
            + tally.xp() / XP_PER
            + tally.unlocks() * UNLOCK
            + tally.deaths() * DEATH;
    }

    /**
     * Best first, over whichever span the caller is showing.
     * <p>
     * Players on the same score share a placing and the next one skips, the way
     * a scoreboard does — two in second means nobody is third.
     */
    public static List<Ranked> rank(List<StatsSyncPayload.Row> rows,
                                    Function<StatsSyncPayload.Row, StatsSyncPayload.Tally> span) {
        List<StatsSyncPayload.Row> sorted = new ArrayList<>(rows);
        // Name as the tie-break so the order holds still between frames rather
        // than shuffling every time two players are level.
        sorted.sort(Comparator.comparingInt((StatsSyncPayload.Row row) -> score(span.apply(row)))
            .reversed().thenComparing(StatsSyncPayload.Row::name));

        List<Ranked> ranked = new ArrayList<>(sorted.size());
        int rank = 0;
        Integer previous = null;
        for (int i = 0; i < sorted.size(); i++) {
            StatsSyncPayload.Tally tally = span.apply(sorted.get(i));
            int score = score(tally);
            if (previous == null || score != previous) rank = i + 1;
            previous = score;
            ranked.add(new Ranked(sorted.get(i), tally, rank, score));
        }
        return ranked;
    }

    public static int colour(int rank) {
        return switch (rank) {
            case 1 -> GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> PLAIN;
        };
    }
}
