package com.synaptic.client;

import java.util.ArrayList;
import java.util.List;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.ConfigUpdatePayload;
import com.synaptic.net.NextRunPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Feature toggles for the whole server, opened with the Synaptic key (K by
 * default). Buttons carry a short name and explain themselves on hover.
 * <p>
 * Built entirely from stock widgets laid out by a {@link GridLayout} — this
 * version of the game replaced the old immediate-mode drawing API, and letting
 * the widgets draw themselves avoids touching the new one at all.
 * <p>
 * Every child is added with its own centring {@link LayoutSettings}. This game
 * version dropped StringWidget's alignment methods, and the widget reports its
 * own width as the width of its text, so text is only centred by centring the
 * whole widget inside its cell — which in turn only works if the text is already
 * there when the layout runs. That is why a toggle carrying a warning rebuilds
 * the screen rather than filling in reserved blank lines: the rebuilt layout
 * measures the real text, and no dead space is held open when there is nothing
 * to say.
 * <p>
 * Nothing is sent until Done: the pending mask is edited locally so a misclick
 * can be walked back with Cancel, and one packet carries the result. The server
 * re-checks permission regardless of what this screen allows.
 */
public final class SynapticSettingsScreen extends Screen {
    // Three columns, not two. Twenty toggles down a two-column grid runs off
    // the bottom of the screen at any normal GUI scale, and the footer buttons
    // with it — which is how a feature that is in this list reads as missing.
    private static final int COLUMNS = 3;
    private static final int BUTTON_WIDTH = 106;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 6;
    private static final int FOOTER_MARGIN = 10;
    /** How far the corner buttons sit from the edges they are pinned to. */
    private static final int CORNER_MARGIN = 8;

    private static final List<String> MERGE_WARNING = List.of(
        "Shared inventory ON merges everyone's items",
        "into one 36-slot inventory.",
        "Anything that does not fit is destroyed.");
    private static final List<String> SPLIT_WARNING = List.of(
        "Shared inventory OFF stops items syncing.",
        "Turning it back on later merges everyone's",
        "inventories and destroys the overflow.");
    private static final List<String> MINING_WARNING = List.of(
        "Tool Swap Mining OFF: when another player",
        "picks up items the shared inventory changes,",
        "which resets your mining progress mid-block",
        "and can stop you breaking it at all.");

    private final boolean editable;
    /** Where Done and Cancel go back to, or null to close to the game. */
    private final Screen parent;
    private int pending;

    /** Opened by the key bind, from the game — closing means back to playing. */
    public SynapticSettingsScreen() {
        this(null);
    }

    /** Opened from another screen, which is where closing should land. */
    public SynapticSettingsScreen(Screen parent) {
        super(Component.literal("Synaptic Settings"));
        this.parent = parent;
        // Set here and not in init(), which runs again on every rebuild and would
        // throw away edits that have not been sent yet.
        this.pending = SynapticConfig.bits();
        // Straight from the server, which decided this per player and sent it
        // alongside the settings. Working the rule out again on this side is what
        // greyed the buttons out for a host playing without cheats.
        this.editable = SynapticConfig.editable();
    }

    private static LayoutSettings centred() {
        return LayoutSettings.defaults().alignHorizontallyCenter();
    }

    private boolean wants(Feature feature) {
        return (pending & feature.bit()) != 0;
    }

    /** What is about to change that the player should hear about first. */
    private List<Warning> warnings() {
        List<Warning> warnings = new ArrayList<>();
        boolean sharing = SynapticConfig.enabled(Feature.INVENTORY);
        if (sharing != wants(Feature.INVENTORY)) {
            warnings.add(new Warning(wants(Feature.INVENTORY) ? MERGE_WARNING : SPLIT_WARNING,
                ChatFormatting.RED));
        }
        // Only the off direction: this one exists to keep shared inventories from
        // breaking mining, so switching it back on costs nothing.
        if (SynapticConfig.enabled(Feature.KEEP_MINING_PROGRESS) && !wants(Feature.KEEP_MINING_PROGRESS)) {
            warnings.add(new Warning(MINING_WARNING, ChatFormatting.YELLOW));
        }
        return warnings;
    }

