// SPDX-License-Identifier: MIT
/*
 * Adapted from contributions/rocky-coasts-20261019, including its second pass.
 * Riven modifications (2026-10-20): package, primitive sampling, explicit target.
 * Riven modifications (2026-11-03): the STACK_ARCH body family, its 3D permit and
 * foundation admission, and the native column certificates and voxel cuts removed;
 * Riven's coast forms build their own bodies. The three height families remain.
 * Packaged notices: META-INF/licenses/rocky-coasts/{MIT.txt,SOURCES.md}.
 */
package cc.orthrus.riven.contrib.rocky;

import java.util.Properties;

/** Independently authored, world-free rocky coast kernels. All lengths are blocks. */
public final class RockyCoasts {
    private RockyCoasts() {}

    public enum Family {
        CLIFF_PLATFORM(0x524F434B434C4946L),
        STEPPED_HEADLAND(0x524F434B53544550L),
        JOINT_COVES(0x524F434B434F5645L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPTED,
        OUTSIDE_SUPPORT,
        WATER_RESERVATION,
        PROTECTED_SHORE,
        NO_ROCK_INTENT,
        LOW_EXPOSURE,
        INSUFFICIENT_RELIEF,
        OCCURRENCE,
        HEADROOM
    }

    /** A run's edit in a column: built solid or carved open. */
    public enum Edit {
        SOLID,
        VOID
    }

    /** Adapter values must come from WorldGenerationContext.terrainHeights, never a second datum. */
    public static final class Reference {
        public final double sea, waterTop, low, high, dropMin, dropMean, dropMax;
        public final double widthMin, widthMean, widthMax, beachMax;

        public Reference(Properties p) {
            sea = number(p, "sea");
            waterTop = number(p, "waterTop");
            low = number(p, "low");
            high = number(p, "high");
            dropMin = number(p, "scarp.magnitude.min");
            dropMean = number(p, "scarp.magnitude.mean");
            dropMax = number(p, "scarp.magnitude.max");
            widthMin = number(p, "scarp.width.min");
            widthMean = number(p, "scarp.width.mean");
            widthMax = number(p, "scarp.width.max");
            beachMax = number(p, "beach.width.max");
            if (waterTop + 1 != sea
                    || !(low < sea && sea < high)
                    || !(0 < dropMin && dropMin <= dropMean && dropMean <= dropMax)
                    || !(0 < widthMin && widthMin <= widthMean && widthMean <= widthMax)
                    || beachMax <= 0)
                throw new IllegalArgumentException("Invalid height reference");
        }
    }

    /** Dimensionless tunings; the families have independent occurrence, detail and width. */
    public static final class Tuning {
        public final Family family;
        public final double occurrence, widthScale, reliefScale, detail, coveDepth;
        public final double minimumRock, minimumExposure;

        public Tuning(Properties p, Family f) {
            family = f;
            String key = f.name() + ".";
            occurrence = bounded(p, key + "occurrence", 0, 1);
            widthScale = bounded(p, key + "widthScale", .5, 2);
            reliefScale = bounded(p, key + "reliefScale", .5, 2);
            detail = bounded(p, key + "detail", 0, .2);
            coveDepth = bounded(p, "coveDepth", .2, 1);
            minimumRock = bounded(p, "minimumRock", .1, 1);
            minimumExposure = bounded(p, "minimumExposure", 0, .9);
        }
    }

    /** Earlier immutable shoreline patch and conservative facts for its entire support.
     * Normal points inland. origin is absolute, and stableId belongs to the earlier coast owner.
     */
    public static final class Anchor {
        public final long seed, stableId;
        public final double x, z, nx, nz, minimumParent, maximumParent, foundationY;
        public final double rockFit, exposure;

