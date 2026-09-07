package com.synaptic.client;

import java.io.IOException;
import java.nio.file.Path;

import com.synaptic.report.SynapticLog;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Describe a bug, save it, and — if you want to — carry it to GitHub or open
 * what was written.
 * <p>
 * Laid out by hand rather than by a {@link net.minecraft.client.gui.layouts.GridLayout}
 * like the settings screen next door. The grid is the right tool for twenty
 * buttons of one size; this screen is a tall text box with a line above and
 * three rows below, and the arithmetic for that is shorter and easier to follow
 * than the cell spans that would describe it.
 * <p>
 * Sized to the window rather than to a number: the content takes four fifths of
 * the width, and the text box takes whatever height is left once the rows above
 * and below have had theirs. A fixed size would either waste a large window or
 * overflow a small one, and this screen is mostly a place to type — the typing
 * room should be whatever the window can spare.
 * <p>
 * Every label is positioned from its own measured width instead of being handed
 * a box to sit in the middle of. This game version dropped StringWidget's
 * alignment methods, so a widget wider than its text sits wherever the default
 * puts it — measuring first means the centring does not depend on knowing which
 * default that is.
 * <p>
 * The typed text and the saved path live in fields, not in the widgets, because
 * saving rebuilds the screen to show where the file went — and a rebuild throws
 * every widget away. Nothing typed is ever lost to pressing a button.
 */
public final class BugReportScreen extends Screen {
    /** Four fifths of the window, so the box grows with it and still has edges. */
    private static final float CONTENT_FRACTION = 0.8f;
    private static final int MARGIN = 16;
    private static final int BOX_MIN = 60;
    private static final int BOX_MAX = 140;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LINE = 12;
    private static final int GAP = 12;
    private static final int SPACING = 6;
    private static final int DESCRIPTION_LIMIT = 4000;

    private static final Component NOTE = Component.literal(
        "Saved to your game folder. Nothing is sent anywhere on its own.");
    private static final Component PLACEHOLDER = Component.literal(
        "What you expected, what happened instead, and how to make it happen again.");

    /** Where Cancel and Escape go back to — the screen this was opened from. */
    private final Screen parent;
    /** Held across rebuilds; the edit box is thrown away by every one of them. */
    private String description = "";
    private Path saved;
    private Component status = NOTE;
    private ChatFormatting statusColour = ChatFormatting.GRAY;

    public BugReportScreen(Screen parent) {
        super(Component.literal("Report a Synaptic bug"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = (int) (this.width * CONTENT_FRACTION);
        int left = (this.width - width) / 2;

        // Everything that is not the text box, so the box can be told what is
        // left rather than being guessed at and overflowing a short window.
        int chrome = LINE + SPACING + LINE + GAP + GAP + LINE + GAP
            + 3 * BUTTON_HEIGHT + 2 * SPACING;
        int box = Math.max(BOX_MIN, Math.min(BOX_MAX, this.height - chrome - 2 * MARGIN));
        int y = Math.max(MARGIN, (this.height - (chrome + box)) / 2);

        addRenderableWidget(label(this.title.copy().withStyle(ChatFormatting.BOLD), y));
        y += LINE + SPACING;
        addRenderableWidget(label(status.copy().withStyle(statusColour), y));
        y += LINE + GAP;

        MultiLineEditBox editor = MultiLineEditBox.builder()
            .setX(left)
            .setY(y)
            .setPlaceholder(PLACEHOLDER)
            .build(this.font, width, box, Component.literal("Description"));
        editor.setCharacterLimit(DESCRIPTION_LIMIT);
        editor.setValue(description);
        editor.setValueListener(typed -> description = typed);
        addRenderableWidget(editor);
        // Typing should start working without a click; this screen is only ever
        // opened by somebody who came here to write something.
        setInitialFocus(editor);
        y += box + GAP;

        addRenderableWidget(label(Component.literal(
            "Versions, your Synaptic settings and this game's Synaptic log are included.")
            .withStyle(ChatFormatting.DARK_GRAY), y));
        y += LINE + GAP;

        // Writing first, then reading what was written, then leaving. Each row
        // splits the content width evenly, and the button on the right is
        // measured to the far edge so the rounding is spent rather than left as
        // a gap that does not line up with the row above.
        int half = (width - SPACING) / 2;
        int right = left + half + SPACING;

        addRenderableWidget(Button.builder(Component.literal("Save report"), button -> save())
            .bounds(left, y, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal("Save and open GitHub"), button -> saveAndOpen())
            .bounds(right, y, left + width - right, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + SPACING;

        // Nothing to open until something has been written this session. Greyed
        // rather than hidden, so the row does not change shape under the cursor.
        Button open = Button.builder(Component.literal("Open report"), button -> openPath(saved))
            .bounds(left, y, half, BUTTON_HEIGHT).build();
        open.active = saved != null;
        addRenderableWidget(open);
        addRenderableWidget(Button.builder(Component.literal("Open report folder"), button -> openFolder())
            .bounds(right, y, left + width - right, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + SPACING;

        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
            .bounds(left, y, width, BUTTON_HEIGHT).build());
    }

    /** Writes the file and reports where it went, or why it did not. */
    private Path save() {
        try {
            saved = BugReport.write(BugReport.compose(description));
            status = Component.literal("Saved to " + saved);
            statusColour = ChatFormatting.GREEN;
        } catch (IOException e) {
            saved = null;
            status = Component.literal("Could not write the report — see the log.");
            statusColour = ChatFormatting.RED;
            SynapticLog.get().warn("Could not write a bug report", e);
        }
        // The status line is what changed, and only a fresh layout can measure
        // and centre a path nobody knew the width of a moment ago. It is also
        // what enables Open report, which the same rebuild picks up.
        rebuildWidgets();
        return saved;
    }

    /**
     * The file first, the browser second. If the write fails the link is not
     * offered at all: its body names the file as the thing to attach, and a link
     * pointing at a file that was never written is worse than no link.
     */
    private void saveAndOpen() {
        Path file = save();
        if (file == null) return;
        // The game's own confirmation, not ours. Leaving the game for a browser
        // is the player's decision to make, and this is the prompt they already
        // know from every other link in Minecraft.
        ConfirmLinkScreen.confirmLinkNow(this, BugReport.issueUri(description, file));
    }

    /**
     * The folder, whether or not anything has been written into it yet — made on
     * the way so the first press opens somewhere real rather than failing at a
     * path that is correct but not there.
     */
    private void openFolder() {
        try {
            openPath(BugReport.ensureFolder());
        } catch (IOException e) {
            status = Component.literal("Could not open the report folder — see the log.");
            statusColour = ChatFormatting.RED;
            SynapticLog.get().warn("Could not open the bug report folder", e);
            rebuildWidgets();
        }
    }

    /** Hands a path to the desktop. Silent about it, as the game is elsewhere. */
    private void openPath(Path path) {
        if (path == null) return;
        Util.getPlatform().openPath(path);
    }

    /**
     * Sized to its own text, so the box it occupies is exactly what it draws and
     * the centring cannot be undone by an alignment default.
     */
    private StringWidget label(Component text, int y) {
        int width = this.font.width(text);
        return new StringWidget((this.width - width) / 2, y, width, LINE, text, this.font);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }
}
