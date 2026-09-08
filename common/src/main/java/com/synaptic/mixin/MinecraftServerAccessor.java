package com.synaptic.mixin;

import java.util.Map;
import java.util.concurrent.Executor;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The three private things a level needs to be built by hand.
 * <p>
 * The server only ever constructs levels for itself, at startup, straight from
 * the save's dimension list, so none of this is exposed. Creating one later
 * means borrowing the same pieces it would have used.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
    /** The live level map. Adding to it is what makes a dimension real to the server. */
    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> synaptic$levels();

    @Accessor("executor")
    Executor synaptic$executor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess synaptic$storageSource();
}
