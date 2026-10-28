package cc.orthrus.riven.worldgen;

/**
 * Canonical terrain before exclusive major formations: earlier habitat, erosion, lowlands and
 * regional passes, with the existing constrained marine path. Implementations preserve absolute
 * coordinates and must not query major formations, cuts, drylands or later coastal shaping, or
 * retain caller-owned mutable scratch. This interface restricts queries, not sample-field ownership.
 */
@FunctionalInterface
public interface MajorFeatureParent {
    void sample(double x, double z, TerrainMap.Sample out);
}
