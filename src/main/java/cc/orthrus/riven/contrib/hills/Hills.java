/* SPDX-License-Identifier: MIT
 * Riven integration: added shared-owner preparation at an explicit anchor.
 * Original synthetic-grid preparation and authored geometry remain available.
 * Packaged license: META-INF/licenses/hills/MIT.txt.
 */
package cc.orthrus.riven.contrib.hills;

/** Independently authored, loader-neutral height kernels. No world, block or scheduler access. */
public final class Hills {
    private Hills() {}

    public enum Family {
        SKEW_HILLS(0x485536534B455731L, "rolling_hills", 1.0, 1.8),
        BRANCH_SPURS(0x4855365350555231L, "foothills", 0.75, 1.7),
        DISSECTED_UPLAND(0x4855364449535331L, "uplands", 1.15, 1.45),
        WARPED_SHOULDERS(0x48553653484C4431L, "foothills", 0.85, 1.5);
        public final long salt;
        public final String referenceId;
        final double gain, aspect;

        Family(long salt, String ref, double gain, double aspect) {
            this.salt = salt;
            referenceId = ref;
            this.gain = gain;
            this.aspect = aspect;
        }
    }

    public enum Reason {
        ACCEPTED,
        INVALID_INPUT,
        MARINE,
        PROTECTED_SHORE,
        RESERVED_WATER,
        WRONG_INTENT,
        NO_MOUNTAIN_PARENT,
        ELEVATION,
        SUPPORT_CLEARANCE,
        HEADROOM,
        OCCURRENCE
    }

    /** Snapshot from context.terrainHeights.feature(...), plus TerrainScale.HORIZONTAL. */
    public static final class Reference {
        public final double sea, low, high, mean, relief, widthMin, widthMean, widthMax, scale;
        public final double allocationLow, allocationHigh;
        public final String fingerprint;

        public Reference(
                double sea,
                double minOffset,
                double meanOffset,
                double maxOffset,
                double relief,
                double widthMin,
                double widthMean,
                double widthMax,
                double scale,
                double allocationLow,
                double allocationHigh,
                String fingerprint) {
            if (!finite(
                            sea,
                            minOffset,
                            meanOffset,
                            maxOffset,
                            relief,
                            widthMin,
                            widthMean,
                            widthMax,
                            scale,
                            allocationLow,
                            allocationHigh)
                    || !(minOffset < meanOffset && meanOffset < maxOffset)
                    || !(relief > 0
                            && widthMin > 0
                            && widthMin <= widthMean
                            && widthMean <= widthMax
                            && scale > 0)
                    || allocationLow > sea + minOffset
                    || allocationHigh < sea + maxOffset
                    || fingerprint == null)
                throw new IllegalArgumentException("Invalid reference snapshot");
            this.sea = sea;
            low = sea + minOffset;
            mean = sea + meanOffset;
            high = sea + maxOffset;
            this.relief = relief;
            this.widthMin = widthMin;
            this.widthMean = widthMean;
            this.widthMax = widthMax;
            this.scale = scale;
            this.allocationLow = allocationLow;
            this.allocationHigh = allocationHigh;
            this.fingerprint = fingerprint;
        }
    }

    /** All dimensions are relative to the reference, except the documented dimensionless controls. */
    public static final class Tuning {
        public final double widthFactor,
                gain,
                aspect,
                occurrence,
                directionJitter,
                roughness,
                skirt;

        public Tuning(
                double widthFactor,
                double gain,
                double aspect,
                double occurrence,
                double directionJitter,
                double roughness,
                double skirt) {
            if (!finite(widthFactor, gain, aspect, occurrence, directionJitter, roughness, skirt)
                    || widthFactor < 0.5
                    || widthFactor > 2
                    || gain <= 0
                    || gain > 2
                    || aspect < 1
                    || aspect > 2.5
                    || occurrence < 0
                    || occurrence > 1
                    || directionJitter < 0
                    || directionJitter > StrictMath.PI
                    || roughness < 0
                    || roughness > 0.2
                    || skirt < 0.1
                    || skirt > 0.5)
                throw new IllegalArgumentException("Tuning out of bounded range");
            this.widthFactor = widthFactor;
            this.gain = gain;
            this.aspect = aspect;
            this.occurrence = occurrence;
            this.directionJitter = directionJitter;
            this.roughness = roughness;
            this.skirt = skirt;
        }

        public static Tuning defaults(Family f) {
            return new Tuning(1, f.gain, f.aspect, 0.85, 0.65, 0.07, 0.28);
        }
    }

