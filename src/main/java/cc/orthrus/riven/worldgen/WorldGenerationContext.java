package cc.orthrus.riven.worldgen;

import java.util.concurrent.ForkJoinPool;

/** Host-built seed maps with optional reuse of derived regional geometry. */
public final class WorldGenerationContext {
    public final TerrainHeightReference terrainHeights = TerrainHeightReference.DEFAULT;
    public final WorldScale scale;
    public final GenerationMaps maps;

    public WorldGenerationContext(
            long seed, WorldScale scale, ForkJoinPool pool, RegionalStore store) {
        this.scale = scale;
        maps = new GenerationMaps(seed, terrainHeights, scale, pool, store);
    }
}
