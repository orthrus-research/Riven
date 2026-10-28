package cc.orthrus.riven.contrib.dryland;

/** Independently authored, dependency-free dryland geometry. See CONTRACT.md. */
public final class Drylands {
    public enum Family {
        CRESCENTS,
        CROSSING_RIDGES,
        DEFLATION_PAVEMENT,
        YARDANGS
    }

    public enum Reason {
        ACCEPTED,
        MARINE,
        RESERVED,
        HUMID,
        PARENT,
        SUBSTRATE,
        HEADROOM,
        NO_SUPPORT
    }

    // Stable identifiers: never use enum ordinal in a seed or occurrence decision.
    public static final long CRESCENT_SALT = 0x4452593943524553L;
    public static final long CROSSING_SALT = 0x4452593952494447L;
    public static final long DEFLATION_SALT = 0x445259394445464cL;
    public static final long YARDANG_SALT = 0x4452593959415244L;

    /** Populate from WorldGenerationContext.terrainHeights and TerrainScale.HORIZONTAL. */
    public static final class Reference {
        public final double duneWidth, duneHeight, hollowWidth, hollowDepth, ribWidth, ribHeight;
        public final double minY, maxY;

        public Reference(
                double duneWidth,
                double duneHeight,
                double hollowWidth,
                double hollowDepth,
                double ribWidth,
                double ribHeight,
                double minY,
                double maxY) {
            positive(duneWidth);
            positive(duneHeight);
            positive(hollowWidth);
            positive(hollowDepth);
            positive(ribWidth);
            positive(ribHeight);
            if (!finite(minY) || !finite(maxY) || minY >= maxY)
                throw new IllegalArgumentException("Y bounds");
            this.duneWidth = duneWidth;
            this.duneHeight = duneHeight;
            this.hollowWidth = hollowWidth;
            this.hollowDepth = hollowDepth;
            this.ribWidth = ribWidth;
            this.ribHeight = ribHeight;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    /** Dimensionless tuning. Width/magnitude factors stay within the reference envelopes. */
    public static final class Tuning {
        public final double width, magnitude, occurrence, warp, crossingAngle, leeFraction;
        public final double aridityStart, aridityFull;

        public Tuning(
                double width,
                double magnitude,
                double occurrence,
                double warp,
                double crossingAngle,
                double leeFraction,
                double aridityStart,
                double aridityFull) {
            range(width, 0.75, 1.5);
            range(magnitude, 0.5, 1.5);
            range(occurrence, 0, 1);
            range(warp, 0, 0.35);
            range(crossingAngle, 0.45, 1.35);
            range(leeFraction, 0.18, 0.4);
            range(aridityStart, 0, 0.98);
            range(aridityFull, aridityStart + 0.01, 1);
            this.width = width;
            this.magnitude = magnitude;
            this.occurrence = occurrence;
            this.warp = warp;
            this.crossingAngle = crossingAngle;
            this.leeFraction = leeFraction;
            this.aridityStart = aridityStart;
            this.aridityFull = aridityFull;
        }

        public static Tuning defaults() {
            return new Tuning(1, 1, 0.8, 0.2, 0.95, 0.25, 0.55, 0.82);
        }
    }

    /** Earlier immutable column facts; no finished-terrain callback is accepted. */
    public static final class Input {
        public final double parentY, aridity, sand, loose, erodible, parentSlope, terrainFit;
        public final double windX,
                windZ,
                shoreClearance,
                reservationClearance,
                raiseBudget,
                cutBudget;
        public final boolean marine;

        public Input(
                double parentY,
                double aridity,
                double sand,
                double loose,
                double erodible,
                double parentSlope,
                double terrainFit,
                double windX,
                double windZ,
                double shoreClearance,
                double reservationClearance,
                double raiseBudget,
                double cutBudget,
                boolean marine) {
            if (!finite(parentY)) throw new IllegalArgumentException("parentY");
            range(aridity, 0, 1);
            range(sand, 0, 1);
            range(loose, 0, 1);
            range(erodible, 0, 1);
            range(parentSlope, 0, 16);
            range(terrainFit, 0, 1);
            range(windX, -1, 1);
            range(windZ, -1, 1);
            range(shoreClearance, 0, 1e9);
            range(reservationClearance, 0, 1e9);
            range(raiseBudget, 0, 1e9);
            range(cutBudget, 0, 1e9);
            if (windX * windX + windZ * windZ < 0.01)
                throw new IllegalArgumentException("wind vector");
            this.parentY = parentY;
            this.aridity = aridity;
            this.sand = sand;
            this.loose = loose;
            this.erodible = erodible;
            this.parentSlope = parentSlope;
            this.terrainFit = terrainFit;
            this.windX = windX;
            this.windZ = windZ;
            this.shoreClearance = shoreClearance;
            this.reservationClearance = reservationClearance;
            this.raiseBudget = raiseBudget;
            this.cutBudget = cutBudget;
            this.marine = marine;
        }
    }

    /** Caller-owned scratch. One instance per thread; every field resets on each sample. */
    public static final class Output {
        public double delta, weight, pavement, sandCover;
        public int candidates;
        public Reason reason = Reason.NO_SUPPORT;

        private void reset() {
            delta = weight = pavement = sandCover = 0;
            candidates = 0;
            reason = Reason.NO_SUPPORT;
        }
    }

    private final long seed;
    private final Reference ref;
    private final Tuning tune;

    public Drylands(long seed, Reference ref, Tuning tune) {
        if (ref == null || tune == null) throw new NullPointerException();
        this.seed = seed;
        this.ref = ref;
        this.tune = tune;
    }

    public double width(Family f) {
        return tune.width
                * (f == Family.DEFLATION_PAVEMENT
                        ? ref.hollowWidth
                        : f == Family.YARDANGS ? ref.ribWidth : ref.duneWidth);
    }

    public double riseBound(Family f) {
        return tune.magnitude
                * (f == Family.DEFLATION_PAVEMENT
                        ? 0.5
                        : f == Family.YARDANGS ? 0 : ref.duneHeight);
    }

    public double cutBound(Family f) {
        return tune.magnitude
                * (f == Family.DEFLATION_PAVEMENT
                        ? ref.hollowDepth
                        : f == Family.YARDANGS ? ref.ribHeight : 0);
    }

    /** Maximum anchor radius (or local field-cell reach for continuous families), blocks. */
    public double supportRadius(Family f) {
        double w = width(f);
        return f == Family.CRESCENTS
                ? 1.0 * w
                : f == Family.DEFLATION_PAVEMENT ? 1.0 * w : f == Family.YARDANGS ? 16 * w : 24 * w;
    }

    public static long salt(Family f) {
        switch (f) {
            case CRESCENTS:
                return CRESCENT_SALT;
            case CROSSING_RIDGES:
                return CROSSING_SALT;
            case DEFLATION_PAVEMENT:
                return DEFLATION_SALT;
            default:
                return YARDANG_SALT;
        }
    }

    public void sample(Family f, double x, double z, Input in, Output out) {
        if (f == null || in == null || out == null) throw new NullPointerException();
        if (!finite(x)
                || !finite(z)
                || StrictMath.abs(x) > 30000000
                || StrictMath.abs(z) > 30000000)
            throw new IllegalArgumentException("Minecraft coordinate bound");
        out.reset();
        if (in.marine) {
            out.reason = Reason.MARINE;
            return;
        }
        if (in.shoreClearance <= 0 || in.reservationClearance <= 0) {
            out.reason = Reason.RESERVED;
            return;
        }
        if (in.aridity <= tune.aridityStart) {
            out.reason = Reason.HUMID;
            return;
        }
        if (in.terrainFit <= 0 || in.parentSlope >= 0.55) {
            out.reason = Reason.PARENT;
            return;
        }
        double material =
                f == Family.YARDANGS
                        ? in.erodible
                        : f == Family.DEFLATION_PAVEMENT ? in.loose : in.sand;
        if (material <= 0.25) {
            out.reason = Reason.SUBSTRATE;
            return;
        }
        double up = riseBound(f), down = cutBound(f);
        // Reject the entire signed envelope, never flatten overflow against a hard ceiling.
        if (in.raiseBudget < up
                || in.cutBudget < down
                || in.parentY + up > ref.maxY
                || in.parentY - down < ref.minY) {
            out.reason = Reason.HEADROOM;
            return;
        }
        double w = width(f), edge = StrictMath.min(in.shoreClearance, in.reservationClearance);
        double admit =
                smooth((in.aridity - tune.aridityStart) / (tune.aridityFull - tune.aridityStart))
                        * smooth((material - 0.25) / 0.5)
                        * smooth((0.55 - in.parentSlope) / 0.3)
                        * in.terrainFit
                        * smooth(edge / (w * 0.75));
        long s = seed ^ salt(f);
        // Seed-owned continuous transverse shear: caller wind is earlier regional intent.
        double len = StrictMath.sqrt(in.windX * in.windX + in.windZ * in.windZ);
        double c = in.windX / len, sn = in.windZ / len;
        double u = x * c + z * sn, v = -x * sn + z * c;
        double raw;
        switch (f) {
            case CRESCENTS:
                raw = crescents(s, x, z, c, sn, w, out);
                break;
            case CROSSING_RIDGES:
                raw = crossings(s, u, v, w);
                break;
            case DEFLATION_PAVEMENT:
                raw = deflation(s, x, z, c, sn, w, out);
                break;
            default:
                raw = yardangs(s, u, v, w, out);
                break;
        }
        if (raw == 0 && out.pavement == 0) {
            out.reason = Reason.NO_SUPPORT;
            return;
        }
        out.weight = admit;
        out.delta = raw * admit;
        out.pavement *= admit;
        out.sandCover = (f == Family.CRESCENTS || f == Family.CROSSING_RIDGES) ? admit : 0;
        out.reason = Reason.ACCEPTED;
    }

    // Finite jittered point process; max-union prevents overlap from amplifying the rise bound.
    private double crescents(
            long s, double x, double z, double c, double sn, double w, Output out) {
        double cell = 1.65 * w, best = 0;
        long ix = floor(x / cell), iz = floor(z / cell);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                out.candidates++;
                long h = hash(s, ix + dx, iz + dz);
                if (unit(h) >= tune.occurrence) continue;
                double ax = (ix + dx + 0.5 + 0.28 * signed(h + 11)) * cell;
                double az = (iz + dz + 0.5 + 0.28 * signed(h + 29)) * cell;
                double turn = 0.28 * noise(s + 83, ax / (32 * w), az / (32 * w));
                double ct = c * StrictMath.cos(turn) - sn * StrictMath.sin(turn);
                double st = sn * StrictMath.cos(turn) + c * StrictMath.sin(turn);
                double u = ((x - ax) * ct + (z - az) * st) / w;
                double v = (-(x - ax) * st + (z - az) * ct) / w;
                double side = StrictMath.abs(v) / 0.5;
                if (side >= 1) continue;
                // Horns bend downwind; the center has a long stoss and short lee side.
                double crest = -0.12 + 0.46 * side * side + 0.025 * signed(h + 47);
                double q = (u - crest) / (u < crest ? 0.56 : tune.leeFraction);
                if (StrictMath.abs(q) >= 1) continue;
                double envelope = (1 - side * side);
                double shape = smooth(1 - StrictMath.abs(q)) * StrictMath.sqrt(envelope) * envelope;
                best = StrictMath.max(best, shape * (0.7 + 0.3 * unit(h + 53)));
            }
        return best * ref.duneHeight * tune.magnitude;
    }

