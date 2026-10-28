/* SPDX-License-Identifier: MIT
 * Riven integration: read-only support/identity queries and an early eligibility
 * entry point for shared ownership. Geometry and ordered admission are retained.
 * Packaged license: META-INF/licenses/plateaus/MIT.txt.
 */
package cc.orthrus.riven.contrib.plateau;

/** Independent, bounded heightmap prototypes. No Minecraft or loader dependencies. */
@SuppressWarnings("strictfp") // Retain strict evaluation for the Java 8 fallback.
public final strictfp class PlateauKernels {
    private PlateauKernels() {}

    public enum Family {
        WARPED_CAP(0x504C415443415031L),
        MESA_REMNANTS(0x4D45534152454D31L),
        BROKEN_SCARP(0x5343415250465231L),
        BRANCH_BADLANDS(0x4241444252414E31L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPT,
        MARINE,
        WATER_RESERVED,
        SHORE_PROTECTED,
        NO_INTENT,
        TOO_WET,
        NOT_ERODIBLE,
        HEADROOM,
        OUTSIDE_SUPPORT,
        COLUMN_PROTECTED
    }

    /** Copy these values from WorldGenerationContext.terrainHeights, never from final terrain. */
    public static final class Reference {
        public final double plateauWidth,
                plateauRelief,
                plateauY,
                mesaWidth,
                mesaRise,
                scarpWidth,
                scarpDrop,
                valleyCut,
                terraceDrop,
                minY,
                maxY;

        public Reference(
                double plateauWidth,
                double plateauRelief,
                double plateauY,
                double mesaWidth,
                double mesaRise,
                double scarpWidth,
                double scarpDrop,
                double valleyCut,
                double terraceDrop,
                double minY,
                double maxY) {
            double[] positive = {
                plateauWidth,
                plateauRelief,
                mesaWidth,
                mesaRise,
                scarpWidth,
                scarpDrop,
                valleyCut,
                terraceDrop
            };
            for (double v : positive)
                if (!Double.isFinite(v) || v <= 0)
                    throw new IllegalArgumentException("reference scale");
            if (!Double.isFinite(plateauY)
                    || !Double.isFinite(minY)
                    || !Double.isFinite(maxY)
                    || minY >= maxY) throw new IllegalArgumentException("reference heights");
            this.plateauWidth = plateauWidth;
            this.plateauRelief = plateauRelief;
            this.plateauY = plateauY;
            this.mesaWidth = mesaWidth;
            this.mesaRise = mesaRise;
            this.scarpWidth = scarpWidth;
            this.scarpDrop = scarpDrop;
            this.valleyCut = valleyCut;
            this.terraceDrop = terraceDrop;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    public static final class Tuning {
        public final double widthScale, reliefScale, skirtFraction, warp, dissection, aridityFloor;

        public Tuning(
                double widthScale,
                double reliefScale,
                double skirtFraction,
                double warp,
                double dissection,
                double aridityFloor) {
            if (!(widthScale >= 0.5
                    && widthScale <= 1.5
                    && reliefScale >= 0.25
                    && reliefScale <= 1.5
                    && skirtFraction >= 0.08
                    && skirtFraction <= 0.3
                    && warp >= 0
                    && warp <= 0.18
                    && dissection >= 0
                    && dissection <= 1
                    && aridityFloor >= 0
                    && aridityFloor <= 1)) throw new IllegalArgumentException("tuning range");
            this.widthScale = widthScale;
            this.reliefScale = reliefScale;
            this.skirtFraction = skirtFraction;
            this.warp = warp;
            this.dissection = dissection;
            this.aridityFloor = aridityFloor;
        }

        public static Tuning defaults() {
            return new Tuning(1, 1, 0.18, 0.10, 0.75, 0.35);
        }
    }

    /** Immutable anchor admission and conservative bounds over the ENTIRE support including skirt
     * in the caller's declared domain. Riven certifies integer columns plus the anchor;
     * that domain does not establish continuous or alternative-octave bounds.
     * Parent bounds must refer to the exact immutable column surface supplied to sample(). */
    public static final class Earlier {
        public final double parentMin, parentMax, plateauIntent, aridity, erodibility;
        public final boolean marine, waterReserved, shoreProtected;

        public Earlier(
                double parentMin,
                double parentMax,
                double plateauIntent,
                double aridity,
                double erodibility,
                boolean marine,
                boolean waterReserved,
                boolean shoreProtected) {
            if (!Double.isFinite(parentMin)
                    || !Double.isFinite(parentMax)
                    || parentMin > parentMax
                    || !unit(plateauIntent)
                    || !unit(aridity)
                    || !unit(erodibility)) throw new IllegalArgumentException("earlier facts");
            this.parentMin = parentMin;
            this.parentMax = parentMax;
            this.plateauIntent = plateauIntent;
            this.aridity = aridity;
            this.erodibility = erodibility;
            this.marine = marine;
            this.waterReserved = waterReserved;
            this.shoreProtected = shoreProtected;
        }
    }

    public static final class Plan {
        public final Family family;
        public final Reason reason;
        public final double x, z, radius, minDelta, maxDelta;
        private final long seed;
        private final double cos,
                sin,
                width,
                amplitude,
                warp,
                dissection,
                capY,
                capRelief,
                scarpWidth,
                blendWidth;
        private final Earlier earlier;
        // Segment arrays are private and never mutated or exposed. At most 32 tapered segments.
        private final double[] segments;

        public long id() {
            return seed;
        }

        private Plan(
                Family family,
                Reason reason,
                long seed,
                double x,
                double z,
                double radius,
                double width,
                double amplitude,
                double minDelta,
                double maxDelta,
                double angle,
                Tuning tuning,
                Reference ref,
                Earlier earlier) {
            this.family = family;
            this.reason = reason;
            this.seed = seed;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.width = width;
            this.amplitude = amplitude;
            this.minDelta = minDelta;
            this.maxDelta = maxDelta;
            cos = StrictMath.cos(angle);
            sin = StrictMath.sin(angle);
            scarpWidth = ref.scarpWidth * tuning.widthScale;
            blendWidth = width * tuning.skirtFraction;
            warp = tuning.warp;
            dissection = tuning.dissection;
            capY = ref.plateauY;
            capRelief = ref.plateauRelief;
            this.earlier = earlier;
            segments =
                    family == Family.BRANCH_BADLANDS && reason == Reason.ACCEPT
                            ? network(seed, width)
                            : null;
        }
    }

    /** Caller owns one scratch result per worker; kernel has no mutable static state. */
    public static final class Result {
        public double delta, weight;
        public Reason reason;
    }

    public static Plan plan(
            Family family,
            long worldSeed,
            long siteX,
            long siteZ,
            double anchorX,
            double anchorZ,
            Reference ref,
            Tuning tuning,
            Earlier earlier) {
        if (!Double.isFinite(anchorX)
                || !Double.isFinite(anchorZ)
                || StrictMath.abs(anchorX) > 30000000
                || StrictMath.abs(anchorZ) > 30000000)
            throw new IllegalArgumentException("Minecraft coordinates");
        long seed = hash(worldSeed ^ family.salt, siteX, siteZ);
        double width = width(family, seed, ref, tuning);
        double amplitude =
                (family == Family.MESA_REMNANTS
                                ? ref.mesaRise
                                : family == Family.BROKEN_SCARP ? ref.scarpDrop : ref.valleyCut)
                        * tuning.reliefScale;
        if (family == Family.MESA_REMNANTS) amplitude *= 0.72 + 0.28 * unitHash(seed + 202);
        double min = 0, max = 0;
        if (family == Family.WARPED_CAP) {
            min = StrictMath.min(0, ref.plateauY - ref.plateauRelief / 2 - earlier.parentMax);
            max = StrictMath.max(0, ref.plateauY + ref.plateauRelief / 2 - earlier.parentMin);
        } else if (family == Family.MESA_REMNANTS) max = amplitude;
        else min = -amplitude * (family == Family.BRANCH_BADLANDS ? tuning.dissection : 1);
        Reason reason =
                eligibility(
                        family,
                        tuning,
                        earlier.marine,
                        earlier.waterReserved,
                        earlier.shoreProtected,
                        earlier.plateauIntent,
                        earlier.aridity,
                        earlier.erodibility);
        if (reason == Reason.ACCEPT
                && (earlier.parentMin + min < ref.minY || earlier.parentMax + max > ref.maxY))
            reason = Reason.HEADROOM;
        return new Plan(
                family,
                reason,
                seed,
                anchorX,
                anchorZ,
                width * 0.5,
                width,
                amplitude,
                min,
                max,
                unitHash(seed) * StrictMath.PI * 2,
                tuning,
                ref,
                earlier);
    }

    /** Riven adapter query: dimensions need no invented parent or admission facts. */
    public static double supportRadius(
            Family family, long worldSeed, long siteX, long siteZ, Reference ref, Tuning tuning) {
        return width(family, hash(worldSeed ^ family.salt, siteX, siteZ), ref, tuning) * 0.5;
    }

    private static double width(Family family, long seed, Reference ref, Tuning tuning) {
        double width =
                (family == Family.MESA_REMNANTS ? ref.mesaWidth : ref.plateauWidth)
                        * tuning.widthScale;
        if (family == Family.MESA_REMNANTS) width *= 0.65 + 0.60 * unitHash(seed + 201);
        return width;
    }

    /** Ordered anchor gates only; full-support bounds are still required by plan(). */
    public static Reason eligibility(
            Family family,
            Tuning tuning,
            boolean marine,
            boolean waterReserved,
            boolean shoreProtected,
            double plateauIntent,
            double aridity,
            double erodibility) {
        if (!unit(plateauIntent) || !unit(aridity) || !unit(erodibility))
            throw new IllegalArgumentException("earlier intent");
        return marine
                ? Reason.MARINE
                : waterReserved
                        ? Reason.WATER_RESERVED
                        : shoreProtected
                                ? Reason.SHORE_PROTECTED
                                : plateauIntent < 0.35
                                        ? Reason.NO_INTENT
                                        : family == Family.BRANCH_BADLANDS
                                                        && aridity < tuning.aridityFloor
                                                ? Reason.TOO_WET
                                                : family == Family.BRANCH_BADLANDS
                                                                && erodibility < 0.5
                                                        ? Reason.NOT_ERODIBLE
                                                        : Reason.ACCEPT;
    }

    /** protectedDistance is the supplied distance outside ANY immutable reservation, zero inside.
     * Blend width is a caller policy in blocks; no query of neighbors or finished terrain occurs. */
    public static void sample(
            Plan p,
            double x,
            double z,
            double parentY,
            double protectedDistance,
            double protectionBlend,
            Result out) {
        if (!Double.isFinite(x)
                || !Double.isFinite(z)
                || !Double.isFinite(parentY)
                || !Double.isFinite(protectedDistance)
                || protectedDistance < 0
                || !Double.isFinite(protectionBlend)
                || protectionBlend <= 0) throw new IllegalArgumentException("column facts");
        out.delta = 0;
        out.weight = 0;
        out.reason = p.reason;
        if (p.reason != Reason.ACCEPT) return;
        if (protectedDistance == 0) {
            out.reason = Reason.COLUMN_PROTECTED;
            return;
        }
        double dx = x - p.x, dz = z - p.z, r = StrictMath.hypot(dx, dz), half = p.width / 2;
        if (r >= p.radius) {
            out.reason = Reason.OUTSIDE_SUPPORT;
            return;
        }
        if (parentY < p.earlier.parentMin || parentY > p.earlier.parentMax)
            throw new IllegalArgumentException("parent outside admitted envelope");
        double u = dx * p.cos + dz * p.sin, v = -dx * p.sin + dz * p.cos;
        double envelope = 1 - smooth((r - (p.radius - p.blendWidth)) / p.blendWidth);
        double protect = smooth(protectedDistance / protectionBlend), delta;
        switch (p.family) {
            case WARPED_CAP:
                {
                    double wu =
                            u
                                    + p.width
                                            * p.warp
                                            * noise(
                                                    p.seed + 1,
                                                    u / (p.width * 0.41),
                                                    v / (p.width * 0.41));
                    double wv =
                            v
                                    + p.width
                                            * p.warp
                                            * noise(
                                                    p.seed + 2,
                                                    u / (p.width * 0.37),
                                                    v / (p.width * 0.37));
                    double q =
                            StrictMath.pow(
                                    StrictMath.pow(StrictMath.abs(wu) / (half * 0.88), 4)
                                            + StrictMath.pow(StrictMath.abs(wv) / (half * 0.74), 4),
                                    0.25);
                    double cap = 1 - smooth((q - 0.68) / 0.30);
                    double top =
                            p.capY
                                    + p.capRelief
                                            * (0.35
                                                            * noise(
                                                                    p.seed + 3,
                                                                    u / (half * 0.7),
                                                                    v / (half * 0.7))
                                                    + 0.15 * u / p.radius);
                    delta = (top - parentY) * cap;
                    break;
                }
            case MESA_REMNANTS:
                {
                    double angle = StrictMath.atan2(v, u),
                            aspect = 0.66 + 0.22 * unitHash(p.seed + 17);
                    double q = StrictMath.hypot(u, v / aspect) / half;
                    double edge =
                            0.77
                                    + 0.09 * StrictMath.sin(3 * angle + unitHash(p.seed + 11) * 6)
                                    + 0.06
                                            * noise(
                                                    p.seed + 4,
                                                    u / (half * 0.42),
                                                    v / (half * 0.42));
                    double cap = 1 - smooth((q - edge + 0.16) / 0.20);
                    double talus = (1 - smooth((q - edge) / 0.25)) * (1 - cap);
                    double texture =
                            0.93
                                    + 0.045 * noise(p.seed + 5, u / (half * 0.5), v / (half * 0.5))
                                    + 0.025 * u / p.radius;
                    delta = p.amplitude * (cap * texture + 0.17 * talus);
                    break;
                }
            case BROKEN_SCARP:
                {
                    double front =
                            u
                                    + p.width
                                            * p.warp
                                            * (0.65 * noise(p.seed + 6, 0, v / (half * 0.7))
                                                    + 0.35
                                                            * noise(
                                                                    p.seed + 7,
                                                                    0,
                                                                    v / (half * 0.21)));
                    double drop = 0;
                    // Three unequal, locally pinching risers; never quantize a world-Y field.
                    for (int i = 0; i < 3; i++) {
                        double offset =
                                (-0.30
                                                + (i == 0
                                                        ? 0
                                                        : i == 1
                                                                ? 0.16
                                                                        + 0.10
                                                                                * unitHash(
                                                                                        p.seed + 32)
                                                                : 0.43
                                                                        + 0.16
                                                                                * unitHash(
                                                                                        p.seed
                                                                                                + 33)))
                                        * p.width;
                        double bend =
                                0.07
                                        * p.width
                                        * noise(p.seed + 8 + i, 0, v / (half * (0.4 + 0.13 * i)));
                        double riser = (0.7 + 0.7 * unitHash(p.seed + 41 + i)) * p.scarpWidth;
                        double persistence =
                                0.60
                                        + 0.40
                                                * smooth(
                                                        (noise(
                                                                                p.seed + 51 + i,
                                                                                0,
                                                                                v / (half * 0.19))
                                                                        + 0.7)
                                                                / 1.0);
                        double fraction = i == 0 ? 0.47 : i == 1 ? 0.32 : 0.21;
                        drop +=
                                fraction
                                        * persistence
                                        * smooth((front - offset - bend) / riser + 0.5);
                    }
                    delta = -p.amplitude * drop;
                    break;
                }
            case BRANCH_BADLANDS:
                {
                    u +=
                            p.width
                                    * 0.018
                                    * noise(
                                            p.seed + 72,
                                            u / (p.width * 0.18),
                                            v / (p.width * 0.18));
                    v +=
                            p.width
                                    * 0.018
                                    * noise(
                                            p.seed + 73,
                                            u / (p.width * 0.17),
                                            v / (p.width * 0.17));
                    double cut = 0;
                    for (int i = 0; i < p.segments.length; i += 6) {
                        double d =
                                normalizedDistance(
                                        u,
                                        v,
                                        p.segments[i],
                                        p.segments[i + 1],
                                        p.segments[i + 2],
                                        p.segments[i + 3],
                                        p.width * p.segments[i + 4],
                                        p.width * p.segments[i + 5]);
                        double c = 1 - smooth(d);
                        cut = StrictMath.max(cut, c);
                    }
                    double fine =
                            0.78
                                    + 0.22
                                            * (1
                                                    - StrictMath.abs(
                                                            noise(
                                                                    p.seed + 71,
                                                                    u / (p.width * 0.055),
                                                                    v / (p.width * 0.055))));
                    delta = -p.amplitude * p.dissection * cut * fine;
                    break;
                }
            default:
                throw new IllegalStateException("Unimplemented family: " + p.family);
        }
        out.delta = delta * envelope * protect;
        out.weight = envelope * protect;
        out.reason = Reason.ACCEPT;
    }

    private static double[] network(long seed, double width) {
        double[] a = new double[32 * 6], tx = new double[5], tz = new double[5];
        int n = 0;
        for (int i = 0; i < 5; i++) {
            tx[i] = (unitHash(seed + 301 + i) - 0.5) * width * 0.18;
            tz[i] = (-0.42 + 0.21 * i) * width;
            if (i > 0) {
                segment(
                        a,
                        n,
                        tx[i - 1],
                        tz[i - 1],
                        tx[i],
                        tz[i],
                        0.078 - 0.009 * (i - 1),
                        0.078 - 0.009 * i);
                n += 6;
            }
        }
        for (int i = 0; i < 7; i++) {
            double t = (i + 0.12 + 0.76 * unitHash(seed + 81 + i)) / 7 * 3.7;
            int edge = (int) t;
            double fraction = t - edge;
            double x = tx[edge] + (tx[edge + 1] - tx[edge]) * fraction,
                    z = tz[edge] + (tz[edge + 1] - tz[edge]) * fraction;
            double side = unitHash(seed + 91 + i) < 0.5 ? -1 : 1;
            double ex = x + side * (0.13 + 0.20 * unitHash(seed + 101 + i)) * width;
            double ez = z + (0.045 + 0.13 * unitHash(seed + 111 + i)) * width;
            double mx = (x + ex) * 0.5 + (unitHash(seed + 121 + i) - 0.5) * width * 0.07;
            double mz = (z + ez) * 0.5 + (unitHash(seed + 131 + i) - 0.5) * width * 0.08;
            double w = 0.030 + 0.022 * unitHash(seed + 141 + i);
            segment(a, n, x, z, mx, mz, w, w * 0.75);
            n += 6;
            segment(a, n, mx, mz, ex, ez, w * 0.75, w * 0.18);
            n += 6;
            double qx = mx + side * (0.04 + 0.09 * unitHash(seed + 151 + i)) * width;
            double qz = mz - (0.04 + 0.10 * unitHash(seed + 161 + i)) * width;
            segment(a, n, mx, mz, (mx + qx) * 0.5, (mz + qz) * 0.5, w * 0.52, w * 0.36);
            n += 6;
            segment(a, n, (mx + qx) * 0.5, (mz + qz) * 0.5, qx, qz, w * 0.36, w * 0.12);
            n += 6;
        }
        return a;
    }

    private static void segment(
            double[] a, int i, double x, double z, double xx, double zz, double w, double ww) {
        a[i] = x;
        a[i + 1] = z;
        a[i + 2] = xx;
        a[i + 3] = zz;
        a[i + 4] = w;
        a[i + 5] = ww;
    }

    private static double normalizedDistance(
            double x, double z, double ax, double az, double bx, double bz, double w, double ww) {
        double dx = bx - ax,
                dz = bz - az,
                t = clamp(((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz));
        return StrictMath.hypot(x - ax - t * dx, z - az - t * dz) / (w + (ww - w) * t);
    }

    private static boolean unit(double d) {
        return Double.isFinite(d) && d >= 0 && d <= 1;
    }

    private static double clamp(double t) {
        return StrictMath.max(0, StrictMath.min(1, t));
    }

    private static double smooth(double t) {
        t = clamp(t);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static long hash(long seed, long x, long z) {
        return mix(seed ^ mix(x * 0x9E3779B97F4A7C15L) ^ mix(z * 0xD1B54A32D192ED03L));
    }

    private static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    private static double unitHash(long v) {
        return (mix(v) >>> 11) * 0x1.0p-53;
    }

    private static double noise(long seed, double x, double z) {
        long ix = (long) StrictMath.floor(x), iz = (long) StrictMath.floor(z);
        double u = smooth(x - ix), v = smooth(z - iz);
        double a = unitHash(hash(seed, ix, iz)) * 2 - 1,
                b = unitHash(hash(seed, ix + 1, iz)) * 2 - 1;
        double c = unitHash(hash(seed, ix, iz + 1)) * 2 - 1,
                d = unitHash(hash(seed, ix + 1, iz + 1)) * 2 - 1;
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }
}
