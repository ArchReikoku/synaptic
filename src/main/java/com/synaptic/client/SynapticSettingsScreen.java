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
    private static final int SPACING = 6;
    private static final int FULL_WIDTH = BUTTON_WIDTH * COLUMNS + SPACING;

    private final boolean editable;
    private StringWidget warningTop;
    private StringWidget warningBottom;
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
        grid.spacing(SPACING);
        // Everything sits centred in its cell, so a button that spans the full
        // width lands in the middle instead of hugging the left column.
        grid.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper rows = grid.createRowHelper(COLUMNS);

        rows.addChild(label(this.title.copy().withStyle(ChatFormatting.BOLD)), COLUMNS);
        rows.addChild(label(editable
            ? Component.literal("Applies to everyone on the server").withStyle(ChatFormatting.GRAY)
            : Component.literal("Operators only — you can look, but not change")
                .withStyle(ChatFormatting.RED)), COLUMNS);

        // Two empty lines held open for the inventory warning, so the layout does
        // not jump around underneath the cursor when it appears.
        warningTop = label(Component.empty());
        warningBottom = label(Component.empty());
        rows.addChild(warningTop, COLUMNS);
        rows.addChild(warningBottom, COLUMNS);

        for (Feature.Group group : Feature.Group.values()) {
            List<Feature> features = Feature.of(group);
            rows.addChild(label(Component.literal(group.title())
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), COLUMNS);
            for (int i = 0; i < features.size(); i++) {
                boolean lastAndOdd = i == features.size() - 1 && features.size() % COLUMNS != 0;
                // An odd group's final button spans both columns rather than
                // sitting in the left one with a hole beside it.
                if (lastAndOdd) rows.addChild(toggleFor(features.get(i)), COLUMNS);
                else rows.addChild(toggleFor(features.get(i)));
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

        refreshWarning();

        grid.arrangeElements();
        grid.setPosition((this.width - grid.getWidth()) / 2, Math.max(8, (this.height - grid.getHeight()) / 2));
        grid.visitWidgets(this::addRenderableWidget);
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
        if (live == wanted) {
            warningTop.setMessage(Component.empty());
            warningBottom.setMessage(Component.empty());
        } else if (wanted) {
            warningTop.setMessage(red("Turning shared inventory ON merges every player's items into one"));
            warningBottom.setMessage(red("36-slot inventory. Whatever does not fit is destroyed for good."));
        } else {
            warningTop.setMessage(red("Turning shared inventory OFF splits you apart: each player keeps what"));
            warningBottom.setMessage(red("they hold now, and switching it back on later merges and destroys again."));
        }
    }

    private static Component red(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
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
