// SPDX-License-Identifier: MIT
/*
 * Adapted from contributions/sedimentary-coasts-20261019 (reviewed second pass).
 * Riven modifications (2026-10-20): proposal-bound integer-column admission and primitive sampling.
 * Original continuous APIs remain available; native certificates do not certify them.
 * Native extensions remain MIT. Packaged grant: META-INF/licenses/sedimentary-coasts/MIT.txt.
 * Riven modification (2026-10-23): admission thresholds named so callers can pre-check them.
 */
package cc.orthrus.riven.contrib.coast;

/** Independently authored, bounded sediment geometry. No Minecraft or loader dependency. */
public final class SedimentaryCoasts {
    private SedimentaryCoasts() {}

    /** Whole-body admission: least sediment supply, steepest slope, greatest exposure. */
    public static final double MINIMUM_SEDIMENT = .25, MAXIMUM_SLOPE = .32, MAXIMUM_EXPOSURE = .88;

    public enum Family {
        BEACH_BENCH(0x5343445F42454131L),
        SPIT(0x5343445F53504931L),
        BARRIER(0x5343445F42415231L),
        TOMBOLO(0x5343445F544F4D31L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPTED,
        OUTSIDE_SUPPORT,
        WATER_RESERVED,
        MARINE_UNAUTHORIZED,
        NO_SEDIMENT,
        TOO_STEEP,
        TOO_EXPOSED,
        FOUNDATION_TOO_DEEP,
        PARENT_TOO_HIGH,
        BAD_INPUT,
        BAD_ATTACHMENT,
        BLOCKED_CONNECTION,
        COLUMN_DOMAIN,
        UNKNOWN_COLUMNS
    }

    /** Copy values from WorldGenerationContext.terrainHeights; no second elevation datum. */
    public static final class Reference {
        public final double sea, beachMin, beachMean, beachMax, widthMin, widthMean, widthMax;
        public final double beachRelief, plainWidth, shoalDepth;
        public final String fingerprint;

        public Reference(
                double sea,
                double beachMin,
                double beachMean,
                double beachMax,
                double widthMin,
                double widthMean,
                double widthMax,
                double beachRelief,
                double plainWidth,
                double shoalDepth,
                String fingerprint) {
            if (!finite(
                            sea,
                            beachMin,
                            beachMean,
                            beachMax,
                            widthMin,
                            widthMean,
                            widthMax,
                            beachRelief,
                            plainWidth,
                            shoalDepth)
                    || beachMin < 0
                    || beachMax <= beachMin
                    || beachMean < beachMin
                    || beachMean > beachMax
                    || widthMin <= 0
                    || widthMin > widthMean
                    || widthMean > widthMax
                    || beachRelief < 0
                    || plainWidth <= widthMax
                    || shoalDepth <= 0
                    || fingerprint == null)
                throw new IllegalArgumentException("Invalid height reference");
            this.sea = sea;
            this.beachMin = beachMin;
            this.beachMean = beachMean;
            this.beachMax = beachMax;
            this.widthMin = widthMin;
            this.widthMean = widthMean;
            this.widthMax = widthMax;
            this.beachRelief = beachRelief;
            this.plainWidth = plainWidth;
            this.shoalDepth = shoalDepth;
            this.fingerprint = fingerprint;
        }
    }

    /** All dimensions are reference multipliers; width includes flanks and transition skirt. */
    public static final class Tuning {
        public final double widthScale, lengthScale, offsetScale, hook, roughness, bench;

        public Tuning(
                double widthScale,
                double lengthScale,
                double offsetScale,
                double hook,
                double roughness,
                double bench) {
            if (!finite(widthScale, lengthScale, offsetScale, hook, roughness, bench)
                    || widthScale < .35
                    || widthScale > 2
                    || lengthScale < .2
                    || lengthScale > 1.5
                    || offsetScale < .08
                    || offsetScale > .45
                    || hook < .1
                    || hook > .8
                    || roughness < 0
                    || roughness > 1
                    || bench < 0
                    || bench > 1)
                throw new IllegalArgumentException("Tuning outside reviewed support bounds");
            this.widthScale = widthScale;
            this.lengthScale = lengthScale;
            this.offsetScale = offsetScale;
            this.hook = hook;
            this.roughness = roughness;
            this.bench = bench;
        }

        public static Tuning defaults() {
            return new Tuning(1, .68, .23, .42, .55, .65);
        }
    }

