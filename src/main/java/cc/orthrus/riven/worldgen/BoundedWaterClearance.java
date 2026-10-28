package cc.orthrus.riven.worldgen;

/**
 * Earlier surface-water reservation clearance within a finite positive skirt no wider than one
 * hydrology owner. Implementations may resolve earlier neighboring water plans, but must not query
 * major formations or later terrain. Clearance describes protected geometry, not fluid connectivity.
 */
@FunctionalInterface
public interface BoundedWaterClearance {
    double surfaceClearance(double x, double z, double limit);
}
