package cc.orthrus.riven.worldgen;

/** Seed-owned maps and bounded immutable hydrology geometry; no workers or cached terrain. */
public final class WorldGenerationContext {
    public final TerrainHeightReference terrainHeights = TerrainHeightReference.DEFAULT;
    public final WorldScale scale;
    public final GenerationMaps maps;

    public WorldGenerationContext(long seed) {
        this(seed, WorldScale.DEFAULT);
    }

    public WorldGenerationContext(long seed, WorldScale scale) {
        this.scale = scale;
        maps = new GenerationMaps(seed, terrainHeights, BaseTerrainMap.configuredPolicy(), scale);
    }
}