    /** Immutable earlier facts at a certified attachment, not a query of finished terrain. */
    public static final class Anchor {
        public final double x, z, topY, tangentX, tangentZ;
        public final boolean certifiedLand;

        public Anchor(double x, double z, double topY, double tx, double tz, boolean land) {
            if (!finite(x, z, topY, tx, tz) || tx * tx + tz * tz < .5 || tx * tx + tz * tz > 1.5)
                throw new IllegalArgumentException("Bad attachment or tangent");
            double d = StrictMath.sqrt(tx * tx + tz * tz);
            this.x = x;
            this.z = z;
            this.topY = topY;
            tangentX = tx / d;
            tangentZ = tz / d;
            certifiedLand = land;
        }
    }

    /** Exact protected capsule, including caller-specified channel banks. Never writes water. */
    public static final class Reservation {
        public final double ax, az, bx, bz, radius, skirt;

        public Reservation(
                double ax, double az, double bx, double bz, double radius, double skirt) {
            if (!finite(ax, az, bx, bz, radius, skirt) || radius <= 0 || skirt <= 0)
                throw new IllegalArgumentException("Bad reservation");
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.radius = radius;
            this.skirt = skirt;
        }
    }

    /** Earlier candidate-wide supply/exposure/foundation certificate. No dynamic simulation. */
    public static final class Admission {
        public final double sediment, slope, exposure;
        public final boolean foundationCertified;

        public Admission(
                double sediment, double slope, double exposure, boolean foundationCertified) {
            if (!finite(sediment, slope, exposure)
                    || sediment < 0
                    || sediment > 1
                    || slope < 0
                    || exposure < 0
                    || exposure > 1) throw new IllegalArgumentException("Bad admission");
            this.sediment = sediment;
            this.slope = slope;
            this.exposure = exposure;
            this.foundationCertified = foundationCertified;
        }
    }

    /** Supplied column facts. Marine permission is explicit and distinct from marine ownership. */
    public static final class Column {
        public final double x, z, parentY;
        public final boolean marine, sedimentPermission, waterReserved;

        public Column(
                double x,
                double z,
                double parentY,
                boolean marine,
                boolean permission,
                boolean waterReserved) {
            this.x = x;
            this.z = z;
            this.parentY = parentY;
            this.marine = marine;
            sedimentPermission = permission;
            this.waterReserved = waterReserved;
        }
    }

    /** Caller-owned scratch/output. Never share this across simultaneous calls. */
    public static final class Sample {
        public double heightY, weight, proposedWeight, coreTargetY, distance, along;
        public Reason reason;
        public boolean inheritedMarine, emergentSediment;
        private double px, pz, tx, tz;

        private void reset(double parent, boolean marine) {
            heightY = coreTargetY = parent;
            weight = proposedWeight = 0;
            distance = along = 0;
            reason = Reason.OUTSIDE_SUPPORT;
            inheritedMarine = marine;
            emergentSediment = false;
        }
    }

    /** Immutable after construction, arrays private and defensively copied. Max 64 nodes/16 masks. */
    public static final class Plan {
        public final Family family;
        public final long key;
        public final Reference reference;
        public final Tuning tuning;
        public final Reason admission;
        public final double minX, minZ, maxX, maxZ, maxRadius, length;
        private final double[] x, z, s;
        private final Reservation[] reservations;
        private final double startY, endY;

