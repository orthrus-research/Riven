package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Flood cells (riven-caves.json fill): cave water as bodies, not columns. The land splits into
 * cells on a jittered grid, each nearest its own point, with edges that wander along a smooth
 * warp. A cell takes one flat level, its centre's water table, and floods when the formation at
 * that level there is permeable: every cave block from the band's floor up to the level is water.
 * Rock holds the water: carving leaves a sump floor under the band, undulating gently, and a dam
 * along each edge a flooded cell shares, from the lower band floor up to the higher level, thin at
 * its crest and wider below like a rock sill. Water meets only rock, ice or its own flat surface.
 * Every fact is a pure function of seed, definitions and coordinates; a bounded memo keeps cells.
 */
final class FloodMap {
    /** Dams a column can stand in: edges within the widest dam of the column. */
    private static final int MAXIMUM_DAMS = 4;

    /** A column's flood facts: its band, its sump floor and the dams it stands in. */
    static final class Column {
        boolean flooded;
        int level, floor, waterTop;
        int dams;
        final double[] damDistance = new double[MAXIMUM_DAMS];
        final int[] damLo = new int[MAXIMUM_DAMS], damHi = new int[MAXIMUM_DAMS];

        void clear() {
            flooded = false;
            level = floor = waterTop = Integer.MIN_VALUE;
            dams = 0;
        }

        /** Whether block Y lies in this column's flooded band (it holds water where it is a void). */
        boolean holds(long y) {
            return flooded && y > floor && y <= level;
        }

        /** Whether carving leaves block Y as rock: the sump floor or a dam. */
        boolean sealed(long y) {
            if (flooded && y > floor - SEAL && y <= floor) return true;
            for (int i = 0; i < dams; i++)
                if (y >= damLo[i] && y <= damHi[i] && damDistance[i] < damHalf(damHi[i] - y))
                    return true;
            return false;
        }
    }

    /** The geology a cell's centre reads: the sample up to its geology column, no caves. */
    @FunctionalInterface
    interface Centre {
        void sample(double x, double z, TerrainMap.Sample out);
    }

    private static final class Cell {
        final double x, z;
        final boolean flooded;
        final int level;

        Cell(double x, double z, boolean flooded, int level) {
            this.x = x;
            this.z = z;
            this.flooded = flooded;
            this.level = level;
        }
    }

    static final double CELL, JITTER, BAND, PERMEABILITY;
    static final double DAM_HALF, DAM_SLOPE, DAM_MAXIMUM, WARP, WARP_WAVELENGTH;
    static final double FLOOR_WOBBLE, FLOOR_WAVELENGTH;
    static final int SEAL;

    static {
        JsonObject fill = CaveMap.RULES.json.getAsJsonObject("fill");
        CELL = Rules.number(fill, "cellBlocks", 32, 1024);
        JITTER = Rules.number(fill, "jitter", 0, 0.9);
        BAND = Rules.number(fill, "floodBandBlocks", 0, 512);
        PERMEABILITY = Rules.number(fill, "minimumPermeability", 0, 1);
        SEAL = (int) Rules.number(fill, "sealBlocks", 2, 8);
        DAM_HALF = Rules.number(fill, "damHalfWidthBlocks", 1, 4);
        DAM_SLOPE = Rules.number(fill, "damSlope", 0, 2);
        DAM_MAXIMUM = Rules.number(fill, "damMaximumHalfWidthBlocks", DAM_HALF, 16);
        WARP = Rules.number(fill, "edgeWarpBlocks", 0, 64);
        WARP_WAVELENGTH = Rules.number(fill, "edgeWarpWavelengthBlocks", 16, 4096);
        FLOOR_WOBBLE = Rules.number(fill, "floorWobbleBlocks", 0, 16);
        FLOOR_WAVELENGTH = Rules.number(fill, "floorWobbleWavelengthBlocks", 16, 4096);
        // One block of travel moves a warped point at most sqrt((1 + k)^2 + k^2), k the warp's
        // steepest slope (3.75 per wavelength for SeededNoise); a dam must be wider than that, or
        // water could step across it. The floor may change at most one block per block of travel.
        double k = WARP * 3.75 / WARP_WAVELENGTH;
        if (Math.hypot(1 + k, k) >= 2 * DAM_HALF)
            throw new IllegalStateException("fill: edge warp too steep for damHalfWidthBlocks");
        if (FLOOR_WOBBLE * 3.75 / FLOOR_WAVELENGTH >= 0.5)
            throw new IllegalStateException("fill: floor wobble too steep for the seal");
    }

    /** A dam's half-width DEPTH blocks below its crest. */
    static double damHalf(long depth) {
        return Math.min(DAM_MAXIMUM, DAM_HALF + DAM_SLOPE * Math.max(0, depth));
    }

