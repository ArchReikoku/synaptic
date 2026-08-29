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
    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 140;
    private static final int SPACING = 6;
    private static final int FOOTER_MARGIN = 10;

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
    private int pending;

    public SynapticSettingsScreen() {
        super(Component.literal("Synaptic Settings"));
        // Set here and not in init(), which runs again on every rebuild and would
        // throw away edits that have not been sent yet.
        this.pending = SynapticConfig.bits();
        Minecraft minecraft = Minecraft.getInstance();
        this.editable = minecraft.player != null
            && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
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
            : Component.literal("Operators only — you can look, not change")
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
            for (int i = 0; i < features.size(); i++) {
                boolean lastAndOdd = i == features.size() - 1 && features.size() % COLUMNS != 0;
                // An odd group's final button spans both columns rather than
                // sitting in the left one with a hole beside it.
                if (lastAndOdd) rows.addChild(toggleFor(features.get(i)), COLUMNS, centred());
                else rows.addChild(toggleFor(features.get(i)), centred());
            }
        }

        // Set apart from the toggles above: these two commit or discard the lot,
        // and reading as just another row invites clicking one by accident.
        LayoutSettings footer = centred().paddingTop(FOOTER_MARGIN);
        rows.addChild(Button.builder(Component.literal("Done"), button -> {
            if (editable && pending != SynapticConfig.bits()) {
                ClientPlayNetworking.send(new ConfigUpdatePayload(pending));
            }
            onClose();
        }).width(BUTTON_WIDTH).build(), footer);
        rows.addChild(Button.builder(Component.literal("Cancel"), button -> onClose())
            .width(BUTTON_WIDTH).build(), footer);

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(8, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);
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

    private Component labelFor(Feature feature) {
        boolean on = wants(feature);
        return Component.literal(feature.label() + ": ")
            .append(Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private record Warning(List<String> lines, ChatFormatting colour) {}
}