        private Plan(
                Family f,
                long seed,
                long featureId,
                Reference r,
                Tuning t,
                Admission facts,
                double[] xs,
                double[] zs,
                Reservation[] masks,
                Anchor a,
                Anchor b) {
            if (xs.length < 2 || xs.length > 64 || xs.length != zs.length || masks.length > 16)
                throw new IllegalArgumentException(
                        "Bounded plan requires 2..64 nodes, <=16 reservations");
            family = f;
            key = mix(seed ^ f.salt ^ mix(featureId));
            reference = r;
            tuning = t;
            x = xs.clone();
            z = zs.clone();
            s = new double[x.length];
            reservations = masks.clone();
            double loX = Double.POSITIVE_INFINITY, loZ = loX, hiX = -loX, hiZ = -loX;
            for (int i = 0; i < x.length; i++) {
                if (!finite(x[i], z[i])
                        || StrictMath.abs(x[i]) > 30_000_000
                        || StrictMath.abs(z[i]) > 30_000_000)
                    throw new IllegalArgumentException(
                            "Coordinates outside Minecraft world bounds");
                if (i > 0) {
                    double d = StrictMath.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
                    if (d < .001) throw new IllegalArgumentException("Degenerate segment");
                    s[i] = s[i - 1] + d;
                }
                loX = StrictMath.min(loX, x[i]);
                loZ = StrictMath.min(loZ, z[i]);
                hiX = StrictMath.max(hiX, x[i]);
                hiZ = StrictMath.max(hiZ, z[i]);
            }
            for (Reservation m : reservations)
                if (m == null) throw new IllegalArgumentException("Null reservation");
            length = s[s.length - 1];
            if (length > r.plainWidth * 8)
                throw new IllegalArgumentException("Plan length exceeds 8 plain widths");
            maxRadius = f == Family.BEACH_BENCH ? r.widthMax : r.widthMax * .5;
            minX = loX - maxRadius;
            maxX = hiX + maxRadius;
            minZ = loZ - maxRadius;
            maxZ = hiZ + maxRadius;
            startY = a == null ? r.sea + r.beachMean : a.topY;
            endY = b == null ? r.sea + r.beachMean : b.topY;
            Reason why = Reason.ACCEPTED;
            if (facts.sediment < MINIMUM_SEDIMENT) why = Reason.NO_SEDIMENT;
            else if (facts.slope > MAXIMUM_SLOPE) why = Reason.TOO_STEEP;
            else if (facts.exposure > MAXIMUM_EXPOSURE) why = Reason.TOO_EXPOSED;
            else if (!facts.foundationCertified) why = Reason.FOUNDATION_TOO_DEEP;
            else if ((f == Family.SPIT || f == Family.TOMBOLO) && !validAnchor(a, r))
                why = Reason.BAD_ATTACHMENT;
            else if (f == Family.TOMBOLO && !validAnchor(b, r)) why = Reason.BAD_ATTACHMENT;
            // Conservative capsule intersection at planning: reject a tying bar rather than sever
            // its neck.
            if (why == Reason.ACCEPTED && f == Family.TOMBOLO) {
                for (int i = 0; i < x.length - 1; i++)
                    for (Reservation m : reservations)
                        if (segmentDistance(x[i], z[i], x[i + 1], z[i + 1], m.ax, m.az, m.bx, m.bz)
                                <= m.radius + m.skirt + maxRadius) why = Reason.BLOCKED_CONNECTION;
            }
            admission = why;
        }

        public int nodeCount() {
            return x.length;
        }

        public double nodeX(int i) {
            return x[i];
        }

        public double nodeZ(int i) {
            return z[i];
        }
    }

    public static Plan beach(
            long seed,
            long id,
            Reference r,
            Tuning t,
            Admission facts,
            double[] shoreX,
            double[] shoreZ,
            Reservation[] masks) {
        // Shore guide tangent must have land on its left. Sampling never queries neighboring
        // terrain.
        return new Plan(
                Family.BEACH_BENCH, seed, id, r, t, facts, shoreX, shoreZ, masks, null, null);
    }

    /** Recurved spit: two cubic lobes, fixed 40 segments, rooted at an earlier shore attachment. */
    public static Plan spit(
            long seed,
            long id,
            Reference r,
            Tuning t,
            Admission facts,
            Anchor root,
            Reservation[] masks) {
        long k = mix(seed ^ Family.SPIT.salt ^ mix(id));
        double len = r.plainWidth * t.lengthScale * (.78 + .26 * unit(k));
        double tx = root.tangentX, tz = root.tangentZ, nx = tz, nz = -tx;
        double bend = t.hook * (.8 + .4 * unit(mix(k + 1))), dir = unit(mix(k + 2)) < .5 ? -1 : 1;
        tx *= dir;
        tz *= dir; // Seaward normal remains inherited; drift orientation may reverse.
        double[] xx = new double[41], zz = new double[41];
        for (int i = 0; i <= 40; i++) {
            double q = i / 40.0, u, v;
            if (q <= .70) {
                double h = q / .70;
                u = cubic(0, len * .26, len * .58, len * .78, h);
                v = cubic(0, len * .025, len * bend * .45, len * bend * .85, h);
            } else {
                double h = (q - .70) / .30;
                u = cubic(len * .78, len * (.78 + .20 * .30 / .70), len * .99, len * .83, h);
                v =
                        cubic(
                                len * bend * .85,
                                len * bend * (.85 + .40 * .30 / .70),
                                len * bend * 1.65,
                                len * bend * 1.75,
                                h);
            }
            xx[i] = root.x + tx * u + nx * v;
            zz[i] = root.z + tz * u + nz * v;
        }
        return new Plan(Family.SPIT, seed, id, r, t, facts, xx, zz, masks, root, null);
    }

