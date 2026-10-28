package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.Arrays;

/** Seed-owned regional relief preferences. No terrain, ownership, climate or water queries. */
public final class RegionalProfileMap {
    private static final Rules RULES = Rules.load("riven-terrain-regions.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    public enum Profile {
        QUIET(0),
        DISSECTED(1),
        TABULAR(2),
        RUGGED(3);

        private final int index;

        Profile(int index) {
            this.index = index;
        }
    }

    private static final Profile[] PROFILES = {
        Profile.QUIET, Profile.DISSECTED, Profile.TABULAR, Profile.RUGGED
    };
    private final double[] fractions = new double[4],
            wide = new double[4],
            fine = new double[4],
            frequencies = new double[4];
    private final double totalFrequency, fineMix, transition;
    private final Frame coarse, local;

    public RegionalProfileMap(long seed, TerrainHeightReference heights) {
        this(seed, heights, RULES.json);
    }

    RegionalProfileMap(long seed, TerrainHeightReference heights, JsonObject definition) {
        double plain =
                heights.feature("inland_plain").width.targetAverage * TerrainScale.HORIZONTAL;
        double coarseSpan = plain * Rules.number(definition, "coarseInPlainWidths", 2, 64);
        double localSpan = plain * Rules.number(definition, "localInPlainWidths", .5, 16);
        if (localSpan >= coarseSpan)
            throw new IllegalStateException("Regional scales must descend");
        transition = Rules.number(definition, "transitionFraction", .1, 1);
        fineMix = Rules.number(definition, "localMix", 0, .35);
        long identity = SeededNoise.mix(seed ^ Rules.seed(definition, "variationSeed"));
        coarse = new Frame(identity ^ 0x434f415253453031L, coarseSpan);
        local = new Frame(identity ^ 0x4c4f43414c303031L, localSpan);
        double total = 0;
        JsonObject profiles = definition.getAsJsonObject("profiles");
        for (Profile profile : PROFILES) {
            int i = profile.index;
            JsonObject p = profiles.getAsJsonObject(profile.name());
            frequencies[i] = Rules.number(p, "frequencyWeight", 0, 1);
            fractions[i] = Rules.number(p, "amplitudeFraction", .05, .95);
            wide[i] = Rules.number(p, "wideShare", 0, 1);
            fine[i] = Rules.number(p, "fineShare", 0, 1);
            if (wide[i] + fine[i] > 1) throw new IllegalStateException("Regional spectral shares");
            total += frequencies[i];
        }
        if (total <= 0) throw new IllegalStateException("Regional frequency total");
        totalFrequency = total;
    }

    private static final class Frame {
        final long seed;
        final double span, c, s, offsetX, offsetZ;

        Frame(long seed, double span) {
            this.seed = SeededNoise.mix(seed);
            this.span = span;
            double angle = SeededNoise.unit(SeededNoise.mix(seed ^ 1)) * 2 * StrictMath.PI;
            c = StrictMath.cos(angle);
            s = StrictMath.sin(angle);
            offsetX = SeededNoise.unit(SeededNoise.mix(seed ^ 2));
            offsetZ = SeededNoise.unit(SeededNoise.mix(seed ^ 3));
        }
    }

    /** Reusable caller scratch. Preferences and gradients are observations, not certificates. */
    public static final class Sample {
        private final double[] weights = new double[4], dx = new double[4], dz = new double[4];
        private double amplitudeFraction,
                amplitudeDx,
                amplitudeDz,
                wideShare,
                wideDx,
                wideDz,
                fineShare,
                fineDx,
                fineDz;
        private Profile dominant;

        public double weight(Profile profile) {
            return weights[profile.index];
        }

        public double amplitudeFraction() {
            return amplitudeFraction;
        }

        public double amplitudeDx() {
            return amplitudeDx;
        }

        public double amplitudeDz() {
            return amplitudeDz;
        }

        public double wideShare() {
            return wideShare;
        }

        public double wideDx() {
            return wideDx;
        }

        public double wideDz() {
            return wideDz;
        }

        public double fineShare() {
            return fineShare;
        }

        public double fineDx() {
            return fineDx;
        }

        public double fineDz() {
            return fineDz;
        }

        public Profile dominant() {
            return dominant;
        }

        private void clear() {
            Arrays.fill(weights, 0);
            Arrays.fill(dx, 0);
            Arrays.fill(dz, 0);
            amplitudeFraction =
                    amplitudeDx =
                            amplitudeDz =
                                    wideShare = wideDx = wideDz = fineShare = fineDx = fineDz = 0;
            dominant = null;
        }
    }

    /** Four absolute lattice contributors at each of two fixed scales; no retained point data. */
    public void sample(double x, double z, Sample out) {
        Rules.coordinates(x, z);
        if (out == null) throw new NullPointerException("out");
        out.clear();
        add(coarse, x, z, 1 - fineMix, out);
        add(local, x, z, fineMix, out);
        double strongest = -1;
        for (Profile profile : PROFILES) {
            int i = profile.index;
            out.amplitudeFraction += out.weights[i] * fractions[i];
            out.amplitudeDx += out.dx[i] * fractions[i];
            out.amplitudeDz += out.dz[i] * fractions[i];
            out.wideShare += out.weights[i] * wide[i];
            out.wideDx += out.dx[i] * wide[i];
            out.wideDz += out.dz[i] * wide[i];
            out.fineShare += out.weights[i] * fine[i];
            out.fineDx += out.dx[i] * fine[i];
            out.fineDz += out.dz[i] * fine[i];
            if (out.weights[i] > strongest) {
                strongest = out.weights[i];
                out.dominant = profile;
            }
        }
    }

    void clear(Sample out) {
        out.clear();
    }

    private void add(Frame frame, double x, double z, double mix, Sample out) {
        double u = (x * frame.c + z * frame.s) / frame.span + frame.offsetX;
        double v = (-x * frame.s + z * frame.c) / frame.span + frame.offsetZ;
        int ix = (int) Math.floor(u), iz = (int) Math.floor(v);
        double core = (1 - transition) * .5;
        double a = Math.max(0, Math.min(1, (u - ix - core) / transition));
        double b = Math.max(0, Math.min(1, (v - iz - core) / transition));
        double ax = derivative(a) / (transition * frame.span),
                bz = derivative(b) / (transition * frame.span);
        double su = Math.max(0, Math.min(1, Rules.smooth(a))),
                sv = Math.max(0, Math.min(1, Rules.smooth(b)));
        for (int dz = 0; dz < 2; dz++)
            for (int dx = 0; dx < 2; dx++) {
                double wu = dx == 0 ? 1 - su : su, wv = dz == 0 ? 1 - sv : sv;
                double du = (dx == 0 ? -ax : ax) * wv * mix;
                double dv = (dz == 0 ? -bz : bz) * wu * mix;
                int profile =
                        choose(SeededNoise.unit(SeededNoise.cell(frame.seed, ix + dx, iz + dz)));
                out.weights[profile] += wu * wv * mix;
                out.dx[profile] += du * frame.c - dv * frame.s;
                out.dz[profile] += du * frame.s + dv * frame.c;
            }
    }

    private int choose(double unit) {
        double draw = unit * totalFrequency;
        for (Profile profile : PROFILES) {
            draw -= frequencies[profile.index];
            if (draw < 0) return profile.index;
        }
        // The final positive interval also owns any final subtraction roundoff.
        for (int i = 3; i >= 0; i--) if (frequencies[i] > 0) return i;
        throw new AssertionError("Validated frequency total");
    }

    private static double derivative(double t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }
}
