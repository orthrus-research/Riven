// SPDX-License-Identifier: MIT
/*
 * Adapted from contributions/rocky-coasts-20261019, including its second pass.
 * Riven modifications (2026-10-20): package, primitive sampling, explicit target,
 * proposal-bound native certificates, canonical shoreline blending and voxel cuts.
 * Original continuous APIs remain. Native extensions remain MIT.
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
        JOINT_COVES(0x524F434B434F5645L),
        STACK_ARCH(0x524F434B33444F43L);
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
        NO_3D_PERMIT,
        NO_FOUNDATION,
        HEADROOM,
        COLUMN_DOMAIN,
        UNKNOWN_COLUMNS,
        NATIVE_TOPOLOGY
    }

    public enum Edit {
        KEEP,
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

    /** Dimensionless tunings; four families have independent occurrence, detail and width. */
    public static final class Tuning {
        public final Family family;
        public final double occurrence, widthScale, reliefScale, detail, coveDepth;
        public final double minimumRock, minimumExposure, archFraction;

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
            archFraction = bounded(p, "archFraction", 0, 1);
        }
    }

    /** Earlier immutable shoreline patch and conservative certificates for its entire support.
     * Normal points inland. origin is absolute, and stableId belongs to the earlier coast owner.
     * Foundation and permit certificates must cover the FULL edited body footprint, not
     * only the anchor. The larger early-out AABB is not the edited body footprint.
     */
    public static final class Anchor {
        public final long seed, stableId;
        public final double x, z, nx, nz, minimumParent, maximumParent, foundationY;
        public final double rockFit, exposure;
        public final boolean permit3D, foundation;

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
                double exposure,
                boolean permit3D,
                boolean foundation) {
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
            this.permit3D = permit3D;
            this.foundation = foundation;
        }
    }

    /** One snapshot from earlier maps. distance is signed inland; never inferred from height.
     * protectedWeight includes a caller-prepared transition skirt around protected shoreline.
     * waterReserved includes clearance skirts. No terrain/provider callback is accepted.
     */
    public static final class Input {
        public final double x, z, distance, parent, rockFit, exposure, protectedWeight;
        public final boolean marine, waterReserved, permit3D;

        public Input(
                double x,
                double z,
                double distance,
                double parent,
                double rockFit,
                double exposure,
                double protectedWeight,
                boolean marine,
                boolean waterReserved,
                boolean permit3D) {
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
            this.permit3D = permit3D;
        }
    }

    /** Caller-owned scratch: one per worker, overwritten in full on every query. */
    public static final class Column {
        public double height, target, weight, localU, localD, bodyTop;
        public Reason reason;
        public boolean arch;
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
        public final double radiusU, radiusD, centerD, roof, tunnelCenterY, tunnelRadiusY;
        public final double maximumBodyTop, minimumRoofThickness;
        public final boolean arch;

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
            radiusU = width * (.32 + .12 * unit(seed + 2));
            radiusD = width * (.25 + .1 * unit(seed + 3));
            centerD = -offshore * (.46 + .02 * unit(seed + 4));
            roof = r.sea + drop;
            tunnelRadiusY = drop * .22;
            tunnelCenterY = r.sea + drop * .29;
            maximumBodyTop = roof + drop * t.detail;
            minimumRoofThickness = drop * (.34 - t.detail);
            arch = unit(seed + 5) < t.archFraction;
            Reason q = Reason.ACCEPTED;
            if (a.rockFit < t.minimumRock) q = Reason.NO_ROCK_INTENT;
            else if (a.exposure < t.minimumExposure) q = Reason.LOW_EXPOSURE;
            else if (a.minimumParent - r.sea < r.dropMin) q = Reason.INSUFFICIENT_RELIEF;
            else if (a.maximumParent > r.high
                    || a.minimumParent < r.low
                    || (f == Family.STACK_ARCH && maximumBodyTop > r.high)
                    || a.foundationY < r.low
                    || (f == Family.STACK_ARCH && a.foundationY > r.waterTop - r.dropMin))
                q = Reason.HEADROOM;
            else if (f == Family.STACK_ARCH && !a.permit3D) q = Reason.NO_3D_PERMIT;
            else if (f == Family.STACK_ARCH && !a.foundation) q = Reason.NO_FOUNDATION;
            else if (f == Family.STACK_ARCH && arch && minimumRoofThickness < r.dropMin)
                q = Reason.INSUFFICIENT_RELIEF;
            else if (unit(seed + 6) >= t.occurrence) q = Reason.OCCURRENCE;
            admission = q;
        }
    }

    public static Plan prepare(Family family, Anchor earlier, Reference reference, Tuning tuning) {
        if (tuning.family != family)
            throw new IllegalArgumentException("Tuning belongs to another family");
        return new Plan(family, earlier, reference, tuning);
    }

    /** Samples one column, no neighbor reads. Families 1-3 return highest solid Y.
     * STACK_ARCH height is only the envelope; occupancy() is authoritative for that family.
     */
    public static void sample(Plan p, Input i, Column o) {
        sample(
                p,
                i.x,
                i.z,
                i.distance,
                i.parent,
                i.rockFit,
                i.exposure,
                i.protectedWeight,
                i.marine,
                i.waterReserved,
                i.permit3D,
                false,
                o);
    }

    private static void sample(
            Plan p,
            double x,
            double z,
            double distance,
            double parent,
            double rock,
            double exposure,
            double protection,
            boolean marine,
            boolean reserved,
            boolean permit,
            boolean nativeParent,
            Column o) {
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
        if (reserved) {
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
        if ((!marine && !nativeParent && parent < p.anchor.minimumParent)
                || (marine && parent < p.anchor.foundationY)
                || parent > p.anchor.maximumParent
                || (marine && p.family != Family.STACK_ARCH && parent > p.ref.waterTop - 1)) {
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
        if (p.family == Family.STACK_ARCH) {
            bodyColumn(p, x, z, parent, permit, o);
            return;
        }
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
        o.bodyTop = Double.NEGATIVE_INFINITY;
        o.reason = Reason.OUTSIDE_SUPPORT;
        o.arch = p.arch;
        o.localU = (x - p.anchor.x) * (-p.anchor.nz) + (z - p.anchor.z) * p.anchor.nx;
        o.localD = (x - p.anchor.x) * p.anchor.nx + (z - p.anchor.z) * p.anchor.nz;
    }

    private static void bodyColumn(
            Plan p, double x, double z, double parent, boolean permit, Column o) {
        if (!permit) {
            o.reason = Reason.NO_3D_PERMIT;
            o.weight = 0;
            return;
        }
        // Binary occupancy is suppressed in skirts; it must not be alpha blended into water.
        if (o.weight < .999999) {
            o.reason = Reason.OUTSIDE_SUPPORT;
            o.weight = 0;
            return;
        }
        double u = o.localU, d = o.localD, shape;
        if (p.arch) {
            double extent = Math.min(p.width * 2, p.offshore * .6);
            double taper = .72 + .28 * smooth((d + extent) / (p.width * .8));
            double a = Math.abs(u) / (p.radiusU * taper), b = Math.abs(d) / extent;
            shape = a * a * a * a + b * b * b * b * b * b;
        } else {
            shape = Math.sqrt(sq(u / p.radiusU) + sq((d - p.centerD) / p.radiusD));
        }
        if (shape >= 1) {
            o.reason = Reason.OUTSIDE_SUPPORT;
            o.weight = 0;
            return;
        }
        double crest =
                p.roof
                        - p.drop
                                * (.15 * shape * shape
                                        + p.tuning.detail
                                                * noise(
                                                        p.seed + 71,
                                                        x / (p.width * .7),
                                                        z / (p.width * .7)));
        o.bodyTop = crest;
        o.target = crest;
        o.height = Math.max(parent, crest);
    }

    /** y is integer block Y. The column must be from exactly this plan/input on this worker.
     * SOLID/VOID overrides only this formation; KEEP delegates to the earlier solid resolver.
     * Fluids are resolved later. A column envelope cannot represent an arch opening.
     */
    public static Edit occupancy(Plan p, Input i, Column c, int y) {
        return occupancy(p, c, y, p.anchor.foundationY);
    }

    private static Edit occupancy(Plan p, Column c, int y, double foundation) {
        if (p.family != Family.STACK_ARCH
                || c.reason != Reason.ACCEPTED
                || y < foundation
                || y > Math.floor(c.bodyTop)) return Edit.KEEP;
        if (p.arch) {
            double d = (c.localD + p.width * .45) / (p.width * .60);
            double yy = (y - p.tunnelCenterY) / p.tunnelRadiusY;
            // Through-tunnel axis is tangent, across the full thin headland.
            if (d * d + yy * yy < 1) return Edit.VOID;
        }
        return Edit.SOLID;
    }

    /** Geometry only: no accepted continuous Plan or fabricated foundation is exposed. */
    public static final class ColumnProposal {
        private final Plan geometry;
        public final Family family;
        public final boolean arch;
        public final double x,
                z,
                nx,
                nz,
                halfAlong,
                inland,
                offshore,
                width,
                drop,
                radiusU,
                radiusD,
                centerD;
        public final double tunnelCenterY, tunnelRadiusY, maximumBodyTop;
        public final double minX,
                minZ,
                maxX,
                maxZ,
                bodyMinX,
                bodyMinZ,
                bodyMaxX,
                bodyMaxZ,
                supportRadius;
        public final int boxColumns;

        private ColumnProposal(Plan p) {
            geometry = p;
            family = p.family;
            arch = p.arch;
            x = p.anchor.x;
            z = p.anchor.z;
            nx = p.anchor.nx;
            nz = p.anchor.nz;
            halfAlong = p.halfAlong;
            inland = p.inland;
            offshore = p.offshore;
            width = p.width;
            drop = p.drop;
            radiusU = p.radiusU;
            radiusD = p.radiusD;
            centerD = p.centerD;
            tunnelCenterY = p.tunnelCenterY;
            tunnelRadiusY = p.tunnelRadiusY;
            maximumBodyTop = p.maximumBodyTop;
            double[] box = bounds(p.anchor, -halfAlong, halfAlong, -offshore, inland);
            minX = box[0];
            minZ = box[1];
            maxX = box[2];
            maxZ = box[3];
            double extent = Math.min(width * 2, offshore * .6);
            box =
                    bounds(
                            p.anchor,
                            -radiusU,
                            radiusU,
                            arch ? -extent : centerD - radiusD,
                            arch ? extent : centerD + radiusD);
            bodyMinX = box[0];
            bodyMinZ = box[1];
            bodyMaxX = box[2];
            bodyMaxZ = box[3];
            supportRadius = Math.hypot(halfAlong, Math.max(inland, offshore));
            long count =
                    ((long) Math.ceil(maxX) - (long) Math.floor(minX) + 1)
                            * ((long) Math.ceil(maxZ) - (long) Math.floor(minZ) + 1);
            boxColumns = (int) Math.min(Integer.MAX_VALUE, count);
        }
    }

    private static double[] bounds(Anchor a, double minU, double maxU, double minD, double maxD) {
        double minX = Double.POSITIVE_INFINITY, minZ = minX, maxX = -minX, maxZ = -minX;
        for (int u = 0; u < 2; u++)
            for (int d = 0; d < 2; d++) {
                double along = u == 0 ? minU : maxU,
                        inland = d == 0 ? minD : maxD,
                        x = a.x - a.nz * along + a.nx * inland,
                        z = a.z + a.nx * along + a.nz * inland;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        return new double[] {minX, minZ, maxX, maxZ};
    }

    public static ColumnProposal proposeColumns(
            Family family,
            long seed,
            long id,
            double x,
            double z,
            double nx,
            double nz,
            Reference reference,
            Tuning tuning) {
        Anchor pending =
                new Anchor(
                        seed,
                        id,
                        x,
                        z,
                        nx,
                        nz,
                        reference.sea,
                        reference.sea,
                        reference.low,
                        0,
                        0,
                        false,
                        false);
        return new ColumnProposal(prepare(family, pending, reference, tuning));
    }

    public static boolean occurs(ColumnProposal p) {
        return unit(p.geometry.seed + 6) < p.geometry.tuning.occurrence;
    }

    public static boolean inSupport(ColumnProposal p, double x, double z, double distance) {
        double u = (x - p.x) * (-p.nz) + (z - p.z) * p.nx, d = (x - p.x) * p.nx + (z - p.z) * p.nz;
        return Math.abs(u) < p.halfAlong
                && d > -p.offshore
                && d < p.inland
                && distance > -p.offshore
                && distance < p.inland;
    }

    public static double coreWeight(
            ColumnProposal p, int x, int z, double distance, double protection) {
        if (!inSupport(p, x, z, distance)) return 0;
        double u = (x - p.x) * (-p.nz) + (z - p.z) * p.nx, d = (x - p.x) * p.nx + (z - p.z) * p.nz;
        double side = 1 - smooth((Math.abs(u) / p.halfAlong - .72) / .28),
                end = 1 - smooth(((d < 0 ? -d / p.offshore : d / p.inland) - .65) / .35),
                shore =
                        1
                                - smooth(
                                        ((distance < 0
                                                                ? -distance / p.offshore
                                                                : distance / p.inland)
                                                        - .65)
                                                / .35);
        return side * end * shore * (1 - protection);
    }

    /** Unguarded hypothetical body, solely for the native caller's footprint and voxel certificate. */
    public static void probeBodyColumns(ColumnProposal p, int x, int z, double parent, Column out) {
        reset(p.geometry, x, z, parent, out);
        out.weight = 1;
        out.reason = Reason.ACCEPTED;
        bodyColumn(p.geometry, x, z, parent, true, out);
    }

    public static Edit probeOccupancy(ColumnProposal p, Column c, int y, double foundation) {
        return occupancy(p.geometry, c, y, foundation);
    }

    /** Exact source tunnel cylinder; limiting portal search to this domain prevents an outside detour. */
    public static boolean portalCylinder(ColumnProposal p, Column c, int y) {
        double d = (c.localD + p.width * .45) / (p.width * .60),
                v = (y - p.tunnelCenterY) / p.tunnelRadiusY;
        return Math.abs(c.localU) <= p.radiusU + 2.5 && d * d + v * v < 1;
    }

    public static final class ColumnFacts {
        private final ColumnProposal proposal;
        private final double minimumLand,
                maximumParent,
                foundationY,
                rock,
                exposure,
                nativeMin,
                nativeMax;
        private final int columns, landColumns, bodyColumns;
        private final boolean foundation, core, attachment, portals;

        public ColumnFacts(
                ColumnProposal p,
                int columns,
                int landColumns,
                int bodyColumns,
                double minimumLand,
                double maximumParent,
                double foundationY,
                double rock,
                double exposure,
                double nativeMin,
                double nativeMax,
                boolean foundation,
                boolean core,
                boolean attachment,
                boolean portals) {
            if (p == null
                    || columns < 0
                    || landColumns < 0
                    || bodyColumns < 0
                    || !finite(
                            minimumLand,
                            maximumParent,
                            foundationY,
                            rock,
                            exposure,
                            nativeMin,
                            nativeMax)
                    || minimumLand > maximumParent
                    || nativeMin > nativeMax
                    || rock < 0
                    || rock > 1
                    || exposure < 0
                    || exposure > 1)
                throw new IllegalArgumentException("Invalid native certificate");
            proposal = p;
            this.columns = columns;
            this.landColumns = landColumns;
            this.bodyColumns = bodyColumns;
            this.minimumLand = minimumLand;
            this.maximumParent = maximumParent;
            this.foundationY = foundationY;
            this.rock = rock;
            this.exposure = exposure;
            this.nativeMin = nativeMin;
            this.nativeMax = nativeMax;
            this.foundation = foundation;
            this.core = core;
            this.attachment = attachment;
            this.portals = portals;
        }
    }

    public static final class ColumnPlan {
        private final Plan geometry;
        private final double nativeMin, nativeMax;
        public final Family family;
        public final boolean arch;
        public final double minX, minZ, maxX, maxZ;

        private ColumnPlan(Plan p, ColumnProposal proposal, ColumnFacts facts) {
            geometry = p;
            nativeMin = facts.nativeMin;
            nativeMax = facts.nativeMax;
            family = p.family;
            arch = p.arch;
            boolean body = family == Family.STACK_ARCH;
            minX = body ? proposal.bodyMinX : proposal.minX;
            minZ = body ? proposal.bodyMinZ : proposal.minZ;
            maxX = body ? proposal.bodyMaxX : proposal.maxX;
            maxZ = body ? proposal.bodyMaxZ : proposal.maxZ;
        }

        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public static final class ColumnDecision {
        public final ColumnPlan plan;
        public final Reason reason;

        private ColumnDecision(Reason reason, ColumnPlan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    public static ColumnDecision admitColumns(ColumnProposal p, ColumnFacts facts) {
        if (facts.proposal != p || facts.columns != p.boxColumns || facts.landColumns == 0)
            return new ColumnDecision(Reason.UNKNOWN_COLUMNS, null);
        boolean body = p.family == Family.STACK_ARCH;
        if (body
                && (facts.bodyColumns == 0
                        || !facts.core
                        || !facts.attachment
                        || (p.arch && !facts.portals)))
            return new ColumnDecision(Reason.NATIVE_TOPOLOGY, null);
        Plan g = p.geometry;
        Anchor a = g.anchor;
        Anchor certified =
                new Anchor(
                        a.seed,
                        a.stableId,
                        a.x,
                        a.z,
                        a.nx,
                        a.nz,
                        facts.minimumLand,
                        facts.maximumParent,
                        facts.foundationY,
                        facts.rock,
                        facts.exposure,
                        body && facts.core && facts.attachment && (!p.arch || facts.portals),
                        facts.foundation);
        Plan admitted = prepare(p.family, certified, g.ref, g.tuning);
        return new ColumnDecision(
                admitted.admission,
                admitted.admission == Reason.ACCEPTED ? new ColumnPlan(admitted, p, facts) : null);
    }

    /** Canonical shore is the blend parent; the authored cliff target still reads actual pre-shore relief. */
    public static void sampleColumns(
            ColumnPlan p,
            double x,
            double z,
            double distance,
            double broadParent,
            double canonicalParent,
            double protection,
            boolean marine,
            boolean reserved,
            Column out) {
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || !Double.isFinite(x)
                || !Double.isFinite(z)
                || !Double.isFinite(distance)
                || !Double.isFinite(broadParent)
                || !Double.isFinite(canonicalParent)
                || !Double.isFinite(protection)) {
            reset(p.geometry, x, z, canonicalParent, out);
            out.reason = Reason.COLUMN_DOMAIN;
            return;
        }
        if (canonicalParent < p.nativeMin || canonicalParent > p.nativeMax) {
            reset(p.geometry, x, z, canonicalParent, out);
            out.reason = Reason.HEADROOM;
            return;
        }
        Plan g = p.geometry;
        boolean volume = p.family == Family.STACK_ARCH;
        sample(
                g,
                x,
                z,
                distance,
                volume ? canonicalParent : broadParent,
                g.anchor.rockFit,
                g.anchor.exposure,
                protection,
                marine,
                reserved,
                volume,
                volume,
                out);
        if (!volume)
            out.height =
                    out.reason == Reason.ACCEPTED
                            ? canonicalParent + (out.target - canonicalParent) * out.weight
                            : canonicalParent;
    }

    public static Edit occupancyColumns(ColumnPlan p, Column c, int y) {
        return occupancy(p.geometry, c, y, p.geometry.anchor.foundationY);
    }

    /** At most four exact transition boundaries. Binary searches call the same integer occupancy predicate as cubes. */
    public static int columnCuts(ColumnPlan p, Column c, int[] out) {
        Plan g = p.geometry;
        if (p.family != Family.STACK_ARCH || c.reason != Reason.ACCEPTED) return 0;
        int bottom = (int) Math.ceil(g.anchor.foundationY), top = (int) Math.floor(c.bodyTop);
        if (top < bottom) return 0;
        out[0] = bottom;
        out[1] = top + 1;
        if (!p.arch) return 2;
        int center = (int) clamp(Math.round(g.tunnelCenterY), bottom, top);
        if (occupancyColumns(p, c, center) != Edit.VOID) return 2;
        int low = bottom, high = center;
        while (low < high) {
            int m = low + (high - low) / 2;
            if (occupancyColumns(p, c, m) == Edit.VOID) high = m;
            else low = m + 1;
        }
        out[2] = low;
        low = center;
        high = top;
        while (low < high) {
            int m = low + (high - low + 1) / 2;
            if (occupancyColumns(p, c, m) == Edit.VOID) low = m;
            else high = m - 1;
        }
        out[3] = low + 1;
        return 4;
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

    static double sq(double x) {
        return x * x;
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
