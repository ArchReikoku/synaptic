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
    private int pending;

    public SynapticSettingsScreen() {
        super(Component.literal("Synaptic Settings"));
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
        rows.addChild(label(editable
            ? Component.literal("Applies to everyone on the server").withStyle(ChatFormatting.GRAY)
            : Component.literal("Operators only — you can look, but not change")
                .withStyle(ChatFormatting.RED)), COLUMNS);

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

    private Button toggleFor(Feature feature) {
        Button toggle = Button.builder(labelFor(feature), button -> {
            pending ^= feature.bit();
            button.setMessage(labelFor(feature));
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
