package com.synaptic.world;

/**
 * A level that answers with a seed of its own rather than the save's.
 * <p>
 * Implemented on {@code ServerLevel} by ServerLevelSeedMixin. See
 * {@link RuntimeDimension} for why this is the load-bearing part of the whole
 * multi-seed idea.
 */
public interface SeededLevel {
    void synaptic$setSeed(long seed);
}
