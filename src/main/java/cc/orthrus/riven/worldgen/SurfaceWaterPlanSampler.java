package cc.orthrus.riven.worldgen;

/**
 * Reads completed earlier hydrology owner plans for their immutable reservation geometry.
 * Implementations preserve absolute owner-cell coordinates and must not query major formations or
 * later terrain. The existing Plan exposes additional facts; the signature restricts owner lookup,
 * not field visibility or provenance. This is distinct from sampling an explicitly supplied raw
 * network during water planning. No caller scratch or derived terrain may be retained.
 */
@FunctionalInterface
public interface SurfaceWaterPlanSampler {
    Hydro.Plan plan(int cellX, int cellZ);
}
