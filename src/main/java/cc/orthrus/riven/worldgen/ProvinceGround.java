package cc.orthrus.riven.worldgen;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The province ground as a pure regional plan (S4): the province map's blended land fields (level,
 * relief, ridged share, terraced share; {@link ProvinceMap#ground}) on a node grid, read with
 * Catmull-Rom interpolation, so the fields are C1 and their derivatives exact. Nodes are computed a
 * tile at a time and held in a bounded cache cleared whole when full; every node is a pure function
 * of the seed, the definitions and its position, so eviction never changes a result.
 */
final class ProvinceGround {
    static final int FIELDS = 4;
    private static final int TILE = 16, TILES = 2048;

    private final ProvinceMap provinces;
    private final double step;
    private final ConcurrentHashMap<Long, Tile> tiles = new ConcurrentHashMap<>();

    /** One tile of nodes and its own coordinates (a hash collision is recomputed, not misread). */
    private static final class Tile {
        final long i, j;
        final double[] values;

        Tile(long i, long j, double[] values) {
            this.i = i;
            this.j = j;
            this.values = values;
        }
    }

    ProvinceGround(ProvinceMap provinces, double step) {
        if (provinces == null) throw new NullPointerException();
        if (!(step > 0)) throw new IllegalArgumentException("step");
        this.provinces = provinces;
        this.step = step;
    }

    /**
     * The fields at (x, z) into out: values in 0..3, x derivatives in 4..7, z derivatives in 8..11
     * (per block).
     */
    void sample(double x, double z, double[] out) {
        double gx = x / step, gz = z / step;
        long i0 = (long) Math.floor(gx), j0 = (long) Math.floor(gz);
        double u = gx - i0, v = gz - j0;
        double[] wu = new double[4], du = new double[4], wv = new double[4], dv = new double[4];
        weights(u, wu, du);
        weights(v, wv, dv);
        java.util.Arrays.fill(out, 0, 3 * FIELDS, 0);
        for (int b = 0; b < 4; b++)
            for (int a = 0; a < 4; a++) {
                long i = i0 - 1 + a, j = j0 - 1 + b;
                double[] tile = tile(Math.floorDiv(i, TILE), Math.floorDiv(j, TILE)).values;
                int at = FIELDS * (Math.floorMod(j, TILE) * TILE + Math.floorMod(i, TILE));
                double w = wu[a] * wv[b], wx = du[a] * wv[b] / step, wz = wu[a] * dv[b] / step;
                for (int f = 0; f < FIELDS; f++) {
                    double value = tile[at + f];
                    out[f] += w * value;
                    out[FIELDS + f] += wx * value;
                    out[2 * FIELDS + f] += wz * value;
                }
            }
    }

    private static final double LIMIT = 29_900_000;

    private static double inside(double v) {
        return Math.max(-LIMIT, Math.min(LIMIT, v));
    }

    /** Catmull-Rom weights and their derivatives for the four nodes around fraction t. */
    private static void weights(double t, double[] w, double[] d) {
        double t2 = t * t, t3 = t2 * t;
        w[0] = (-t3 + 2 * t2 - t) / 2;
        w[1] = (3 * t3 - 5 * t2 + 2) / 2;
        w[2] = (-3 * t3 + 4 * t2 + t) / 2;
        w[3] = (t3 - t2) / 2;
        d[0] = (-3 * t2 + 4 * t - 1) / 2;
        d[1] = (9 * t2 - 10 * t) / 2;
        d[2] = (-9 * t2 + 8 * t + 1) / 2;
        d[3] = (3 * t2 - 2 * t) / 2;
    }

    private Tile tile(long ti, long tj) {
        long key = SeededNoise.mix(ti * 0x9E3779B97F4A7C15L ^ tj);
        Tile tile = tiles.get(key);
        if (tile != null && tile.i == ti && tile.j == tj) return tile;
        double[] values = new double[TILE * TILE * FIELDS], node = new double[FIELDS];
        for (int n = 0; n < TILE; n++)
            for (int m = 0; m < TILE; m++) {
                // Nodes past the Minecraft range read the ground just inside it, where every
                // province cell's seed point is still a legal coordinate.
                provinces.ground(
                        inside((ti * TILE + m) * step), inside((tj * TILE + n) * step), node);
                System.arraycopy(node, 0, values, FIELDS * (n * TILE + m), FIELDS);
            }
        Tile fresh = new Tile(ti, tj, values);
        if (tiles.size() >= TILES) tiles.clear();
        tiles.put(key, fresh);
        return fresh;
    }
}
