// SPDX-License-Identifier: MIT
// Riven integration, 2026-10-20: added separate shared-anchor column proposals,
// certificates and integer-only plans. The continuous API and shape arithmetic remain.
package cc.orthrus.riven.contrib.volcanic;

/** Independently authored, finite heightfield geometry. No Minecraft or loader dependencies. */
public final class VolcanicKernels {
    private VolcanicKernels() {}

    public static final double CELL = 1536.0;
    public static final double COORDINATE_LIMIT = 30000000.0;
    public static final String REVISION = "volcanic-geometry-2";
    public static final long SELECT_SALT = 0x524956564F4C3031L;

    public enum Family {
        CONE(0x56434F4E45303031L, .35),
        CALDERA(0x5643414C44303031L, .28),
        FISSURE(0x5646495353303031L, .50),
        LOBE_PLATEAU(0x56504C4154303031L, .20);
        public final long salt;
        public final double maxParentSlope;

        Family(long salt, double slope) {
            this.salt = salt;
            maxParentSlope = slope;
        }
    }

    public enum Reason {
        ACCEPTED,
        OCCURRENCE,
        MARINE,
        WATER_RESERVATION,
        PROTECTED_SHORE,
        LOW_POTENTIAL,
        STEEP_PARENT,
        TOP_HEADROOM,
        BOTTOM_HEADROOM,
        UNKNOWN_SUPPORT,
        WORLD_BOUNDS
    }

    private static final Family[] FAMILIES = Family.values();

    public static final class Range {
        public final double min, mean, max;

        public Range(double min, double mean, double max) {
            if (!finite(min, mean, max) || min <= 0 || min > mean || mean > max)
                throw new IllegalArgumentException("Invalid reference range");
            this.min = min;
            this.mean = mean;
            this.max = max;
        }

        double limit(double value) {
            return clamp(value, min, max);
        }
    }

    /** Construct from WorldGenerationContext.terrainHeights, never another height definition. */
    public static final class Reference {
        public final Range coneRise,
                coneWidth,
                craterCut,
                craterWidth,
                spurRise,
                spurWidth,
                plateauRise,
                plateauWidth;
        public final double lowestSurface, highestSurface;
        public final String fingerprint;

        public Reference(
                Range cr,
                Range cw,
                Range cut,
                Range cavityWidth,
                Range sr,
                Range sw,
                Range pr,
                Range pw,
                double low,
                double high,
                String fingerprint) {
            if (cr == null
                    || cw == null
                    || cut == null
                    || cavityWidth == null
                    || sr == null
                    || sw == null
                    || pr == null
                    || pw == null
                    || !finite(low, high)
                    || low >= high
                    || fingerprint == null)
                throw new IllegalArgumentException("Incomplete height reference");
            coneRise = cr;
            coneWidth = cw;
            craterCut = cut;
            craterWidth = cavityWidth;
            spurRise = sr;
            spurWidth = sw;
            plateauRise = pr;
            plateauWidth = pw;
            lowestSurface = low;
            highestSurface = high;
            this.fingerprint = fingerprint;
        }
    }

    public static final class Tuning {
        public final double size, relief, crater, irregularity, occurrence, minimumPotential;

        public Tuning(
                double size,
                double relief,
                double crater,
                double irregularity,
                double occurrence,
                double minimumPotential) {
            if (!finite(size, relief, crater, irregularity, occurrence, minimumPotential)
                    || size < .5
                    || size > 1.3
                    || relief < .5
                    || relief > 1.5
                    || crater < .5
                    || crater > 1.5
                    || irregularity < 0
                    || irregularity > 1
                    || occurrence < 0
                    || occurrence > 1
                    || minimumPotential < 0
                    || minimumPotential > 1)
                throw new IllegalArgumentException("Tuning outside bounded supported range");
            this.size = size;
            this.relief = relief;
            this.crater = crater;
            this.irregularity = irregularity;
            this.occurrence = occurrence;
            this.minimumPotential = minimumPotential;
        }

        public static Tuning defaults() {
            return new Tuning(1, 1, 1, .75, .65, .45);
        }
    }

    /** Conservative facts over the ENTIRE closed support disk, including the blend skirt.
     * This is a proof supplied by the caller, not a center-only observation or sampled guess. */
    public static final class Certificate {
        public final boolean marine, waterReserved, protectedShore;
        public final double minParent, maxParent, maxSlope, minVolcanicPotential;