    /** Earlier immutable anchor facts. Parent min/max MUST bound the entire requested disk
     * in the caller's declared certification domain (continuous standalone fixtures,
     * integer columns plus the anchor in Riven's shared-owner integration).
     * shoreDistance bounds distance to the union of marine ownership and protected shore.
     * waterDistance bounds distance to all reserved water geometry, including channel banks,
     * in that same domain. A lattice certificate is not a continuous-plane proof.
     * Intent weights and direction come from BROAD terrain, never this sampler's result. */
    public static final class Facts {
        public final boolean marine, protectedShore, reservedWater;
        public final double parentMin, parentMax, anchorY, hillFit, uplandFit, mountainAdjacency;
        public final double direction, shoreDistance, waterDistance;

        public Facts(
                boolean marine,
                boolean protectedShore,
                boolean reservedWater,
                double parentMin,
                double parentMax,
                double anchorY,
                double hillFit,
                double uplandFit,
                double mountainAdjacency,
                double direction,
                double shoreDistance,
                double waterDistance) {
            this.marine = marine;
            this.protectedShore = protectedShore;
            this.reservedWater = reservedWater;
            this.parentMin = parentMin;
            this.parentMax = parentMax;
            this.anchorY = anchorY;
            this.hillFit = hillFit;
            this.uplandFit = uplandFit;
            this.mountainAdjacency = mountainAdjacency;
            this.direction = direction;
            this.shoreDistance = shoreDistance;
            this.waterDistance = waterDistance;
        }

        boolean valid() {
            return finite(
                            parentMin,
                            parentMax,
                            anchorY,
                            hillFit,
                            uplandFit,
                            mountainAdjacency,
                            direction,
                            shoreDistance,
                            waterDistance)
                    && parentMin <= anchorY
                    && anchorY <= parentMax
                    && hillFit >= 0
                    && hillFit <= 1
                    && uplandFit >= 0
                    && uplandFit <= 1
                    && mountainAdjacency >= 0
                    && mountainAdjacency <= 1
                    && shoreDistance >= 0
                    && waterDistance >= 0;
        }
    }

    public interface Earlier {
        Facts facts(double anchorX, double anchorZ, double supportRadius);
    }

    public static final class Selection {
        public final Reason reason;
        public final Site site;

        private Selection(Reason r, Site site) {
            reason = r;
            this.site = site;
        }
    }

    /** Fixed support bounds include all warp, internal geometry and blending. */
    public static double maxRadius(Reference r, Tuning p) {
        return 0.5
                * clamp(r.widthMean * p.widthFactor * 1.2, r.widthMin, r.widthMax)
                * r.scale
                * p.aspect;
    }

    public static double spacing(Reference r, Tuning p) {
        return maxRadius(r, p) * 1.35;
    }

    /** One candidate per absolute cell; an ineligible candidate remains empty. No retry/neighbor recursion. */
    public static Selection prepare(
            long seed,
            long cellX,
            long cellZ,
            Family family,
            Reference r,
            Tuning p,
            Earlier earlier) {
        if (StrictMath.abs((double) cellX) > 100000000
                || StrictMath.abs((double) cellZ) > 100000000)
            return rejected(Reason.INVALID_INPUT);
        long key = key(seed ^ family.salt, cellX, cellZ);
        double cell = spacing(r, p), x = (cellX + 0.5 + 0.80 * (unit(key, 1) - 0.5)) * cell;
        double z = (cellZ + 0.5 + 0.80 * (unit(key, 2) - 0.5)) * cell;
        return prepareAt(key, x, z, family, r, p, earlier);
    }

    /** Riven integration entry: one family chosen by the shared Sites owner.
     * Admission and seed-derived geometry match the standalone path; this does
     * not create a second grid or move a rejected shared owner to a new site.
     */
    public static Selection prepareSelected(
            long seed,
            long ownerX,
            long ownerZ,
            double x,
            double z,
            Family family,
            Reference r,
            Tuning p,
            Earlier earlier) {
        if (family == null
                || r == null
                || p == null
                || earlier == null
                || !finite(x, z)
                || StrictMath.abs(x) > 30000000
                || StrictMath.abs(z) > 30000000
                || StrictMath.abs((double) ownerX) > 100000000
                || StrictMath.abs((double) ownerZ) > 100000000)
            return rejected(Reason.INVALID_INPUT);
        return prepareAt(key(seed ^ family.salt, ownerX, ownerZ), x, z, family, r, p, earlier);
    }