    /** Shore-following offshore ribbon. Caller supplies shoreline nodes with land to the left. */
    public static Plan barrier(
            long seed,
            long id,
            Reference r,
            Tuning t,
            Admission facts,
            double[] shoreX,
            double[] shoreZ,
            Reservation[] masks) {
        if (shoreX.length != shoreZ.length || shoreX.length < 2 || shoreX.length > 64)
            throw new IllegalArgumentException("Bad shoreline guide");
        double[] xx = new double[shoreX.length], zz = new double[shoreZ.length];
        long k = mix(seed ^ Family.BARRIER.salt ^ mix(id));
        for (int i = 0; i < xx.length; i++) {
            int a = StrictMath.max(0, i - 1), b = StrictMath.min(xx.length - 1, i + 1);
            double dx = shoreX[b] - shoreX[a],
                    dz = shoreZ[b] - shoreZ[a],
                    n = StrictMath.hypot(dx, dz);
            if (n < .001) throw new IllegalArgumentException("Degenerate shoreline");
            double offset =
                    r.plainWidth
                            * t.offsetScale
                            * (1
                                    + .14
                                            * noise(
                                                    k,
                                                    shoreX[i] / r.plainWidth,
                                                    shoreZ[i] / r.plainWidth));
            xx[i] = shoreX[i] + dz / n * offset;
            zz[i] = shoreZ[i] - dx / n * offset;
        }
        return new Plan(Family.BARRIER, seed, id, r, t, facts, xx, zz, masks, null, null);
    }

    /** Dual-ended bowed tie: fixed 32 segments, no island discovery or wave simulation. */
    public static Plan tombolo(
            long seed,
            long id,
            Reference r,
            Tuning t,
            Admission facts,
            Anchor mainland,
            Anchor island,
            Reservation[] masks) {
        long k = mix(seed ^ Family.TOMBOLO.salt ^ mix(id));
        double dx = island.x - mainland.x, dz = island.z - mainland.z, d = StrictMath.hypot(dx, dz);
        if (d < r.widthMean * 2 || d > r.plainWidth * 1.5)
            throw new IllegalArgumentException("Tie length outside bounds");
        double bow = (unit(k) * 2 - 1) * d * .13;
        double[] xx = new double[33], zz = new double[33];
        for (int i = 0; i < xx.length; i++) {
            double q = i / 32.0, b = 4 * q * (1 - q) * bow;
            xx[i] = mainland.x + q * dx - dz / d * b;
            zz[i] = mainland.z + q * dz + dx / d * b;
        }
        return new Plan(Family.TOMBOLO, seed, id, r, t, facts, xx, zz, masks, mainland, island);
    }

    /** O(63+16), no allocation, no mutable shared state, no callbacks or recursive terrain reads. */
    public static void sample(Plan p, Column c, Sample out) {
        sample(p, c.x, c.z, c.parentY, c.marine, c.sedimentPermission, c.waterReserved, true, out);
    }

