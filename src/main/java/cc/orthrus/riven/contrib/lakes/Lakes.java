/* SPDX-License-Identifier: MIT
 * Independently authored Riven contribution, 2026. See the original contribution package.
 * Riven integration changes: records expanded to immutable Java 8 classes; switch
 * syntax lowered without changing geometry arithmetic. Optional aligned entry points
 * let an earlier river supply the basin/outlet axis. MIT remains applicable.
 * Packaged license: META-INF/licenses/lakes/MIT.txt.
 */
package cc.orthrus.riven.contrib.lakes;

/** Finite, cut-only surface basins. No world access, caches, callbacks or chunk writes. */
public final class Lakes {
    private Lakes() {}

    public enum Family {
        HARMONIC(0x4c414b4548415231L),
        LOBES(0x4c414b454c4f4231L),
        SWEPT(0x4c414b4553575031L),
        CELLULAR(0x4c414b4543454c31L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPT,
        NO_SUPPLY,
        MARINE,
        PROTECTED,
        RESERVATION,
        UNCERTIFIED_SUPPORT,
        LOW_RIM,
        EXCESS_CUT,
        ALLOCATION,
        UNSEALED_FLOOR,
        RECEIVER
    }

    /** Caller maps WorldGenerationContext.terrainHeights; values are NOT Earth scales. */
    public static final class Reference {
        public final String id;
        public final double widthMin;
        public final double widthMax;
        public final double depthMin;
        public final double depthMax;
        public final int minSolidY;
        public final int maxSolidY;

        public Reference(
                String id,
                double widthMin,
                double widthMax,
                double depthMin,
                double depthMax,
                int minSolidY,
                int maxSolidY) {
            this.id = id;
            this.widthMin = widthMin;
            this.widthMax = widthMax;
            this.depthMin = depthMin;
            this.depthMax = depthMax;
            this.minSolidY = minSolidY;
            this.maxSolidY = maxSolidY;
            finite(widthMin, widthMax, depthMin, depthMax);
            if (id == null
                    || widthMin <= 0
                    || widthMax < widthMin
                    || depthMin < 1
                    || depthMax < depthMin
                    || minSolidY >= maxSolidY) throw new IllegalArgumentException("reference");
        }

        public String id() {
            return id;
        }

        public double widthMin() {
            return widthMin;
        }

        public double widthMax() {
            return widthMax;
        }

        public double depthMin() {
            return depthMin;
        }

        public double depthMax() {
            return depthMax;
        }

        public int minSolidY() {
            return minSolidY;
        }

        public int maxSolidY() {
            return maxSolidY;
        }
    }

    /** Width is a flank-inclusive short-axis envelope, not a noise wavelength.
     * aspect <= 2.5, detail <= .20, shelfFraction <= .55, islandRadius <= .15.
     * Depth and outletDepth are whole occupied-water blocks: fractional caps
     * would otherwise be exceeded when the highest solid is rounded down.
     * islandRadius=0 disables islands; outletWidth=0 requests a closed body.
     */
    public static final class Tuning {
        public final double width;
        public final double depth;
        public final double aspect;
        public final double detail;
        public final double shelfFraction;
        public final double islandRadius;
        public final double skirtFraction;
        public final double freeboard;
        public final double maxCut;
        public final double outletWidth;
        public final double outletDepth;

        public Tuning(
                double width,
                double depth,
                double aspect,
                double detail,
                double shelfFraction,
                double islandRadius,
                double skirtFraction,
                double freeboard,
                double maxCut,
                double outletWidth,
                double outletDepth) {
            this.width = width;
            this.depth = depth;
            this.aspect = aspect;
            this.detail = detail;
            this.shelfFraction = shelfFraction;
            this.islandRadius = islandRadius;
            this.skirtFraction = skirtFraction;
            this.freeboard = freeboard;
            this.maxCut = maxCut;
            this.outletWidth = outletWidth;
            this.outletDepth = outletDepth;
            finite(
                    width,
                    depth,
                    aspect,
                    detail,
                    shelfFraction,
                    islandRadius,
                    skirtFraction,
                    freeboard,
                    maxCut,
                    outletWidth,
                    outletDepth);
            if (width <= 0
                    || depth < 1
                    || depth != StrictMath.floor(depth)
                    || aspect < 1
                    || aspect > 2.5
                    || detail < 0
                    || detail > .20
                    || shelfFraction < .08
                    || shelfFraction > .55
                    || islandRadius < 0
                    || islandRadius > .15
                    || skirtFraction < .04
                    || skirtFraction > .15
                    || freeboard < 2
                    || maxCut < depth
                    || outletWidth < 0
                    || outletDepth < 1
                    || outletDepth > 4
                    || outletDepth != StrictMath.floor(outletDepth)
                    || (outletWidth > 0 && (outletWidth < 2 || outletWidth > width * .15)))
                throw new IllegalArgumentException("tuning bounds");
        }

