package com.synaptic.mixin;

import java.util.ArrayList;
import java.util.List;

import com.synaptic.client.ChatMirror;
import com.synaptic.client.LobbyClient;
import com.synaptic.client.PlayerHeads;
import com.synaptic.client.Ratings;
import com.synaptic.client.SessionTable;
import com.synaptic.client.WipeReport;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.NextRunPayload;
import com.synaptic.net.StatsSyncPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the death screen with the run's obituary.
 * <p>
 * A shared death is a group event, so the screen reads as one: whose death it
 * was and how, then what the whole session has amounted to. Vanilla's own
 * buttons are kept — they still do what they say — but moved into a row in the
 * bottom corner where they read as an afterthought rather than the point.
 * <p>
 * Vanilla's drawing is cancelled and {@code Screen}'s called in its place. That
 * is exactly what {@link DeathScreen} does before adding its own centred "Game
 * Over" and score, so skipping its body drops those two lines and keeps the
 * widgets, which is all that is wanted.
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {
    @Unique
    private static final int SYNAPTIC$BACKDROP = 0xD8000000;
    @Unique
    private static final int SYNAPTIC$PANEL = 0x66000000;
    @Unique
    private static final int SYNAPTIC$EDGE = 0x28FFFFFF;
    @Unique
    private static final int SYNAPTIC$STRIPE = 0x10FFFFFF;
    @Unique
    private static final int SYNAPTIC$SELF_ROW = 0x18FFFFFF;
    @Unique
    private static final int SYNAPTIC$TITLE = 0xFFFF4F45;
    @Unique
    private static final int SYNAPTIC$TITLE_DROP = 0xFF3D0F0C;
    @Unique
    private static final int SYNAPTIC$TEXT = 0xFFF2F2F5;
    @Unique
    private static final int SYNAPTIC$MUTED = 0xFF8A8A96;
    @Unique
    private static final int SYNAPTIC$LABEL = 0xFF6E6E7A;
    @Unique
    private static final int SYNAPTIC$OFFLINE = 0xFF5A5A64;
    @Unique
    private static final int SYNAPTIC$OFFLINE_VEIL = 0xA00B0B0F;
    /** The chosen view, and the one on offer. */
    @Unique
    private static final int SYNAPTIC$TAB_ON = 0xFFFFFFFF;
    @Unique
    private static final int SYNAPTIC$TAB_OFF = 0xFF6E6E7A;
    @Unique
    private static final int SYNAPTIC$TAB_HEIGHT = 12;

    /** How far each band reaches in, as a share of the edge it comes from. */
    @Unique
    private static final int SYNAPTIC$GLOW_PERCENT = 13;
    /**
     * Alpha against the edge itself, out of 255 — a hint rather than a colour.
     * Anything stronger stops reading as light on the frame and starts reading
     * as a red border drawn round the picture.
     */
    @Unique
    private static final float SYNAPTIC$GLOW_ALPHA = 13.0F;
    @Unique
    private static final int SYNAPTIC$GLOW_RED = 0xE0342A;

    @Unique
    private static final String[] SYNAPTIC$LABELS =
        {"dealt", "taken", "food", "hunger", "xp", "unlocks", "deaths", "score"};
    @Unique
    private static final int SYNAPTIC$FACE = 16;
    /** Room for the placing, wide enough for two digits. */
    @Unique
    private static final int SYNAPTIC$RANK_WIDTH = 16;
    @Unique
    private static final int SYNAPTIC$ROW = 22;
    @Unique
    private static final int SYNAPTIC$GAP = 16;
    @Unique
    private static final int SYNAPTIC$BUTTON_WIDTH = 104;
    @Unique
    private static final int SYNAPTIC$BUTTON_HEIGHT = 20;
    @Unique
    private static final int SYNAPTIC$MARGIN = 10;
    /** The mirrored chat panel under the table. */
    @Unique
    private static final int SYNAPTIC$CHAT_LINE = 10;
    @Unique
    private static final int SYNAPTIC$CHAT_SCRIM = 0x4D000000;
    /** Rows of the table on screen at once before it starts scrolling. */
    @Unique
    private static final int SYNAPTIC$MAX_ROWS = 6;

    @Unique
    private int synaptic$scroll;
    /**
     * Which span the table is showing. The run by default: a report is about the
     * attempt that just ended, and the world total is the thing you go looking
     * for rather than the thing you are handed.
     */
    @Unique
    private boolean synaptic$showRun = true;
    /** Where the two labels ended up, so a click can be matched to one. */
    @Unique
    private int synaptic$runTabLeft;
    @Unique
    private int synaptic$runTabRight;
    @Unique
    private int synaptic$allTabLeft;
    @Unique
    private int synaptic$allTabRight;
    @Unique
    private int synaptic$tabsTop;

    private DeathScreenMixin() {
        super(null);
    }

    /**
     * Rebuild the button row, and add Next run to the end of it for the host.
     * <p>
     * Vanilla's buttons are found rather than replaced, so whatever it decided
     * to offer — Respawn in a normal world, Spectate in a hardcore one — is what
     * gets moved. The last of them is always the way out to the title screen,
     * which is renamed: on a shared run it is not a menu you are going back to,
     * it is everybody else you are leaving.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void synaptic$rebuildButtons(CallbackInfo ci) {
        if (!SynapticConfig.enabled(Feature.DEATH_REPORT)) return;

        List<Button> buttons = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) buttons.add(button);
        }
        if (!buttons.isEmpty()) {
            buttons.get(buttons.size() - 1).setMessage(Component.literal("Leave world"));
        }

        // Host only, and not shown at all to anyone else. Starting a run throws
        // away the world everybody is playing, so it is theirs to press — the
        // server enforces that too, but a guest should never be offered it.
        if (SynapticConfig.enabled(Feature.RUN_RESET) && SynapticConfig.editable()) {
            Button next = Button.builder(Component.literal("Next run"), button -> {
                ClientPlayNetworking.send(new NextRunPayload());
                // Straight to the loading screen rather than waiting for the
                // server: generating candidates takes seconds, and a button that
                // does nothing visible for that long reads as broken.
                LobbyClient.showTourScreen(Minecraft.getInstance());
            }).bounds(0, 0, SYNAPTIC$BUTTON_WIDTH, SYNAPTIC$BUTTON_HEIGHT).build();
            this.addRenderableWidget(next);
            buttons.add(next);
        }

        // Laid out right to left from the corner, so Next run sits outermost
        // where the thumb goes and the way out is furthest from it.
        int x = this.width - SYNAPTIC$MARGIN;
        int y = this.height - SYNAPTIC$MARGIN - SYNAPTIC$BUTTON_HEIGHT;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Button button = buttons.get(i);
            button.setWidth(SYNAPTIC$BUTTON_WIDTH);
            x -= SYNAPTIC$BUTTON_WIDTH;
            button.setX(x);
            button.setY(y);
            x -= 6;
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void synaptic$drawReport(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     float partialTick, CallbackInfo ci) {
        if (!SynapticConfig.enabled(Feature.DEATH_REPORT)) return;
        Font font = this.font;
        if (font == null) return;

        graphics.fill(0, 0, this.width, this.height, SYNAPTIC$BACKDROP);
        synaptic$drawGlow(graphics);
        int below = synaptic$drawHeader(graphics, font);
        below = synaptic$drawTable(graphics, font, below);
        synaptic$drawChat(graphics, font, below + 10);

        // Widgets last and by hand: cancelling vanilla's body would otherwise
        // take the buttons with it.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ci.cancel();
    }

    /**
     * Red bleeding inward from every edge.
     * <p>
     * Four gradients rather than one ring, each measured against the edge it
     * comes from: the sides reach in an eighth of the width, the top and bottom
     * an eighth of the height. A single square ring would be as deep at the top
     * as at the sides, which on a wide screen reads as a red box drawn round the
     * picture rather than light falling on it.
     * <p>
     * Each band is laid down as full-length one-pixel lines, so the alpha ramps
     * cleanly from a third at the edge to nothing at the inner limit and the
     * corners deepen on their own where two bands cross. Kept deliberately faint
     * — it is a wash over the whole frame, not a border.
     */
    @Unique
    private void synaptic$drawGlow(GuiGraphicsExtractor graphics) {
        int across = this.width * SYNAPTIC$GLOW_PERCENT / 100;
        for (int i = 0; i < across; i++) {
            int colour = synaptic$glow(i, across);
            if (colour == 0) continue;
            graphics.fill(i, 0, i + 1, this.height, colour);
            graphics.fill(this.width - i - 1, 0, this.width - i, this.height, colour);
        }
        int down = this.height * SYNAPTIC$GLOW_PERCENT / 100;
        for (int i = 0; i < down; i++) {
            int colour = synaptic$glow(i, down);
            if (colour == 0) continue;
            graphics.fill(0, i, this.width, i + 1, colour);
            graphics.fill(0, this.height - i - 1, this.width, this.height - i, colour);
        }
    }

    /** One line of a band: strongest against the edge, gone by the inner limit. */
    @Unique
    private static int synaptic$glow(int step, int depth) {
        float fade = 1.0F - (float) step / depth;
        int alpha = Math.round(fade * fade * SYNAPTIC$GLOW_ALPHA);
        return alpha <= 0 ? 0 : (alpha << 24) | SYNAPTIC$GLOW_RED;
    }

    /** The face of whoever ended it, their name, and how. Returns the y to carry on from. */
    @Unique
    private int synaptic$drawHeader(GuiGraphicsExtractor graphics, Font font) {
        int top = Math.max(16, this.height / 6);
        int centre = this.width / 2;

        // Uppercase and cut, in the manner of the game's own logo: the vanilla
        // font is already a pixel face, so scaling it up is all the treatment it
        // needs, with one dark copy behind for the carving.
        String heading = "RUN OVER";
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centre, top);
        pose.scale(3.0F, 3.0F);
        int half = font.width(heading) / 2;
        graphics.text(font, heading, -half + 1, 1, SYNAPTIC$TITLE_DROP, false);
        graphics.text(font, heading, -half, 0, SYNAPTIC$TITLE, false);
        pose.popMatrix();

        int y = top + 34;
        if (WipeReport.present()) {
            // Face and name on one line, centred together rather than the name
            // centred with a head hung off it.
            String name = WipeReport.name();
            int block = SYNAPTIC$FACE + 6 + font.width(name);
            int x = centre - block / 2;
            PlayerHeads.draw(graphics, WipeReport.victim(), x, y, SYNAPTIC$FACE, 0);
            graphics.text(font, name, x + SYNAPTIC$FACE + 6, y + (SYNAPTIC$FACE - 8) / 2,
                SYNAPTIC$TEXT, false);
            y += SYNAPTIC$FACE + 6;

            // The game's death messages open with the player's name, which is
            // already sitting above in bigger type. Said twice it reads as a
            // stutter, so the line is trimmed back to what happened.
            String cause = WipeReport.cause();
            if (cause.startsWith(name + " ")) cause = cause.substring(name.length() + 1);
            graphics.text(font, cause, centre - font.width(cause) / 2, y, SYNAPTIC$MUTED, false);
            y += 14;
        }

        String world = "World " + SessionTable.session();
        String attempt = "#" + SessionTable.run();
        String clock = SessionTable.clock() + " survived";
        String total = SessionTable.totalClock() + " total";
        int span = font.width(world) + 5 + font.width(attempt) + 8 + font.width(clock)
            + 8 + font.width(total);
        int x = centre - span / 2;
        graphics.text(font, world, x, y, SYNAPTIC$MUTED, false);
        x += font.width(world) + 5;
        graphics.text(font, attempt, x, y, SYNAPTIC$LABEL, false);
        x += font.width(attempt) + 8;
        graphics.text(font, clock, x, y, SYNAPTIC$MUTED, false);
        x += font.width(clock) + 8;
        graphics.text(font, total, x, y, SYNAPTIC$LABEL, false);
        return y + 20;
    }

    /**
     * The session so far, one row per player, on the same figures the tab list
     * shows. Returns the y it finished at, so the chat below knows where to go.
     */
    @Unique
    private int synaptic$drawTable(GuiGraphicsExtractor graphics, Font font, int top) {
        List<StatsSyncPayload.Row> rows = SessionTable.rows();
        if (rows.isEmpty()) return top;

        List<Ratings.Ranked> ranked = Ratings.rank(rows,
            synaptic$showRun ? StatsSyncPayload.Row::run : StatsSyncPayload.Row::all);
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

        int[] columns = new int[SYNAPTIC$LABELS.length];
        for (int c = 0; c < columns.length; c++) {
            columns[c] = font.width(SYNAPTIC$LABELS[c]);
            for (String[] value : values) columns[c] = Math.max(columns[c], font.width(value[c]));
        }
        int nameWidth = 0;
        for (Ratings.Ranked entry : ranked) {
            nameWidth = Math.max(nameWidth, font.width(entry.row().name()));
        }

        int width = SYNAPTIC$GAP + SYNAPTIC$RANK_WIDTH + SYNAPTIC$FACE + 8 + nameWidth;
        for (int column : columns) width += column + SYNAPTIC$GAP;
        width += SYNAPTIC$GAP;
        // Given a floor as well as a ceiling: a narrow table centred under a
        // wide headline reads as an afterthought, so the panel holds a steady
        // band across the middle of the screen whatever the numbers are.
        width = Math.max(width, this.width * 62 / 100);
        width = Math.min(width, this.width - 40);

        int left = (this.width - width) / 2;
        int right = left + width;

        // Capped rather than allowed to grow: a full server would otherwise
        // push the table down over the chat and the buttons both. Past the cap
        // it scrolls, and the count of what is hidden is drawn under it.
        int visible = Math.min(rows.size(), SYNAPTIC$MAX_ROWS);
        int hidden = rows.size() - visible;
        synaptic$scroll = Math.max(0, Math.min(synaptic$scroll, hidden));
        int height = SYNAPTIC$ROW * visible + 14;

        synaptic$drawViewTabs(graphics, font, right, top);
        graphics.fill(left, top, right, top + height, SYNAPTIC$PANEL);
        graphics.fill(left, top, right, top + 1, SYNAPTIC$EDGE);
        graphics.fill(left, top + height - 1, right, top + height, SYNAPTIC$EDGE);

        int y = top + 8;
        for (int i = synaptic$scroll; i < synaptic$scroll + visible; i++) {
            Ratings.Ranked entry = ranked.get(i);
            StatsSyncPayload.Row row = entry.row();
            Minecraft minecraft = Minecraft.getInstance();
            boolean self = minecraft.player != null && minecraft.player.getUUID().equals(row.id());
            if (self) graphics.fill(left, y - 3, right, y + SYNAPTIC$ROW - 3, SYNAPTIC$SELF_ROW);
            else if (i % 2 == 1) graphics.fill(left, y - 3, right, y + SYNAPTIC$ROW - 3, SYNAPTIC$STRIPE);

            int colour = row.online() ? SYNAPTIC$TEXT : SYNAPTIC$OFFLINE;
            int x = left + SYNAPTIC$GAP;
            String place = String.valueOf(entry.rank());
            graphics.text(font, place, x + SYNAPTIC$RANK_WIDTH - 6 - font.width(place), y + 5,
                Ratings.colour(entry.rank()), false);
            x += SYNAPTIC$RANK_WIDTH;
            PlayerHeads.draw(graphics, row.id(), x, y + 1, SYNAPTIC$FACE,
                row.online() ? 0 : SYNAPTIC$OFFLINE_VEIL);
            graphics.text(font, row.name(), x + SYNAPTIC$FACE + 8, y + 5, colour, false);

            // Figures hang off the right edge so the block of numbers stays
            // aligned however long the names get.
            int figure = right - SYNAPTIC$GAP;
            for (int c = columns.length - 1; c >= 0; c--) {
                String value = values[i][c];
                String label = SYNAPTIC$LABELS[c];
                graphics.text(font, value, figure - font.width(value), y, colour, false);
                graphics.text(font, label, figure - font.width(label), y + 10, SYNAPTIC$LABEL, false);
                figure -= columns[c] + SYNAPTIC$GAP;
            }
            y += SYNAPTIC$ROW;
        }

        if (hidden > 0) {
            String more = synaptic$scroll + " above, " + (hidden - synaptic$scroll)
                + " below — scroll";
            graphics.text(font, more, (this.width - font.width(more)) / 2, top + height + 4,
                SYNAPTIC$LABEL, false);
            return top + height + 14;
        }
        return top + height;
    }

    /**
     * The last few things said, drawn into the report.
     * <p>
     * A copy rather than the real chat: the HUD draws that behind every screen,
     * so the only ways to show it are to leave a hole in the backdrop or to
     * repaint it here. Repainting puts it where it belongs — centred under the
     * table, in the report's own width — and the lines keep the colours they had,
     * so the damage that ended the run still reads in red.
     */
    @Unique
    private void synaptic$drawChat(GuiGraphicsExtractor graphics, Font font, int top) {
        List<Component> lines = ChatMirror.lines();
        if (lines.isEmpty()) return;

        int width = 0;
        for (Component line : lines) width = Math.max(width, font.width(line));
        width = Math.min(width + SYNAPTIC$GAP * 2, this.width - 40);
        int height = lines.size() * SYNAPTIC$CHAT_LINE + 10;

        // Never into the button row: the report is worth more than the tail of
        // the chat, so this is what gets cut when the screen runs out of room.
        int limit = this.height - SYNAPTIC$MARGIN * 2 - SYNAPTIC$BUTTON_HEIGHT;
        int room = (limit - top - 10) / SYNAPTIC$CHAT_LINE;
        if (room < 1) return;
        if (lines.size() > room) {
            lines = lines.subList(lines.size() - room, lines.size());
            height = lines.size() * SYNAPTIC$CHAT_LINE + 10;
        }

        int left = (this.width - width) / 2;
        graphics.fill(left, top, left + width, top + height, SYNAPTIC$CHAT_SCRIM);

        int y = top + 5;
        for (Component line : lines) {
            graphics.text(font, line, left + SYNAPTIC$GAP, y, SYNAPTIC$TEXT, false);
            y += SYNAPTIC$CHAT_LINE;
        }
    }

    /** The table scrolls when there are more players than fit. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (SynapticConfig.enabled(Feature.DEATH_REPORT)) {
            synaptic$scroll = Math.max(0, synaptic$scroll - (int) Math.signum(dy));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    /**
     * The two views, sitting on top of the table rather than inside it.
     * <p>
     * Plain text, because they are a choice about what you are reading and not a
     * thing to press: the one in force is white and the other is grey and a size
     * smaller, which says which is which without a border round either.
     */
    @Unique
    private void synaptic$drawViewTabs(GuiGraphicsExtractor graphics, Font font, int right, int top) {
        String all = "Overall stats";
        String run = "This run";
        int allWidth = font.width(all);
        int runWidth = font.width(run);

        synaptic$tabsTop = top - SYNAPTIC$TAB_HEIGHT - 2;
        synaptic$allTabRight = right;
        synaptic$allTabLeft = right - allWidth;
        synaptic$runTabRight = synaptic$allTabLeft - 10;
        synaptic$runTabLeft = synaptic$runTabRight - runWidth;

        graphics.text(font, run, synaptic$runTabLeft, synaptic$tabsTop + 2,
            synaptic$showRun ? SYNAPTIC$TAB_ON : SYNAPTIC$TAB_OFF, false);
        graphics.text(font, all, synaptic$allTabLeft, synaptic$tabsTop + 2,
            synaptic$showRun ? SYNAPTIC$TAB_OFF : SYNAPTIC$TAB_ON, false);
        // A rule under whichever is in force, the only mark either one gets.
        int underlineLeft = synaptic$showRun ? synaptic$runTabLeft : synaptic$allTabLeft;
        int underlineRight = synaptic$showRun ? synaptic$runTabRight : synaptic$allTabRight;
        graphics.fill(underlineLeft, synaptic$tabsTop + 11, underlineRight,
            synaptic$tabsTop + 12, SYNAPTIC$TAB_ON);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (SynapticConfig.enabled(Feature.DEATH_REPORT)) {
            double x = event.x();
            double y = event.y();
            boolean onRow = y >= synaptic$tabsTop && y <= synaptic$tabsTop + SYNAPTIC$TAB_HEIGHT;
            if (onRow && x >= synaptic$runTabLeft && x <= synaptic$runTabRight) {
                synaptic$showRun = true;
                synaptic$scroll = 0;
                return true;
            }
            if (onRow && x >= synaptic$allTabLeft && x <= synaptic$allTabRight) {
                synaptic$showRun = false;
                synaptic$scroll = 0;
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    /** Damage is counted in half-hearts internally; the report speaks in hearts. */
    @Unique
    private static String synaptic$hearts(float damage) {
        float value = Math.round(damage / 2.0F * 10.0F) / 10.0F;
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }

    @Unique
    private static String synaptic$count(int total) {
        if (total < 1000) return String.valueOf(total);
        return Math.round(total / 100.0F) / 10.0F + "k";
    }
}