    // Two warped phases, with nonlinear bounded union: intersecting crests form compound dunes.
    private double crossings(long s, double u, double v, double w) {
        double region = 1.65 * noise(s + 3, u / (16 * w), v / (16 * w));
        double admission =
                smooth(
                        (noise(s + 7, u / (10 * w), v / (10 * w)) + 1) * 0.5
                                - (1 - tune.occurrence));
        double shear = tune.warp * noise(s + 13, u / (5 * w), v / (5 * w));
        double a = u / (1.35 * w) + shear + region;
        double b =
                (u * StrictMath.cos(tune.crossingAngle) + v * StrictMath.sin(tune.crossingAngle))
                                / (1.65 * w)
                        + 0.7 * region
                        + 0.4 * noise(s + 19, u / (7 * w), v / (7 * w));
        double p = ridge(a, tune.leeFraction), q = ridge(b, tune.leeFraction);
        double second = smooth((noise(s + 31, u / (9 * w), v / (9 * w)) + 0.5) / 1.1);
        return (1 - (1 - p) * (1 - 0.82 * q * second))
                * admission
                * ref.duneHeight
                * tune.magnitude;
    }

    private double deflation(
            long s, double x, double z, double c, double sn, double w, Output out) {
        double cell = 1.5 * w, bowl = 0;
        long ix = floor(x / cell), iz = floor(z / cell);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                out.candidates++;
                long h = hash(s, ix + dx, iz + dz);
                if (unit(h) >= tune.occurrence) continue;
                double ax = (ix + dx + 0.5 + 0.25 * signed(h + 11)) * cell;
                double az = (iz + dz + 0.5 + 0.25 * signed(h + 23)) * cell;
                double u = ((x - ax) * c + (z - az) * sn) / (0.68 * w);
                double v = (-(x - ax) * sn + (z - az) * c) / (0.5 * w);
                // Bound the polynomial warp before evaluation; it must never fold into remote
                // support.
                if (StrictMath.abs(u) >= 1.1 || StrictMath.abs(v) >= 1.3) continue;
                double r2 = u * u + v * v * (1 + 0.22 * u) + 0.13 * u * v * v - 0.1 * v * u * u;
                if (r2 >= 1) continue;
                bowl = StrictMath.max(bowl, smooth(1 - r2) * (0.65 + 0.35 * unit(h + 41)));
            }
        double patch = smooth((noise(s + 57, x / (0.34 * w), z / (0.34 * w)) + 0.25) / 0.65);
        out.pavement = tune.occurrence * patch * (0.3 + 0.7 * bowl);
        // Sub-block relief represents gravel-scale geometry; material assignment is a later stage.
        double grit = 0.5 * smooth((noise(s + 71, x / 3, z / 3) + 0.2) / 0.8) * out.pavement;
        return tune.magnitude * (-ref.hollowDepth * bowl + grit);
    }