    @Override
    protected void init() {
        GridLayout grid = new GridLayout();
        grid.spacing(SPACING);
        GridLayout.RowHelper rows = grid.createRowHelper(COLUMNS);

        rows.addChild(label(this.title.copy().withStyle(ChatFormatting.BOLD)), COLUMNS, centred());
        rows.addChild(label(editable
            ? Component.literal("Applies to everyone on the server").withStyle(ChatFormatting.GRAY)
            : Component.literal("Only the host can change these — you can look")
                .withStyle(ChatFormatting.RED)), COLUMNS, centred());

        for (Warning warning : warnings()) {
            for (String line : warning.lines()) {
                rows.addChild(label(Component.literal(line).withStyle(warning.colour())),
                    COLUMNS, centred());
            }
        }

        for (Feature.Group group : Feature.Group.values()) {
            List<Feature> features = Feature.of(group);
            rows.addChild(label(Component.literal(group.title())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), COLUMNS, centred());
            int whole = features.size() - features.size() % COLUMNS;
            for (int i = 0; i < whole; i++) {
                rows.addChild(toggleFor(features.get(i)), centred());
            }
            // Whatever does not fill a row goes in as one spanning child, so the
            // last one or two sit centred instead of hugging the first column
            // with a hole beside them. Written for any remainder rather than for
            // the counts these groups happen to have today.
            if (whole < features.size()) {
                LinearLayout tail = LinearLayout.horizontal().spacing(SPACING);
                for (int i = whole; i < features.size(); i++) {
                    tail.addChild(toggleFor(features.get(i)));
                }
                rows.addChild(tail, COLUMNS, centred());
            }
        }

        // The pair goes in as ONE child spanning every column, so it always gets
        // a row to itself. Added as two children they were simply the next two
        // cells in the same grid: whenever the last group did not happen to fill
        // its row, Done dropped into the leftover cell beside a toggle and
        // Cancel started a row of its own underneath. Which of those it looked
        // like depended on how many features existed — removing one feature was
        // enough to rearrange the footer.
        LinearLayout footer = LinearLayout.horizontal().spacing(SPACING);
        footer.addChild(Button.builder(Component.literal("Done"), button -> {
            if (editable && pending != SynapticConfig.bits()) {
                ClientPlayNetworking.send(new ConfigUpdatePayload(pending));
            }
            onClose();
        }).width(BUTTON_WIDTH).build());
        footer.addChild(Button.builder(Component.literal("Cancel"), button -> onClose())
            .width(BUTTON_WIDTH).build());
        rows.addChild(footer, COLUMNS, centred().paddingTop(FOOTER_MARGIN));

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(8, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);

        addCornerButtons();
    }

    /**
     * The two buttons that are not settings, in the corners and out of the way.
     * <p>
     * Neither belongs in the grid. The grid is a list of things this screen
     * changes, ending in the two buttons that decide whether those changes
     * happen; a bug report and a fresh run are neither, and putting them in the
     * footer made Done and Cancel share a row with something that ignores both.
     * The corners are the part of this screen the grid never reaches.
     * <p>
     * Added after the grid has been placed and visited, so they are positioned
     * against the screen rather than against a layout — and so a rebuild puts
     * them back in the same corners whatever the grid did in between.
     */
    private void addCornerButtons() {
        addRenderableWidget(Button.builder(Component.literal("Report a bug"),
            button -> this.minecraft.setScreenAndShow(new BugReportScreen(this)))
            .bounds(CORNER_MARGIN, CORNER_MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Host only, like every other way of starting a run: it throws away the
        // world everybody is playing. The server refuses it regardless.
        if (!SynapticConfig.enabled(Feature.RUN_RESET) || !editable) return;
        addRenderableWidget(Button.builder(Component.literal("Next run"), button -> {
            // Deliberately not sending `pending` first. This abandons the world
            // the settings were about, so saving them on the way out would write
            // a change nobody gets to play — the run that starts reads the
            // settings actually in force, which is what the grid is showing.
            ClientPlayNetworking.send(new NextRunPayload());
            // Straight to the loading screen: generating candidates takes
            // seconds, and a button that does nothing visible for that long
            // reads as broken.
            LobbyClient.showTourScreen(Minecraft.getInstance());
        }).bounds(this.width - CORNER_MARGIN - BUTTON_WIDTH, CORNER_MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());
    }

    private Button toggleFor(Feature feature) {
        Button toggle = Button.builder(labelFor(feature), button -> {
            pending ^= feature.bit();
            // Warnings appear and disappear with the toggle, and only a fresh
            // layout can measure and centre them, so these two rebuild the screen
            // instead of just relabelling their own button.
            if (feature == Feature.INVENTORY || feature == Feature.KEEP_MINING_PROGRESS) {
                rebuildWidgets();
            } else {
                button.setMessage(labelFor(feature));
            }
        }).width(BUTTON_WIDTH).build();
        toggle.active = editable;
        toggle.setTooltip(Tooltip.create(Component.literal(feature.description())));
        return toggle;
    }

    /**
     * Sized to the text rather than to the column: StringWidget reports its text
     * width as its own, so a fixed width here would be ignored by the layout and
     * only confuse the centring.
     */
    private StringWidget label(Component text) {
        return new StringWidget(text, this.font);
    }

    @Override
    public void onClose() {
        if (parent == null) {
            super.onClose();
        } else {
            this.minecraft.setScreenAndShow(parent);
        }
    }

    private Component labelFor(Feature feature) {
        boolean on = wants(feature);
        return Component.literal(feature.label() + ": ")
            .append(Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private record Warning(List<String> lines, ChatFormatting colour) {}
}