    private static void sample(
            Plan p,
            double x,
            double z,
            double parent,
            boolean marine,
            boolean permission,
            boolean reserved,
            boolean admitted,
            Sample out) {
        out.reset(parent, marine);
        if (!finite3(x, z, parent)) {
            out.reason = Reason.BAD_INPUT;
            return;
        }
        if (admitted && p.admission != Reason.ACCEPTED) {
            out.reason = p.admission;
            return;
        }
        if (x < p.minX || x > p.maxX || z < p.minZ || z > p.maxZ) return;
        nearest(p, x, z, out);
        Reference r = p.reference;
        Tuning t = p.tuning;
        double q = out.along / p.length;
        double signed = (x - out.px) * (-out.tz) + (z - out.pz) * out.tx;
        double n = noise(p.key, x / (r.widthMean * 3), z / (r.widthMean * 3));
        // Depositional bodies include a wider submerged apron; the reference cap includes all
        // flanks.
        double apron = p.family == Family.BEACH_BENCH ? 1 : 1.75;
        double width =
                clamp(r.widthMean * t.widthScale * apron * (1 + .22 * n), r.widthMin, r.widthMax);
        double weight, target;
        if (p.family == Family.BEACH_BENCH) {
            // A shore-normal face and a single broad, gently corrugated bench (no repeated strata).
            if (signed < 0 || signed > width || out.distance > width) return;
            // Euclidean nearest distance stays continuous through a guide's convex vertices.
            // The oriented projection selects the land side, not the distance magnitude.
            double u = out.distance / width;
            weight =
                    smooth(u / .16)
                            * smooth((1 - u) / .22)
                            * smooth(out.along / width)
                            * smooth((p.length - out.along) / width);
            double face = smooth(u / .56),
                    bench = smooth((u - .42) / .16) * (1 - smooth((u - .80) / .14));
            target =
                    r.sea
                            + r.beachMin
                            + (r.beachMean - r.beachMin) * face
                            + t.bench * r.beachRelief * .45 * bench
                            + t.roughness * r.beachRelief * .28 * n * face;
        } else {
            double radial = out.distance, half = width * .5;
            if (p.family == Family.SPIT) {
                half *=
                        .72
                                + .22 * StrictMath.sin(q * 3.141592653589793)
                                + .32 * smooth((q - .66) / .24);
                half = clamp(half, r.widthMin * .5, r.widthMax * .5);
            } else if (p.family == Family.TOMBOLO) {
                // Two endpoint fans meet a narrow continuous neck, not a shore-parallel ribbon.
                half *= .65 + .85 * StrictMath.pow(StrictMath.abs(2 * q - 1), 2);
                half = clamp(half, r.widthMin * .5, r.widthMax * .5);
            }
            if (radial >= half) return;
            double u = radial / half;
            weight = 1 - smooth(u);
            // Rounded finite ends. Ties retain their endpoint attachment disks.
            if (p.family == Family.BARRIER)
                weight *=
                        smooth(out.along / (width * 1.2))
                                * smooth((p.length - out.along) / (width * 1.2));
            if (p.family == Family.SPIT) weight *= 1 - smooth((q - .965) / .035) * smooth(u / .4);
            double crest = r.beachMean + t.roughness * r.beachRelief * .34 * n;
            if (p.family == Family.BARRIER) {
                // Asymmetric ocean/lagoon faces; centerline crest never receives random breaches.
                double shape = smooth(1 - u);
                double lateral = signed < 0 ? -radial : radial;
                double asym = 1 - .12 * smooth((lateral / half + .2) / .4);
                crest = r.beachMin + (crest - r.beachMin) * shape * asym;
            } else if (p.family == Family.TOMBOLO) {
                double ends = p.startY + (p.endY - p.startY) * smooth(q) - r.sea;
                crest = ends + (crest - ends) * smooth(q / .22) * smooth((1 - q) / .22);
            } else {
                crest -= r.beachRelief * .22 * u * u;
                crest = p.startY - r.sea + (crest - (p.startY - r.sea)) * smooth(q / .12);
            }
            target = r.sea + crest;
        }
        if (weight <= 0) return;
        out.proposedWeight = weight;
        if (reserved) {
            out.reason = Reason.WATER_RESERVED;
            return;
        }
        for (Reservation m : p.reservations) {
            double d = StrictMath.sqrt(pointDistance2(x, z, m.ax, m.az, m.bx, m.bz));
            if (d <= m.radius) {
                out.reason = Reason.WATER_RESERVED;
                return;
            }
            weight *= smooth((d - m.radius) / m.skirt);
        }
        if (marine && !permission) {
            out.reason = Reason.MARINE_UNAUTHORIZED;
            return;
        }
        if (parent < r.sea - r.shoalDepth) {
            out.reason = Reason.FOUNDATION_TOO_DEEP;
            return;
        }
        double parentLimit =
                r.sea + r.beachMax + (p.family == Family.BEACH_BENCH ? r.beachRelief : 0);
        if (parent > parentLimit) {
            out.reason = Reason.PARENT_TOO_HIGH;
            return;
        }
        target = clamp(target, r.sea + r.beachMin, r.sea + r.beachMax);
        // Beach may cut/fill the earlier low shore; offshore bodies are deposition only.
        if (p.family != Family.BEACH_BENCH) target = StrictMath.max(parent, target);
        out.coreTargetY = target;
        out.weight = weight;
        out.heightY = parent + (target - parent) * weight;
        out.reason = Reason.ACCEPTED;
        out.emergentSediment = marine && out.heightY >= r.sea;
    }

