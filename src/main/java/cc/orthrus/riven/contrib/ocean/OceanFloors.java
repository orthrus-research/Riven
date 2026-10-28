/* SPDX-License-Identifier: MIT */
package cc.orthrus.riven.contrib.ocean;

import java.util.Properties;

/** Independently authored geometry only. No world, fluid, cache or engine dependencies. */
@SuppressWarnings("strictfp") // Retain strict evaluation for the Java 8 fallback.
public final strictfp class OceanFloors {
    private OceanFloors() {}

    public static final long SHELF_SALT = 0x5348454c46303031L;
    public static final long RIDGE_SALT = 0x5249444745303031L;
    public static final long TRENCH_SALT = 0x5452454e43483031L;
    public static final long BANK_SALT = 0x42414e4b53303031L;

    public enum Reason {
        APPLIED,
        NON_MARINE,
        RESERVED,
        CONTEXT,
        OUTSIDE_SUPPORT,
        HEADROOM
    }

    /** Earlier immutable column facts. distance is positive offshore, in blocks.
     * Affinities are earlier, continuous [0,1] opportunity fields, NOT finished terrain. */
    public static final class Input {
        public final boolean marine, reserved;
        public final double distance, parentY, ridgeAffinity, trenchAffinity, bankAffinity;

        public Input(
                boolean marine,
                boolean reserved,
                double distance,
                double parentY,
                double ridgeAffinity,
                double trenchAffinity,
                double bankAffinity) {
            if (!finite(distance)
                    || !finite(parentY)
                    || !unit(ridgeAffinity)
                    || !unit(trenchAffinity)
                    || !unit(bankAffinity))
                throw new IllegalArgumentException("Nonfinite column or invalid earlier affinity");
            this.marine = marine;
            this.reserved = reserved;
            this.distance = distance;
            this.parentY = parentY;
            this.ridgeAffinity = ridgeAffinity;
            this.trenchAffinity = trenchAffinity;
            this.bankAffinity = bankAffinity;
        }

        public Input withParent(double y) {
            return new Input(
                    marine, reserved, distance, y, ridgeAffinity, trenchAffinity, bankAffinity);
        }
    }

    /** One per caller/thread; never share an instance between simultaneous calls. */
    public static final class Output {
        public double bedY, weight;
        public Reason reason;
        public long siteId;

        private void reset(Input in) {
            bedY = in.parentY;
            weight = 0;
            reason = Reason.OUTSIDE_SUPPORT;
            siteId = 0;
        }
    }

    public static final class Feature {
        public final double lo, mean, hi, widthMin, width, widthMax, relief;

        public Feature(
                double lo,
                double mean,
                double hi,
                double widthMin,
                double width,
                double widthMax,
                double relief) {
            if (!(finite(lo)
                    && finite(mean)
                    && finite(hi)
                    && lo <= mean
                    && mean <= hi
                    && 1 <= widthMin
                    && widthMin <= width
                    && width <= widthMax
                    && widthMax <= 30000000
                    && finite(relief)
                    && relief >= 0))
                throw new IllegalArgumentException("Invalid reference feature");
            this.lo = lo;
            this.mean = mean;
            this.hi = hi;
            this.widthMin = widthMin;
            this.width = width;
            this.widthMax = widthMax;
            this.relief = relief;
        }
    }

    /** Feed from WorldGenerationContext.terrainHeights in a later adapter; no static default. */
    public static final class Reference {
        public final double sea, waterTop, bottom;
        public final Feature shelf, slope, deep, trench, shoal;

        public Reference(
                double sea,
                double waterTop,
                double bottom,
                Feature shelf,
                Feature slope,
                Feature deep,
                Feature trench,
                Feature shoal) {
            if (!finite(sea)
                    || !finite(waterTop)
                    || !finite(bottom)
                    || waterTop + 1 != sea
                    || bottom > trench.lo
                    || slope.width > deep.width * .3
                    || trench.width > deep.width * .3
                    || !(shelf.mean >= slope.mean
                            && slope.mean >= deep.mean
                            && deep.mean >= trench.mean)
                    || shelf.lo > shelf.mean - shelf.relief * .5
                    || shelf.hi < shelf.mean + shelf.relief * .5
                    || deep.lo > deep.mean - deep.relief * .5
                    || deep.hi < deep.mean + deep.relief * .5
                    || trench.lo > trench.mean - trench.relief * .22
                    || trench.hi < trench.mean + trench.relief * .22
                    || shoal.lo > shoal.mean - shoal.relief * .5
                    || shoal.hi < shoal.mean + shoal.relief * .5
                    || Math.min(shelf.lo, Math.min(slope.lo, Math.min(deep.lo, shoal.lo))) < bottom
                    || Math.max(
                                    shelf.hi,
                                    Math.max(
                                            slope.hi,
                                            Math.max(deep.hi, Math.max(trench.hi, shoal.hi))))
                            > waterTop)
                throw new IllegalArgumentException("Inconsistent datum or bottom margin");
            this.sea = sea;
            this.waterTop = waterTop;
            this.bottom = bottom;
            this.shelf = shelf;
            this.slope = slope;
            this.deep = deep;
            this.trench = trench;
            this.shoal = shoal;
        }

        public static Reference fromProperties(Properties p) {
            double sea = num(p, "sea");
            return new Reference(
                    sea,
                    num(p, "waterTop"),
                    num(p, "bottom"),
                    feature(p, "continental_shelf", sea),
                    feature(p, "continental_slope", sea),
                    feature(p, "deep_ocean", sea),
                    feature(p, "ocean_trench", sea),
                    feature(p, "shoal", sea));
        }

        private static Feature feature(Properties p, String id, double sea) {
            return new Feature(
                    sea + num(p, id + ".lo"),
                    sea + num(p, id + ".mean"),
                    sea + num(p, id + ".hi"),
                    num(p, id + ".widthMin"),
                    num(p, id + ".width"),
                    num(p, id + ".widthMax"),
                    num(p, id + ".relief"));
        }
    }

    /** Dimensionless controls. Distances and envelopes remain reference-derived. */
    public static final class Tuning {
        public final double shelfScale,
                slopeScale,
                detail,
                ridgeChance,
                trenchChance,
                bankChance,
                relief;

        public Tuning(
                double shelfScale,
                double slopeScale,
                double detail,
                double ridgeChance,
                double trenchChance,
                double bankChance,
                double relief) {
            if (!(shelfScale >= .8
                    && shelfScale <= 1.5
                    && slopeScale >= .75
                    && slopeScale <= 1.5
                    && unit(detail)
                    && unit(ridgeChance)
                    && unit(trenchChance)
                    && unit(bankChance)
                    && relief >= .25
                    && relief <= 1))
                throw new IllegalArgumentException("Tuning exceeds proven search/envelope bounds");
            this.shelfScale = shelfScale;
            this.slopeScale = slopeScale;
            this.detail = detail;
            this.ridgeChance = ridgeChance;
            this.trenchChance = trenchChance;
            this.bankChance = bankChance;
            this.relief = relief;
        }

        public static Tuning fromProperties(Properties p) {
            return new Tuning(
                    num(p, "shelfScale"),
                    num(p, "slopeScale"),
                    num(p, "detail"),
                    num(p, "ridgeChance"),
                    num(p, "trenchChance"),
                    num(p, "bankChance"),
                    num(p, "relief"));
        }
    }

    public interface Kernel {
        void sample(double x, double z, Input input, Output output);
    }

    private abstract static class Base implements Kernel {
        final long seed;
        final Reference r;
        final Tuning t;

        Base(long seed, long salt, Reference r, Tuning t) {
            this.seed = mix(seed ^ salt);
            this.r = r;
            this.t = t;
        }

        final boolean begin(double x, double z, Input in, Output out) {
            if (!finite(x) || !finite(z) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000)
                throw new IllegalArgumentException(
                        "Coordinates outside supported Minecraft domain");
            out.reset(in);
            if (!in.marine) {
                out.reason = Reason.NON_MARINE;
                return false;
            }
            if (in.reserved) {
                out.reason = Reason.RESERVED;
                return false;
            }
            if (in.parentY < r.bottom || in.parentY > r.waterTop) {
                out.reason = Reason.HEADROOM;
                return false;
            }
            return true;
        }

        final double gate(Input in, double affinity, double start, double width, Output out) {
            double g = smooth((in.distance - start) / width) * smooth(affinity);
            if (g == 0) out.reason = Reason.CONTEXT;
            return g;
        }
    }

    /** Family 1: directed distance profile with independently variable shelf/slope dimensions. */
    public static final class ShelfSlope extends Base {
        public ShelfSlope(long seed, Reference r, Tuning t) {
            super(seed, SHELF_SALT, r, t);
            if (r.shelf.width * t.shelfScale * .85 - r.shoal.width < r.shelf.widthMin
                    || r.shelf.width * t.shelfScale * 1.15 > r.shelf.widthMax
                    || r.slope.width * t.slopeScale * .85 < r.slope.widthMin
                    || r.slope.width * t.slopeScale * 1.15 > r.slope.widthMax)
                throw new IllegalArgumentException(
                        "Tuned shelf/slope envelope exceeds reference widths");
        }

        public void sample(double x, double z, Input in, Output out) {
            if (!begin(x, z, in, out)) return;
            if (in.distance <= 0) {
                out.reason = Reason.CONTEXT;
                return;
            }
            double broad = noise(seed, x / (r.deep.width * 2), z / (r.deep.width * 2));
            double shelfWidth = r.shelf.width * t.shelfScale * (1 + .15 * broad);
            double slopeWidth =
                    r.slope.width
                            * t.slopeScale
                            * (1 + .15 * noise(seed + 1, x / r.deep.width, z / r.deep.width));
            double coast = smooth(in.distance / r.shoal.width);
            double descent = smooth((in.distance - shelfWidth) / slopeWidth);
            double shelf =
                    r.shelf.mean
                            + t.detail
                                    * r.shelf.relief
                                    * .5
                                    * noise(seed + 2, x / r.shelf.width, z / r.shelf.width);
            double deep =
                    r.deep.mean
                            + t.detail
                                    * r.deep.relief
                                    * .5
                                    * noise(seed + 3, x / r.deep.width, z / r.deep.width);
            double y = lerp(lerp(in.parentY, shelf, coast), deep, descent);
            if (y < r.bottom || y > r.waterTop) {
                out.reason = Reason.HEADROOM;
                return;
            }
            out.bedY = y;
            out.weight = coast;
            out.reason = Reason.APPLIED;
        }
    }

    /** Family 2: finite, curved, seven-node ridges with saddle modulation and axial rifts. */
    public static final class RidgeChain extends Base {
        public RidgeChain(long seed, Reference r, Tuning t) {
            super(seed, RIDGE_SALT, r, t);
        }

        public void sample(double x, double z, Input in, Output out) {
            if (!begin(x, z, in, out)) return;
            double g = gate(in, in.ridgeAffinity, r.shelf.width, r.slope.width, out);
            if (g == 0) return;
            // Convex blending to a reference-based target; no additive pileup at crossings.
            double target = lerp(r.deep.mean, r.slope.mean, .68 * t.relief);
            if (in.parentY >= target) {
                out.reason = Reason.CONTEXT;
                return;
            }
            if (target > r.waterTop || target < r.bottom) {
                out.reason = Reason.HEADROOM;
                return;
            }
            corridor(x, z, in, out, g, target);
        }

        private void corridor(double x, double z, Input in, Output out, double g, double target) {
            double cell = r.deep.width;
            int cx = floor(x / cell), cz = floor(z / cell);
            double best = 0;
            long winner = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long id = hash(seed, cx + dx, cz + dz);
                    if (random(id, 0) >= t.ridgeChance) continue;
                    double ax = (cx + dx + .5 + (random(id, 1) - .5) * .36) * cell;
                    double az = (cz + dz + .5 + (random(id, 2) - .5) * .36) * cell;
                    double angle = random(id, 3) * Math.PI * 2,
                            c = StrictMath.cos(angle),
                            s = StrictMath.sin(angle);
                    double u = (x - ax) * c + (z - az) * s, v = -(x - ax) * s + (z - az) * c;
                    double length = cell * (.5 + .16 * random(id, 4)),
                            half = r.slope.width * (.45 + .15 * random(id, 5));
                    if (Math.abs(u) > length / 2 + half || Math.abs(v) > cell * .08 + half)
                        continue;
                    double local = 0, px = -length / 2, pz = node(id, 0, cell);
                    for (int k = 1; k <= 6; k++) {
                        double qx = -length / 2 + length * k / 6, qz = node(id, k, cell);
                        double vx = qx - px,
                                vz = qz - pz,
                                a = clamp(((u - px) * vx + (v - pz) * vz) / (vx * vx + vz * vz));
                        double dist = sq(u - px - a * vx) + sq(v - pz - a * vz);
                        double q = StrictMath.sqrt(dist) / half, along = (k - 1 + a) / 6;
                        double saddle =
                                .76
                                        + .24
                                                * sq(
                                                        StrictMath.sin(
                                                                along * Math.PI * 3
                                                                        + random(id, 8) * 3));
                        double rift = 1 - .22 * bump(q / .16);
                        local = Math.max(local, bump(q) * saddle * rift);
                        px = qx;
                        pz = qz;
                    }
                    double w = local * g;
                    if (w > best) {
                        best = w;
                        winner = id;
                    }
                }
            if (best > 0) {
                out.bedY = lerp(in.parentY, target, best);
                out.weight = best;
                out.siteId = winner;
                out.reason = Reason.APPLIED;
            }
        }
    }

    /** Family 3: bounded asymmetric curving trench, broad outer wall and narrow inner wall. */
    public static final class TrenchCorridor extends Base {
        public TrenchCorridor(long seed, Reference r, Tuning t) {
            super(seed, TRENCH_SALT, r, t);
        }

        public void sample(double x, double z, Input in, Output out) {
            if (!begin(x, z, in, out)) return;
            double g =
                    gate(in, in.trenchAffinity, r.shelf.width + r.slope.width, r.slope.width, out);
            if (g == 0) return;
            if (r.trench.lo < r.bottom) {
                out.reason = Reason.HEADROOM;
                return;
            }
            double cell = r.deep.width;
            int cx = floor(x / cell), cz = floor(z / cell);
            double best = 0, sum = 0, delta = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long id = hash(seed, cx + dx, cz + dz);
                    if (random(id, 0) >= t.trenchChance) continue;
                    double ax = (cx + dx + .5 + (random(id, 1) - .5) * .36) * cell;
                    double az = (cz + dz + .5 + (random(id, 2) - .5) * .36) * cell;
                    double angle = random(id, 3) * Math.PI * 2,
                            c = StrictMath.cos(angle),
                            s = StrictMath.sin(angle);
                    double u = (x - ax) * c + (z - az) * s, v = -(x - ax) * s + (z - az) * c;
                    double length = cell * (.55 + .1 * random(id, 4)),
                            half = r.trench.width * .5 * (.85 + .3 * random(id, 5));
                    if (Math.abs(u) > length / 2 || Math.abs(v) > half * 1.5 + cell * .06) continue;
                    double a = u / (length / 2),
                            axis =
                                    cell * .06 * (a * a - .4)
                                            + cell
                                                    * .018
                                                    * StrictMath.sin(a * 4 + random(id, 6) * 6);
                    double signed = v - axis, width = half * (signed < 0 ? .65 : 1.35);
                    double end = 1 - smooth((Math.abs(a) - .6) / .4);
                    double q = Math.abs(signed) / width;
                    double w = (1 - smooth((q - .12) / .88)) * end * g;
                    double target =
                            lerp(r.deep.mean, r.trench.mean, t.relief)
                                    + r.trench.relief
                                            * .22
                                            * t.detail
                                            * noise(id, u / r.trench.width, 0);
                    // Admission weight vanishes continuously before a target crosses the frozen
                    // parent.
                    // Otherwise removing a non-cutting site can abruptly change overlap
                    // normalization.
                    w *= smooth((in.parentY - target) / Math.max(1, r.trench.relief));
                    if (target < in.parentY && w > 0) {
                        sum += w;
                        delta += w * (target - in.parentY);
                        if (w > best) {
                            best = w;
                            out.siteId = id;
                        }
                    }
                }
            if (best > 0) {
                out.bedY = in.parentY + best * delta / sum;
                out.weight = best;
                out.reason = Reason.APPLIED;
            }
        }
    }

    /** Family 4: finite lobed submerged banks with irregular caps and compact apron skirts. */
    public static final class BankComplex extends Base {
        public BankComplex(long seed, Reference r, Tuning t) {
            super(seed, BANK_SALT, r, t);
        }

        public void sample(double x, double z, Input in, Output out) {
            if (!begin(x, z, in, out)) return;
            double g = gate(in, in.bankAffinity, r.shoal.width, r.shoal.width, out);
            if (g == 0) return;
            if (r.shoal.hi > r.waterTop) {
                out.reason = Reason.HEADROOM;
                return;
            }
            double cell = r.shelf.width * 2;
            int cx = floor(x / cell), cz = floor(z / cell);
            double best = 0, sum = 0, delta = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    long id = hash(seed, cx + dx, cz + dz);
                    if (random(id, 0) >= t.bankChance) continue;
                    double ax = (cx + dx + .5 + (random(id, 1) - .5) * .36) * cell;
                    double az = (cz + dz + .5 + (random(id, 2) - .5) * .36) * cell;
                    double angle = random(id, 3) * Math.PI * 2,
                            c = StrictMath.cos(angle),
                            s = StrictMath.sin(angle);
                    double u = (x - ax) * c + (z - az) * s, v = -(x - ax) * s + (z - az) * c;
                    double radius = r.shelf.width * (.28 + .12 * random(id, 4));
                    if (Math.abs(u) > radius * 1.4 || Math.abs(v) > radius * 1.4) continue;
                    double w = 0;
                    for (int k = 0; k < 3; k++) {
                        double lx = (random(id, 10 + k * 3) - .5) * radius * .7,
                                lz = (random(id, 11 + k * 3) - .5) * radius * .7;
                        double q =
                                StrictMath.sqrt(
                                        sq((u - lx) / radius)
                                                + sq(
                                                        (v - lz)
                                                                / (radius
                                                                        * (.58
                                                                                + .2
                                                                                        * random(
                                                                                                id,
                                                                                                12
                                                                                                        + k
                                                                                                                * 3)))));
                        w = Math.max(w, 1 - smooth((q - .38) / .62));
                    }
                    w *= g;
                    double cap =
                            r.shoal.mean
                                    + t.detail
                                            * r.shoal.relief
                                            * .5
                                            * noise(id, x / r.shoal.width, z / r.shoal.width);
                    double target = lerp(r.shelf.mean, cap, t.relief);
                    w *= smooth((target - in.parentY) / Math.max(1, r.shoal.relief));
                    if (target > in.parentY && w > 0) {
                        sum += w;
                        delta += w * (target - in.parentY);
                        if (w > best) {
                            best = w;
                            out.siteId = id;
                        }
                    }
                }
            if (best > 0) {
                out.bedY = in.parentY + best * delta / sum;
                out.weight = best;
                out.reason = Reason.APPLIED;
            }
        }
    }

    // Zero value and first two derivatives at support edge; independently expressed polynomial.
    static double bump(double q) {
        if (q >= 1) return 0;
        double b = 1 - q * q;
        return b * b * b;
    }

    static double node(long id, int k, double cell) {
        return (random(id, 30 + k) - .5) * cell * .16;
    }

    static double smooth(double q) {
        q = clamp(q);
        return clamp(q * q * q * (q * (q * 6 - 15) + 10));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double sq(double x) {
        return x * x;
    }

    static int floor(double x) {
        return (int) StrictMath.floor(x);
    }

    static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    static boolean unit(double v) {
        return v >= 0 && v <= 1;
    }

    static double num(Properties p, String key) {
        return Double.parseDouble(p.getProperty(key));
    }

    // Local integer avalanche, no mutable PRNG, signed floor cells work at negative coordinates.
    static long mix(long v) {
        v ^= v >>> 29;
        v *= 0x319642b2d24d8ec3L;
        v ^= v >>> 32;
        v *= 0x96de1b173f119089L;
        return v ^ (v >>> 28);
    }

    static long hash(long seed, int x, int z) {
        return mix(seed ^ mix(x * 0x632be59bd9b4e019L) ^ mix(z * 0x9e3779b97f4a7c15L));
    }

    static double random(long id, int salt) {
        return (mix(id + salt * 0x9e3779b97f4a7c15L) >>> 11) * 0x1.0p-53;
    }

    static double noise(long seed, double x, double z) {
        int ix = floor(x), iz = floor(z);
        double a = smooth(x - ix), b = smooth(z - iz);
        return 2
                        * lerp(
                                lerp(
                                        random(hash(seed, ix, iz), 0),
                                        random(hash(seed, ix + 1, iz), 0),
                                        a),
                                lerp(
                                        random(hash(seed, ix, iz + 1), 0),
                                        random(hash(seed, ix + 1, iz + 1), 0),
                                        a),
                                b)
                - 1;
    }
}
