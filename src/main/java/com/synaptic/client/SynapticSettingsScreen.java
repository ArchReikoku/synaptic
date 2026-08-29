package com.synaptic.client;

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
 * Nothing is sent until Done: the pending mask is edited locally so a misclick
 * can be walked back with Cancel, and one packet carries the result. The server
 * re-checks permission regardless of what this screen allows.
 */
public final class SynapticSettingsScreen extends Screen {
    private static final int COLUMNS = 2;
    private static final int BUTTON_WIDTH = 140;
    private static final int ROW_HEIGHT = 20;
    private static final int FULL_WIDTH = BUTTON_WIDTH * COLUMNS + 6;

    private final boolean editable;
    private final boolean firstRun;
    private StringWidget warning;
    private int pending;

    public SynapticSettingsScreen(boolean firstRun) {
        super(Component.literal(firstRun ? "Synaptic — set up this world" : "Synaptic Settings"));
        this.firstRun = firstRun;
        this.pending = SynapticConfig.bits();
        Minecraft minecraft = Minecraft.getInstance();
        this.editable = minecraft.player != null
            && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected void init() {
        this.pending = SynapticConfig.bits();

        GridLayout grid = new GridLayout();
        grid.spacing(6);
        GridLayout.RowHelper rows = grid.createRowHelper(COLUMNS);

        rows.addChild(label(this.title.copy().withStyle(ChatFormatting.BOLD)), COLUMNS);
        rows.addChild(label(subtitle()), COLUMNS);
        // Sits empty until a toggle earns it, so the layout does not jump.
        warning = label(Component.empty());
        rows.addChild(warning, COLUMNS);

        for (Feature.Group group : Feature.Group.values()) {
            List<Feature> features = Feature.of(group);
            rows.addChild(label(Component.literal(group.title())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), COLUMNS);
            for (Feature feature : features) {
                rows.addChild(toggleFor(feature));
            }
            // The grid wants whole rows; an odd group would otherwise pull the
            // next section's header up alongside its last button.
            if (features.size() % COLUMNS != 0) {
                rows.addChild(new StringWidget(BUTTON_WIDTH, ROW_HEIGHT, Component.empty(), this.font));
            }
        }

        rows.addChild(Button.builder(Component.literal("Done"), button -> {
            if (editable && pending != SynapticConfig.bits()) {
                ClientPlayNetworking.send(new ConfigUpdatePayload(pending));
            }
            onClose();
        }).width(BUTTON_WIDTH).build());
        rows.addChild(Button.builder(Component.literal("Cancel"), button -> onClose())
            .width(BUTTON_WIDTH).build());

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(8, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);
    }

    private Component subtitle() {
        if (!editable) {
            return Component.literal("Operators only — you can look, but not change")
                .withStyle(ChatFormatting.RED);
        }
        return firstRun
            ? Component.literal("First time in this world — set it up now, press K to change it later")
                .withStyle(ChatFormatting.GRAY)
            : Component.literal("Applies to everyone on the server").withStyle(ChatFormatting.GRAY);
    }

    /**
     * Shared inventory is the one toggle that costs something to change once
     * people are carrying things, so it says so before Done rather than after.
     * On the first-run screen there is nothing to lose yet, so it stays quiet.
     */
    private void refreshWarning() {
        boolean live = SynapticConfig.enabled(Feature.INVENTORY);
        boolean wanted = (pending & Feature.INVENTORY.bit()) != 0;
        if (firstRun || live == wanted) {
            warning.setMessage(Component.empty());
        } else if (wanted) {
            warning.setMessage(Component.literal(
                "Turning shared inventory ON pools everyone's items — anything past 36 slots is lost")
                .withStyle(ChatFormatting.RED));
        } else {
            warning.setMessage(Component.literal(
                "Turning shared inventory OFF lets everyone's items drift apart from here")
                .withStyle(ChatFormatting.YELLOW));
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

    private StringWidget label(Component text) {
        return new StringWidget(FULL_WIDTH, ROW_HEIGHT, text, this.font);
    }

    private Component labelFor(Feature feature) {
        boolean on = (pending & feature.bit()) != 0;
        return Component.literal(feature.label() + ": ")
            .append(Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