    /** Tentative geometry only. Its foundation is deliberately not admitted. */
    public static final class ColumnProposal {
        private final Plan geometry;
        private final long seed, id;
        private final Anchor a, b;
        public final Family family;
        public final double minX, minZ, maxX, maxZ;
        public final int boxColumns;

        private ColumnProposal(Plan p, long seed, long id, Anchor a, Anchor b) {
            geometry = p;
            this.seed = seed;
            this.id = id;
            this.a = a;
            this.b = b;
            family = p.family;
            minX = p.minX;
            minZ = p.minZ;
            maxX = p.maxX;
            maxZ = p.maxZ;
            long count =
                    ((long) StrictMath.ceil(maxX) - (long) StrictMath.floor(minX) + 1)
                            * ((long) StrictMath.ceil(maxZ) - (long) StrictMath.floor(minZ) + 1);
            boxColumns = count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        }
    }

    public static ColumnProposal proposeColumns(
            Family f,
            long seed,
            long id,
            Reference r,
            Tuning t,
            double[] guideX,
            double[] guideZ,
            Anchor mainland,
            Anchor island,
            Reservation[] masks) {
        Admission pending = new Admission(1, 0, 0, false);
        Plan p;
        switch (f) {
            case BEACH_BENCH:
                p = beach(seed, id, r, t, pending, guideX, guideZ, masks);
                break;
            case SPIT:
                p = spit(seed, id, r, t, pending, mainland, masks);
                break;
            case BARRIER:
                p = barrier(seed, id, r, t, pending, guideX, guideZ, masks);
                break;
            case TOMBOLO:
                p = tombolo(seed, id, r, t, pending, mainland, island, masks);
                break;
            default:
                throw new IllegalArgumentException("Unknown family");
        }
        return new ColumnProposal(p, seed, id, mainland, island);
    }

    /** Admission inspects these exact columns; it does not make the proposal sampleable as a plan. */
    public static void probeColumns(
            ColumnProposal p,
            int x,
            int z,
            double parent,
            boolean marine,
            boolean permission,
            boolean reserved,
            Sample out) {
        sample(p.geometry, x, z, parent, marine, permission, reserved, false, out);
    }

    public static boolean masked(ColumnProposal p, int x, int z) {
        for (Reservation m : p.geometry.reservations)
            if (pointDistance2(x, z, m.ax, m.az, m.bx, m.bz) <= m.radius * m.radius) return true;
        return false;
    }

    public static final class ColumnFacts {
        private final ColumnProposal proposal;
        private final Admission admission;
        private final int columns, active, islandColumns;
        private final boolean guide, connected;

        public ColumnFacts(
                ColumnProposal p,
                double sediment,
                double slope,
                double exposure,
                int columns,
                int active,
                boolean guide,
                boolean connected,
                int islandColumns) {
            if (p == null || columns < 0 || active < 0 || active > columns || islandColumns < 0)
                throw new IllegalArgumentException("Invalid native column certificate");
            proposal = p;
            admission =
                    new Admission(sediment, slope, exposure, columns == p.boxColumns && active > 0);
            this.columns = columns;
            this.active = active;
            this.guide = guide;
            this.connected = connected;
            this.islandColumns = islandColumns;
        }
    }

    /** Separate native proof domain. The continuous Plan is not exposed by this wrapper. */
    public static final class ColumnPlan {
        private final Plan geometry;
        public final Family family;
        public final double minX, minZ, maxX, maxZ;

        private ColumnPlan(Plan p) {
            geometry = p;
            family = p.family;
            minX = p.minX;
            minZ = p.minZ;
            maxX = p.maxX;
            maxZ = p.maxZ;
        }

        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
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