    private final long seed;
    private final Centre centre;
    private final SeededNoise warpX, warpZ, wobble;
    private final CellMemo<Cell> cells = new CellMemo<>(4096);
    private final ThreadLocal<TerrainMap.Sample> scratch =
            ThreadLocal.withInitial(TerrainMap.Sample::new);

    FloodMap(long worldSeed, Centre centre) {
        seed = SeededNoise.mix(worldSeed ^ 0x464c4f4f44533131L);
        this.centre = centre;
        warpX = new SeededNoise(SeededNoise.mix(seed ^ 0x5741525058313131L));
        warpZ = new SeededNoise(SeededNoise.mix(seed ^ 0x574152505a313131L));
        wobble = new SeededNoise(SeededNoise.mix(seed ^ 0x574f42424c453131L));
    }

    private Cell cell(int i, int j) {
        return cells.get(
                i,
                j,
                () -> {
                    long key = SeededNoise.cell(seed, i, j);
                    double margin = (1 - JITTER) / 2;
                    double
                            x =
                                    (i
                                                    + margin
                                                    + JITTER
                                                            * SeededNoise.unit(
                                                                    SeededNoise.mix(key + 1)))
                                            * CELL,
                            z =
                                    (j
                                                    + margin
                                                    + JITTER
                                                            * SeededNoise.unit(
                                                                    SeededNoise.mix(key + 2)))
                                            * CELL;
                    TerrainMap.Sample s = scratch.get();
                    centre.sample(x, z, s);
                    GeologyMap.Column g = s.geology;
                    int level = (int) Math.floor(g.waterTable);
                    // Water stays clear of the lava and rests in permeable rock at its level.
                    boolean flooded =
                            level - BAND - FLOOR_WOBBLE - SEAL > g.lavaY + 2
                                    && g.formation(level).permeability >= PERMEABILITY;
                    return new Cell(x, z, flooded, level);
                });
    }

    /** The column's flood facts. */
    void column(double x, double z, Column out) {
        out.clear();
        double bx = Math.floor(x) + 0.5, bz = Math.floor(z) + 0.5;
        // Cells are read at a warped position, so their edges wander instead of running straight.
        double px = bx + WARP * warpX.sample(bx / WARP_WAVELENGTH, bz / WARP_WAVELENGTH),
                pz = bz + WARP * warpZ.sample(bx / WARP_WAVELENGTH, bz / WARP_WAVELENGTH);
        int ci = (int) Math.floor(px / CELL), cj = (int) Math.floor(pz / CELL);
        // The owner is the nearest point of the 5 x 5 cells around; dams read the others.
        Cell owner = null;
        double best = Double.POSITIVE_INFINITY;
        Cell[] near = new Cell[25];
        for (int dj = -2; dj <= 2; dj++)
            for (int di = -2; di <= 2; di++) {
                Cell c = cell(ci + di, cj + dj);
                near[(dj + 2) * 5 + di + 2] = c;
                double d = (px - c.x) * (px - c.x) + (pz - c.z) * (pz - c.z);
                if (d < best) {
                    best = d;
                    owner = c;
                }
            }
        if (owner.flooded) {
            out.flooded = true;
            out.level = owner.level;
            out.floor = floor(owner, bx, bz);
            out.waterTop = owner.level;
        }
        // A dam stands along each edge a flooded cell shares, from the lower band floor up to
        // the higher level, so two bodies never meet and none meets a dry cave.
        for (Cell c : near) {
            if (c == owner || !(owner.flooded || c.flooded)) continue;
            double dx = c.x - owner.x, dz = c.z - owner.z, length = Math.hypot(dx, dz);
            double distance = length / 2 - ((px - owner.x) * dx + (pz - owner.z) * dz) / length;
            if (distance >= DAM_MAXIMUM || out.dams == MAXIMUM_DAMS) continue;
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            if (owner.flooded) {
                lo = floor(owner, bx, bz) - SEAL - 1;
                hi = owner.level;
            }
            if (c.flooded) {
                lo = Math.min(lo, floor(c, bx, bz) - SEAL - 1);
                hi = Math.max(hi, c.level);
            }
            out.damDistance[out.dams] = distance;
            out.damLo[out.dams] = lo;
            out.damHi[out.dams++] = hi;
            out.waterTop = Math.max(out.waterTop, hi);
        }
    }

    /** A flooded cell's band floor at a column: below the level by the band, gently undulating. */
    private int floor(Cell c, double x, double z) {
        return (int)
                Math.floor(
                        c.level
                                - BAND
                                + FLOOR_WOBBLE
                                        * wobble.sample(
                                                x / FLOOR_WAVELENGTH, z / FLOOR_WAVELENGTH));
    }
}