    // Anisotropic cellular residual: elongated bedrock cores survive a cut field.
    // 5x5 search covers every core with metric < 1; more distant minima cannot affect the cut.
    private double yardangs(long s, double u, double v, double w, Output out) {
        double a = u / (4 * w),
                b = (v + tune.warp * w * noise(s + 3, u / (7 * w), v / (7 * w))) / w;
        long ix = floor(a), iz = floor(b);
        double nearest = Double.POSITIVE_INFINITY;
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                out.candidates++;
                long h = hash(s, ix + dx, iz + dz);
                double cx = ix + dx + 0.5 + 0.2 * signed(h + 11);
                double cz = iz + dz + 0.5 + 0.2 * signed(h + 23);
                double longitudinal = a - cx;
                // Wider blunt upwind nose and tapered lee, without overhangs or occupancy claims.
                double breadth =
                        (0.3 + 0.08 * unit(h + 37))
                                / (longitudinal < 0 ? 1 : (1 + 1.8 * longitudinal));
                double bend = 0.1 * signed(h + 47) * longitudinal;
                double across = b - cz - bend;
                double metric =
                        longitudinal * longitudinal / (0.42 + 0.23 * unit(h + 61))
                                + (across / breadth) * (across / breadth);
                nearest = StrictMath.min(nearest, metric);
            }
        double core = smooth(1 - nearest);
        double patch =
                smooth((noise(s + 53, u / (9 * w), v / (9 * w)) + 1) * 0.5 - (1 - tune.occurrence));
        return -ref.ribHeight * tune.magnitude * patch * (1 - core);
    }

    private static double ridge(double phase, double lee) {
        double p = phase - StrictMath.floor(phase), crest = 1 - lee;
        return p < crest ? smooth(p / crest) : smooth((1 - p) / lee);
    }

    public static double smooth(double t) {
        t = StrictMath.max(0, StrictMath.min(1, t));
        return t * t * (3 - 2 * t);
    }

    public static double noise(long seed, double x, double z) {
        long ix = floor(x), iz = floor(z);
        double a = smooth(x - ix), b = smooth(z - iz);
        return lerp(
                lerp(signed(hash(seed, ix, iz)), signed(hash(seed, ix + 1, iz)), a),
                lerp(signed(hash(seed, ix, iz + 1)), signed(hash(seed, ix + 1, iz + 1)), a),
                b);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long floor(double v) {
        return (long) StrictMath.floor(v);
    }

    public static long hash(long seed, long x, long z) {
        long h = seed + x * 0x632be59bd9b4e019L + z * 0x9e3779b97f4a7c15L;
        h = (h ^ (h >>> 29)) * 0xd6e8feb86659fd93L;
        h = (h ^ (h >>> 32)) * 0xa5a3564e27f8862bL;
        return h ^ (h >>> 31);
    }

    private static double unit(long h) {
        return (hash(h, 0, 0) >>> 11) * 0x1.0p-53;
    }

    private static double signed(long h) {
        return 2 * unit(h) - 1;
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static void positive(double v) {
        if (!finite(v) || v <= 0) throw new IllegalArgumentException("positive dimension");
    }

    private static void range(double v, double lo, double hi) {
        if (!finite(v) || v < lo || v > hi)
            throw new IllegalArgumentException("range " + lo + ".." + hi);
    }
}