        public double width() {
            return width;
        }

        public double depth() {
            return depth;
        }

        public double aspect() {
            return aspect;
        }

        public double detail() {
            return detail;
        }

        public double shelfFraction() {
            return shelfFraction;
        }

        public double islandRadius() {
            return islandRadius;
        }

        public double skirtFraction() {
            return skirtFraction;
        }

        public double freeboard() {
            return freeboard;
        }

        public double maxCut() {
            return maxCut;
        }

        public double outletWidth() {
            return outletWidth;
        }

        public double outletDepth() {
            return outletDepth;
        }
    }

    /** Immutable EARLIER facts valid over the entire requested support including outlet.
     * certifiedRadius is a disk about anchor, bounding min/max highest-solid parent.
     * reservedClear excludes the matching outlet handoff, which receiverCertified covers.
     * sealedFloor certifies no older cave/fluid leak below the deepest permitted bed.
     * Supplied waterY means highest occupied water block, NOT surface boundary Y.
     */
    public static final class Earlier {
        public final int waterY;
        public final boolean supplied;
        public final boolean marine;
        public final boolean protectedShore;
        public final boolean reservedClear;
        public final double certifiedRadius;
        public final double parentMin;
        public final double parentMax;
        public final boolean sealedFloor;
        public final boolean receiverCertified;
        public final int receiverWaterY;

        public Earlier(
                int waterY,
                boolean supplied,
                boolean marine,
                boolean protectedShore,
                boolean reservedClear,
                double certifiedRadius,
                double parentMin,
                double parentMax,
                boolean sealedFloor,
                boolean receiverCertified,
                int receiverWaterY) {
            this.waterY = waterY;
            this.supplied = supplied;
            this.marine = marine;
            this.protectedShore = protectedShore;
            this.reservedClear = reservedClear;
            this.certifiedRadius = certifiedRadius;
            this.parentMin = parentMin;
            this.parentMax = parentMax;
            this.sealedFloor = sealedFloor;
            this.receiverCertified = receiverCertified;
            this.receiverWaterY = receiverWaterY;
            finite(certifiedRadius, parentMin, parentMax);
            if (certifiedRadius < 0 || parentMax < parentMin)
                throw new IllegalArgumentException("earlier bounds");
        }

        public int waterY() {
            return waterY;
        }

        public boolean supplied() {
            return supplied;
        }

        public boolean marine() {
            return marine;
        }

        public boolean protectedShore() {
            return protectedShore;
        }

        public boolean reservedClear() {
            return reservedClear;
        }

        public double certifiedRadius() {
            return certifiedRadius;
        }

        public double parentMin() {
            return parentMin;
        }

        public double parentMax() {
            return parentMax;
        }

        public boolean sealedFloor() {
            return sealedFloor;
        }

        public boolean receiverCertified() {
            return receiverCertified;
        }

        public int receiverWaterY() {
            return receiverWaterY;
        }
    }

    public static final class Decision {
        public final Reason reason;
        public final Plan plan;

        public Decision(Reason reason, Plan plan) {
            this.reason = reason;
            this.plan = plan;
        }

        public Reason reason() {
            return reason;
        }

        public Plan plan() {
            return plan;
        }
    }

    /** Read-only candidate layout for EARLIER terrain/receiver certification, not selection. */
    public static final class Support {
        public final double radius;
        public final double outletX;
        public final double outletZ;
        public final double terminalRadius;

        public Support(double radius, double outletX, double outletZ, double terminalRadius) {
            this.radius = radius;
            this.outletX = outletX;
            this.outletZ = outletZ;
            this.terminalRadius = terminalRadius;
        }

        public double radius() {
            return radius;
        }

        public double outletX() {
            return outletX;
        }

        public double outletZ() {
            return outletZ;
        }

        public double terminalRadius() {
            return terminalRadius;
        }
    }

