package cc.orthrus.riven.worldgen;

/**
 * Reads only the explicitly supplied earlier network, without owner-plan lookup or later terrain
 * queries. The caller supplies the raw network before coastal and river decisions; the Plan type
 * itself does not enforce that provenance. Implementations must not retain the network or the
 * caller-owned mutable scratch, construct another owner, or sample completed hydrology.
 */
@FunctionalInterface
public interface EarlierNetworkSampler {
    void sample(double x, double z, Hydro.Plan network, Hydro.Sample out);
}