        public Anchor(
                long seed,
                long stableId,
                double x,
                double z,
                double nx,
                double nz,
                double minimumParent,
                double maximumParent,
                double foundationY,
                double rockFit,
                double exposure) {
            if (!finite(x, z, nx, nz, minimumParent, maximumParent, foundationY, rockFit, exposure)
                    || Math.abs(nx * nx + nz * nz - 1) > 1e-8
                    || minimumParent > maximumParent
                    || rockFit < 0
                    || rockFit > 1
                    || exposure < 0
                    || exposure > 1) throw new IllegalArgumentException("Invalid immutable anchor");
            this.seed = seed;
            this.stableId = stableId;
            this.x = x;
            this.z = z;
            this.nx = nx;
            this.nz = nz;
            this.minimumParent = minimumParent;
            this.maximumParent = maximumParent;
            this.foundationY = foundationY;
            this.rockFit = rockFit;
            this.exposure = exposure;
        }
    }

    /** One snapshot from earlier maps. distance is signed inland; never inferred from height.
     * protectedWeight includes a caller-prepared transition skirt around protected shoreline.
     * waterReserved includes clearance skirts. No terrain/provider callback is accepted.
     */
    public static final class Input {
        public final double x, z, distance, parent, rockFit, exposure, protectedWeight;
        public final boolean marine, waterReserved;

        public Input(
                double x,
                double z,
                double distance,
                double parent,
                double rockFit,
                double exposure,
                double protectedWeight,
                boolean marine,
                boolean waterReserved) {
            if (!finite(x, z, distance, parent, rockFit, exposure, protectedWeight)
                    || rockFit < 0
                    || rockFit > 1
                    || exposure < 0
                    || exposure > 1
                    || protectedWeight < 0
                    || protectedWeight > 1)
                throw new IllegalArgumentException("Invalid earlier column");
            this.x = x;
            this.z = z;
            this.distance = distance;
            this.parent = parent;
            this.rockFit = rockFit;
            this.exposure = exposure;
            this.protectedWeight = protectedWeight;
            this.marine = marine;
            this.waterReserved = waterReserved;
        }
    }

    /** Caller-owned scratch: one per worker, overwritten in full on every query. */
    public static final class Column {
        public double height, target, weight, localU, localD;
        public Reason reason;
    }

    /** Immutable finite plan. No per-world caches, shared scratch or lazy mutation. */
    public static final class Plan {
        public final Family family;
        public final Anchor anchor;
        public final Reference ref;
        public final Tuning tuning;
        public final Reason admission;
        public final long seed;
        public final double halfAlong, inland, offshore, width, drop;

        private Plan(Family f, Anchor a, Reference r, Tuning t) {
            family = f;
            anchor = a;
            ref = r;
            tuning = t;
            seed = mix(a.seed ^ f.salt ^ mix(a.stableId));
            width = clamp(r.widthMean * t.widthScale, r.widthMin, r.widthMax);
            drop = clamp(r.dropMean * t.reliefScale * (.8 + .6 * unit(seed)), r.dropMin, r.dropMax);
            // Reference-scaled profile control, not measured edited footprint or noise wavelength.
            halfAlong = r.widthMax * 4;
            inland = r.widthMax * 2;
            offshore = r.beachMax * 1.25;
            Reason q = Reason.ACCEPTED;
            if (a.rockFit < t.minimumRock) q = Reason.NO_ROCK_INTENT;
            else if (a.exposure < t.minimumExposure) q = Reason.LOW_EXPOSURE;
            else if (a.minimumParent - r.sea < r.dropMin) q = Reason.INSUFFICIENT_RELIEF;
            else if (a.maximumParent > r.high || a.minimumParent < r.low || a.foundationY < r.low)
                q = Reason.HEADROOM;
            else if (unit(seed + 6) >= t.occurrence) q = Reason.OCCURRENCE;
            admission = q;
        }
    }

    public static Plan prepare(Family family, Anchor earlier, Reference reference, Tuning tuning) {
        if (tuning.family != family)
            throw new IllegalArgumentException("Tuning belongs to another family");
        return new Plan(family, earlier, reference, tuning);
    }