    public static Support support(
            long worldSeed, double anchorX, double anchorZ, long siteId, Family family, Tuning t) {
        long key = seedKey(worldSeed, anchorX, anchorZ, siteId, family);
        double angle = 2 * StrictMath.PI * unit(key, 1), end = 1.30 * t.width * t.aspect * .5;
        return new Support(
                radius(t),
                t.outletWidth > 0 ? anchorX + end * waveCos(angle) : Double.NaN,
                t.outletWidth > 0 ? anchorZ + end * waveSin(angle) : Double.NaN,
                t.outletWidth > 0 ? t.outletWidth * .5 + t.width * .5 * t.skirtFraction : 0);
    }

    /** The caller's immutable earlier receiver supplies this axis, in radians.
     * Use the same exact trigonometric axis for its certificate and the Plan.
     */
    public static Support supportAligned(
            long worldSeed,
            double anchorX,
            double anchorZ,
            long siteId,
            Family family,
            Tuning t,
            double angle) {
        seedKey(worldSeed, anchorX, anchorZ, siteId, family);
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("nonfinite axis");
        double end = 1.30 * t.width * t.aspect * .5;
        return new Support(
                radius(t),
                t.outletWidth > 0 ? anchorX + end * StrictMath.cos(angle) : Double.NaN,
                t.outletWidth > 0 ? anchorZ + end * StrictMath.sin(angle) : Double.NaN,
                t.outletWidth > 0 ? t.outletWidth * .5 + t.width * .5 * t.skirtFraction : 0);
    }

    private static double radius(Tuning t) {
        double rx = t.width * t.aspect * .5, rz = t.width * .5;
        return t.outletWidth > 0
                ? 1.30 * rx + t.outletWidth * .5 + rz * t.skirtFraction
                : 1.10 * rx;
    }

    private static long seedKey(
            long worldSeed, double anchorX, double anchorZ, long siteId, Family family) {
        if (!Double.isFinite(anchorX) || !Double.isFinite(anchorZ))
            throw new IllegalArgumentException("nonfinite anchor");
        if (Math.abs(anchorX) > 30_000_000 || Math.abs(anchorZ) > 30_000_000)
            throw new IllegalArgumentException("Minecraft absolute anchor bounds");
        anchorX = anchorX == 0 ? 0 : anchorX;
        anchorZ = anchorZ == 0 ? 0 : anchorZ;
        return mix(
                worldSeed
                        ^ family.salt
                        ^ mix(Double.doubleToLongBits(anchorX))
                        ^ Long.rotateLeft(mix(Double.doubleToLongBits(anchorZ)), 23)
                        ^ mix(siteId));
    }

    /** Plan once per earlier owner/site. Rejected candidates stay empty: no retry search. */
    public static Decision plan(
            long worldSeed,
            double anchorX,
            double anchorZ,
            long siteId,
            Family family,
            Tuning t,
            Reference ref,
            Earlier earlier) {
        if (t.width < ref.widthMin
                || t.width > ref.widthMax
                || t.depth < ref.depthMin
                || t.depth > ref.depthMax)
            throw new IllegalArgumentException("outside adopted reference");
        long key = seedKey(worldSeed, anchorX, anchorZ, siteId, family);
        Reason reason = admit(t, earlier, ref, radius(t));
        // Rejections allocate no Plan or primitive arrays.
        return new Decision(
                reason,
                reason == Reason.ACCEPT
                        ? new Plan(family, t, earlier, anchorX, anchorZ, key)
                        : null);
    }

    /** Same geometry/admission contract, rotated onto an earlier reserved receiver. */
    public static Decision planAligned(
            long worldSeed,
            double anchorX,
            double anchorZ,
            long siteId,
            Family family,
            Tuning t,
            Reference ref,
            Earlier earlier,
            double angle) {
        if (!Double.isFinite(angle)) throw new IllegalArgumentException("nonfinite axis");
        if (t.width < ref.widthMin
                || t.width > ref.widthMax
                || t.depth < ref.depthMin
                || t.depth > ref.depthMax)
            throw new IllegalArgumentException("outside adopted reference");
        long key = seedKey(worldSeed, anchorX, anchorZ, siteId, family);
        Reason reason = admit(t, earlier, ref, radius(t));
        return new Decision(
                reason,
                reason == Reason.ACCEPT
                        ? new Plan(
                                family,
                                t,
                                earlier,
                                anchorX,
                                anchorZ,
                                key,
                                StrictMath.cos(angle),
                                StrictMath.sin(angle))
                        : null);
    }

