package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Contour jitter (terrain-v147): one short-wave warp w(x), a pure function of seed and the base
 * terrain definitions, that the base terrain, the terrain passes and drainage valley sides read
 * so soil ground loses its long, evenly spaced one-block contour lines.
 *
 * <p>A reader takes its shape at x + w(x). The height then moves by about grad(h) . w: nothing on
 * flat ground, more on slopes. Each octave is a pair of gradient-noise fields (one per axis) at
 * {@code wavelengthBlocks}, scaled by {@code blocks}; the sum of blocks / wavelength is bounded so
 * x + w(x) never folds (its Jacobian determinant stays near or above 0.35).
 */
public final class ContourJitter {
    private static final Rules RULES = Rules.load("riven-base-terrain.json", 1);

    private final double[] scale, blocks;
    private final SeededNoise[] noiseX, noiseZ;

    /**
     * Valley sides: the jitter a valley of span s takes is j s / (s + valleyEase), so its share of
     * the span stays under a quarter and the side never turns back.
     */
    final double valleyEase;

    /**
     * The jitter fades out as the ground's slope climbs from fadeFrom to fadeTo blocks per block:
     * a warp steepens ground as much as it turns contours, so it is spent on gentle soil ground
     * and gone where a slope already nears a block per block.
     */
    private final double fadeFrom, fadeTo;

    /**
     * The share of the jitter the terrain passes' soft forms (hummocks, knolls, dunes) take. Their
     * flanks have no slope cap of their own, so the warp's stretch steepens them into steps.
     */
    final double passesShare;

    /** The warp at one point: displacement, and the Jacobian of x + w(x) (m_ab = dp_a / dx_b). */
    public static final class Warp {
        public double x, z, mxx = 1, mxz, mzx, mzz = 1;
        private final HeightSample scratch = new HeightSample();

        public double determinant() {
            return mxx * mzz - mxz * mzx;
        }

        /**
         * Scales the warp by k(x), whose gradient is (kx, kz): w becomes k w and the Jacobian
         * I + k Jw + w (x) grad k, so x + k(x) w(x) stays a warp with an exact Jacobian.
         */
        public void fade(double k, double kx, double kz) {
            double jxx = mxx - 1, jzz = mzz - 1;
            mxx = 1 + k * jxx + x * kx;
            mxz = k * mxz + x * kz;
            mzx = k * mzx + z * kx;
            mzz = 1 + k * jzz + z * kz;
            x *= k;
            z *= k;
        }

        /** No warp: what a reused sample reports where its reader did not run. */
        public void clear() {
            x = z = mxz = mzx = 0;
            mxx = mzz = 1;
            scratch.set(0, 0, 0);
        }
    }

    ContourJitter(long seed) {
        JsonObject j = RULES.json.getAsJsonObject("contourJitter");
        JsonArray octaves = j.getAsJsonArray("octaves");
        int n = octaves.size();
        scale = new double[n];
        blocks = new double[n];
        noiseX = new SeededNoise[n];
        noiseZ = new SeededNoise[n];
        double stretch = 0;
        for (int k = 0; k < n; k++) {
            JsonObject o = octaves.get(k).getAsJsonObject();
            scale[k] = Rules.number(o, "wavelengthBlocks", 8, 256);
            blocks[k] = Rules.number(o, "blocks", 0, 16);
            // A gradient octave's slope stays under about 2.5 per wavelength.
            stretch += blocks[k] / scale[k];
            noiseX[k] = new SeededNoise(SeededNoise.mix(seed ^ (0x4a49545458L + 2 * k)));
            noiseZ[k] = new SeededNoise(SeededNoise.mix(seed ^ (0x4a4954545aL + 2 * k)));
        }
        if (stretch > .3) throw new IllegalStateException("contourJitter folds: blocks/wavelength");
        valleyEase = Rules.number(j, "valleyEaseBlocks", 4, 256);
        fadeFrom = Rules.number(j, "fadeFromSlope", 0, 4);
        fadeTo = Rules.number(j, "fadeToSlope", fadeFrom + .05, 8);
        passesShare = Rules.number(j, "passesShare", 0, 1);
    }

    /** The share of the jitter ground of this slope (blocks per block) takes, 1 to 0. */
    public double fade(double slope) {
        return 1 - Rules.smooth((slope - fadeFrom) / (fadeTo - fadeFrom));
    }

    /** fade's derivative with respect to the slope. */
    public double fadeSlope(double slope) {
        double t = (slope - fadeFrom) / (fadeTo - fadeFrom);
        if (t <= 0 || t >= 1) return 0;
        return -30 * t * t * (t - 1) * (t - 1) / (fadeTo - fadeFrom);
    }

    /** The warp at (x, z), into out. */
    public void sample(double x, double z, Warp out) {
        out.clear();
        HeightSample s = out.scratch;
        for (int k = 0; k < scale.length; k++) {
            double a = blocks[k], w = scale[k];
            if (a == 0) continue;
            noiseX[k].gradient(x / w + .37, z / w + .81, s);
            out.x += a * s.height;
            out.mxx += a * s.dx / w;
            out.mxz += a * s.dz / w;
            noiseZ[k].gradient(x / w + .62, z / w + .14, s);
            out.z += a * s.height;
            out.mzx += a * s.dx / w;
            out.mzz += a * s.dz / w;
        }
    }

    /**
     * A valley side's distance from its bank, jittered: beyond + 4u(1 - u) a with u = beyond /
     * span, exactly 0 at the bank and span at the valley's edge, so flush banks and the edge
     * stay where they were. a is the scalar jitter j eased on narrow valleys (its share of the
     * span under a quarter, so u never turns back) and bounded so the side still climbs at most
     * 0.9 blocks per block before the tangential share: a side of rise r climbs at most 1.875 r
     * per span, the jitter stretches u by at most 1 + 4|a| / span, and where the side already
     * nears a block per block (Hydro's capped ramp) the jitter is gone.
     */
    public double valley(double beyond, double span, double rise, double j) {
        if (j == 0 || span <= 0 || beyond <= 0 || beyond >= span) return beyond;
        double room = .9 / (1.875 * Math.max(rise, 1e-9)) - 1;
        if (room <= 0) return beyond;
        double a = j * span / (span + valleyEase);
        double limit = span * room / 4;
        if (Math.abs(a) > limit) a = Math.copySign(limit, a);
        double u = beyond / span;
        return beyond + 4 * u * (1 - u) * a;
    }

    /**
     * The warp's x displacement alone: a scalar jitter in blocks for readers that move one
     * distance (a valley side's distance from its bank) rather than a point.
     */
    public double along(double x, double z, HeightSample scratch) {
        double v = 0;
        for (int k = 0; k < scale.length; k++) {
            double a = blocks[k], w = scale[k];
            if (a == 0) continue;
            noiseX[k].gradient(x / w + .37, z / w + .81, scratch);
            v += a * scratch.height;
        }
        return v;
    }
}
