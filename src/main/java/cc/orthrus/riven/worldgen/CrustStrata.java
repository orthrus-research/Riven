package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.BedSource;

/**
 * The crust's bed source: the column's geology (its formations, beds and climate tint bands, from
 * riven-geology.json). Its tint bands count from the depth top it is given, so no rock varies with
 * the surface beyond what the geology itself says.
 */
final class CrustStrata implements BedSource<TerrainMap.Sample> {
    @Override
    public String id() {
        return "geology";
    }

    @Override
    public int boundaries(
            TerrainMap.Sample s, int bottom, int top, int depthTop, int[] out, int n) {
        // Geology's tint bands count from the surface it is given, so it is always the depth
        // top; rock above it (a built volume) changes only at its units' edges.
        if (depthTop >= bottom) n = s.geology.boundaries(bottom, depthTop, out, n);
        if (top - 1 > depthTop)
            n = s.geology.boundaries(Math.max(bottom, depthTop), top - 1, out, n);
        return n;
    }

    @Override
    public TerrainBlock block(TerrainMap.Sample s, int y, int depthTop) {
        return s.geology.block(y, depthTop);
    }

    @Override
    public int maximumBoundaries() {
        // Two geology calls: to the depth top and above it.
        return 2 * GeologyMap.MAXIMUM_BOUNDARIES;
    }

    @Override
    public int surfaceDepth() {
        return 0;
    }
}