    private static Selection prepareAt(
            long key, double x, double z, Family family, Reference r, Tuning p, Earlier earlier) {
        double width =
                clamp(
                                r.widthMean * p.widthFactor * (0.8 + 0.4 * unit(key, 3)),
                                r.widthMin,
                                r.widthMax)
                        * r.scale;
        double b = width * 0.5,
                a = b * p.aspect,
                amplitude = r.relief * p.gain * (0.8 + 0.2 * unit(key, 4));
        Facts facts = earlier.facts(x, z, a);
        if (facts == null || !facts.valid()) return rejected(Reason.INVALID_INPUT);
        if (facts.marine) return rejected(Reason.MARINE);
        if (facts.protectedShore) return rejected(Reason.PROTECTED_SHORE);
        if (facts.reservedWater) return rejected(Reason.RESERVED_WATER);
        double fit = family == Family.DISSECTED_UPLAND ? facts.uplandFit : facts.hillFit;
        if (fit < 0.3) return rejected(Reason.WRONG_INTENT);
        if ((family == Family.BRANCH_SPURS || family == Family.WARPED_SHOULDERS)
                && facts.mountainAdjacency < 0.3) return rejected(Reason.NO_MOUNTAIN_PARENT);
        if (facts.anchorY < r.low || facts.anchorY > r.high) return rejected(Reason.ELEVATION);
        if (StrictMath.min(facts.shoreDistance, facts.waterDistance) < a)
            return rejected(Reason.SUPPORT_CLEARANCE);
        double cut = amplitude * negativeBound(family), raise = amplitude;
        if (facts.parentMin - cut < StrictMath.max(r.low, r.allocationLow)
                || facts.parentMax + raise > StrictMath.min(r.high, r.allocationHigh))
            return rejected(Reason.HEADROOM);
        // Occurrence is intentionally last so rejection histograms preserve physical reasons.
        if (unit(key, 5) >= p.occurrence * smooth((fit - 0.3) / 0.55))
            return rejected(Reason.OCCURRENCE);
        double angle = facts.direction + (2 * unit(key, 6) - 1) * p.directionJitter;
        return new Selection(
                Reason.ACCEPTED, new Site(family, key, x, z, a, b, angle, amplitude, p));
    }

    private static Selection rejected(Reason reason) {
        return new Selection(reason, null);
    }

    public static double negativeBound(Family f) {
        return f == Family.DISSECTED_UPLAND ? 0.5 : 0;
    }

    /** Immutable site; private geometry arrays never escape. Concurrent sample calls allocate nothing. */
    public static final class Site {
        public final Family family;
        public final long key;
        public final double x, z, majorRadius, minorRadius, amplitude;
        private final double c, s, skirt, roughness;
        private final double[]
                segments; // ax,az,bx,bz,width,weight in normalized ellipse coordinates

        private Site(
                Family family,
                long key,
                double x,
                double z,
                double a,
                double b,
                double angle,
                double amplitude,
                Tuning p) {
            this.family = family;
            this.key = key;
            this.x = x;
            this.z = z;
            majorRadius = a;
            minorRadius = b;
            this.amplitude = amplitude;
            c = StrictMath.cos(angle);
            s = StrictMath.sin(angle);
            skirt = p.skirt;
            roughness = p.roughness;
            segments =
                    family == Family.BRANCH_SPURS
                            ? branches(key)
                            : family == Family.DISSECTED_UPLAND ? gullies(key) : new double[0];
        }

        public double worldX(double u, double v) {
            return x + c * u * majorRadius - s * v * minorRadius;
        }

        public double worldZ(double u, double v) {
            return z + s * u * majorRadius + c * v * minorRadius;
        }

        /** C1 compact skirt. This weight is also used for bounded overlapping composition. */
        public double weight(double worldX, double worldZ) {
            double dx = worldX - x, dz = worldZ - z;
            double u = (c * dx + s * dz) / majorRadius, v = (-s * dx + c * dz) / minorRadius;
            double q = u * u + v * v;
            return q >= 1 ? 0 : smooth((1 - StrictMath.sqrt(q)) / skirt);
        }

