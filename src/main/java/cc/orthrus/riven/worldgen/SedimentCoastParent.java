package cc.orthrus.riven.worldgen;

/**
 * Canonical complete terrain before sedimentary coastal edits, including rocky geometry and the
 * existing aquifer roof seal. Implementations must not query sedimentary plans, final terrain or
 * final biome consumers. Coordinates remain absolute and caller-owned mutable scratch must not be
 * retained. This boundary is later than RockyCoastParent and does not authorize the same queries.
 */
@FunctionalInterface
public interface SedimentCoastParent {
    void sample(double x, double z, TerrainMap.Sample out);
}