    private static Reason admit(Tuning t, Earlier e, Reference ref, double supportRadius) {
        if (!e.supplied) return Reason.NO_SUPPLY;
        if (e.marine) return Reason.MARINE;
        if (e.protectedShore) return Reason.PROTECTED;
        if (!e.reservedClear) return Reason.RESERVATION;
        if (e.certifiedRadius < supportRadius) return Reason.UNCERTIFIED_SUPPORT;
        if (e.parentMin < e.waterY + t.freeboard) return Reason.LOW_RIM;
        double lowWater = t.outletWidth > 0 ? Math.min(e.waterY, e.receiverWaterY) : e.waterY;
        double lowest =
                t.outletWidth > 0
                        ? Math.min(e.waterY - t.depth, lowWater - t.outletDepth)
                        : e.waterY - t.depth;
        if (e.parentMax - lowest > t.maxCut) return Reason.EXCESS_CUT;
        if (lowest < ref.minSolidY || e.parentMax > ref.maxSolidY) return Reason.ALLOCATION;
        if (!e.sealedFloor) return Reason.UNSEALED_FLOOR;
        if (t.outletWidth > 0
                && (!e.receiverCertified
                        || e.receiverWaterY > e.waterY
                        || e.waterY - e.receiverWaterY > 4)) return Reason.RECEIVER;
        return Reason.ACCEPT;
    }

    /** Caller-owned scratch. Sharing one Plan is safe; each worker must own its Sample. */
    public static final class Sample {
        public double bed, head;

        /** Riven integration extension: existing shoreline field for a matching dry rim. */
        public double shoreDistance;

        public int highestSolidY, waterY;
        public boolean wet, outlet;
    }

    public static final class Plan {
        public final Family family;
        public final double anchorX, anchorZ, supportRadius;
        public final long key;
        public final Tuning t;
        public final Earlier earlier;
        private final double cos, sin, rx, rz, phase, phase2, islandU, islandV;
        // Never exposed; construction is the only mutation. 5 lobes / 12 segments / 7 planes.
        private final double[] a = new double[13], b = new double[13], c = new double[13];

        private Plan(Family family, Tuning t, Earlier earlier, double x, double z, long key) {
            this(
                    family,
                    t,
                    earlier,
                    x,
                    z,
                    key,
                    waveCos(2 * StrictMath.PI * unit(key, 1)),
                    waveSin(2 * StrictMath.PI * unit(key, 1)));
        }

        private Plan(
                Family family,
                Tuning t,
                Earlier earlier,
                double x,
                double z,
                long key,
                double cos,
                double sin) {
            this.family = family;
            this.t = t;
            this.earlier = earlier;
            anchorX = x;
            anchorZ = z;
            this.key = key;
            this.cos = cos;
            this.sin = sin;
            rx = t.width * t.aspect * .5;
            rz = t.width * .5;
            phase = 2 * StrictMath.PI * unit(key, 2);
            phase2 = 2 * StrictMath.PI * unit(key, 3);
            // All basin fields are positive before normalized radius .92; skirt <= .15.
            // Swept/lobed extent proof is listed in README. Outlet ends at 1.30*rx.
            supportRadius = radius(t);
            for (int i = 0; i < 13; i++) {
                if (family == Family.LOBES) {
                    double theta = 2 * StrictMath.PI * i / 5 + phase;
                    a[i] = i == 0 ? 0 : .30 * waveCos(theta);
                    b[i] = i == 0 ? 0 : .30 * waveSin(theta);
                    c[i] = i == 0 ? .43 : .27 + .13 * unit(key, 10 + i);
                } else if (family == Family.SWEPT) {
                    double u = (i - 6) / 6.0;
                    a[i] = .64 * u;
                    // Multiplication pins the middle to the supplied outlet anchor, while
                    // phase varies between open hooks, asymmetric bends and S-shaped traces.
                    b[i] =
                            (.19 + .625 * t.detail)
                                    * waveSin(StrictMath.PI * u + phase)
                                    * waveSin(StrictMath.PI * .5 * StrictMath.abs(u));
                    c[i] = .17 + .045 * unit(key, 30 + i);
                } else {
                    double theta = 2 * StrictMath.PI * i / 7 + phase;
                    a[i] = waveCos(theta);
                    b[i] = waveSin(theta);
                    c[i] = .47 + .15 * unit(key, 50 + i);
                }
            }
            islandU = -.23;
            islandV =
                    family == Family.SWEPT
                            ? b[3] + (b[4] - b[3]) * (islandU - a[3]) / (a[4] - a[3])
                            : .12;
        }

