package com.synaptic.mixin;

import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The save's own settings, which carry the name shown in the world list.
 * <p>
 * Held privately and never meant to change while a world is open — vanilla only
 * renames a save from the menu, with the world closed. Swapping the whole record
 * is what makes a live rename stick: whatever is in here is what gets written to
 * level.dat on the next save, so setting the name any other way is undone by the
 * first autosave.
 */
@Mixin(PrimaryLevelData.class)
public interface PrimaryLevelDataAccessor {
    @Accessor("settings")
    LevelSettings synaptic$settings();

    @Accessor("settings")
    void synaptic$setSettings(LevelSettings settings);
}
