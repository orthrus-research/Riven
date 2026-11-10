package cc.orthrus.riven.worldgen;

import java.util.concurrent.ForkJoinPool;

/** Seed-owned maps and bounded immutable hydrology geometry; no workers or cached terrain. */
public final class WorldGenerationContext {
    public final TerrainHeightReference terrainHeights = TerrainHeightReference.DEFAULT;
    public final WorldScale scale;
    public final GenerationMaps maps;

    public WorldGenerationContext(long seed, WorldScale scale, ForkJoinPool pool) {
        this.scale = scale;
        maps = new GenerationMaps(seed, terrainHeights, scale, pool);
    }
}
