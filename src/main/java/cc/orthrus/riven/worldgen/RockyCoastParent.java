package cc.orthrus.riven.worldgen;

/**
 * Canonical complete terrain before rocky coastal edits, including earlier land/ocean shaping,
 * shore and water constraints and aquifer roof sealing. Implementations must not query rocky or
 * sedimentary plans, final terrain or final biome consumers. Coordinates remain absolute and
 * caller-owned mutable scratch must not be retained. This signature restricts queries, not fields.
 */
@FunctionalInterface
public interface RockyCoastParent {
    void sample(double x, double z, TerrainMap.Sample out);
}