        /** Bounded local offset in highest-solid-Y units; does not place water or materials. */
        public double delta(double worldX, double worldZ) {
            double dx = worldX - x, dz = worldZ - z;
            double u = (c * dx + s * dz) / majorRadius, v = (-s * dx + c * dz) / minorRadius;
            double q = u * u + v * v;
            if (q >= 1) return 0;
            double skirtWeight = smooth((1 - StrictMath.sqrt(q)) / skirt), h;
            switch (family) {
                case SKEW_HILLS:
                    {
                        // Continuous skew: wider shoulder on one side, no half-plane derivative
                        // seam.
                        double asym = 0.28 + 0.18 * unit(key, 11);
                        double vv = v * (1 + asym * v),
                                uu = u + 0.20 * v * v + 0.10 * noise(key, u * 2, v * 2);
                        double core =
                                cap(
                                        uu / (0.77 + 0.12 * unit(key, 12)),
                                        vv / (0.75 + 0.17 * unit(key, 13)));
                        double shoulder =
                                cap(
                                        (u + 0.20 + 0.25 * unit(key, 14)) / 0.63,
                                        (v - 0.10 - 0.22 * unit(key, 15)) / 0.60);
                        h = 0.70 * core + 0.30 * shoulder;
                        break;
                    }
                case BRANCH_SPURS:
                    {
                        double product = 1;
                        for (int i = 0; i < segments.length; i += 6) {
                            double d = segmentDistance(u, v, segments, i);
                            double ridge = cap(d / segments[i + 4], 0) * segments[i + 5];
                            product *= 1 - ridge;
                        }
                        h = 1 - product;
                        break;
                    }
                case DISSECTED_UPLAND:
                    {
                        double incision = 0;
                        for (int i = 0; i < segments.length; i += 6) {
                            double cut =
                                    cap(segmentDistance(u, v, segments, i) / segments[i + 4], 0)
                                            * segments[i + 5];
                            incision = StrictMath.max(incision, cut);
                        }
                        // Rounded interfluves and dry, branching valleys; no drainage/erosion
                        // solver.
                        h =
                                0.58
                                        + 0.09 * noise(key ^ 23, u * 2.4, v * 2.4)
                                        + 0.07 * noise(key ^ 24, u * 4.3, v * 4.3)
                                        - 0.90 * incision;
                        h *=
                                smooth(
                                        (1
                                                        - StrictMath.sqrt(q)
                                                        - 0.06
                                                                * (1
                                                                        + noise(
                                                                                key ^ 25, u * 3,
                                                                                v * 3)))
                                                / 0.24);
                        break;
                    }
                default:
                    {
                        // Warped, nonperiodic ridged value fields; long shoulders share a regional
                        // axis.
                        double wu = u + 0.23 * noise(key ^ 31, u * 2.1, v * 2.1);
                        double wv = v + 0.23 * noise(key ^ 32, u * 2.1, v * 2.1);
                        double n =
                                0.66 * noise(key ^ 33, wu * 2.7, wv * 4.8)
                                        + 0.24 * noise(key ^ 34, wu * 5.3, wv * 7.1)
                                        + 0.10 * noise(key ^ 35, wu * 9.7, wv * 12.3);
                        h =
                                0.08
                                        + 0.76
                                                / (1 + 8 * n * n)
                                                * (0.55
                                                        + 0.45
                                                                * noise01(
                                                                        key ^ 36, wu * 1.8,
                                                                        wv * 2.7));
                    }
            }
            // Roughening multiplies the family shape and cannot exceed the declared amplitude.
            double detail = 1 - roughness * (0.5 + 0.5 * noise(key ^ 41, u * 13.7, v * 11.3));
            return amplitude * skirtWeight * h * detail;
        }

        public int segmentCount() {
            return segments.length / 6;
        }
    }

    private static double[] branches(long key) {
        double[] s = new double[12 * 6];
        double[] tx = {-0.81, -0.30, 0.25, 0.76}, tz = new double[4];
        for (int i = 0; i < 4; i++) tz[i] = (unit(key, 140 + i) - 0.5) * 0.34;
        for (int i = 0; i < 3; i++)
            put(s, i, tx[i], tz[i], tx[i + 1], tz[i + 1], 0.22 - 0.025 * i, 0.95);
        for (int i = 0; i < 4; i++) {
            double t = 0.16 + 0.21 * i, ax = -0.81 + 1.57 * t;
            int trunk = ax < tx[1] ? 0 : ax < tx[2] ? 1 : 2;
            double local = (ax - tx[trunk]) / (tx[trunk + 1] - tx[trunk]);
            double az = tz[trunk] + local * (tz[trunk + 1] - tz[trunk]);
            double side = (i % 2 == 0 ? 1 : -1), bx = ax + 0.12 + 0.12 * unit(key, 50 + i);
            double bz = side * (0.48 + 0.16 * unit(key, 60 + i));
            put(s, 3 + i, ax, az, bx, bz, 0.12 + 0.025 * unit(key, 70 + i), 0.70);
            put(
                    s,
                    7 + i,
                    ax * 0.35 + bx * 0.65,
                    az * 0.35 + bz * 0.65,
                    bx - 0.18,
                    bz + side * 0.17,
                    0.085,
                    0.44);
        }
        put(s, 11, 0.19, -0.06, 0.41, 0.41, 0.13, 0.63);
        return s;
    }

