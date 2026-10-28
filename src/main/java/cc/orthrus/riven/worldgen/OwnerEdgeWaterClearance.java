package cc.orthrus.riven.worldgen;

/**
 * Conservative earlier surface-water clearance within the current owner, including its edge.
 * Preserves Hydro's two-coordinate overload; this is not the finite-skirt neighboring-owner query.
 * Implementations must not query major formations or later terrain. Clearance describes reserved
 * geometry, not supplied fluid connectivity.
 */
@FunctionalInterface
public interface OwnerEdgeWaterClearance {
    double surfaceClearance(double x, double z);
}
