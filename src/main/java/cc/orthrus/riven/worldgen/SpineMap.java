package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Continent spines (terrain-v127): where mountain ranges run, from the land's own shape. The
 * spine is the ridge of the distance to the coast, the land's medial axis: away from it that
 * distance rises one block per block, on it the rise stops, so the smoothed distance's slope
 * falling below {@code flatSlope} marks it, through saddles too, on every landmass wide enough to
 * hold it. A range is the band around the spine, as wide as {@code widthShare} of the land's half
 * width there and as strong as that half width allows, so broad continents carry high ranges and
 * narrow land low ridges or none.
 *
 * <p>Read as a weight in 0..1 on a node grid ({@code cellBlocks}, Catmull-Rom between nodes).
 * Nodes are built a tile at a time from the continental land alone: an exact Euclidean distance
 * transform of the sea, a box-blurred copy, its slope, then an exact feature transform from the
 * spine cells carrying the half width of the nearest one. Each tile reads a halo wide enough that
 * every distance it uses is exact, so a node is a pure function of the seed, the definitions and
 * its position; tiles live in a bounded cache cleared whole when full.
 */
public final class SpineMap {
    private static final double BIG = 1e30, LIMIT = 29_900_000;
    private static final int TILES = 64;

    private final ContinentalMap continents;
    private final long seed;
    private final BuildPool pool;
    private final double cell, cap, flatSlope, halfFrom, halfFull, widthShare, minWidth;
    private final int tile, halo, blur;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final SharedBuild<Tile> builds = new SharedBuild<>();

    /** One tile of node weights and its own coordinates (a hash collision is rebuilt, not misread). */
    private static final class Tile {
        final long i, j;
        final double[] weight;

        Tile(long i, long j, double[] weight) {
            this.i = i;
            this.j = j;
            this.weight = weight;
        }
    }

    public SpineMap(long worldSeed, ContinentalMap continents, WorldScale scale, BuildPool pool) {
        if (continents == null || scale == null || pool == null) throw new NullPointerException();
        this.continents = continents;
        seed = SeededNoise.mix(worldSeed ^ 0x5350494e454e4fL);
        this.pool = pool;
        JsonObject j = ProvinceMap.RULES.json.getAsJsonObject("spines");
        cell = Rules.number(j, "cellBlocks", 4, 256) * scale.layout;
        tile = (int) Rules.number(j, "tileCells", 16, 512);
        cap = Rules.number(j, "capBlocks", 64, 8192) * scale.layout;
        flatSlope = Rules.number(j, "flatSlope", .05, .95);
        halfFrom = Rules.number(j, "halfWidthFromBlocks", 0, 4096) * scale.layout;
        halfFull = Rules.number(j, "halfWidthFullBlocks", 1, 8192) * scale.layout;
        if (!(halfFull > halfFrom)) throw new IllegalStateException("spines: full must pass from");
        widthShare = Rules.number(j, "widthShare", .05, 1.5);
        minWidth = Rules.number(j, "minWidthBlocks", 1, 4096) * scale.layout;
        blur =
                Math.max(
                        1,
                        (int)
                                Math.round(
                                        Rules.number(j, "blurBlocks", 1, 2048)
                                                * scale.layout
                                                / cell));
        halo = (int) Math.ceil(cap * (1 + widthShare) / cell) + 3 * blur + 3;
        nodeFrom = Rules.number(j, "nodeWeight", 0, 1);
    }

    /** The range weight a spine node needs (mountain plans stand on the ranges' cores). */
    private final double nodeFrom;

    private static final double[] NONE = new double[0];
    private static final int NODE_MEMO = 1 << 16;
    private final ConcurrentHashMap<Long, double[]> nodeMemo = new ConcurrentHashMap<>();

