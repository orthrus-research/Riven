package cc.orthrus.riven.contrib.lowland;

/** Independently authored, dependency-free heightmap kernels. SPDX-License-Identifier: MIT */
@SuppressWarnings("strictfp") // Retain strict evaluation for the Java 8 fallback.
public final strictfp class Lowlands {
    private Lowlands() {}

    public enum Family {
        WARPED_PLAIN(0x4c4f573557415250L),
        COMPACT_PATCHES(0x4c4f573550415443L),
        CELLULAR_HUMMOCKS(0x4c4f573543454c4cL),
        LOW_TERRACES(0x4c4f573554455252L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPTED,
        INVALID_INPUT,
        MARINE,
        NOT_LOWLAND,
        STEEP_PARENT,
        SHORE_RESERVED,
        WATER_RESERVED,
        HEADROOM,
        NOT_SELECTED
    }

    /** Construct from WorldGenerationContext.terrainHeights and TerrainScale.HORIZONTAL. */
    public static final class Reference {
        public final double seaY, minY, maxY, featherY, reliefMax;
        public final double plainWidth, knollWidth, hollowWidth, riserWidth, riserHeight;

        public Reference(
                double seaY,
                double minOffset,
                double maxOffset,
                double featherY,
                double reliefMax,
                double plainWidth,
                double knollWidth,
                double hollowWidth,
                double riserWidth,
                double riserHeight,
                double horizontal) {
            double[] values = {
                seaY,
                minOffset,
                maxOffset,
                featherY,
                reliefMax,
                plainWidth,
                knollWidth,
                hollowWidth,
                riserWidth,
                riserHeight,
                horizontal
            };
            for (double v : values)
                if (!Double.isFinite(v)) throw new IllegalArgumentException("Nonfinite reference");
            if (!(minOffset < maxOffset
                    && featherY > 0
                    && reliefMax > 0
                    && plainWidth > 0
                    && knollWidth > 0
                    && hollowWidth > 0
                    && riserWidth > 0
                    && riserHeight > 0
                    && horizontal > 0)) throw new IllegalArgumentException("Invalid reference");
            this.seaY = seaY;
            minY = seaY + minOffset;
            maxY = seaY + maxOffset;
            this.featherY = featherY;
            this.reliefMax = reliefMax;
            this.plainWidth = plainWidth * horizontal;
            this.knollWidth = knollWidth * horizontal;
            this.hollowWidth = hollowWidth * horizontal;
            this.riserWidth = riserWidth * horizontal;
            this.riserHeight = riserHeight;
            if (!finite(
                            this.minY,
                            this.maxY,
                            this.plainWidth,
                            this.knollWidth,
                            this.hollowWidth,
                            this.riserWidth)
                    || !(this.minY < this.maxY
                            && this.plainWidth > 0
                            && this.knollWidth > 0
                            && this.hollowWidth > 0
                            && this.riserWidth > 0))
                throw new IllegalArgumentException("Invalid derived reference dimensions");
        }
    }

    /** Immutable tuning; explicit ratios retain the reference as the authority. */
    public static final class Tuning {
        public final double amplitude, broadSpan, patchSpan, cellSpan, warp;
        public final double density, slopeStart, slopeEnd, shoreSkirt, waterSkirt;

        public Tuning(
                Reference r,
                double reliefFraction,
                double broadInPlainWidths,
                double patchInHollowWidths,
                double cellInKnollWidths,
                double warpFraction,
                double density,
                double slopeStart,
                double slopeEnd) {
            if (!(finite(
                            reliefFraction,
                            broadInPlainWidths,
                            patchInHollowWidths,
                            cellInKnollWidths,
                            warpFraction,
                            density,
                            slopeStart,
                            slopeEnd)
                    && reliefFraction > 0
                    && reliefFraction <= 0.5
                    && broadInPlainWidths >= 0.25
                    && broadInPlainWidths <= 2
                    && patchInHollowWidths >= 0.5
                    && patchInHollowWidths <= 2
                    && cellInKnollWidths >= 0.5
                    && cellInKnollWidths <= 2
                    && warpFraction >= 0
                    && warpFraction <= 0.3
                    && density >= 0
                    && density <= 1
                    && slopeStart >= 0
                    && slopeEnd > slopeStart
                    && slopeEnd <= 0.25))
                throw new IllegalArgumentException("Tuning outside bounded lowland ranges");
            amplitude = r.reliefMax * reliefFraction;
            broadSpan = r.plainWidth * broadInPlainWidths;
            patchSpan = r.hollowWidth * patchInHollowWidths;
            cellSpan = r.knollWidth * cellInKnollWidths;
            warp = warpFraction;
            this.density = density;
            this.slopeStart = slopeStart;
            this.slopeEnd = slopeEnd;
            shoreSkirt = r.knollWidth * 0.5;
            waterSkirt = r.riserWidth * 2;
            double step = Math.min(r.riserHeight / 3, .30 * amplitude);
            double thresholdReach =
                    (Math.max(Math.abs(r.minY - r.seaY), Math.abs(r.maxY - r.seaY)) + amplitude)
                            / step;
            if (!finite(
                            amplitude,
                            broadSpan,
                            patchSpan,
                            cellSpan,
                            shoreSkirt,
                            waterSkirt,
                            thresholdReach)
                    || amplitude < 1e-6
                    || step <= 0
                    || thresholdReach > 0x1.0p40
                    || broadSpan <= 0
                    || patchSpan <= 0
                    || cellSpan <= 0
                    || shoreSkirt <= 0
                    || waterSkirt <= 0)
                throw new IllegalArgumentException("Derived dimensions cannot be sampled reliably");
        }
    }

    /** All facts precede shaping. Clearances are signed distances OUTSIDE complete reservations. */
    public static final class Earlier {
        public final double parentY, lowland, slope, shoreClearance, waterClearance;
        public final double moisture, directionX, directionZ, selectedWeight, minY, maxY;
        public final boolean marine;

        public Earlier(
                double parentY,
                double lowland,
                double slope,
                boolean marine,
                double shoreClearance,
                double waterClearance,
                double moisture,
                double directionX,
                double directionZ,
                double selectedWeight,
                double minY,
                double maxY) {
            this.parentY = parentY;
            this.lowland = lowland;
            this.slope = slope;
            this.marine = marine;
            this.shoreClearance = shoreClearance;
            this.waterClearance = waterClearance;
            this.moisture = moisture;
            this.directionX = directionX;
            this.directionZ = directionZ;
            this.selectedWeight = selectedWeight;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    /** Caller-owned output: never share this object between simultaneous calls. */
    public static final class Sample {
        public double y, delta, weight;
        public Reason reason;
        public int candidateVisits;
    }

    /** Smooth optional regional family weights. Uses earlier intent/climate, never a shaped height. */
    public static void selection(
            long seed, double x, double z, Reference r, Earlier in, double[] out) {
        if (out.length < 4) throw new IllegalArgumentException("Four weights required");
        if (!valid(x, z, in) || in.marine || in.lowland <= 0) {
            for (int i = 0; i < 4; i++) out[i] = 0;
            return;
        }
        double scale = 2 * r.plainWidth, sum = 0;
        for (int i = 0; i < 4; i++) {
            Family f =
                    i == 0
                            ? Family.WARPED_PLAIN
                            : i == 1
                                    ? Family.COMPACT_PATCHES
                                    : i == 2 ? Family.CELLULAR_HUMMOCKS : Family.LOW_TERRACES;
            double fit =
                    f == Family.CELLULAR_HUMMOCKS
                            ? 0.4 + 0.6 * in.moisture
                            : f == Family.LOW_TERRACES ? 0.9 - 0.4 * in.moisture : 0.8;
            out[i] =
                    fit
                            * (0.55
                                    + 0.45
                                            * noise(
                                                    seed ^ f.salt ^ 0x53454c454354L,
                                                    x / scale,
                                                    z / scale));
            sum += out[i];
        }
        for (int i = 0; i < 4; i++) out[i] /= sum;
    }

    /** Fixed work, no allocation, no caches, no callbacks, no chunk state. */
    public static void sample(
            Family family,
            long seed,
            double x,
            double z,
            Reference r,
            Tuning t,
            Earlier in,
            Sample out) {
        out.y = in.parentY;
        out.delta = out.weight = 0;
        out.candidateVisits = 0;
        if (!valid(x, z, in)) {
            out.reason = Reason.INVALID_INPUT;
            return;
        }
        if (in.marine) {
            out.reason = Reason.MARINE;
            return;
        }
        if (in.lowland <= 0) {
            out.reason = Reason.NOT_LOWLAND;
            return;
        }
        if (in.slope >= t.slopeEnd) {
            out.reason = Reason.STEEP_PARENT;
            return;
        }
        if (in.shoreClearance <= 0) {
            out.reason = Reason.SHORE_RESERVED;
            return;
        }
        if (in.waterClearance <= 0) {
            out.reason = Reason.WATER_RESERVED;
            return;
        }
        double lo = Math.max(r.minY, in.minY), hi = Math.min(r.maxY, in.maxY);
        // Reject the full +/- amplitude before evaluation. Do not clip a finished feature.
        if (in.parentY - t.amplitude < lo || in.parentY + t.amplitude > hi) {
            out.reason = Reason.HEADROOM;
            return;
        }
        if (in.selectedWeight <= 0) {
            out.reason = Reason.NOT_SELECTED;
            return;
        }
        double weight =
                in.lowland
                        * in.selectedWeight
                        * (1 - smooth((in.slope - t.slopeStart) / (t.slopeEnd - t.slopeStart)))
                        * smooth(in.shoreClearance / t.shoreSkirt)
                        * smooth(in.waterClearance / t.waterSkirt)
                        * smooth((in.parentY - lo - t.amplitude) / r.featherY)
                        * smooth((hi - in.parentY - t.amplitude) / r.featherY);
        long s = seed ^ family.salt;
        double raw;
        switch (family) {
            case WARPED_PLAIN:
                raw = warped(s, x, z, r, t);
                break;
            case COMPACT_PATCHES:
                raw = patches(s, x, z, r, t, out);
                break;
            case CELLULAR_HUMMOCKS:
                raw = cellular(s, x, z, r, t, in, out);
                break;
            case LOW_TERRACES:
                out.candidateVisits = 5;
                raw = terraces(s, x, z, r, t, in);
                break;
            default:
                throw new AssertionError(family);
        }
        out.delta = weight * raw;
        out.y = in.parentY + out.delta;
        out.weight = weight;
        out.reason = Reason.ACCEPTED;
    }

    private static double warped(long s, double x, double z, Reference r, Tuning t) {
        double span = t.broadSpan;
        double wx = t.warp * span * noise(s ^ 11, x / span, z / span);
        double wz = t.warp * span * noise(s ^ 17, (x + 71) / span, (z - 113) / span);
        // Noncommensurate spans and fixed rotations suppress visible octave alignment.
        double u = x + wx, v = z + wz;
        return t.amplitude
                * (0.64
                                * noise(
                                        s ^ 23,
                                        (.8 * u + .6 * v) / (.43 * span),
                                        (-.6 * u + .8 * v) / (.43 * span))
                        + 0.25
                                * noise(
                                        s ^ 31,
                                        (-.28 * u + .96 * v) / (.173 * span),
                                        (.96 * u + .28 * v) / (.173 * span))
                        + 0.11 * noise(s ^ 41, x / (.46 * r.knollWidth), z / (.46 * r.knollWidth)));
    }

    private static double patches(long s, double x, double z, Reference r, Tuning t, Sample out) {
        double spacing = t.patchSpan;
        long cx = floor(x / spacing), cz = floor(z / spacing);
        double sum = 0, mass = 0;
        // Radius <= .94 spacing, centers stay within their cell: 3x3 is complete.
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                out.candidateVisits++;
                long h = hash(s, cx + dx, cz + dz);
                if (unit(h) >= t.density) continue;
                double px = (cx + dx + unit(mix(h ^ 1))) * spacing;
                double pz = (cz + dz + unit(mix(h ^ 2))) * spacing;
                // Normalize a hashed direction: no trigonometric argument reduction/allocation.
                double ca = 2 * unit(mix(h ^ 3)) - 1, sa = 2 * unit(mix(h ^ 13)) - 1;
                double length = StrictMath.sqrt(ca * ca + sa * sa);
                if (length < 1e-8) {
                    ca = 1;
                    sa = 0;
                } else {
                    ca /= length;
                    sa /= length;
                }
                double u = (x - px) * ca + (z - pz) * sa, v = -(x - px) * sa + (z - pz) * ca;
                double major = spacing * (.58 + .36 * unit(mix(h ^ 4)));
                double minor = major * (.45 + .38 * unit(mix(h ^ 5)));
                double q = u * u / (major * major) + v * v / (minor * minor);
                if (q >= 1) continue;
                double k = (1 - q) * (1 - q) * (1 - q); // compact C2 boundary
                double sign = unit(mix(h ^ 6)) < .55 ? -1 : 1;
                // Asymmetric flank while retaining |value|<=kernel.
                double skew = .72 + .28 * u / major;
                sum += sign * k * skew;
                mass += k;
            }
        return t.amplitude
                * (.82 * sum / (1 + mass)
                        + .18 * noise(s ^ 53, x / (.75 * r.knollWidth), z / (.75 * r.knollWidth)));
    }

    private static double cellular(
            long s, double x, double z, Reference r, Tuning t, Earlier in, Sample out) {
        double scale = Math.max(Math.abs(in.directionX), Math.abs(in.directionZ));
        double hx = scale > 0 ? in.directionX / scale : 1,
                hz = scale > 0 ? in.directionZ / scale : 0;
        double length = StrictMath.sqrt(hx * hx + hz * hz), ux = hx / length, uz = hz / length;
        // Area-preserving mild anisotropy; orientation is an earlier regional field.
        double spacing = t.cellSpan;
        double xx = (x * ux + z * uz) / 1.18, zz = (-x * uz + z * ux) * 1.18;
        double wx = .6 * spacing * noise(s ^ 101, xx / (3.7 * spacing), zz / (3.7 * spacing));
        double wz =
                .6
                        * spacing
                        * noise(s ^ 103, (xx + 93) / (3.7 * spacing), (zz - 71) / (3.7 * spacing));
        xx += wx;
        zz += wz;
        long cx = floor(xx / spacing), cz = floor(zz / spacing);
        double d1 = Double.POSITIVE_INFINITY, d2 = Double.POSITIVE_INFINITY, gain = 1;
        // Full-cell jitter, plus occasional extra sites, suppress regular cell-size repetition.
        // Own site <=sqrt(2) cells. The cardinal cell toward the closer X boundary
        // supplies a second site <=sqrt(1.5^2+1^2)<1.803 cells away.
        // Omitted cells start >=2 cells away: the nearest TWO remain complete.
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                long h = hash(s, cx + dx, cz + dz);
                int count = unit(mix(h ^ 109)) < .35 ? 2 : 1;
                for (int site = 0; site < count; site++) {
                    out.candidateVisits++;
                    long j = site == 0 ? h : mix(h ^ 107);
                    double px = (cx + dx + unit(j)) * spacing;
                    double pz = (cz + dz + unit(mix(j ^ 7))) * spacing;
                    double a = (xx - px) / spacing, b = (zz - pz) / spacing, d = a * a + b * b;
                    if (d < d1) {
                        d2 = d1;
                        d1 = d;
                        gain = .35 + .65 * unit(mix(j ^ 113));
                    } else if (d < d2) d2 = d;
                }
            }
        double gap = (d2 - d1) / (d2 + d1 + .12);
        double mound = 1.5 * gain * gap * gap - .5;
        return t.amplitude
                * (.67 * mound
                        + .23 * noise(s ^ 61, x / t.broadSpan, z / t.broadSpan)
                        + .10 * noise(s ^ 67, x / (.5 * r.knollWidth), z / (.5 * r.knollWidth)));
    }

    private static double terraces(long s, double x, double z, Reference r, Tuning t, Earlier in) {
        double scale = Math.max(Math.abs(in.directionX), Math.abs(in.directionZ));
        double hx = scale > 0 ? in.directionX / scale : 1,
                hz = scale > 0 ? in.directionZ / scale : 0;
        double length = StrictMath.sqrt(hx * hx + hz * hz), ux = hx / length, uz = hz / length;
        double a = x * ux + z * uz, b = -x * uz + z * ux;
        double broad = .40 * t.amplitude * noise(s ^ 71, a / (.55 * t.broadSpan), b / t.broadSpan);
        double potential = in.parentY + broad;
        double step = Math.min(r.riserHeight / 3, .30 * t.amplitude);
        double normalized = (potential - r.seaY) / step;
        long bin = floor(normalized);
        double lo = -Double.MAX_VALUE, hi = Double.MAX_VALUE;
        // Jitter <= .22 step, knots remain ordered; 5 thresholds suffice.
        for (int j = -2; j <= 2; j++) {
            long k = bin + j;
            double knot = k + .22 * (2 * unit(hash(s ^ 79, k, 0)) - 1);
            if (knot <= normalized) lo = Math.max(lo, knot);
            else hi = Math.min(hi, knot);
        }
        double phase = (normalized - lo) / (hi - lo);
        double riserFraction =
                Math.max(.14, Math.min(.42, r.riserWidth * Math.max(in.slope, .025) / step));
        double transition = smooth((phase - (.66 - riserFraction / 2)) / riserFraction);
        double bench = r.seaY + step * (lo + (hi - lo) * transition);
        // |bench-potential|<=1.44 step<=.432 amplitude; total <.912 amplitude.
        return bench
                - in.parentY
                + .08 * t.amplitude * noise(s ^ 83, a / (.6 * r.knollWidth), b / r.knollWidth);
    }

    private static boolean valid(double x, double z, Earlier i) {
        return Double.isFinite(x)
                && Double.isFinite(z)
                && Double.isFinite(i.parentY)
                && Double.isFinite(i.lowland)
                && Double.isFinite(i.slope)
                && Double.isFinite(i.shoreClearance)
                && Double.isFinite(i.waterClearance)
                && Double.isFinite(i.moisture)
                && Double.isFinite(i.directionX)
                && Double.isFinite(i.directionZ)
                && Double.isFinite(i.selectedWeight)
                && Double.isFinite(i.minY)
                && Double.isFinite(i.maxY)
                && Math.abs(x) <= 30_000_000
                && Math.abs(z) <= 30_000_000
                && i.lowland >= 0
                && i.lowland <= 1
                && i.selectedWeight >= 0
                && i.selectedWeight <= 1
                && i.moisture >= 0
                && i.moisture <= 1
                && i.slope >= 0
                && i.minY < i.maxY;
    }

    private static boolean finite(double... a) {
        for (double v : a) if (!Double.isFinite(v)) return false;
        return true;
    }

    private static long floor(double d) {
        return (long) StrictMath.floor(d);
    }

    public static long mix(long h) {
        h ^= h >>> 29;
        h *= 0x319642b2d24d8ec3L;
        h ^= h >>> 32;
        h *= 0x96de1b173f119089L;
        return h ^ (h >>> 28);
    }

    private static long hash(long s, long x, long z) {
        return mix(
                s
                        + mix(x ^ 0x7a2b4c6e8193a5d7L)
                        + Long.rotateLeft(mix(z ^ 0x134679bde02468acL), 27));
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    public static double smooth(double x) {
        double t = Math.max(0, Math.min(1, x));
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    /** Independent hash-valued interpolation; no copied permutation or gradient table. */
    public static double noise(long s, double x, double z) {
        long ix = floor(x), iz = floor(z);
        double u = smooth(x - ix), v = smooth(z - iz);
        double a = 2 * unit(hash(s, ix, iz)) - 1, b = 2 * unit(hash(s, ix + 1, iz)) - 1;
        double c = 2 * unit(hash(s, ix, iz + 1)) - 1, d = 2 * unit(hash(s, ix + 1, iz + 1)) - 1;
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }
}
