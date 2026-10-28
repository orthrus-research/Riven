package cc.orthrus.riven.worldgen;

/**
 * Earlier groundwater potential for planners, ignoring local water observations and later shaping.
 * Implementations preserve absolute coordinates and must not retain or modify caller-owned earlier
 * facts, construct hydrology plans or query finished terrain. The input scratch remains mutable.
 */
@FunctionalInterface
public interface GroundwaterPotential {
    double potential(double x, double z, TerrainMap.BaseSample earlier);
}
