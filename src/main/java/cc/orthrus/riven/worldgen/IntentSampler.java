package cc.orthrus.riven.worldgen;

/**
 * Earlier broad terrain, continental, climate and shore facts only. Includes earlier marine
 * admission, but no local hydrology plans or finished shaping. Implementations must preserve
 * absolute coordinates and must not retain the caller-owned scratch or query later stages.
 * The scratch is mutable; this interface restricts queries, not ownership of its individual fields.
 */
@FunctionalInterface
public interface IntentSampler {
    void sample(double x, double z, TerrainMap.BaseSample out);
}