    public static ColumnDecision admitColumns(ColumnProposal p, ColumnFacts facts) {
        if (facts.proposal != p
                || facts.columns != p.boxColumns
                || facts.active == 0
                || !facts.guide) return new ColumnDecision(Reason.UNKNOWN_COLUMNS, null);
        if ((p.family == Family.SPIT || p.family == Family.TOMBOLO) && !facts.connected)
            return new ColumnDecision(Reason.BLOCKED_CONNECTION, null);
        if (p.family == Family.TOMBOLO && facts.islandColumns == 0)
            return new ColumnDecision(Reason.BAD_ATTACHMENT, null);
        Plan g = p.geometry;
        Plan admitted =
                new Plan(
                        p.family,
                        p.seed,
                        p.id,
                        g.reference,
                        g.tuning,
                        facts.admission,
                        g.x,
                        g.z,
                        g.reservations,
                        p.a,
                        p.b);
        return new ColumnDecision(
                admitted.admission,
                admitted.admission == Reason.ACCEPTED ? new ColumnPlan(admitted) : null);
    }

    public static void sampleColumns(
            ColumnPlan p,
            double x,
            double z,
            double parent,
            boolean marine,
            boolean permission,
            boolean reserved,
            Sample out) {
        if (x != StrictMath.floor(x) || z != StrictMath.floor(z)) {
            out.reset(parent, marine);
            out.reason = Reason.COLUMN_DOMAIN;
            return;
        }
        sample(p.geometry, x, z, parent, marine, permission, reserved, true, out);
    }

    private static void nearest(Plan p, double x, double z, Sample out) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < p.x.length - 1; i++) {
            double dx = p.x[i + 1] - p.x[i], dz = p.z[i + 1] - p.z[i], d2 = dx * dx + dz * dz;
            double q = clamp(((x - p.x[i]) * dx + (z - p.z[i]) * dz) / d2, 0, 1);
            double px = p.x[i] + q * dx,
                    pz = p.z[i] + q * dz,
                    ex = x - px,
                    ez = z - pz,
                    dd = ex * ex + ez * ez;
            if (dd < best) {
                best = dd;
                out.px = px;
                out.pz = pz;
                double len = StrictMath.sqrt(d2);
                out.tx = dx / len;
                out.tz = dz / len;
                out.along = p.s[i] + q * len;
            }
        }
        out.distance = StrictMath.sqrt(best);
    }

    private static boolean validAnchor(Anchor a, Reference r) {
        return a != null
                && a.certifiedLand
                && a.topY >= r.sea + r.beachMin
                && a.topY <= r.sea + r.beachMax;
    }

    private static double cubic(double a, double b, double c, double d, double t) {
        double u = 1 - t;
        return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d;
    }

    private static double pointDistance2(
            double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, den = dx * dx + dz * dz;
        double q = den == 0 ? 0 : clamp(((x - ax) * dx + (z - az) * dz) / den, 0, 1);
        double ex = x - ax - q * dx, ez = z - az - q * dz;
        return ex * ex + ez * ez;
    }

    private static double cross(double ax, double az, double bx, double bz, double cx, double cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    private static double segmentDistance(
            double ax,
            double az,
            double bx,
            double bz,
            double cx,
            double cz,
            double dx,
            double dz) {
        double p = cross(ax, az, bx, bz, cx, cz), q = cross(ax, az, bx, bz, dx, dz);
        double u = cross(cx, cz, dx, dz, ax, az), v = cross(cx, cz, dx, dz, bx, bz);
        if (((p > 0 && q < 0) || (p < 0 && q > 0)) && ((u > 0 && v < 0) || (u < 0 && v > 0)))
            return 0;
        return StrictMath.sqrt(
                StrictMath.min(
                        StrictMath.min(
                                pointDistance2(ax, az, cx, cz, dx, dz),
                                pointDistance2(bx, bz, cx, cz, dx, dz)),
                        StrictMath.min(
                                pointDistance2(cx, cz, ax, az, bx, bz),
                                pointDistance2(dx, dz, ax, az, bx, bz))));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double lattice(long key, long x, long z) {
        return 2 * unit(mix(key ^ mix(x) ^ Long.rotateLeft(mix(z), 29))) - 1;
    }

    private static double noise(long key, double x, double z) {
        long ix = (long) StrictMath.floor(x), iz = (long) StrictMath.floor(z);
        double u = smooth(x - ix), v = smooth(z - iz);
        double a = lattice(key, ix, iz),
                b = lattice(key, ix + 1, iz),
                c = lattice(key, ix, iz + 1),
                d = lattice(key, ix + 1, iz + 1);
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }

    private static double clamp(double x, double a, double b) {
        return StrictMath.max(a, StrictMath.min(b, x));
    }

    private static double smooth(double x) {
        x = clamp(x, 0, 1);
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static boolean finite3(double a, double b, double c) {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c);
    }

    private static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }
}