    /** Samples one column's highest solid Y, with no neighbor reads. */
    public static void sample(Plan p, Input i, Column o) {
        double x = i.x, z = i.z, distance = i.distance, parent = i.parent;
        double rock = i.rockFit, exposure = i.exposure, protection = i.protectedWeight;
        boolean marine = i.marine;
        reset(p, x, z, parent, o);
        o.reason = p.admission;
        if (p.admission != Reason.ACCEPTED) return;
        if (Math.abs(o.localU) >= p.halfAlong
                || o.localD <= -p.offshore
                || o.localD >= p.inland
                || distance <= -p.offshore
                || distance >= p.inland) {
            o.reason = Reason.OUTSIDE_SUPPORT;
            return;
        }
        if (i.waterReserved) {
            o.reason = Reason.WATER_RESERVATION;
            return;
        }
        if (protection >= 1) {
            o.reason = Reason.PROTECTED_SHORE;
            return;
        }
        if (rock < p.tuning.minimumRock) {
            o.reason = Reason.NO_ROCK_INTENT;
            return;
        }
        if (exposure < p.tuning.minimumExposure) {
            o.reason = Reason.LOW_EXPOSURE;
            return;
        }
        if ((!marine && parent < p.anchor.minimumParent)
                || (marine && parent < p.anchor.foundationY)
                || parent > p.anchor.maximumParent
                || (marine && parent > p.ref.waterTop - 1)) {
            o.reason = Reason.HEADROOM;
            return;
        }
        double side = 1 - smooth((Math.abs(o.localU) / p.halfAlong - .72) / .28);
        double end =
                o.localD < 0
                        ? 1 - smooth((-o.localD / p.offshore - .65) / .35)
                        : 1 - smooth((o.localD / p.inland - .65) / .35);
        double shore =
                distance < 0
                        ? 1 - smooth((-distance / p.offshore - .65) / .35)
                        : 1 - smooth((distance / p.inland - .65) / .35);
        o.weight = side * end * shore * (1 - protection);
        // Continuous earlier eligibility fields fade height edits at their admitted edge.
        o.weight *= fit(rock, p.tuning.minimumRock) * fit(exposure, p.tuning.minimumExposure);
        double noise = noise(p.seed, x / p.width, z / p.width);
        double along = noise(p.seed + 13, o.localU / (p.width * 2), .37);
        double toe = p.ref.sea + Math.max(0, p.drop * p.tuning.detail * (.6 + .4 * noise));
        double target;
        if (marine) {
            // Never invent emergent land or assign water. Explicit marine ownership wins.
            double platform =
                    p.ref.waterTop
                            - 1
                            - .045 * Math.abs(distance)
                            + p.drop * p.tuning.detail * .3 * noise;
            target = Math.min(p.ref.waterTop - 1, platform);
        } else if (p.family == Family.CLIFF_PLATFORM) {
            double retreat = p.width * (.9 + .35 * along);
            double face = p.width * (.22 + .1 * unit(p.seed + 17));
            double rise = smooth((distance - retreat) / face);
            double crest = Math.min(parent, p.ref.sea + p.drop * (1 + .10 * along));
            target = toe + (crest - toe) * rise;
            target += p.drop * p.tuning.detail * .5 * noise * rise;
        } else if (p.family == Family.STEPPED_HEADLAND) {
            // Three irregularly spaced scarps, tilted and laterally offset. No floor(Y/k).
            double shoulder = p.width * (.3 + .6 * along);
            double v = (distance - shoulder) / p.width;
            double w1 = .17 + .12 * unit(p.seed + 21), w2 = .26 + .15 * unit(p.seed + 22);
            double a = .2 + .15 * unit(p.seed + 23),
                    b = .9 + .45 * unit(p.seed + 24),
                    c = 1.8 + .5 * unit(p.seed + 25);
            double rise =
                    w1 * smooth((v - a) / .22)
                            + w2 * smooth((v - b) / .25)
                            + (1 - w1 - w2) * smooth((v - c) / .35);
            double crest = Math.min(parent, p.ref.sea + p.drop * (1 + .14 * along));
            target = toe + (crest - toe) * rise + p.drop * p.tuning.detail * noise * rise;
        } else {
            // A fixed three-lobe network with oblique joint slots; finite, no recursive cracks.
            double retreat = .45 * p.width;
            for (int k = 0; k < 3; k++) {
                double center =
                        (k - 1) * p.halfAlong * .48 + (unit(p.seed + 31 + k) - .5) * p.width;
                double radius = p.width * (.8 + .8 * unit(p.seed + 41 + k));
                double s = (o.localU - center) / radius;
                retreat +=
                        p.width * p.tuning.coveDepth * (1 + .4 * unit(p.seed + 51 + k)) * bump(s);
            }
            double face = smooth((distance - retreat) / (.3 * p.width));
            double joint = noise(p.seed + 61, (o.localU + .55 * distance) / (p.width * .7), .13);
            double slot =
                    smooth((joint - .38) / .22) * bump((distance - p.width) / (p.width * 1.7));
            double crest = Math.min(parent, p.ref.sea + p.drop * (1 + .15 * along));
            target = toe + (crest - toe) * face - p.drop * .42 * slot * face;
            target += p.drop * p.tuning.detail * noise * face;
        }
        // Relief and upper envelope are inherited. Land edits never move the marine boundary.
        target =
                marine
                        ? Math.min(target, p.ref.waterTop - 1)
                        : Math.max(p.ref.sea, Math.min(parent, target));
        o.target = target;
        o.height = parent + (target - parent) * o.weight;
    }