        public Certificate(
                boolean marine,
                boolean waterReserved,
                boolean protectedShore,
                double minParent,
                double maxParent,
                double maxSlope,
                double potential) {
            if (!finite(minParent, maxParent, maxSlope, potential)
                    || minParent > maxParent
                    || maxSlope < 0
                    || potential < 0
                    || potential > 1)
                throw new IllegalArgumentException("Invalid immutable support certificate");
            this.marine = marine;
            this.waterReserved = waterReserved;
            this.protectedShore = protectedShore;
            this.minParent = minParent;
            this.maxParent = maxParent;
            this.maxSlope = maxSlope;
            minVolcanicPotential = potential;
        }
    }

    public interface Earlier {
        /** Pure, bounded, thread-safe lookup into immutable earlier facts. No final terrain query.
         * Return null when conservative support bounds are unavailable: the candidate stays empty. */
        Certificate certify(double absoluteX, double absoluteZ, double supportRadius);
    }

    public static final class Decision {
        public final Reason reason;
        public final Plan plan;

        private Decision(Reason reason, Plan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    /** Immutable, private geometry arrays; safely share between generation threads. */
    public static final class Plan {
        public final Family family;
        public final long seed, cellX, cellZ;
        public final double x, z, radius, rise, cut, shortWidth, craterWidth;
        private final double cos, sin, phase, irregularity;
        private final double[] px, pz, rx, rz, level;

        private Plan(
                Family family,
                long seed,
                long cx,
                long cz,
                double x,
                double z,
                double diameter,
                double rise,
                double cut,
                double shortWidth,
                double craterWidth,
                double irregularity) {
            this.family = family;
            this.seed = seed;
            cellX = cx;
            cellZ = cz;
            this.x = x;
            this.z = z;
            radius = diameter / 2;
            this.rise = rise;
            this.cut = cut;
            this.shortWidth = shortWidth;
            this.craterWidth = craterWidth;
            this.irregularity = irregularity;
            double angle = unit(seed, 10) * 2 * StrictMath.PI;
            cos = StrictMath.cos(angle);
            sin = StrictMath.sin(angle);
            phase = unit(seed, 11) * 6.283185307179586;
            int count = family == Family.FISSURE ? 6 : 7;
            px = new double[count];
            pz = new double[count];
            rx = new double[count];
            rz = new double[count];
            level = new double[count];
            for (int i = 0; i < count; i++) {
                double a = unit(seed, 40 + i * 7) * 6.283185307179586;
                double d = .48 * StrictMath.sqrt(unit(seed, 41 + i * 7));
                px[i] = d * StrictMath.cos(a);
                pz[i] = d * StrictMath.sin(a);
                rx[i] = .28 + .23 * unit(seed, 42 + i * 7);
                rz[i] = .24 + .19 * unit(seed, 43 + i * 7);
                level[i] = .58 + .28 * unit(seed, 44 + i * 7);
                if (family == Family.FISSURE) {
                    px[i] = -.66 + 1.32 * i / (count - 1);
                    pz[i] = (.22 * unit(seed, 45 + i * 7) - .11) * irregularity;
                }
            }
        }

        /** Signed relative highest-solid Y contribution, exactly zero at/outside radius.
         * No allocation, state mutation, fluid, material, cave or neighboring chunk operation. */
        public double delta(double absoluteX, double absoluteZ) {
            if (!Double.isFinite(absoluteX) || !Double.isFinite(absoluteZ))
                throw new IllegalArgumentException("Nonfinite coordinate");
            double dx = (absoluteX - x) / radius, dz = (absoluteZ - z) / radius;
            double s = StrictMath.sqrt(dx * dx + dz * dz);
            if (s >= 1) return 0;
            double u = cos * dx + sin * dz, v = -sin * dx + cos * dz;
            double angle = StrictMath.atan2(v, u);
            double warp =
                    1
                            + irregularity
                                    * (.08 * StrictMath.sin(3 * angle + phase)
                                            + .04 * StrictMath.sin(5 * angle - phase));
            double r = StrictMath.sqrt(u * u + v * v * 1.13) / warp;
            double rough = noise(seed, u * 11 + 13, v * 11 - 7);
            double up = 0, down = 0;
            switch (family) {
                case CONE:
                    double craterRadius = craterWidth / (2 * radius);
                    // An inner shoulder avoids retaining a mathematical apex inside the crater.
                    double cone =
                            StrictMath.pow(
                                    positive(1 - StrictMath.max(r, craterRadius * .75) / .90),
                                    1.35);
                    up = rise * cone * (.90 + .10 * rough);
                    double cr =
                            StrictMath.sqrt(
                                    sq(u - .055 * irregularity) + sq(v + .035 * irregularity));
                    down = cut * bump(cr / craterRadius);
                    // Shallow flank flutes taper at summit; all factors remain in [0,1].
                    up *=
                            1
                                    - irregularity
                                            * .16
                                            * (.5 + .5 * StrictMath.sin(9 * angle + phase + 2 * r))
                                            * bump((r - .45) / .40);
                    break;
                case CALDERA:
                    double basinRadius = craterWidth / (2 * radius);
                    double rim = bump((r - basinRadius - .06) / .20);
                    up =
                            rise
                                    * (.70 * rim * (.90 + .10 * StrictMath.sin(3 * angle + phase))
                                            + .12 * bump(r));
                    up += rise * .10 * bump(StrictMath.sqrt(sq(u - .18) + sq(v + .08)) / .18);
                    double basin = 1 - smooth(basinRadius * .65, basinRadius, r);
                    down = cut * basin;
                    for (int i = 0; i < px.length; i++) {
                        double d =
                                StrictMath.sqrt(sq((u - px[i]) / rx[i]) + sq((v - pz[i]) / rz[i]));
                        down = StrictMath.max(down, cut * level[i] * (1 - smooth(.52, 1, d)));
                    }
                    up *= .96 + .04 * rough;
                    break;
                case FISSURE:
                    double distance = 10;
                    for (int i = 0; i < px.length - 1; i++)
                        distance =
                                StrictMath.min(
                                        distance,
                                        segmentDistance(u, v, px[i], pz[i], px[i + 1], pz[i + 1]));
                    double w = shortWidth / (2 * radius);
                    up = rise * .66 * bump(distance / w) * (.85 + .15 * rough);
                    for (int i = 0; i < px.length; i++) {
                        double d = StrictMath.sqrt(sq(u - px[i]) + sq(v - pz[i]));
                        up = StrictMath.max(up, rise * level[i] * bump(d / (w * .95)));
                        down = StrictMath.max(down, cut * .55 * bump(d / (w * .22)));
                    }
                    break;
                case LOBE_PLATEAU:
                    for (int i = 0; i < px.length; i++) {
                        double bend = irregularity * .055 * noise(seed + i * 31, u * 6, v * 6);
                        double d =
                                StrictMath.sqrt(
                                        sq((u - px[i] + bend) / rx[i]) + sq((v - pz[i]) / rz[i]));
                        double roof = level[i] + .05 * noise(seed + i, u * 7, v * 7);
                        up = StrictMath.max(up, rise * roof * (1 - smooth(.58, 1, d)));
                        // Three finite collapsed roof basins, not caves or active lava lakes.
                        if (i < 3)
                            down =
                                    StrictMath.max(
                                            down,
                                            cut
                                                    * bump(
                                                            StrictMath.sqrt(
                                                                            sq(u - px[i] * .55)
                                                                                    + sq(
                                                                                            v
                                                                                                    - pz[
                                                                                                                    i]
                                                                                                            * .55))
                                                                    / .14));
                    }
                    break;
                default:
                    throw new AssertionError(family);
            }
            return (up - down) * (1 - smooth(.82, 1, s));
        }

        public boolean contains(double absoluteX, double absoluteZ) {
            return sq(absoluteX - x) + sq(absoluteZ - z) < radius * radius;
        }
    }

    /** Family selection depends solely on world seed + absolute owner cell. */
    public static Family selectedFamily(long worldSeed, long cellX, long cellZ) {
        return FAMILIES[(int) (hash(worldSeed ^ SELECT_SALT, cellX, cellZ) & 3)];
    }

    public static Decision plan(
            long worldSeed,
            long cellX,
            long cellZ,
            Family family,
            Reference ref,
            Tuning tuning,
            Earlier earlier) {
        if (family == null
                || ref == null
                || tuning == null
                || earlier == null
                || StrictMath.abs((double) cellX) > 20000
                || StrictMath.abs((double) cellZ) > 20000)
            throw new IllegalArgumentException("Invalid plan input / Minecraft coordinate extent");
        long seed = hash(worldSeed ^ family.salt, cellX, cellZ);
        if (unit(seed, 1) >= tuning.occurrence) return new Decision(Reason.OCCURRENCE, null);
        double x = (cellX + .5) * CELL + (unit(seed, 2) - .5) * CELL * .24;
        double z = (cellZ + .5) * CELL + (unit(seed, 3) - .5) * CELL * .24;
        double variation = .85 + .30 * unit(seed, 4);
        Range riseRange =
                family == Family.FISSURE
                        ? ref.spurRise
                        : family == Family.LOBE_PLATEAU ? ref.plateauRise : ref.coneRise;
        Range widthRange = family == Family.LOBE_PLATEAU ? ref.plateauWidth : ref.coneWidth;
        double widthBase =
                family == Family.CALDERA
                        ? ref.coneWidth.mean * 2
                        : family == Family.FISSURE ? ref.spurWidth.mean * 6 : widthRange.mean;
        double diameter =
                StrictMath.min(1024, widthRange.limit(widthBase * tuning.size * variation));
        double rise = riseRange.limit(riseRange.mean * tuning.relief * (.85 + .30 * unit(seed, 5)));
        double cut =
                ref.craterCut.limit(ref.craterCut.mean * tuning.crater * (.8 + .4 * unit(seed, 6)));
        double craterWidth = ref.craterWidth.limit(ref.craterWidth.mean * tuning.size * variation);
        if (family == Family.CALDERA) craterWidth = ref.craterWidth.limit(diameter * .48);
        // Cone crater fits its host; reference minima take precedence over desired proportions.
        craterWidth = StrictMath.min(craterWidth, diameter * .45);
        double shortWidth = ref.spurWidth.limit(ref.spurWidth.mean * tuning.size * variation);
        if (StrictMath.abs(x) + diameter / 2 > COORDINATE_LIMIT
                || StrictMath.abs(z) + diameter / 2 > COORDINATE_LIMIT)
            return new Decision(Reason.WORLD_BOUNDS, null);
        Certificate c = earlier.certify(x, z, diameter / 2);
        if (c == null) return new Decision(Reason.UNKNOWN_SUPPORT, null);
        Reason reason =
                c.marine
                        ? Reason.MARINE
                        : c.waterReserved
                                ? Reason.WATER_RESERVATION
                                : c.protectedShore
                                        ? Reason.PROTECTED_SHORE
                                        : c.minVolcanicPotential < tuning.minimumPotential
                                                ? Reason.LOW_POTENTIAL
                                                : c.maxSlope > family.maxParentSlope
                                                        ? Reason.STEEP_PARENT
                                                        : c.maxParent + rise > ref.highestSurface
                                                                ? Reason.TOP_HEADROOM
                                                                : c.minParent - cut
                                                                                < ref.lowestSurface
                                                                        ? Reason.BOTTOM_HEADROOM
                                                                        : Reason.ACCEPTED;
        if (reason != Reason.ACCEPTED) return new Decision(reason, null);
        return new Decision(
                reason,
                new Plan(
                        family,
                        seed,
                        cellX,
                        cellZ,
                        x,
                        z,
                        diameter,
                        rise,
                        cut,
                        shortWidth,
                        craterWidth,
                        tuning.irregularity));
    }

    /** Exactly one owner plan, at most one certificate. No recursive neighbor selection.
     * Every support fits its cell: jitter184.32 + radius512 < cell half-width768.
     * For bulk sampling retain immutable admitted plans in caller-owned batch storage. */
    public static double delta(
            long seed, double x, double z, Reference ref, Tuning tuning, Earlier earlier) {
        if (!Double.isFinite(x)
                || !Double.isFinite(z)
                || StrictMath.abs(x) > COORDINATE_LIMIT
                || StrictMath.abs(z) > COORDINATE_LIMIT)
            throw new IllegalArgumentException("Outside supported absolute coordinate extent");
        long cx = (long) StrictMath.floor(x / CELL), cz = (long) StrictMath.floor(z / CELL);
        Decision d = plan(seed, cx, cz, selectedFamily(seed, cx, cz), ref, tuning, earlier);
        return d.plan == null ? 0 : d.plan.delta(x, z);
    }

    /** Native-column proposal. This does not claim a continuous support certificate. */
    public static final class ColumnProposal {
        public final Family family;
        public final long seed, cellX, cellZ;
        public final double x, z, radius, rise, cut, shortWidth, craterWidth;
        private final Reference reference;
        private final Tuning tuning;

        private ColumnProposal(
                long worldSeed,
                long cx,
                long cz,
                Family family,
                double x,
                double z,
                Reference ref,
                Tuning tuning) {
            if (family == null
                    || ref == null
                    || tuning == null
                    || !finite(x, z)
                    || StrictMath.abs((double) cx) > 100000000
                    || StrictMath.abs((double) cz) > 100000000)
                throw new IllegalArgumentException("Invalid native-column proposal");
            this.family = family;
            cellX = cx;
            cellZ = cz;
            this.x = x;
            this.z = z;
            reference = ref;
            this.tuning = tuning;
            seed = hash(worldSeed ^ family.salt, cx, cz);
            // The same dimension recipe as the original continuous planner. Shape
            // sampling below uses its existing Plan implementation without changes.
            double variation = .85 + .30 * unit(seed, 4);
            Range riseRange =
                    family == Family.FISSURE
                            ? ref.spurRise
                            : family == Family.LOBE_PLATEAU ? ref.plateauRise : ref.coneRise;
            Range widthRange = family == Family.LOBE_PLATEAU ? ref.plateauWidth : ref.coneWidth;
            double widthBase =
                    family == Family.CALDERA
                            ? ref.coneWidth.mean * 2
                            : family == Family.FISSURE ? ref.spurWidth.mean * 6 : widthRange.mean;
            double diameter =
                    StrictMath.min(1024, widthRange.limit(widthBase * tuning.size * variation));
            radius = diameter / 2;
            rise = riseRange.limit(riseRange.mean * tuning.relief * (.85 + .30 * unit(seed, 5)));
            cut =
                    ref.craterCut.limit(
                            ref.craterCut.mean * tuning.crater * (.8 + .4 * unit(seed, 6)));
            double cavity = ref.craterWidth.limit(ref.craterWidth.mean * tuning.size * variation);
            if (family == Family.CALDERA) cavity = ref.craterWidth.limit(diameter * .48);
            craterWidth = StrictMath.min(cavity, diameter * .45);
            shortWidth = ref.spurWidth.limit(ref.spurWidth.mean * tuning.size * variation);
        }
    }

    public static ColumnProposal proposeColumnsAt(
            long worldSeed,
            long cx,
            long cz,
            Family family,
            double x,
            double z,
            Reference ref,
            Tuning tuning) {
        return new ColumnProposal(worldSeed, cx, cz, family, x, z, ref, tuning);
    }

    public static double maximumColumnRadius(Family family, Reference ref, Tuning tuning) {
        Range width = family == Family.LOBE_PLATEAU ? ref.plateauWidth : ref.coneWidth;
        double base =
                family == Family.CALDERA
                        ? ref.coneWidth.mean * 2
                        : family == Family.FISSURE ? ref.spurWidth.mean * 6 : width.mean;
        return StrictMath.min(1024, width.limit(base * tuning.size * 1.15)) / 2;
    }

    /** Cheap gates before the host requests any earlier terrain. */
    public static Reason columnPrerequisite(ColumnProposal p) {
        if (unit(p.seed, 1) >= p.tuning.occurrence) return Reason.OCCURRENCE;
        if (StrictMath.abs(p.x) + p.radius > COORDINATE_LIMIT
                || StrictMath.abs(p.z) + p.radius > COORDINATE_LIMIT) return Reason.WORLD_BOUNDS;
        return Reason.ACCEPTED;
    }

    /** Exhaustive native-column facts for this exact immutable proposal. Include every
     * integer column in its closed disk. Slope is the host's declared neighboring-column
     * rise/run diagnostic, not a derivative bound on the original continuous parent.
     * This type cannot satisfy the stronger original Certificate/Earlier contract. */
    public static final class ColumnCertificate {
        private final ColumnProposal proposal;
        public final boolean marine, waterReserved, protectedShore;
        public final double minParent, maxParent, maxColumnSlope, minVolcanicPotential;
        public final int columns;

        public ColumnCertificate(
                ColumnProposal p,
                boolean marine,
                boolean water,
                boolean shore,
                double min,
                double max,
                double slope,
                double potential,
                int columns) {
            if (p == null
                    || !finite(min, max, slope, potential)
                    || min > max
                    || slope < 0
                    || potential < 0
                    || potential > 1
                    || columns <= 0)
                throw new IllegalArgumentException("Invalid native-column certificate");
            proposal = p;
            this.marine = marine;
            waterReserved = water;
            protectedShore = shore;
            minParent = min;
            maxParent = max;
            maxColumnSlope = slope;
            minVolcanicPotential = potential;
            this.columns = columns;
        }
    }

    /** Geometry can only be evaluated on the certified native integer-column domain. */
    public static final class ColumnPlan {
        public final ColumnProposal proposal;
        private final Plan geometry;

        private ColumnPlan(ColumnProposal p) {
            proposal = p;
            geometry =
                    new Plan(
                            p.family,
                            p.seed,
                            p.cellX,
                            p.cellZ,
                            p.x,
                            p.z,
                            p.radius * 2,
                            p.rise,
                            p.cut,
                            p.shortWidth,
                            p.craterWidth,
                            p.tuning.irregularity);
        }

        public double delta(int x, int z) {
            return geometry.delta(x, z);
        }

        public boolean contains(int x, int z) {
            return geometry.contains(x, z);
        }
    }

    public static final class ColumnDecision {
        public final Reason reason;
        public final ColumnPlan plan;

        private ColumnDecision(Reason reason, ColumnPlan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    public static ColumnDecision selectColumns(ColumnProposal p, ColumnCertificate c) {
        Reason first = columnPrerequisite(p);
        if (first != Reason.ACCEPTED) return new ColumnDecision(first, null);
        if (c == null) return new ColumnDecision(Reason.UNKNOWN_SUPPORT, null);
        if (c.proposal != p)
            throw new IllegalArgumentException(
                    "Certificate belongs to a different column proposal");
        Reason reason =
                c.marine
                        ? Reason.MARINE
                        : c.waterReserved
                                ? Reason.WATER_RESERVATION
                                : c.protectedShore
                                        ? Reason.PROTECTED_SHORE
                                        : c.minVolcanicPotential < p.tuning.minimumPotential
                                                ? Reason.LOW_POTENTIAL
                                                : c.maxColumnSlope > p.family.maxParentSlope
                                                        ? Reason.STEEP_PARENT
                                                        : c.maxParent + p.rise
                                                                        > p.reference.highestSurface
                                                                ? Reason.TOP_HEADROOM
                                                                : c.minParent - p.cut
                                                                                < p.reference
                                                                                        .lowestSurface
                                                                        ? Reason.BOTTOM_HEADROOM
                                                                        : Reason.ACCEPTED;
        return new ColumnDecision(reason, reason == Reason.ACCEPTED ? new ColumnPlan(p) : null);
    }

    private static boolean finite(double... values) {
        for (double v : values) if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        return true;
    }

    private static double segmentDistance(
            double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double t = clamp(((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz), 0, 1);
        return StrictMath.sqrt(sq(x - ax - t * dx) + sq(z - az - t * dz));
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double positive(double x) {
        return StrictMath.max(0, x);
    }

    private static double clamp(double x, double a, double b) {
        return StrictMath.max(a, StrictMath.min(b, x));
    }

    private static double smooth(double lo, double hi, double x) {
        double t = clamp((x - lo) / (hi - lo), 0, 1);
        return t * t * t * (10 + t * (-15 + 6 * t));
    }

    private static double bump(double x) {
        return 1 - smooth(0, 1, StrictMath.abs(x));
    }

    private static long mix(long x) {
        x ^= x >>> 29;
        x *= 0x319642B2D24D8EC3L;
        x ^= x >>> 32;
        x *= 0x96DE1B173F119089L;
        return x ^ (x >>> 28);
    }

    private static long hash(long seed, long x, long z) {
        return mix(seed ^ mix(x + 0x13579BDFL) ^ mix(z + 0x2468ACE0L));
    }

    private static double unit(long seed, long salt) {
        return (mix(seed + salt * 0x632BE59BD9B4E019L) >>> 11) * 0x1.0p-53;
    }

    private static double noise(long seed, double x, double z) {
        long ix = (long) StrictMath.floor(x), iz = (long) StrictMath.floor(z);
        double tx = smooth(0, 1, x - ix), tz = smooth(0, 1, z - iz);
        double a = (hash(seed, ix, iz) >>> 11) * 0x1.0p-53;
        double b = (hash(seed, ix + 1, iz) >>> 11) * 0x1.0p-53;
        double c = (hash(seed, ix, iz + 1) >>> 11) * 0x1.0p-53;
        double d = (hash(seed, ix + 1, iz + 1) >>> 11) * 0x1.0p-53;
        return 2 * ((a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz) - 1;
    }
}
