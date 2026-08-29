package com.synaptic.client;

import java.util.ArrayList;
import java.util.List;

import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.ConfigUpdatePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

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
 * whole widget inside its cell. Warning lines are kept shorter than the button
 * block for the same reason: a line wider than the grid would widen the columns
 * and push the two button rows apart.
 * <p>
 * Nothing is sent until Done: the pending mask is edited locally so a misclick
 * can be walked back with Cancel, and one packet carries the result. The server
 * re-checks permission regardless of what this screen allows.
 */
public final class SynapticSettingsScreen extends Screen {
    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 140;
    private static final int ROW_HEIGHT = 20;
    private static final int SPACING = 6;
    private static final int WARNING_LINES = 3;

    private static final List<String> MERGE_WARNING = List.of(
        "Shared inventory ON merges everyone's items",
        "into one 36-slot inventory.",
        "Anything that does not fit is destroyed.");
    private static final List<String> SPLIT_WARNING = List.of(
        "Shared inventory OFF stops items syncing.",
        "Turning it back on later merges everyone's",
        "inventories and destroys the overflow.");

    private final boolean editable;
    private final List<StringWidget> warnings = new ArrayList<>();
    private int pending;

    public SynapticSettingsScreen() {
        super(Component.literal("Synaptic Settings"));
        this.pending = SynapticConfig.bits();
        Minecraft minecraft = Minecraft.getInstance();
        this.editable = minecraft.player != null
            && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static LayoutSettings centred() {
        return LayoutSettings.defaults().alignHorizontallyCenter();
    }

    @Override
    protected void init() {
        this.pending = SynapticConfig.bits();
        this.warnings.clear();

        GridLayout grid = new GridLayout();
        grid.spacing(SPACING);
        GridLayout.RowHelper rows = grid.createRowHelper(COLUMNS);

        rows.addChild(label(this.title.copy().withStyle(ChatFormatting.BOLD)), COLUMNS, centred());
        rows.addChild(label(editable
            ? Component.literal("Applies to everyone on the server").withStyle(ChatFormatting.GRAY)
            : Component.literal("Operators only — you can look, not change")
                .withStyle(ChatFormatting.RED)), COLUMNS, centred());

        // Held open empty so the rows below do not jump when a warning appears.
        for (int i = 0; i < WARNING_LINES; i++) {
            StringWidget line = label(Component.empty());
            warnings.add(line);
            rows.addChild(line, COLUMNS, centred());
        }

        for (Feature.Group group : Feature.Group.values()) {
            List<Feature> features = Feature.of(group);
            rows.addChild(label(Component.literal(group.title())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), COLUMNS, centred());
            for (int i = 0; i < features.size(); i++) {
                boolean lastAndOdd = i == features.size() - 1 && features.size() % COLUMNS != 0;
                // An odd group's final button spans both columns rather than
                // sitting in the left one with a hole beside it.
                if (lastAndOdd) rows.addChild(toggleFor(features.get(i)), COLUMNS, centred());
                else rows.addChild(toggleFor(features.get(i)), centred());
            }
        }

        rows.addChild(Button.builder(Component.literal("Done"), button -> {
            if (editable && pending != SynapticConfig.bits()) {
                ClientPlayNetworking.send(new ConfigUpdatePayload(pending));
            }
            onClose();
        }).width(BUTTON_WIDTH).build(), centred());
        rows.addChild(Button.builder(Component.literal("Cancel"), button -> onClose())
            .width(BUTTON_WIDTH).build(), centred());

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(8, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);

        // After the layout, never before: this re-centres the warning lines, and
        // arrangeElements would overwrite that.
        refreshWarning();
    }

    /**
     * Shared inventory is the one toggle that destroys things, so it says what it
     * will do before Done rather than after. Turning it on merges every player's
     * inventory into a single 36-slot one and drops whatever will not fit;
     * turning it off is safe now but sets up that same merge for later.
     */
    private void refreshWarning() {
        boolean live = SynapticConfig.enabled(Feature.INVENTORY);
        boolean wanted = (pending & Feature.INVENTORY.bit()) != 0;
        List<String> lines = live == wanted ? List.of() : wanted ? MERGE_WARNING : SPLIT_WARNING;
        for (int i = 0; i < warnings.size(); i++) {
            StringWidget line = warnings.get(i);
            line.setMessage(i < lines.size()
                ? Component.literal(lines.get(i)).withStyle(ChatFormatting.RED)
                : Component.empty());
            // The grid arranges once, in init(), when these lines are still empty
            // — and a zero-width widget "centred" in its span lands with its left
            // edge on the middle of it. Filling in the text later does not re-run
            // the layout, so the line would draw from that midpoint rightwards.
            // Re-centre by hand on every change; the grid is centred on the same
            // midpoint, so the two agree.
            line.setX(this.width / 2 - line.getWidth() / 2);
        }
    }

    private Button toggleFor(Feature feature) {
        Button toggle = Button.builder(labelFor(feature), button -> {
            pending ^= feature.bit();
            button.setMessage(labelFor(feature));
            if (feature == Feature.INVENTORY) refreshWarning();
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

    private Component labelFor(Feature feature) {
        boolean on = (pending & feature.bit()) != 0;
        return Component.literal(feature.label() + ": ")
            .append(Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
