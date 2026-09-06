package com.synaptic.mixin;

import java.util.List;

import com.synaptic.client.Ratings;
import com.synaptic.client.SessionTable;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.StatsSyncPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the session table to the player list.
 * <p>
 * Built as a card rather than a spreadsheet: a heading bar naming the session, a
 * row per player carrying their face and a colour of their own, and the figures
 * paired with dim labels so a column can be read without a header above it. The
 * colour is derived from the player's id, so it is the same in every session and
 * on everyone's screen — it becomes how you find your own row rather than the
 * name being the only handle.
 * <p>
 * Drawn instead of vanilla's list and in its place. Everyone who has joined appears, whether or not
 * they are here now — an absent player's totals are still part of the run — with
 * the offline ones dimmed and their faces greyed.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    /** "deaths": deaths of this player's that took the whole group down with them. */
    @Unique
    private static final String[] SYNAPTIC$LABELS =
        {"dealt", "taken", "food", "hunger", "xp", "unlocks", "deaths", "score"};

    @Unique
    private static final int SYNAPTIC$PAD = 8;
    @Unique
    private static final int SYNAPTIC$ROW = 20;
    @Unique
    private static final int SYNAPTIC$FACE = 14;
    @Unique
    private static final int SYNAPTIC$ACCENT_WIDTH = 3;
    /** Room for the placing, wide enough for two digits. */
    @Unique
    private static final int SYNAPTIC$RANK_WIDTH = 14;
    @Unique
    private static final int SYNAPTIC$COLUMN_GAP = 14;
    /** Vanilla's list starts here, and this takes its place. */
    @Unique
    private static final int SYNAPTIC$TOP_MARGIN = 10;

    @Unique
    private static final int SYNAPTIC$BACKDROP = 0xE00B0B0F;
    @Unique
    private static final int SYNAPTIC$HEADER = 0xFF16161C;
    @Unique
    private static final int SYNAPTIC$EDGE = 0x30FFFFFF;
    @Unique
    private static final int SYNAPTIC$STRIPE = 0x12FFFFFF;
    @Unique
    private static final int SYNAPTIC$SELF_ROW = 0x1AFFFFFF;
    @Unique
    private static final int SYNAPTIC$TITLE_TEXT = 0xFFFFFFFF;
    @Unique
    private static final int SYNAPTIC$SUBTITLE_TEXT = 0xFF6E6E7A;
    @Unique
    private static final int SYNAPTIC$VALUE_TEXT = 0xFFFFFFFF;
    @Unique
    private static final int SYNAPTIC$LABEL_TEXT = 0xFF6E6E7A;
    @Unique
    private static final int SYNAPTIC$NAME_TEXT = 0xFFFFFFFF;
    @Unique
    private static final int SYNAPTIC$OFFLINE_TEXT = 0xFF5A5A64;
    /** Laid over an absent player's face so the row reads as gone at a glance. */
    @Unique
    private static final int SYNAPTIC$OFFLINE_VEIL = 0xA00B0B0F;

    /** One per player, picked by id. Muted enough to sit under white text. */
    @Unique
    private static final int[] SYNAPTIC$ACCENTS = {
        0xFF5AC8FA, 0xFF4CD964, 0xFFFFCC33, 0xFFFF7A5A,
        0xFFC792EA, 0xFF64D8CB, 0xFFFF8AC4, 0xFF9AA7FF
    };

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void synaptic$drawSessionTable(GuiGraphicsExtractor graphics, int width,
                                           Scoreboard scoreboard, Objective objective,
                                           CallbackInfo ci) {
        if (!SynapticConfig.enabled(Feature.SESSION_TAB)) return;
        List<StatsSyncPayload.Row> rows = SessionTable.rows();
        // Nothing synced yet — a fresh join, or a server without the mod.
        if (rows.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        if (font == null) return;

        // Ordered by score rather than by who joined first: a table with a
        // placing in it that is not sorted by that placing is a puzzle.
        // The run, never the world total: the tab is what you glance at mid-run
        // to see how this attempt is going.
        List<Ratings.Ranked> ranked = Ratings.rank(rows, StatsSyncPayload.Row::run);
        String[][] values = new String[ranked.size()][];
        for (int i = 0; i < ranked.size(); i++) {
            StatsSyncPayload.Tally tally = ranked.get(i).tally();
            values[i] = new String[] {
                synaptic$hearts(tally.dealt()),
                synaptic$hearts(tally.taken()),
                String.valueOf(tally.food()),
                String.valueOf(Math.round(tally.hunger())),
                synaptic$count(tally.xp()),
                String.valueOf(tally.unlocks()),
                String.valueOf(tally.deaths()),
                String.valueOf(ranked.get(i).score())
            };
        }

        // A figure column is as wide as the wider of its label and its widest
        // value, so the columns hold still while the numbers grow.
        int[] columns = new int[SYNAPTIC$LABELS.length];
        for (int c = 0; c < columns.length; c++) {
            columns[c] = font.width(SYNAPTIC$LABELS[c]);
            for (String[] value : values) columns[c] = Math.max(columns[c], font.width(value[c]));
        }

        int nameWidth = 0;
        for (Ratings.Ranked entry : ranked) {
            nameWidth = Math.max(nameWidth, font.width(entry.row().name()));
        }
        int figuresWidth = 0;
        for (int column : columns) figuresWidth += column + SYNAPTIC$COLUMN_GAP;

        int contentWidth = SYNAPTIC$ACCENT_WIDTH + SYNAPTIC$PAD + SYNAPTIC$RANK_WIDTH
            + SYNAPTIC$FACE + SYNAPTIC$PAD + nameWidth + SYNAPTIC$COLUMN_GAP + figuresWidth;
        // "World" rather than "session": a session is our word for the thing a
        // save is, and nobody sitting down to play needs to learn it. The run
        // number rides behind it in grey as the attempt count.
        String title = "World " + SessionTable.session();
        // Said out loud, because the figures underneath reset with every run and
        // a table that quietly zeroed itself would look broken.
        String scope = "this run";
        String attempt = "#" + SessionTable.run();
        // Both clocks: how long this try has lasted, and how long the world has
        // taken across every try. The second answers "where did the evening go",
        // and the runs it counts have long since been deleted.
        String subtitle = SessionTable.clock() + " try  ·  " + SessionTable.totalClock()
            + " total  ·  " + rows.size() + (rows.size() == 1 ? " player" : " players");
        int headerWidth = SYNAPTIC$PAD * 5 + font.width(title) + font.width(attempt)
            + font.width(scope) + font.width(subtitle);
        // Capped at the screen: eight columns and a long name will otherwise
        // run off both edges at a large GUI scale.
        int tableWidth = Math.min(Math.max(contentWidth + SYNAPTIC$PAD, headerWidth), width - 8);

        int headerHeight = 15;
        int tableHeight = headerHeight + SYNAPTIC$ROW * rows.size() + 3;
        int left = (width - tableWidth) / 2;
        // Where vanilla's own list sat, because this replaces it rather than
        // joining it — two lists on one screen is one more than anybody needs.
        int top = SYNAPTIC$TOP_MARGIN;
        int right = left + tableWidth;
        int bottom = top + tableHeight;

        graphics.fill(left, top, right, bottom, SYNAPTIC$BACKDROP);
        graphics.fill(left, top, right, top + headerHeight, SYNAPTIC$HEADER);
        graphics.text(font, title, left + SYNAPTIC$PAD, top + 4, SYNAPTIC$TITLE_TEXT, false);
        graphics.text(font, attempt, left + SYNAPTIC$PAD + font.width(title) + 5, top + 4,
            SYNAPTIC$SUBTITLE_TEXT, false);
        graphics.text(font, scope,
            left + SYNAPTIC$PAD + font.width(title) + 5 + font.width(attempt) + 6, top + 4,
            SYNAPTIC$LABEL_TEXT, false);
        graphics.text(font, subtitle, right - SYNAPTIC$PAD - font.width(subtitle), top + 4,
            SYNAPTIC$SUBTITLE_TEXT, false);
        graphics.fill(left, top + headerHeight, right, top + headerHeight + 1, SYNAPTIC$EDGE);

        int y = top + headerHeight + 2;
        for (int i = 0; i < ranked.size(); i++) {
            synaptic$drawPlayer(graphics, font, minecraft, ranked.get(i), values[i], columns,
                left, y, right, i);
            y += SYNAPTIC$ROW;
        }
        synaptic$outline(graphics, left, top, right, bottom);
        ci.cancel();
    }

    @Unique
    private void synaptic$drawPlayer(GuiGraphicsExtractor graphics, Font font, Minecraft minecraft,
                                     Ratings.Ranked entry, String[] values, int[] columns,
                                     int left, int y, int right, int index) {
        StatsSyncPayload.Row row = entry.row();
        boolean self = minecraft.player != null && minecraft.player.getUUID().equals(row.id());
        if (self) graphics.fill(left, y, right, y + SYNAPTIC$ROW, SYNAPTIC$SELF_ROW);
        else if (index % 2 == 1) graphics.fill(left, y, right, y + SYNAPTIC$ROW, SYNAPTIC$STRIPE);

        int accent = SYNAPTIC$ACCENTS[Math.floorMod(row.id().hashCode(), SYNAPTIC$ACCENTS.length)];
        if (!row.online()) accent = SYNAPTIC$OFFLINE_TEXT;
        graphics.fill(left, y, left + SYNAPTIC$ACCENT_WIDTH, y + SYNAPTIC$ROW, accent);

        int x = left + SYNAPTIC$ACCENT_WIDTH + SYNAPTIC$PAD;
        // Gold, silver and bronze carry themselves; everyone else is a number in
        // the same grey as the labels, which is the point of a placing.
        String place = String.valueOf(entry.rank());
        graphics.text(font, place, x + SYNAPTIC$RANK_WIDTH - 4 - font.width(place),
            y + (SYNAPTIC$ROW - 8) / 2, Ratings.colour(entry.rank()), false);
        x += SYNAPTIC$RANK_WIDTH;

        int faceTop = y + (SYNAPTIC$ROW - SYNAPTIC$FACE) / 2;
        synaptic$drawFace(graphics, minecraft, row, x, faceTop);
        x += SYNAPTIC$FACE + SYNAPTIC$PAD;

        int nameColour = row.online() ? SYNAPTIC$NAME_TEXT : SYNAPTIC$OFFLINE_TEXT;
        graphics.text(font, row.name(), x, y + (SYNAPTIC$ROW - 8) / 2, nameColour, false);

        // Figures hang off the right edge, so the block of numbers stays aligned
        // however long the names get.
        int figureRight = right - SYNAPTIC$PAD;
        for (int c = columns.length - 1; c >= 0; c--) {
            int columnLeft = figureRight - columns[c];
            String value = values[c];
            String label = SYNAPTIC$LABELS[c];
            // Value over label, both right-aligned: the number is what is being
            // read and the label only has to be findable.
            graphics.text(font, value, figureRight - font.width(value), y + 3,
                row.online() ? SYNAPTIC$VALUE_TEXT : SYNAPTIC$OFFLINE_TEXT, false);
            graphics.text(font, label, figureRight - font.width(label), y + 12,
                SYNAPTIC$LABEL_TEXT, false);
            figureRight = columnLeft - SYNAPTIC$COLUMN_GAP;
        }
    }

    /** A hairline round the panel, drawn as four one-pixel fills. */
    @Unique
    private static void synaptic$outline(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, top + 1, SYNAPTIC$EDGE);
        graphics.fill(left, bottom - 1, right, bottom, SYNAPTIC$EDGE);
        graphics.fill(left, top, left + 1, bottom, SYNAPTIC$EDGE);
        graphics.fill(right - 1, top, right, bottom, SYNAPTIC$EDGE);
    }

    /**
     * The face from the player's own skin, hat layer included.
     * <p>
     * A skin is 64 by 64 with the face at (8,8) and the hat over it at (40,8),
     * so the coordinates below are those pixels as fractions of the sheet.
     * Anyone the client has never met — everyone offline — falls back to the
     * skin their id would have been given by default.
     */
    @Unique
    private static void synaptic$drawFace(GuiGraphicsExtractor graphics, Minecraft minecraft,
                                          StatsSyncPayload.Row row, int x, int y) {
        PlayerInfo info = minecraft.getConnection() == null
            ? null : minecraft.getConnection().getPlayerInfo(row.id());
        Identifier skin = info != null
            ? info.getSkin().body().texturePath()
            : DefaultPlayerSkin.get(row.id()).body().texturePath();

        int right = x + SYNAPTIC$FACE;
        int bottom = y + SYNAPTIC$FACE;
        graphics.blit(skin, x, y, right, bottom, 8 / 64.0F, 16 / 64.0F, 8 / 64.0F, 16 / 64.0F);
        graphics.blit(skin, x, y, right, bottom, 40 / 64.0F, 48 / 64.0F, 8 / 64.0F, 16 / 64.0F);
        if (!row.online()) graphics.fill(x, y, right, bottom, SYNAPTIC$OFFLINE_VEIL);
    }

    /** Damage is counted in half-hearts internally; the table speaks in hearts. */
    @Unique
    private static String synaptic$hearts(float damage) {
        float value = Math.round(damage / 2.0F * 10.0F) / 10.0F;
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    /** Session experience runs into the thousands; four digits of it is noise. */
    @Unique
    private static String synaptic$count(int total) {
        if (total < 1000) return String.valueOf(total);
        return Math.round(total / 100.0F) / 10.0F + "k";
    }
}
