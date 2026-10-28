package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Earlier regional axis preferences, independent of terrain, owners, climate and water. */
public final class RegionalFabricMap {
    private static final Rules RULES = Rules.load("riven-regional-fabric.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final double[] AXIS_X = new double[256], AXIS_Z = new double[256];

    static {
        // A fixed immutable basis avoids trigonometric work at each lattice contributor.
        for (int i = 0; i < AXIS_X.length; i++) {
            double angle = i * (2 * Math.PI / AXIS_X.length);
            AXIS_X[i] = StrictMath.cos(angle);
            AXIS_Z[i] = StrictMath.sin(angle);
        }
    }

    private final long seed;
    private final double span, transition, minimumConfidence, fullConfidence;
    private final double c, s, offsetX, offsetZ;

    public RegionalFabricMap(long worldSeed, TerrainHeightReference heights) {
        this(worldSeed, heights, RULES.json);
    }

    RegionalFabricMap(long worldSeed, TerrainHeightReference heights, JsonObject json) {
        if (heights == null || json == null) throw new NullPointerException();
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(json, "variationSeed"));
        span =
                heights.feature("inland_plain").width.targetAverage
                        * Rules.number(json, "spanInPlainWidths", 2, 64)
                        * TerrainScale.HORIZONTAL;
        transition = Rules.number(json, "transitionFraction", .1, 1);
        minimumConfidence = Rules.number(json, "minimumConfidence", 0, .95);
        fullConfidence = Rules.number(json, "fullConfidence", .05, 1);
        if (fullConfidence <= minimumConfidence)
            throw new IllegalStateException("Fabric confidence interval must increase");
        double angle = SeededNoise.unit(SeededNoise.mix(seed ^ 0x464142524f544154L)) * 2 * Math.PI;
        c = StrictMath.cos(angle);
        s = StrictMath.sin(angle);
        offsetX = SeededNoise.unit(SeededNoise.mix(seed ^ 0x4641424f46465831L)) * 128 - 64;
        offsetZ = SeededNoise.unit(SeededNoise.mix(seed ^ 0x4641424f46465a31L)) * 128 - 64;
    }

    /** Doubled-angle components preserve the equivalence of axes separated by pi radians. */
    public static final class Sample {
        private double doubledX, doubledZ, confidence;

        public double doubledX() {
            return doubledX;
        }

        public double doubledZ() {
            return doubledZ;
        }

        public double confidence() {
            return confidence;
        }
    }

    /** Four absolute lattice contributors, no retained points or per-query objects. */
    public void sample(double x, double z, Sample out) {
        Rules.coordinates(x, z);
        if (out == null) throw new NullPointerException("out");
        double u = (x * c + z * s) / span + offsetX;
        double v = (-x * s + z * c) / span + offsetZ;
        int ix = (int) Math.floor(u), iz = (int) Math.floor(v);
        double core = (1 - transition) * .5;
        double a = Math.max(0, Math.min(1, (u - ix - core) / transition));
        double b = Math.max(0, Math.min(1, (v - iz - core) / transition));
        double su = Math.max(0, Math.min(1, Rules.smooth(a)));
        double sv = Math.max(0, Math.min(1, Rules.smooth(b)));
        double qx = 0, qz = 0;
        for (int dz = 0; dz < 2; dz++)
            for (int dx = 0; dx < 2; dx++) {
                double weight = (dx == 0 ? 1 - su : su) * (dz == 0 ? 1 - sv : sv);
                int axis = (int) (SeededNoise.cell(seed, ix + dx, iz + dz) >>> 56);
                qx += weight * AXIS_X[axis];
                qz += weight * AXIS_Z[axis];
            }
        out.doubledX = qx;
        out.doubledZ = qz;
        out.confidence = Math.min(1, StrictMath.hypot(qx, qz));
    }

    /** Steer an owner's neutral heading along the shortest unoriented arc; never normalize an axis. */
    public double heading(Sample sample, double neutral) {
        if (sample == null) throw new NullPointerException("sample");
        if (!Double.isFinite(neutral))
            throw new IllegalArgumentException("Finite neutral heading required");
        if (sample.confidence <= minimumConfidence) return neutral;
        double influence =
                Rules.smooth(
                        (sample.confidence - minimumConfidence)
                                / (fullConfidence - minimumConfidence));
        if (influence == 0) return neutral;
        double axis = .5 * StrictMath.atan2(sample.doubledZ, sample.doubledX);
        double delta = StrictMath.IEEEremainder(axis - neutral, Math.PI);
        return neutral + influence * delta;
    }
}