    /**
     * Spine nodes within radius of (x, z) on a jittered lattice of spacing: each lattice cell holds
     * one candidate point, a node where the range weight there reaches nodeWeight, heading along
     * the ridge (the eigenvector of the weight's Hessian with the larger eigenvalue, the direction
     * in which the weight curves least). Writes (cell x, cell z, 0, x, z, heading) per node into
     * out, as many as fit, and returns the count. Pure per lattice cell, held in a bounded memo.
     */
    public int nodes(double x, double z, double spacing, double radius, double[] out) {
        int i0 = (int) Math.floor((x - radius) / spacing),
                i1 = (int) Math.floor((x + radius) / spacing);
        int j0 = (int) Math.floor((z - radius) / spacing),
                j1 = (int) Math.floor((z + radius) / spacing);
        int count = 0;
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                double[] n = node(i, j, spacing);
                if (n.length == 0 || Math.hypot(n[0] - x, n[1] - z) > radius) continue;
                if (6 * (count + 1) > out.length) return count;
                out[6 * count] = i;
                out[6 * count + 1] = j;
                out[6 * count + 2] = 0;
                out[6 * count + 3] = n[0];
                out[6 * count + 4] = n[1];
                out[6 * count + 5] = n[2];
                count++;
            }
        return count;
    }

    private double[] node(int i, int j, double spacing) {
        long key = SeededNoise.cell(seed ^ Double.doubleToLongBits(spacing), i, j);
        double[] known = nodeMemo.get(key);
        if (known != null) return known;
        long h = SeededNoise.cell(seed, i, j);
        double px = (i + .2 + .6 * SeededNoise.unit(h)) * spacing;
        double pz = (j + .2 + .6 * SeededNoise.unit(SeededNoise.mix(h))) * spacing;
        double w = weight(px, pz);
        double[] made = NONE;
        if (w >= nodeFrom) {
            double s = spacing / 3;
            double xp = weight(px + s, pz), xm = weight(px - s, pz);
            double zp = weight(px, pz + s), zm = weight(px, pz - s);
            double hxx = (xp - 2 * w + xm) / (s * s), hzz = (zp - 2 * w + zm) / (s * s);
            double hxz =
                    (weight(px + s, pz + s)
                                    - weight(px + s, pz - s)
                                    - weight(px - s, pz + s)
                                    + weight(px - s, pz - s))
                            / (4 * s * s);
            made = new double[] {px, pz, .5 * Math.atan2(2 * hxz, hxx - hzz)};
        }
        if (nodeMemo.size() >= NODE_MEMO) nodeMemo.clear();
        nodeMemo.put(key, made);
        return made;
    }

    /** The range weight at (x, z), 0 off every range to 1 on a full one. */
    public double weight(double x, double z) {
        double gx = x / cell, gz = z / cell;
        long i0 = (long) Math.floor(gx), j0 = (long) Math.floor(gz);
        double[] wu = new double[4], du = new double[4], wv = new double[4], dv = new double[4];
        ProvinceGround.weights(gx - i0, wu, du);
        ProvinceGround.weights(gz - j0, wv, dv);
        double value = 0;
        for (int b = 0; b < 4; b++)
            for (int a = 0; a < 4; a++) {
                long i = i0 - 1 + a, j = j0 - 1 + b;
                Tile t = tile(Math.floorDiv(i, tile), Math.floorDiv(j, tile));
                value +=
                        wu[a]
                                * wv[b]
                                * t.weight[Math.floorMod(j, tile) * tile + Math.floorMod(i, tile)];
            }
        return Math.max(0, Math.min(1, value));
    }

    private Tile tile(long ti, long tj) {
        long key = SeededNoise.mix(ti * 0x9E3779B97F4A7C15L ^ tj ^ 0x5350494e45L);
        Tile t = tiles.get(key);
        if (t != null && t.i == ti && t.j == tj) return t;
        return builds.get(
                key,
                () -> {
                    Tile again = tiles.get(key);
                    if (again != null && again.i == ti && again.j == tj) return again;
                    return build(ti, tj);
                },
                fresh -> {
                    if (tiles.size() >= TILES) tiles.clear();
                    tiles.put(key, fresh);
                });
    }

    private Tile build(long ti, long tj) {
        int n = tile + 2 * halo;
        long i0 = ti * tile - halo, j0 = tj * tile - halo;
        // The land, a row per item across the build pool.
        boolean[] land = new boolean[n * n];
        pool.forEach(
                n,
                r -> {
                    ContinentalMap.Ownership s = new ContinentalMap.Ownership();
                    double z = inside((j0 + r) * cell);
                    for (int a = 0; a < n; a++) {
                        continents.ownership(inside((i0 + a) * cell), z, s);
                        land[r * n + a] = !s.marine;
                    }
                });
        // Distance to the sea, in blocks, capped.
        boolean[] sea = new boolean[n * n];
        for (int k = 0; k < n * n; k++) sea[k] = !land[k];
        double[] d = new double[n * n];
        int[] nearest = new int[n * n];
        transform(sea, n, d, nearest);
        for (int k = 0; k < n * n; k++) d[k] = Math.min(cap, Math.sqrt(d[k]) * cell);
        // Smoothed, then its slope: the spine is where the rise stops.
        double[] smooth = d.clone();
        boxBlur(smooth, n, blur);
        boolean[] spine = new boolean[n * n];
        for (int r = 1; r < n - 1; r++)
            for (int a = 1; a < n - 1; a++) {
                int k = r * n + a;
                if (!land[k] || smooth[k] <= halfFrom) continue;
                double sx = (smooth[k + 1] - smooth[k - 1]) / (2 * cell);
                double sz = (smooth[k + n] - smooth[k - n]) / (2 * cell);
                spine[k] = Math.hypot(sx, sz) < flatSlope;
            }
        // Distance to the spine, carrying the half width of the nearest spine cell.
        double[] t = new double[n * n];
        transform(spine, n, t, nearest);
        double[] weight = new double[tile * tile];
        for (int r = 0; r < tile; r++)
            for (int a = 0; a < tile; a++) {
                int k = (r + halo) * n + a + halo, at = nearest[k];
                if (at < 0) continue;
                double half = smooth[at];
                double width = Math.max(minWidth, widthShare * half);
                double across = Rules.smooth(1 - Math.sqrt(t[k]) * cell / width);
                double strength = Rules.smooth((half - halfFrom) / (halfFull - halfFrom));
                weight[r * tile + a] = across * strength;
            }
        return new Tile(ti, tj, weight);
    }

    private static double inside(double v) {
        return Math.max(-LIMIT, Math.min(LIMIT, v));
    }

    /**
     * The exact squared Euclidean distance (in cells) from every cell to the nearest feature cell,
     * and that cell's index (-1 and BIG where there is none): Felzenszwalb and Huttenlocher's lower
     * envelope of parabolas, by columns then rows.
     */
    static void transform(boolean[] feature, int n, double[] out, int[] nearest) {
        double[] column = new double[n * n],
                f = new double[n],
                d = new double[n],
                z = new double[n + 1];
        int[] row = new int[n * n], arg = new int[n], v = new int[n];
        for (int a = 0; a < n; a++) {
            for (int r = 0; r < n; r++) f[r] = feature[r * n + a] ? 0 : BIG;
            envelope(f, n, d, arg, v, z);
            for (int r = 0; r < n; r++) {
                column[r * n + a] = d[r];
                row[r * n + a] = arg[r];
            }
        }
        for (int r = 0; r < n; r++) {
            for (int a = 0; a < n; a++) f[a] = column[r * n + a];
            envelope(f, n, d, arg, v, z);
            for (int a = 0; a < n; a++) {
                int p = arg[a];
                if (p < 0 || d[a] >= BIG) {
                    out[r * n + a] = BIG;
                    nearest[r * n + a] = -1;
                } else {
                    out[r * n + a] = d[a];
                    nearest[r * n + a] = row[r * n + p] * n + p;
                }
            }
        }
    }

    /** The lower envelope of parabolas (q - p)^2 + f[p] over the finite f, sampled at every q. */
    private static void envelope(double[] f, int n, double[] d, int[] arg, int[] v, double[] z) {
        int k = -1;
        for (int q = 0; q < n; q++) {
            if (f[q] >= BIG) continue;
            double s = Double.NEGATIVE_INFINITY;
            while (k >= 0) {
                int p = v[k];
                s = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * (q - p));
                if (s <= z[k]) k--;
                else break;
            }
            k++;
            v[k] = q;
            z[k] = k == 0 ? Double.NEGATIVE_INFINITY : s;
        }
        if (k < 0) {
            java.util.Arrays.fill(d, 0, n, BIG);
            java.util.Arrays.fill(arg, 0, n, -1);
            return;
        }
        int j = 0;
        for (int q = 0; q < n; q++) {
            while (j < k && z[j + 1] < q) j++;
            int p = v[j];
            d[q] = (double) (q - p) * (q - p) + f[p];
            arg[q] = p;
        }
    }

    /** Three passes of a box of radius r along each axis, edges clamped. */
    private static void boxBlur(double[] a, int n, int r) {
        double[] line = new double[n], sum = new double[n + 1];
        for (int pass = 0; pass < 3; pass++)
            for (int axis = 0; axis < 2; axis++)
                for (int o = 0; o < n; o++) {
                    for (int q = 0; q < n; q++) line[q] = a[axis == 0 ? o * n + q : q * n + o];
                    sum[0] = 0;
                    for (int q = 0; q < n; q++) sum[q + 1] = sum[q] + line[q];
                    for (int q = 0; q < n; q++) {
                        int lo = Math.max(0, q - r), hi = Math.min(n, q + r + 1);
                        // Clamped edges: the missing part of the box repeats the edge value.
                        double total =
                                sum[hi]
                                        - sum[lo]
                                        + line[0] * Math.max(0, r - q)
                                        + line[n - 1] * Math.max(0, q + r + 1 - n);
                        a[axis == 0 ? o * n + q : q * n + o] = total / (2 * r + 1);
                    }
                }
    }
}