    private static void reset(Plan p, double x, double z, double parent, Column o) {
        o.height = o.target = parent;
        o.weight = 0;
        o.reason = Reason.OUTSIDE_SUPPORT;
        o.localU = (x - p.anchor.x) * (-p.anchor.nz) + (z - p.anchor.z) * p.anchor.nx;
        o.localD = (x - p.anchor.x) * p.anchor.nx + (z - p.anchor.z) * p.anchor.nz;
    }

    static double number(Properties p, String key) {
        double v = Double.parseDouble(p.getProperty(key));
        if (!Double.isFinite(v)) throw new IllegalArgumentException(key);
        return v;
    }

    static double bounded(Properties p, String key, double lo, double hi) {
        double v = number(p, key);
        if (v < lo || v > hi) throw new IllegalArgumentException(key);
        return v;
    }

    static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }

    static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    static double smooth(double x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    static double fit(double value, double threshold) {
        return threshold == 1
                ? (value == 1 ? 1 : 0)
                : smooth((value - threshold) / Math.min(.2, 1 - threshold));
    }

    static double bump(double x) {
        double a = Math.max(0, 1 - x * x);
        return a * a;
    }

    // SplitMix64 finalizer, public-domain primitive by Sebastiano Vigna; see SOURCES.md.
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static double unit(long s) {
        return (mix(s) >>> 11) * 0x1.0p-53;
    }

    static double noise(long seed, double x, double z) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double a = smooth(x - ix), b = smooth(z - iz);
        double v00 = unit(seed ^ mix(ix) ^ Long.rotateLeft(mix(iz), 27));
        double v10 = unit(seed ^ mix(ix + 1) ^ Long.rotateLeft(mix(iz), 27));
        double v01 = unit(seed ^ mix(ix) ^ Long.rotateLeft(mix(iz + 1), 27));
        double v11 = unit(seed ^ mix(ix + 1) ^ Long.rotateLeft(mix(iz + 1), 27));
        return 2 * ((v00 + (v10 - v00) * a) * (1 - b) + (v01 + (v11 - v01) * a) * b) - 1;
    }
}
