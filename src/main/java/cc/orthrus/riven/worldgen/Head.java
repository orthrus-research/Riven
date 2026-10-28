package cc.orthrus.riven.worldgen;

/** Cheap regional groundwater potential. This is neither rainfall nor a water balance. */
public final class Head implements GroundwaterPotential {
    private final SeededNoise permeability;
    private final TerrainHeightReference heights;

    Head(long seed, TerrainHeightReference heights) {
        this.heights = heights;
        permeability = new SeededNoise(SeededNoise.mix(seed ^ 0x4845414452454348L));
    }

    public double at(double x, double z, TerrainMap.BaseSample base) {
        if (base.ocean.marine && base.ocean.supplied) return heights.waterTopY;
        if (base.water.wet || (base.water.kind == Hydro.Kind.WETLAND && base.water.weight > .99))
            return base.water.head;
        return potential(x, z, base);
    }

    /** Earlier head field for planners; deliberately ignores all later water plans. */
    @Override
    public double potential(double x, double z, TerrainMap.BaseSample base) {
        if (base.ocean.marine && base.ocean.supplied) return heights.waterTopY;
        double potential =
                heights.waterTopY
                        + .55 * (base.shore.height - heights.waterTopY)
                        - 8 * (1 - base.climate.moisture)
                        + 4 * permeability.sample(x / 3072, z / 3072);
        return Math.min(base.shore.height - 2, potential);
    }
}
