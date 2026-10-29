package cc.orthrus.riven.worldgen;

/**
 * A stage's per-column result inside a reused {@link TerrainMap.Sample}. Clearing leaves the
 * stage's no-op output over the given surface: what a column reports where the stage does not
 * run. The sample registers each result where it declares it, so every column resets the stages
 * it skips and no result carries over from an earlier coordinate.
 */
interface StageResult {
    void clear(double surface);
}