        /** Allocation-free sample; parent is immutable earlier highest-solid height.
         * No queries of finished terrain. Only this column is described, never written.
         */
        public void sample(double x, double z, double parent, Sample out) {
            if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(parent))
                throw new IllegalArgumentException("nonfinite sample");
            if (parent < earlier.parentMin || parent > earlier.parentMax)
                throw new IllegalArgumentException("parent outside certified envelope");
            out.bed = parent;
            out.head = Double.NaN;
            out.wet = false;
            out.outlet = false;
            out.shoreDistance = Double.POSITIVE_INFINITY;
            out.waterY = earlier.waterY;
            out.highestSolidY = (int) StrictMath.floor(parent);
            double dx = x - anchorX, dz = z - anchorZ;
            if (StrictMath.abs(dx) > supportRadius || StrictMath.abs(dz) > supportRadius) return;
            double u = (dx * cos + dz * sin) / rx, v = (-dx * sin + dz * cos) / rz;
            double d = shore(u, v);
            out.shoreDistance = d;
            if (d < t.skirtFraction) {
                double bed;
                if (d <= 0) {
                    double inward = -d;
                    double core;
                    switch (family) {
                        case HARMONIC:
                            core = .59;
                            break;
                        case LOBES:
                            core = .43;
                            break;
                        case SWEPT:
                            core = .19;
                            break;
                        case CELLULAR:
                            core = .50;
                            break;
                        default:
                            throw new AssertionError();
                    }
                    double normalized = inward / core;
                    double shelfDepth = Math.min(2, t.depth * .35);
                    double depth =
                            shelfDepth * smooth(normalized / .09)
                                    + (t.depth - shelfDepth)
                                            * smooth(
                                                    (normalized - t.shelfFraction)
                                                            / (1 - t.shelfFraction));
                    bed = earlier.waterY - depth;
                    // An off-axis, elliptical island; disappears smoothly into original bed.
                    if (t.islandRadius > 0) {
                        double ir =
                                StrictMath.hypot((u - islandU) / 1.1, v - islandV) / t.islandRadius;
                        double weight = 1 - smooth(ir);
                        bed += (earlier.waterY + 2 - bed) * weight;
                    }
                } else
                    bed = earlier.waterY + (parent - earlier.waterY) * smooth(d / t.skirtFraction);
                out.bed = Math.min(parent, bed);
            }
            if (t.outletWidth > 0) {
                double along = u * rx, end = 1.30 * rx;
                double at = clamp(along, 0, end);
                double dist = StrictMath.hypot(along - at, v * rz);
                double bank = rz * t.skirtFraction, half = t.outletWidth * .5;
                if (dist < half + bank) {
                    // Hold lake head through ALL possible basin supports; drop outside them.
                    double f = smooth((at / rx - 1.10) / .20);
                    int w =
                            (int)
                                    StrictMath.floor(
                                            earlier.waterY
                                                    + (earlier.receiverWaterY - earlier.waterY) * f
                                                    + 1e-12);
                    double cut = w - t.outletDepth;
                    double channel =
                            dist <= half
                                    ? cut
                                    : cut + (parent - cut) * smooth((dist - half) / bank);
                    if (channel < out.bed) {
                        out.bed = channel;
                        out.waterY = w;
                        out.outlet = true;
                    }
                }
            }
            out.highestSolidY = (int) StrictMath.floor(out.bed);
            out.wet = out.highestSolidY < out.waterY;
            if (out.wet) out.head = out.waterY + 1.0;
        }

