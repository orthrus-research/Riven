// SPDX-License-Identifier: MIT
// Riven integration, 2026-10-20: added explicit-anchor proposals and a configured
// support bound. Shape arithmetic and the original standalone grid remain unchanged.
// The host declares integer-column certificates; they do not prove the continuous plane.
package cc.orthrus.riven.contrib.cold;

import java.util.Properties;

/** Independently authored, finite cold-region height kernels. No Minecraft dependencies. */
public final class ColdRegions {
    private ColdRegions() {}

    public enum Family {
        CIRQUE(0x4349525155453130L),
        TROUGH(0x54524f5547483130L),
        DRUMLINS(0x4452554d4c493130L),
        MORAINES(0x4d4f5241494e3130L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public static final int WARM = 1,
            MARINE = 2,
            RESERVED = 4,
            RELIEF = 8,
            SLOPE = 16,
            HEADROOM = 32,
            OCCURRENCE = 64;
    private static final String[] REASONS = {
        "warm", "marine", "reservation", "relief", "slope", "headroom", "occurrence"
    };

    public static String reasonName(int index) {
        if (index < 0 || index >= REASONS.length)
            throw new IllegalArgumentException("reason index");
        return REASONS[index];
    }

    /** Values supplied from WorldGenerationContext.terrainHeights and TerrainScale.HORIZONTAL.
     * Widths below are already physical blocks; vertical values are never horizontally scaled. */
    public static final class Reference {
        public final double cirqueWidth,
                cirqueDepth,
                knollWidth,
                knollHeight,
                spurWidth,
                spurHeight,
                regionWidth,
                lowest,
                highest;

        public Reference(
                double cirqueWidth,
                double cirqueDepth,
                double knollWidth,
                double knollHeight,
                double spurWidth,
                double spurHeight,
                double regionWidth,
                double lowest,
                double highest) {
            for (double x :
                    new double[] {
                        cirqueWidth,
                        cirqueDepth,
                        knollWidth,
                        knollHeight,
                        spurWidth,
                        spurHeight,
                        regionWidth
                    }) positive(x);
            if (!Double.isFinite(lowest) || !Double.isFinite(highest) || lowest >= highest)
                throw new IllegalArgumentException("surface envelope");
            this.cirqueWidth = cirqueWidth;
            this.cirqueDepth = cirqueDepth;
            this.knollWidth = knollWidth;
            this.knollHeight = knollHeight;
            this.spurWidth = spurWidth;
            this.spurHeight = spurHeight;
            this.regionWidth = regionWidth;
            this.lowest = lowest;
            this.highest = highest;
        }
    }

    /** Immutable parameters; all multipliers have explicit finite ranges. */
    public static final class Tuning {
        public final double width,
                magnitude,
                bend,
                roughness,
                coldMinimum,
                occurrence,
                hangingFraction,
                skirt;
        public final int drumlinCount, moraineCount;

        public Tuning(Properties p) {
            width = number(p, "widthMultiplier", 1, .65, 1.4);
            magnitude = number(p, "magnitudeMultiplier", 1, .5, 1.4);
            bend = number(p, "bend", .18, 0, .30);
            roughness = number(p, "roughness", .12, 0, .22);
            coldMinimum = number(p, "coldMinimum", .55, 0, 1);
            occurrence = number(p, "occurrence", .68, 0, 1);
            hangingFraction = number(p, "hangingFraction", .40, .25, .65);
            skirt = number(p, "skirtFraction", .14, .08, .25);
            drumlinCount = (int) number(p, "drumlinCount", 30, 6, 36);
            moraineCount = (int) number(p, "moraineCount", 4, 2, 5);
            if (drumlinCount != number(p, "drumlinCount", 30, 6, 36)
                    || moraineCount != number(p, "moraineCount", 4, 2, 5))
                throw new IllegalArgumentException("integer counts required");
        }
    }

    /** Earlier immutable facts valid over the candidate's entire circular support, including skirt.
     * Clearance is a lower bound from anchor to protected shoreline/water reservations
     * over the caller's declared domain. Riven certifies integer support columns,
     * the anchor and a halo, not every point in the continuous plane.
     * Parent min/max are conservative bounds on the supplied pre-feature parent, not final terrain.
     * Relief is the earlier declared 256-block P95-P05 diagnostic; direction is supplied broad
     * downhill/structural intent, or a seed-derived regional direction, never inferred recursively. */
    public static final class Earlier {
        public final double cold, relief, slope, parentMin, parentMax, clearance, direction;
        public final boolean marine;

        public Earlier(
                double cold,
                double relief,
                double slope,
                double parentMin,
                double parentMax,
                double clearance,
                double direction,
                boolean marine) {
            if (!Double.isFinite(cold)
                    || cold < 0
                    || cold > 1
                    || !Double.isFinite(relief)
                    || relief < 0
                    || !Double.isFinite(slope)
                    || slope < 0
                    || !Double.isFinite(parentMin)
                    || !Double.isFinite(parentMax)
                    || parentMax < parentMin
                    || !Double.isFinite(clearance)
                    || clearance < 0
                    || !Double.isFinite(direction))
                throw new IllegalArgumentException("earlier facts");
            this.cold = cold;
            this.relief = relief;
            this.slope = slope;
            this.parentMin = parentMin;
            this.parentMax = parentMax;
            this.clearance = clearance;
            this.direction = direction;
            this.marine = marine;
        }
    }

    /** One bounded proposal per caller-owned site, independent of admission and call order. */
    public static final class Candidate {
        public final Family family;
        public final long key;
        public final double x, z, width, depth, length, radius, cellSize;
        private final Reference reference;
        private final Tuning tuning;

        private Candidate(Family f, long seed, long cellX, long cellZ, Reference r, Tuning t) {
            this(f, seed, cellX, cellZ, r, t, 0, 0, false);
        }

        private Candidate(
                Family f,
                long seed,
                long cellX,
                long cellZ,
                Reference r,
                Tuning t,
                double anchorX,
                double anchorZ,
                boolean suppliedAnchor) {
            if (Math.abs((double) cellX) > 100000000 || Math.abs((double) cellZ) > 100000000)
                throw new IllegalArgumentException("site coordinate range");
            if (suppliedAnchor && (!Double.isFinite(anchorX) || !Double.isFinite(anchorZ)))
                throw new IllegalArgumentException("finite anchor");
            family = f;
            reference = r;
            tuning = t;
            key = mix(seed ^ f.salt ^ mix(cellX) ^ Long.rotateLeft(mix(cellZ), 29));
            width =
                    (f == Family.DRUMLINS
                                    ? r.knollWidth
                                    : f == Family.MORAINES ? r.spurWidth : r.cirqueWidth)
                            * t.width
                            * (.82 + .36 * unit(key, 1));
            depth =
                    (f == Family.DRUMLINS
                                    ? r.knollHeight
                                    : f == Family.MORAINES ? r.spurHeight : r.cirqueDepth)
                            * t.magnitude
                            * (.80 + .35 * unit(key, 2));
            length =
                    width
                            * (f == Family.TROUGH
                                    ? 3.4 + .7 * unit(key, 3)
                                    : f == Family.CIRQUE ? 1.6 : 5.6);
            // Bounding circles are deliberately conservative. Every exact primitive lies inside.
            radius =
                    f == Family.CIRQUE
                            ? width * 1.18
                            : f == Family.TROUGH
                                    ? length * .62 + width
                                    : f == Family.DRUMLINS ? width * 8.0 : width * 4.6;
            double maxRadius = maximumRadius(f, r, t);
            cellSize = Math.max(r.regionWidth * 2, maxRadius * 3.0);
            x = suppliedAnchor ? anchorX : (cellX + .5 + (unit(key, 4) - .5) * .30) * cellSize;
            z = suppliedAnchor ? anchorZ : (cellZ + .5 + (unit(key, 5) - .5) * .30) * cellSize;
        }
    }

    public static Candidate propose(
            Family f, long seed, long cellX, long cellZ, Reference r, Tuning t) {
        return new Candidate(f, seed, cellX, cellZ, r, t);
    }

    /** The host supplies its shared owner's absolute anchor; this does not query the isolated grid. */
    public static Candidate proposeAt(
            Family f,
            long seed,
            long cellX,
            long cellZ,
            double x,
            double z,
            Reference r,
            Tuning t) {
        return new Candidate(f, seed, cellX, cellZ, r, t, x, z, true);
    }

    /** Maximum support for every seeded proposal with this exact reference and tuning. */
    public static double maximumRadius(Family f, Reference r, Tuning t) {
        double maxWidth =
                (f == Family.DRUMLINS
                                ? r.knollWidth
                                : f == Family.MORAINES ? r.spurWidth : r.cirqueWidth)
                        * t.width
                        * 1.18;
        return maxWidth
                * (f == Family.CIRQUE
                        ? 1.18
                        : f == Family.TROUGH ? 4.1 * .62 + 1 : f == Family.DRUMLINS ? 8.0 : 4.6);
    }

    /** Several bits may be present; first reason is a reporting convenience, not a hidden fallback. */
    public static int reject(Candidate c, Earlier e, Reference r, Tuning t) {
        if (c.reference != r || c.tuning != t)
            throw new IllegalArgumentException(
                    "Reuse the immutable Reference and Tuning that created this candidate");
        int bits = 0;
        if (e.cold < t.coldMinimum) bits |= WARM;
        if (e.marine) bits |= MARINE;
        if (e.clearance < c.radius) bits |= RESERVED;
        boolean cut = c.family == Family.CIRQUE || c.family == Family.TROUGH;
        if (cut && e.relief < c.depth * .70) bits |= RELIEF;
        if (!cut && e.relief > c.depth * 3.5) bits |= RELIEF;
        if (cut ? e.slope < .012 || e.slope > .65 : e.slope > .22) bits |= SLOPE;
        if (e.parentMin < r.lowest
                || e.parentMax > r.highest
                || (cut ? e.parentMin - c.depth < r.lowest : e.parentMax + c.depth > r.highest))
            bits |= HEADROOM;
        if (unit(c.key, 6) >= t.occurrence) bits |= OCCURRENCE;
        return bits;
    }

    /** Empty sites remain empty. No retry, neighboring-winner lookup, or force-admission. */
    public static Plan select(Candidate c, Earlier e, Reference r, Tuning t) {
        return reject(c, e, r, t) == 0 ? new Plan(c, e, t) : null;
    }

    /** Immutable plan; primitive arrays are private and never escape. Share freely among workers. */
    public static final class Plan {
        public final Candidate candidate;
        public final double angle, cos, sin;
        private final Tuning tuning;
        private final double phase, curve, branchSide;
        private final double[] bx, bz, ba, bl, bw, bh, offset;

        private Plan(Candidate c, Earlier e, Tuning t) {
            candidate = c;
            tuning = t;
            angle = e.direction + (unit(c.key, 7) - .5) * .36;
            cos = StrictMath.cos(angle);
            sin = StrictMath.sin(angle);
            phase = unit(c.key, 8) * 2 * StrictMath.PI;
            curve = (unit(c.key, 9) * 2 - 1) * t.bend;
            branchSide = unit(c.key, 10) < .5 ? -1 : 1;
            int n = c.family == Family.DRUMLINS ? t.drumlinCount : 0;
            bx = new double[n];
            bz = new double[n];
            ba = new double[n];
            bl = new double[n];
            bw = new double[n];
            bh = new double[n];
            int permutation = (int) (unit(c.key, 99) * 4);
            int multiplier =
                    permutation == 0 ? 5 : permutation == 1 ? 7 : permutation == 2 ? 11 : 13;
            int startSlot = (int) (unit(c.key, 98) * 36);
            for (int i = 0; i < n; i++) {
                // 6x6 jittered sites; absolute anchor key, no world-sized process or relaxation.
                int slot = (i * multiplier + startSlot) % 36;
                double uu =
                        ((slot % 6) - 2.5 + (unit(c.key, 100 + i * 7) - .5) * 1.25)
                                * c.width
                                * 1.30;
                double vv =
                        ((slot / 6) - 2.5 + (unit(c.key, 101 + i * 7) - .5) * 1.25)
                                * c.width
                                * 1.15;
                bx[i] = uu + c.width * .35 * StrictMath.sin(vv / (c.width * 1.8) + phase);
                bz[i] = vv + c.width * .30 * StrictMath.sin(uu / (c.width * 2.3) + phase);
                ba[i] = (unit(c.key, 102 + i * 7) - .5) * .34;
                bl[i] = c.width * (1.0 + .55 * unit(c.key, 103 + i * 7));
                bw[i] = c.width * (.32 + .18 * unit(c.key, 104 + i * 7));
                bh[i] = c.depth * (.55 + .45 * unit(c.key, 105 + i * 7));
            }
            offset = new double[c.family == Family.MORAINES ? t.moraineCount : 0];
            double cursor = -c.width * 1.0;
            for (int i = 0; i < offset.length; i++) {
                offset[i] = cursor;
                cursor += c.width * (.48 + .42 * unit(c.key, 500 + i));
            }
        }

        /** Signed block-height displacement from supplied parent. No allocation or writes. */
        public double delta(double x, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(z))
                throw new IllegalArgumentException("finite coordinates");
            double dx = x - candidate.x, dz = z - candidate.z;
            if (dx * dx + dz * dz >= candidate.radius * candidate.radius) return 0;
            double u = dx * cos + dz * sin, v = -dx * sin + dz * cos, value;
            switch (candidate.family) {
                case CIRQUE:
                    value = cirque(u, v);
                    break;
                case TROUGH:
                    value = trough(u, v);
                    break;
                case DRUMLINS:
                    value = drumlins(u, v);
                    break;
                default:
                    value = moraines(u, v);
                    break;
            }
            // Common C1 support skirt, not smoothing of previously generated terrain.
            double skirt =
                    1
                            - smooth(
                                    (StrictMath.hypot(u, v) / candidate.radius - (1 - tuning.skirt))
                                            / tuning.skirt);
            return value * skirt;
        }

        private double cirque(double u, double v) {
            double w = candidate.width, d = candidate.depth;
            double wobble =
                    1 + tuning.roughness * .5 * StrictMath.sin(3 * StrictMath.atan2(v, u) + phase);
            double q = StrictMath.hypot(u / (w * .53), v / (w * .5)) / wobble;
            // Broad hollow, asymmetric headwall, open downstream lip. All shapes compact.
            double bowl =
                    (1 - smooth((q - .40) / .60)) * (.94 + .06 * StrictMath.sin(v / w * 6 + phase));
            double center = w * .09 * StrictMath.sin(u / w * 2.6 + phase);
            double neck = .12 - .045 * smooth(u / w);
            double side = 1 - smooth((Math.abs(v - center) / w - neck) / .12);
            double exit =
                    smooth((u / w + .1) / .30) * (1 - smooth((u / w - .60) / .45)) * side * .67;
            return -d * Math.max(bowl, exit);
        }

        public double troughCenter(double u) {
            return curve
                    * candidate.width
                    * StrictMath.sin(u / candidate.length * StrictMath.PI * 2 + phase);
        }

        /** Diagnostic cross-section along the center of the hanging tributary. */
        public double hangingU(double v) {
            double along = branchSide * (v - troughCenter(-candidate.length * .10));
            return -candidate.length * .10 + curve * along * .7;
        }

        private double trough(double u, double v) {
            double w = candidate.width, d = candidate.depth, l = candidate.length;
            double end = 1 - smooth((Math.abs(u) / l - .36) / .14);
            double center = troughCenter(u);
            double variableWidth = w * (.46 + .055 * StrictMath.sin(u / l * 9 + phase));
            double cross = Math.abs(v - center) / variableWidth;
            double main = (1 - smooth((cross - .30) / .70)) * end;
            main *= .92 + .08 * StrictMath.cos(u / l * 8 + phase);
            // Hanging tributary terminates against the main wall. min-union retains its higher
            // floor until the main trough takes over; never sums two full cuts at a junction.
            double along = branchSide * (v - troughCenter(-l * .10));
            double branchU = -l * .10 + curve * along * .7;
            double branchCross = Math.abs(u - branchU) / (w * .24);
            double branchEnd =
                    smooth((along / w + .08) / .20) * (1 - smooth((along / w - 1.0) / .55));
            double tributary =
                    (1 - smooth((branchCross - .28) / .72)) * branchEnd * tuning.hangingFraction;
            return -d * Math.max(main, tributary);
        }

        private double drumlins(double u, double v) {
            double result = 0;
            for (int i = 0; i < bx.length; i++) {
                double du = u - bx[i], dv = v - bz[i];
                // Cheap small-angle shear gives coherent but unequal directions without trig per
                // hill.
                double a = du + ba[i] * dv, b = dv - ba[i] * du;
                double longitudinal = a / (bl[i] * (a < 0 ? .62 : 1.30));
                double lateral = b / (bw[i] * (1 - .20 * clamp(a / bl[i], -1, 1)));
                double q = longitudinal * longitudinal + lateral * lateral;
                if (q < 1) result = Math.max(result, bh[i] * sq(1 - q));
            }
            return result;
        }

        private double moraines(double u, double v) {
            double w = candidate.width, d = candidate.depth;
            double reach = 1 - smooth((Math.abs(v) / w - 2.10) / .70);
            if (reach == 0) return 0;
            double result = 0;
            for (int i = 0; i < offset.length; i++) {
                double arc = offset[i] - .20 * v * v / w;
                double wiggle =
                        tuning.roughness
                                * w
                                * StrictMath.sin(v / w * (2.1 + i * .37) + phase + i * 1.7);
                double normalDistance = (u - arc - wiggle) / StrictMath.sqrt(1 + sq(.4 * v / w));
                double halfWidth = w * (.28 + .03 * StrictMath.sin(v / w * 4 + phase + i));
                double q = Math.abs(normalDistance) / halfWidth;
                double height =
                        d
                                * (.60 + .25 * unit(candidate.key, 600 + i))
                                * (1
                                        - tuning.roughness
                                        + tuning.roughness * StrictMath.sin(v / w * 7 + phase + i));
                if (q < 1) result = Math.max(result, height * sq(1 - q) * reach);
            }
            // Short lateral ridges join the terminal complex; feathered, never uniform shelves.
            double end = 1 - smooth((Math.abs(u / w + .8) - 1.0) / .65);
            double sideDistance = Math.abs(Math.abs(v) - w * (2.1 + .09 * u / w)) / (w * .16);
            if (sideDistance < 1) result = Math.max(result, d * .46 * sq(1 - sideDistance) * end);
            return result;
        }

        public int primitiveBound() {
            return candidate.family == Family.DRUMLINS
                    ? bx.length
                    : candidate.family == Family.MORAINES
                            ? offset.length + 2
                            : candidate.family == Family.TROUGH ? 2 : 2;
        }

        /** Per-column defense in depth. parent is pre-feature and immutable; marine/water/shore
         * are explicitly owned upstream. Exclusion fade is caller-supplied C1 [0,1] evidence.
         * This guard is not a replacement for full-support admission. */
        public double height(
                double x, double z, double parent, boolean protectedColumn, double exclusionFade) {
            if (!Double.isFinite(parent)
                    || !Double.isFinite(exclusionFade)
                    || exclusionFade < 0
                    || exclusionFade > 1) throw new IllegalArgumentException("column facts");
            return protectedColumn ? parent : parent + delta(x, z) * exclusionFade;
        }
    }

    /** Stateless SplitMix64 finalizer; Vigna's reference is public domain. See NOTICE. */
    public static long mix(long v) {
        v ^= v >>> 30;
        v *= 0xbf58476d1ce4e5b9L;
        v ^= v >>> 27;
        v *= 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }

    public static double unit(long seed, long slot) {
        return (mix(seed + slot * 0x9e3779b97f4a7c15L) >>> 11) * 0x1.0p-53;
    }

    static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    static double sq(double x) {
        return x * x;
    }

    static void positive(double x) {
        if (!Double.isFinite(x) || x <= 0)
            throw new IllegalArgumentException("positive finite reference");
    }

    static double number(Properties p, String k, double d, double lo, double hi) {
        double x = Double.parseDouble(p.getProperty(k, Double.toString(d)));
        if (!Double.isFinite(x) || x < lo || x > hi) throw new IllegalArgumentException(k);
        return x;
    }
}
