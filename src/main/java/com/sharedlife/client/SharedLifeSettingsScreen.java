package com.sharedlife.client;

import com.sharedlife.config.Feature;
import com.sharedlife.config.SharedLifeConfig;
import com.sharedlife.net.ConfigUpdatePayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * Feature toggles for the whole server, opened with the Shared Life key (K by
 * default).
 * <p>
 * Built entirely from stock widgets laid out by a {@link GridLayout} — this
 * version of the game replaced the old immediate-mode drawing API, and letting
 * the widgets draw themselves avoids touching the new one at all.
 * <p>
 * Nothing is sent until Done: the pending mask is edited locally so a misclick
 * can be walked back with Cancel, and one packet carries the result. The server
 * re-checks permission regardless of what this screen allows.
 */
public final class SharedLifeSettingsScreen extends Screen {
    private static final int BUTTON_WIDTH = 210;
    private static final int BUTTON_HEIGHT = 20;

    private final boolean editable;
    private int pending;

    public SharedLifeSettingsScreen() {
        super(Component.literal("Shared Life Settings"));
        this.pending = SharedLifeConfig.bits();
        Minecraft minecraft = Minecraft.getInstance();
        this.editable = minecraft.player != null
            && minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected void init() {
        this.pending = SharedLifeConfig.bits();

        GridLayout grid = new GridLayout();
        grid.spacing(6);
        GridLayout.RowHelper rows = grid.createRowHelper(2);

        rows.addChild(new StringWidget(BUTTON_WIDTH * 2, BUTTON_HEIGHT, this.title, this.font), 2);
        rows.addChild(new StringWidget(BUTTON_WIDTH * 2, BUTTON_HEIGHT, editable
            ? Component.literal("Changes apply to everyone on the server").withStyle(ChatFormatting.GRAY)
            : Component.literal("Operators only — you can look, but not change")
                .withStyle(ChatFormatting.RED), this.font), 2);

        for (Feature feature : Feature.values()) {
            Button toggle = Button.builder(labelFor(feature), button -> {
                pending ^= feature.bit();
                button.setMessage(labelFor(feature));
            }).width(BUTTON_WIDTH).build();
            toggle.active = editable;
            rows.addChild(toggle);
        }

        if (Feature.values().length % 2 != 0) {
            rows.addChild(new StringWidget(BUTTON_WIDTH, BUTTON_HEIGHT, Component.empty(), this.font));
        }

        rows.addChild(Button.builder(Component.literal("Done"), button -> {
            if (editable && pending != SharedLifeConfig.bits()) {
                ClientPlayNetworking.send(new ConfigUpdatePayload(pending));
            }
            onClose();
        }).width(BUTTON_WIDTH).build());
        rows.addChild(Button.builder(Component.literal("Cancel"), button -> onClose())
            .width(BUTTON_WIDTH).build());

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(10, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);
    }

    private Component labelFor(Feature feature) {
        boolean on = (pending & feature.bit()) != 0;
        return Component.literal(feature.label() + ": ")
            .append(Component.literal(on ? "ON" : "OFF")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }
}
