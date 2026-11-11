package com.synaptic.mixin;

import com.synaptic.world.RunManager;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a portal inside the run it was lit in.
 * <p>
 * Both portals ask for fixed dimensions: the nether one flips between
 * {@code NETHER} and {@code OVERWORLD}, the end one between {@code END} and
 * {@code OVERWORLD}. Left alone, every run shares the save's originals — coming
 * back from the nether lands in an empty overworld nobody is playing, and a
 * dragon killed in one run is still dead in the next.
 * <p>
 * So all three are answered with the run's own. The overworld is the run itself;
 * the nether and end are built on first use from the run's seed. Only requests
 * made while a run is in progress are redirected, and nothing else about how a
 * portal picks its landing spot is touched.
 */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
public abstract class PortalDestinationMixin {
    @Redirect(method = "getPortalDestination",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getLevel"
                + "(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel synaptic$backToTheRun(MinecraftServer server, ResourceKey<Level> asked) {
        ServerLevel mine = RunManager.companion(server, asked);
        return mine != null ? mine : server.getLevel(asked);
    }

    /**
     * Answer with the name vanilla is comparing against, so it can tell which
     * way through the portal this is.
     */
    @Redirect(method = "getPortalDestination",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;dimension"
                + "()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> synaptic$asVanillaSeesIt(ServerLevel level) {
        return RunManager.canonical(level);
    }
}
