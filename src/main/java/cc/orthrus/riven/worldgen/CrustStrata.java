package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.BedSource;

/**
 * The crust's bed source: the column's geology (its formations, beds and climate tint bands, from
 * riven-geology.json), or within a wonder's strata depth its own banded palette from
 * riven-materials.json, so a wonder's walls show strata top to foot.
 */
final class CrustStrata implements BedSource<TerrainMap.Sample> {
    private final int maximumBoundaries;

    CrustStrata(TerrainHeightReference heights) {
        // Two geology calls (to the depth top and above it) and one palette band per thickness.
        maximumBoundaries =
                2 * GeologyMap.MAXIMUM_BOUNDARIES
                        + (heights.maximumYExclusive - heights.worldMinimumY) / Materials.THICKNESS
                        + 3;
    }

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
        if (s.wonders.palette != null) {
            int strataBottom = strataFloor(s, depthTop) - Materials.STRATA_DEPTH + 1;
            out[n++] = strataBottom;
            double tilt = tilt(s);
            long previous = band(tilt, strataBottom);
            for (int y = Math.max(strataBottom + 1, bottom + 1); y < top; y++) {
                long band = band(tilt, y);
                if (band != previous) out[n++] = y;
                previous = band;
            }
        }
        return n;
    }

    @Override
    public TerrainBlock block(TerrainMap.Sample s, int y, int depthTop) {
        TerrainBlock[] palette = s.wonders.palette;
        if (palette == null || y <= strataFloor(s, depthTop) - Materials.STRATA_DEPTH)
            return s.geology.block(y, depthTop);
        return palette[
                (int)
                        Long.remainderUnsigned(
                                SeededNoise.mix(Materials.SEED + band(tilt(s), y)),
                                palette.length)];
    }

    @Override
    public int maximumBoundaries() {
        return maximumBoundaries;
    }

    @Override
    public int surfaceDepth() {
        return Materials.STRATA_DEPTH;
    }

    /** The level strata depth counts down from: the depth top, or a wonder's lower floor. */
    private static int strataFloor(TerrainMap.Sample s, int depthTop) {
        return s.wonders.palette == null
                ? depthTop
                : Math.min(depthTop, (int) Math.floor(s.wonders.base));
    }

    /** The column's strata tilt, in blocks. */
    private static double tilt(TerrainMap.Sample s) {
        return Materials.TILT_BLOCKS
                * Materials.TILT.sample(
                        s.x / Materials.TILT_WAVELENGTH, s.z / Materials.TILT_WAVELENGTH);
    }

    /** The stratum index at Y: tilted bands thicknessBlocks thick. */
    private static long band(double tilt, long y) {
        return (long) Math.floor((y + tilt) / Materials.THICKNESS);
    }
}