    private static double[] gullies(long key) {
        double[] s = new double[20 * 6], tx = {-1.02, -0.53, -0.08, 0.32, 0.69}, tz = new double[5];
        for (int i = 0; i < 5; i++) tz[i] = (unit(key, 80 + i) - 0.5) * 0.40;
        for (int i = 0; i < 4; i++)
            put(s, i, tx[i], tz[i], tx[i + 1], tz[i + 1], 0.14 - 0.015 * i, 0.96);
        for (int j = 0; j < 4; j++) {
            double t = 0.22 + 0.21 * j + (unit(key, 90 + j) - 0.5) * 0.1;
            double ax = -0.98 + 1.60 * t;
            int i = ax < tx[1] ? 0 : ax < tx[2] ? 1 : ax < tx[3] ? 2 : 3;
            double local = (ax - tx[i]) / (tx[i + 1] - tx[i]),
                    az = tz[i] + local * (tz[i + 1] - tz[i]);
            double side = j % 2 == 0 ? 1 : -1, bx = ax + 0.05 + 0.15 * unit(key, 100 + j);
            double bz = side * (0.72 + 0.19 * unit(key, 110 + j));
            double mx = (ax + bx) * 0.5 - 0.09, my = (az + bz) * 0.5;
            put(s, 4 + j * 2, ax, az, mx, my, 0.115, 0.88);
            put(s, 5 + j * 2, mx, my, bx, bz, 0.085, 0.79);
            put(s, 12 + j * 2, mx, my, mx - 0.22, my + side * 0.22, 0.075, 0.75);
            put(
                    s,
                    13 + j * 2,
                    bx * 0.6 + mx * 0.4,
                    bz * 0.6 + my * 0.4,
                    bx + 0.17,
                    bz - side * 0.03,
                    0.065,
                    0.68);
        }
        return s;
    }

    private static void put(
            double[] s, int i, double ax, double az, double bx, double bz, double w, double h) {
        int o = i * 6;
        s[o] = ax;
        s[o + 1] = az;
        s[o + 2] = bx;
        s[o + 3] = bz;
        s[o + 4] = w;
        s[o + 5] = h;
    }

    private static double segmentDistance(double x, double z, double[] s, int i) {
        double dx = s[i + 2] - s[i], dz = s[i + 3] - s[i + 1];
        double t = clamp(((x - s[i]) * dx + (z - s[i + 1]) * dz) / (dx * dx + dz * dz), 0, 1);
        return StrictMath.hypot(x - s[i] - t * dx, z - s[i + 1] - t * dz);
    }

    private static double cap(double u, double v) {
        double q = u * u + v * v;
        return q >= 1 ? 0 : (1 - q) * (1 - q) * (1 - q);
    }

    public static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double t, double lo, double hi) {
        return StrictMath.max(lo, StrictMath.min(hi, t));
    }

    private static boolean finite(double... xs) {
        for (double x : xs) if (!Double.isFinite(x)) return false;
        return true;
    }

    // Locally authored coordinate hashing; no permutation table or third-party noise
    // implementation.
    public static long mix(long x) {
        x ^= x >>> 29;
        x *= 0x319642B2D24D8EC3L;
        x ^= x >>> 32;
        x *= 0x96DE1B173F119089L;
        return x ^ (x >>> 28);
    }

    public static long key(long seed, long x, long z) {
        return mix(
                seed
                        ^ mix(x + 0x632BE59BD9B4E019L)
                        ^ Long.rotateLeft(mix(z + 0x9E3779B97F4A7C15L), 27));
    }

    public static double unit(long key, long lane) {
        return (mix(key + lane * 0xD1342543DE82EF95L) >>> 11) * 0x1.0p-53;
    }

    public static double noise01(long seed, double x, double z) {
        return (noise(seed, x, z) + 1) * 0.5;
    }

    public static double noise(long seed, double x, double z) {
        long ix = (long) StrictMath.floor(x), iz = (long) StrictMath.floor(z);
        double fx = smooth(x - ix), fz = smooth(z - iz);
        double a = unit(key(seed, ix, iz), 0), b = unit(key(seed, ix + 1, iz), 0);
        double c = unit(key(seed, ix, iz + 1), 0), d = unit(key(seed, ix + 1, iz + 1), 0);
        return 2 * ((a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz) - 1;
    }
}
