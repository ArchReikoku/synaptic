package com.synaptic.command;

import java.util.Locale;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.synaptic.config.Feature;
import com.synaptic.config.SynapticConfig;
import com.synaptic.net.SynapticNetworking;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * {@code /synaptic} — the settings without the settings screen.
 * <p>
 * Reading is open to anyone: the settings govern everyone's game, so everyone
 * may see what they are playing under. Changing needs the same authority as the
 * screen. This is also the only way in for a server console or a player without
 * the mod installed client-side.
 */
public final class SynapticCommand {
    private SynapticCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("synaptic")
                .executes(context -> list(context.getSource()));

            for (Feature feature : Feature.values()) {
                root.then(Commands.literal(feature.name().toLowerCase(Locale.ROOT))
                    .requires(SynapticCommand::mayConfigure)
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> set(context.getSource(), feature,
                            BoolArgumentType.getBool(context, "enabled")))));
            }

            dispatcher.register(root);
        });
    }

    private static boolean mayConfigure(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        // Same rule as the screen, asked in the same place, so the two routes
        // cannot disagree about who is allowed to change what.
        if (player != null) return SynapticNetworking.canConfigure(player);
        // No player behind it: the server console, which outranks everyone.
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Synaptic settings")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        for (Feature.Group group : Feature.Group.values()) {
            source.sendSuccess(() -> Component.literal(group.title())
                .withStyle(ChatFormatting.YELLOW), false);
            for (Feature feature : Feature.of(group)) {
                boolean on = SynapticConfig.enabled(feature);
                source.sendSuccess(() -> Component.literal("  " + feature.label() + ": ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(on ? "ON" : "OFF")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED))
                    .append(Component.literal("  (" + feature.name().toLowerCase(Locale.ROOT) + ")")
                        .withStyle(ChatFormatting.DARK_GRAY)), false);
            }
        }
        return Feature.values().length;
    }

    private static int set(CommandSourceStack source, Feature feature, boolean enabled) {
        if (SynapticConfig.enabled(feature) == enabled) {
            source.sendFailure(Component.literal(feature.label() + " is already "
                + (enabled ? "on" : "off") + "."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        int bits = enabled
            ? SynapticConfig.bits() | feature.bit()
            : SynapticConfig.bits() & ~feature.bit();
        // Straight through the same path the settings screen uses, so the save,
        // the re-seed, the warnings and the client sync all still happen.
        SynapticNetworking.applyChange(server, bits);
        source.sendSuccess(() -> Component.literal(feature.label() + " is now ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(enabled ? "ON" : "OFF")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)), true);
        return 1;
    }
}