        /** Zero is the nominal shoreline, negative is basin interior. Not an exact SDF. */
        private double shore(double u, double v) {
            double d;
            switch (family) {
                case HARMONIC:
                    {
                        double theta = StrictMath.atan2(v, u);
                        double r =
                                .59
                                        + t.detail
                                                * (.55 * waveSin(3 * theta + phase)
                                                        + .30 * waveSin(5 * theta + phase2)
                                                        + .15 * waveSin(8 * theta - phase));
                        d = StrictMath.hypot(u, v) - r;
                        break;
                    }
                case LOBES:
                    {
                        // Polynomial smooth union, fixed 5 terms; no global field summation.
                        d = StrictMath.hypot(u - a[0], v - b[0]) - c[0];
                        for (int i = 1; i < 5; i++) {
                            double e = StrictMath.hypot(u - a[i], v - b[i]) - c[i];
                            double k = .035 + t.detail * .25,
                                    h = Math.max(k - StrictMath.abs(d - e), 0) / k;
                            d = Math.min(d, e) - h * h * k * .25;
                        }
                        break;
                    }
                case SWEPT:
                    {
                        // Nearest of 12 finite variable-width segments; continuous capsule union.
                        d = Double.POSITIVE_INFINITY;
                        for (int i = 0; i < 12; i++) {
                            double du = a[i + 1] - a[i], dv = b[i + 1] - b[i];
                            double f =
                                    clamp(
                                            ((u - a[i]) * du + (v - b[i]) * dv)
                                                    / (du * du + dv * dv),
                                            0,
                                            1);
                            double e =
                                    StrictMath.hypot(u - a[i] - f * du, v - b[i] - f * dv)
                                            - (c[i] + f * (c[i + 1] - c[i]));
                            d = Math.min(d, e);
                        }
                        break;
                    }
                case CELLULAR:
                    {
                        // Seven seeded half-planes bound one cell; compact inward scallops.
                        d = Double.NEGATIVE_INFINITY;
                        for (int i = 0; i < 7; i++) d = Math.max(d, u * a[i] + v * b[i] - c[i]);
                        double scallop =
                                t.detail * .32 * waveSin(11 * u + phase) * waveSin(9 * v + phase2);
                        d += scallop;
                        break;
                    }
                default:
                    throw new AssertionError();
            }
            // Gentle seed-directed bottom/shore irregularity, zero extra neighbor queries.
            return d;
        }

        public double worldX(double u, double v) {
            return anchorX + u * rx * cos - v * rz * sin;
        }

        public double worldZ(double u, double v) {
            return anchorZ + u * rx * sin + v * rz * cos;
        }
    }

    public static double smooth(double x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    public static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    /** Scalar Taylor sine through x^21 after bounded range reduction.
     * Kernel arguments are bounded to |x|<100. Remainder on [-pi/2,pi/2]
     * is <= (pi/2)^23/23! before floating-point rounding. No lookup table.
     * Java26 StrictMath.sin internally allocates argument-reduction scratch on this JVM.
     */
    static double waveSin(double x) {
        double pi = StrictMath.PI, two = 2 * pi;
        x -= two * StrictMath.floor((x + pi) / two);
        if (x > pi * .5) x = pi - x;
        else if (x < -pi * .5) x = -pi - x;
        double q = x * x;
        return x
                * (1
                        + q
                                * (-1.0 / 6
                                        + q
                                                * (1.0 / 120
                                                        + q
                                                                * (-1.0 / 5040
                                                                        + q
                                                                                * (1.0 / 362880
                                                                                        + q
                                                                                                * (-1.0
                                                                                                                / 39916800
                                                                                                        + q
                                                                                                                * (1.0
                                                                                                                                / 6227020800.0
                                                                                                                        + q
                                                                                                                                * (-1.0
                                                                                                                                                / 1307674368000.0
                                                                                                                                        + q
                                                                                                                                                * (1.0
                                                                                                                                                                / 355687428096000.0
                                                                                                                                                        + q
                                                                                                                                                                * (-1.0
                                                                                                                                                                                / 121645100408832000.0
                                                                                                                                                                        + q
                                                                                                                                                                                * (1.0
                                                                                                                                                                                        / 51090942171709440000.0)))))))))));
    }

    static double waveCos(double x) {
        return waveSin(x + StrictMath.PI * .5);
    }

    public static long mix(long v) {
        // Independent avalanche polynomial; no mutable PRNG and no external noise source.
        v ^= v >>> 29;
        v *= 0x319642b2d24d8ec3L;
        v ^= v >>> 32;
        v *= 0x96de1b173f119089L;
        return v ^ (v >>> 28);
    }

    public static double unit(long seed, long index) {
        return (mix(seed + index * 0x632be59bd9b4e019L) >>> 11) * 0x1.0p-53;
    }

    private static void finite(double... values) {
        for (double v : values)
            if (!Double.isFinite(v)) throw new IllegalArgumentException("nonfinite input");
    }
}
