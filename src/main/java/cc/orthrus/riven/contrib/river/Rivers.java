/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Riven contributors.
 * Adapted from contributions/rivers-streams-20261019. The original continuous API
 * remains; native columns use separate bound admission/plan types, physical width
 * scaling and a foundation reservation for dry banks/braid bars. Geometry is retained.
 * Packaged license: META-INF/licenses/rivers/MIT.txt.
 */
package cc.orthrus.riven.contrib.river;

import java.util.ArrayList;
import java.util.List;

/** Independently authored finite river geometry. No world access, jobs, caches or writes. */
public final class Rivers {
    private Rivers() {}

    public enum Family {
        BEND(0x52495642454e4431L, .012),
        SPLIT(0x52495653504c5431L, .020),
        POCKET(0x524956504f434b31L, .100),
        JUNCTION(0x5249564a554e4331L, .040);
        public final long salt;
        public final double maxGrade;

        Family(long salt, double maxGrade) {
            this.salt = salt;
            this.maxGrade = maxGrade;
        }
    }

    public enum Rejection {
        ACCEPT,
        NO_SUPPLY,
        NO_RECEIVER,
        UNRESERVED,
        PROTECTED,
        UNSUPPORTED,
        GRADE,
        SCALE,
        VOXEL_WIDTH,
        BOUNDS,
        CORRIDOR,
        JUNCTION_REQUIRED,
        JUNCTION_MISMATCH,
        FOLDED
    }

    /** Values are from earlier immutable corridor evidence, never shaped-terrain probes.
     * lateralAllowance covers BOTH displacement and channel/bank support from each input line.
     * minParent/maxParent and bank/bed support must certify that entire swept envelope.
     * supported includes bank freeboard >=1 above the corresponding local water block,
     * continuous floor support below the maximum permitted depth, and no lateral leak. */
    public static final class Admission {
        public final boolean supplied, receiver, reserved, protectedShore, supported;
        public final double lateralAllowance, minParent, maxParent;

        public Admission(
                boolean supplied,
                boolean receiver,
                boolean reserved,
                boolean protectedShore,
                boolean supported,
                double lateralAllowance,
                double minParent,
                double maxParent) {
            this.supplied = supplied;
            this.receiver = receiver;
            this.reserved = reserved;
            this.protectedShore = protectedShore;
            this.supported = supported;
            this.lateralAllowance = lateralAllowance;
            this.minParent = minParent;
            this.maxParent = maxParent;
            require(
                    finite(lateralAllowance, minParent, maxParent)
                            && lateralAllowance > 0
                            && minParent <= maxParent);
        }
    }

    /** Separate certificate domain: every native integer column of the declared envelope.
     * The caller supplies actual earlier reservations/receivers and a constructed sealed
     * foundation. Binding prevents a certificate being attached to a different proposal.
     * This is not a certificate over the continuous coordinate plane.
     */
    public static final class ColumnAdmission {
        private final Family family;
        private final Route main, tributary;
        private final Tuning tuning;
        private final double horizontalScale;
        private final Admission facts;
        public final int columns;

        public ColumnAdmission(
                Family family,
                Route main,
                Route tributary,
                Tuning tuning,
                double horizontalScale,
                boolean supplied,
                boolean receiver,
                boolean reserved,
                boolean protectedShore,
                boolean supported,
                double lateralAllowance,
                double minParent,
                double maxParent,
                int columns) {
            require(
                    family != null
                            && main != null
                            && tuning != null
                            && Double.isFinite(horizontalScale)
                            && horizontalScale > 0
                            && horizontalScale <= 1
                            && columns > 0);
            this.family = family;
            this.main = main;
            this.tributary = tributary;
            this.tuning = tuning;
            this.horizontalScale = horizontalScale;
            this.columns = columns;
            facts =
                    new Admission(
                            supplied,
                            receiver,
                            reserved,
                            protectedShore,
                            supported,
                            lateralAllowance,
                            minParent,
                            maxParent);
        }
    }

    public static final class ColumnResult {
        public final Rejection reason;
        public final ColumnPlan plan;

        private ColumnResult(Rejection reason, ColumnPlan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    /** The private continuous geometry cannot escape the native certificate domain. */
    public static final class ColumnPlan {
        public final Family family;
        public final long routeId;
        public final int segments;
        public final double minX, maxX, minZ, maxZ;
        public final double minParent, maxParent;
        private final Plan geometry;

        private ColumnPlan(Plan plan, double minParent, double maxParent) {
            geometry = plan;
            family = plan.family;
            routeId = plan.routeId;
            segments = plan.segments;
            this.minParent = minParent;
            this.maxParent = maxParent;
            minX = plan.minX - plan.columnPadding;
            maxX = plan.maxX + plan.columnPadding;
            minZ = plan.minZ - plan.columnPadding;
            maxZ = plan.maxZ + plan.columnPadding;
        }

        public void sample(double x, double z, double parent, boolean permitted, Sample out) {
            require(Double.isFinite(parent) && parent >= minParent && parent <= maxParent);
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || x != Math.floor(x)
                    || z != Math.floor(z)) {
                out.clear(parent);
                return;
            }
            geometry.sample(x, z, parent, permitted, out, true);
        }
    }

    /** Earlier route, ordered upstream to downstream; water is highest occupied water block Y.
     * width is FULL footprint, not radius. Defensive copies make routes shareable. */
    public static final class Route {
        private final double[] x, z, w, s;
        public final long id;
        public final double width, depth, length, grade;

        public Route(long id, double[] x, double[] z, double[] waterY, double width, double depth) {
            require(
                    x != null
                            && z != null
                            && waterY != null
                            && x.length >= 2
                            && x.length <= 33
                            && x.length == z.length
                            && x.length == waterY.length);
            require(finite(width, depth) && width > 0 && depth > 0);
            this.id = id;
            this.x = x.clone();
            this.z = z.clone();
            this.w = waterY.clone();
            this.width = width;
            this.depth = depth;
            s = new double[x.length];
            double g = 0;
            for (int i = 0; i < x.length; i++) {
                require(
                        finite(x[i], z[i], w[i])
                                && Math.abs(x[i]) <= 29999000
                                && Math.abs(z[i]) <= 29999000);
                if (i > 0) {
                    double d = StrictMath.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
                    require(d >= 2 && d <= 256 && w[i] <= w[i - 1]);
                    s[i] = s[i - 1] + d;
                    g = Math.max(g, (w[i - 1] - w[i]) / d);
                }
            }
            length = s[s.length - 1];
            grade = g;
            require(length >= 16 && length <= 2048);
        }

        public int nodes() {
            return x.length;
        }

        public double x(int i) {
            return x[i];
        }

        public double z(int i) {
            return z[i];
        }

        public double water(int i) {
            return w[i];
        }
    }

    /** All values are dimensionless except bankSkirt (blocks). Bounded to keep admission conservative. */
    public static final class Tuning {
        public final double bendWidths,
                widthVariation,
                poolFraction,
                barFraction,
                splitWidths,
                bankSkirt;

        public Tuning(
                double bendWidths,
                double widthVariation,
                double poolFraction,
                double barFraction,
                double splitWidths,
                double bankSkirt) {
            require(
                    finite(
                            bendWidths,
                            widthVariation,
                            poolFraction,
                            barFraction,
                            splitWidths,
                            bankSkirt));
            require(
                    bendWidths >= 0
                            && bendWidths <= 2
                            && widthVariation >= 0
                            && widthVariation <= .25
                            && poolFraction >= 0
                            && poolFraction <= .35
                            && barFraction >= 0
                            && barFraction <= .8
                            && splitWidths >= .5
                            && splitWidths <= 1.5
                            && bankSkirt >= 1
                            && bankSkirt <= 12);
            this.bendWidths = bendWidths;
            this.widthVariation = widthVariation;
            this.poolFraction = poolFraction;
            this.barFraction = barFraction;
            this.splitWidths = splitWidths;
            this.bankSkirt = bankSkirt;
        }

        public static Tuning defaults() {
            return new Tuning(1.6, .16, .28, .65, 1.1, 4);
        }
    }

    public static final class Result {
        public final Rejection reason;
        public final Plan plan;

        private Result(Rejection reason, Plan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    private static final class Line {
        final double[] x, z, w, r, d, skew;
        final int n;

        Line(int n) {
            this.n = n;
            x = new double[n];
            z = new double[n];
            w = new double[n];
            r = new double[n];
            d = new double[n];
            skew = new double[n];
        }
    }

    /** Caller owns one mutable sample per concurrent caller. No allocation in sample(). */
    public static final class Sample {
        public double surface, waterY, weight, depth;

        /** Native-only pinned bank/bar weight, with a second outer feather. */
        public double foundationWeight;

        public boolean wet, bar;

        private void clear(double parent) {
            surface = parent;
            waterY = Double.NaN;
            weight = depth = foundationWeight = 0;
            wet = bar = false;
        }
    }

    public static final class Plan {
        public final Family family;
        public final long seed, routeId;
        public final double minX, maxX, minZ, maxZ, bankSkirt, maximumDepth;
        public final int segments;
        private final Line[] lines;
        private final double columnPadding;

        private Plan(
                Family f, long seed, long id, Line[] lines, double skirt, double maximumDepth) {
            family = f;
            this.seed = seed;
            routeId = id;
            this.lines = lines;
            bankSkirt = skirt;
            this.maximumDepth = maximumDepth;
            double a = Double.POSITIVE_INFINITY, b = -a, c = a, d = -a;
            int count = 0;
            for (Line l : lines) {
                count += l.n - 1;
                for (int i = 0; i < l.n; i++) {
                    double margin = l.r[i] + skirt;
                    a = Math.min(a, l.x[i] - margin);
                    b = Math.max(b, l.x[i] + margin);
                    c = Math.min(c, l.z[i] - margin);
                    d = Math.max(d, l.z[i] + margin);
                }
            }
            minX = a;
            maxX = b;
            minZ = c;
            maxZ = d;
            segments = count;
            double span = 0, minRadius = Double.POSITIVE_INFINITY, maxRadius = 0;
            for (Line line : lines)
                for (double value : line.r) {
                    minRadius = Math.min(minRadius, value);
                    maxRadius = Math.max(maxRadius, value);
                }
            if (f == Family.SPLIT)
                for (int i = 0; i < lines[0].n; i++)
                    span =
                            Math.max(
                                    span,
                                    StrictMath.hypot(
                                                    lines[0].x[i] - lines[1].x[i],
                                                    lines[0].z[i] - lines[1].z[i])
                                            * .5);
            columnPadding = skirt + span + maxRadius - minRadius;
        }

        /** parent is an earlier frozen column height; permitted is an earlier reservation mask.
         * No eligibility decisions read this result. Endpoint disks are owned by adjacent supplied reaches. */
        public void sample(double x, double z, double parent, boolean permitted, Sample out) {
            sample(x, z, parent, permitted, out, false);
        }

        private void sample(
                double x,
                double z,
                double parent,
                boolean permitted,
                Sample out,
                boolean nativeBanks) {
            out.clear(parent);
            double extra = nativeBanks ? columnPadding : 0;
            if (!permitted
                    || x < minX - extra
                    || x > maxX + extra
                    || z < minZ - extra
                    || z > maxZ + extra) return;
            double wetBed = Double.POSITIVE_INFINITY, wetW = Double.POSITIVE_INFINITY;
            double bankSurface = parent, maxWeight = 0, guardWater = Double.NEGATIVE_INFINITY;
            boolean bar = false;
            for (Line l : lines)
                for (int i = 0; i < l.n - 1; i++) {
                    double dx = l.x[i + 1] - l.x[i],
                            dz = l.z[i + 1] - l.z[i],
                            len2 = dx * dx + dz * dz;
                    double t = clamp(((x - l.x[i]) * dx + (z - l.z[i]) * dz) / len2, 0, 1);
                    double px = x - l.x[i] - t * dx, pz = z - l.z[i] - t * dz;
                    double r = mix(l.r[i], l.r[i + 1], t), dist = StrictMath.hypot(px, pz);
                    if (nativeBanks)
                        out.foundationWeight =
                                Math.max(
                                        out.foundationWeight,
                                        1 - smooth((dist - r - bankSkirt) / bankSkirt));
                    if (nativeBanks && dist <= Math.max(l.r[i], l.r[i + 1]) + 1) {
                        // A face-adjacent wet column can lie anywhere along this segment.
                        // Its endpoint head envelope bounds the required dry solid top.
                        guardWater = Math.max(guardWater, Math.floor(Math.max(l.w[i], l.w[i + 1])));
                        out.foundationWeight = 1;
                    }
                    if (dist >= r + bankSkirt) continue;
                    double w = mix(l.w[i], l.w[i + 1], t), dep = mix(l.d[i], l.d[i + 1], t);
                    double q = dist / r, side = (dx * pz - dz * px) / (StrictMath.sqrt(len2) * r);
                    double skew = mix(l.skew[i], l.skew[i + 1], t);
                    if (q < 1) {
                        // Keep at least one voxel of wet thalweg and a shallow wetted edge.
                        double shaped =
                                clamp(dep * (1 - .64 * q * q) - dep * skew * side, 1, maximumDepth);
                        double bed = Math.floor(w) - shaped;
                        wetBed = Math.min(wetBed, bed);
                        wetW = Math.min(wetW, Math.floor(w));
                        bar |= skew * side > .12;
                        maxWeight = 1;
                    } else {
                        double weight = 1 - smooth((dist - r) / bankSkirt);
                        bankSurface = Math.min(bankSurface, mix(parent, Math.floor(w) + 1, weight));
                        maxWeight = Math.max(maxWeight, weight);
                    }
                }
            if (nativeBanks && family == Family.SPLIT) {
                // The ribbon between paired threads is an owned dry bar even where
                // neither channel's ordinary bank skirt reaches its middle.
                Line left = lines[0], right = lines[1];
                for (int i = 0; i < left.n - 1; i++) {
                    double ax = (left.x[i] + right.x[i]) * .5, az = (left.z[i] + right.z[i]) * .5;
                    double bx = (left.x[i + 1] + right.x[i + 1]) * .5,
                            bz = (left.z[i + 1] + right.z[i + 1]) * .5;
                    double dx = bx - ax, dz = bz - az;
                    double t = clamp(((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz), 0, 1);
                    double ra =
                            StrictMath.hypot(left.x[i] - right.x[i], left.z[i] - right.z[i]) * .5
                                    + Math.max(left.r[i], right.r[i]);
                    double rb =
                            StrictMath.hypot(
                                                    left.x[i + 1] - right.x[i + 1],
                                                    left.z[i + 1] - right.z[i + 1])
                                            * .5
                                    + Math.max(left.r[i + 1], right.r[i + 1]);
                    double dist = StrictMath.hypot(x - ax - t * dx, z - az - t * dz);
                    out.foundationWeight =
                            Math.max(
                                    out.foundationWeight,
                                    1 - smooth((dist - mix(ra, rb, t) - bankSkirt) / bankSkirt));
                }
            }
            out.weight = maxWeight;
            if (wetBed < Double.POSITIVE_INFINITY) {
                out.surface = wetBed;
                out.waterY = wetW;
                out.depth = wetW - wetBed;
                out.wet = wetBed < wetW;
                out.bar = bar;
            } else out.surface = nativeBanks ? Math.max(bankSurface, guardWater) : bankSurface;
        }

        public int lines() {
            return lines.length;
        }

        public int nodes(int line) {
            return lines[line].n;
        }

        public double x(int line, int node) {
            return lines[line].x[node];
        }

        public double z(int line, int node) {
            return lines[line].z[node];
        }

        public double water(int line, int node) {
            return lines[line].w[node];
        }

        public double radius(int line, int node) {
            return lines[line].r[node];
        }
    }

    /** One bed's width and depth band from the adopted height contract. */
    public static final class Band {
        final double minWidth, maxWidth, minDepth, maxDepth;

        public Band(double minWidth, double maxWidth, double minDepth, double maxDepth) {
            require(
                    Double.isFinite(minWidth)
                            && Double.isFinite(maxWidth)
                            && Double.isFinite(minDepth)
                            && Double.isFinite(maxDepth));
            this.minWidth = minWidth;
            this.maxWidth = maxWidth;
            this.minDepth = minDepth;
            this.maxDepth = maxDepth;
        }
    }

    /**
     * The height facts a river kernel reads, supplied by its adapter from the adopted height
     * contract: the stream and river bed bands and the reference surface bounds.
     */
    public static final class Reference {
        final Band stream, river;
        final int lowestSurface, highestSurface;

        public Reference(Band stream, Band river, int lowestSurface, int highestSurface) {
            require(stream != null && river != null && lowestSurface <= highestSurface);
            this.stream = stream;
            this.river = river;
            this.lowestSurface = lowestSurface;
            this.highestSurface = highestSurface;
        }
    }

    public static Result prepare(
            Family f,
            long seed,
            Route main,
            Route tributary,
            Admission a,
            Tuning tune,
            Reference heights) {
        return prepareGeometry(f, seed, main, tributary, a, tune, heights, 1);
    }

    public static ColumnResult prepareColumns(
            Family f,
            long seed,
            Route main,
            Route tributary,
            ColumnAdmission a,
            Tuning tune,
            Reference heights,
            double horizontalScale) {
        require(
                a.family == f
                        && a.main == main
                        && a.tributary == tributary
                        && a.tuning == tune
                        && a.horizontalScale == horizontalScale);
        double highest = Math.floor(main.water(0));
        if (tributary != null) highest = Math.max(highest, Math.floor(tributary.water(0)));
        if (a.facts.minParent < highest + 1) return new ColumnResult(Rejection.UNSUPPORTED, null);
        Result result =
                prepareGeometry(f, seed, main, tributary, a.facts, tune, heights, horizontalScale);
        return new ColumnResult(
                result.reason,
                result.plan == null
                        ? null
                        : new ColumnPlan(result.plan, a.facts.minParent, a.facts.maxParent));
    }

    private static Result prepareGeometry(
            Family f,
            long seed,
            Route main,
            Route tributary,
            Admission a,
            Tuning tune,
            Reference heights,
            double horizontalScale) {
        if (!a.supplied) return reject(Rejection.NO_SUPPLY);
        if (!a.receiver) return reject(Rejection.NO_RECEIVER);
        if (!a.reserved) return reject(Rejection.UNRESERVED);
        if (a.protectedShore) return reject(Rejection.PROTECTED);
        if (!a.supported) return reject(Rejection.UNSUPPORTED);
        if (f == Family.JUNCTION && tributary == null) return reject(Rejection.JUNCTION_REQUIRED);
        Band ref = f == Family.POCKET ? heights.stream : heights.river;
        double minimumWidth = ref.minWidth * horizontalScale,
                maximumWidth = ref.maxWidth * horizontalScale;
        Route[] routes = f == Family.JUNCTION ? new Route[] {main, tributary} : new Route[] {main};
        double maxMove =
                (f == Family.BEND
                                ? tune.bendWidths
                                : f == Family.SPLIT
                                        ? tune.splitWidths
                                        : f == Family.POCKET ? .25 : .35)
                        * main.width;
        double maxWidth = main.width * (1 + tune.widthVariation), maxDepth = ref.maxDepth;
        for (Route route : routes) {
            if (f == Family.JUNCTION) maxMove = Math.max(maxMove, .35 * route.width);
            if (route.grade > f.maxGrade) return reject(Rejection.GRADE);
            if (route.width * (1 - tune.widthVariation) < minimumWidth
                    || route.width * (1 + tune.widthVariation) > maximumWidth
                    || route.depth * (1 - tune.poolFraction) < ref.minDepth
                    || route.depth * (1 + tune.poolFraction) > ref.maxDepth)
                return reject(Rejection.SCALE);
            // A Euclidean tube thinner than a grid-cell diagonal can leave only diagonal
            // water columns. Reserve a positive rounding margin at world-sized coordinates.
            if (route.width * (1 - tune.widthVariation) < StrictMath.sqrt(2) + 1e-6)
                return reject(Rejection.VOXEL_WIDTH);
            maxWidth = Math.max(maxWidth, route.width * (1 + tune.widthVariation));
            maxDepth = Math.max(maxDepth, route.depth * (1 + tune.poolFraction));
            if (Math.floor(route.w[route.w.length - 1]) - maxDepth < heights.lowestSurface
                    || route.w[0] + 2 > heights.highestSurface
                    || a.minParent < heights.lowestSurface
                    || a.maxParent > heights.highestSurface) return reject(Rejection.BOUNDS);
        }
        if (a.lateralAllowance < maxMove + maxWidth * .5 + tune.bankSkirt)
            return reject(Rejection.CORRIDOR);
        if (f == Family.JUNCTION) {
            int end = tributary.x.length - 1, join = -1;
            for (int i = 1; i < main.x.length - 1; i++)
                if (StrictMath.hypot(main.x[i] - tributary.x[end], main.z[i] - tributary.z[end])
                        < 1e-6) join = i;
            if (join < 0 || Math.abs(main.w[join] - tributary.w[end]) > 1e-6)
                return reject(Rejection.JUNCTION_MISMATCH);
        }
        long key =
                hash(
                        seed
                                ^ f.salt
                                ^ hash(main.id)
                                ^ Double.doubleToLongBits(main.x[0])
                                ^ hash(Double.doubleToLongBits(main.z[0])));
        List<Line> lines = new ArrayList<Line>();
        if (f == Family.SPLIT) {
            lines.add(build(main, key, f, tune, -1, minimumWidth));
            lines.add(build(main, key, f, tune, 1, minimumWidth));
        } else {
            lines.add(build(main, key, f, tune, 0, minimumWidth));
            if (f == Family.JUNCTION)
                lines.add(build(tributary, hash(key ^ tributary.id), f, tune, 0, minimumWidth));
        }
        for (Line l : lines) if (folded(l)) return reject(Rejection.FOLDED);
        if (f == Family.JUNCTION) {
            Line trunk = lines.get(0), branch = lines.get(1);
            int last = branch.n - 1;
            for (Line l : lines)
                for (int i = 0; i < l.n; i++) {
                    double q =
                            StrictMath.hypot(l.x[i] - branch.x[last], l.z[i] - branch.z[last])
                                    / (main.width * 1.5);
                    double scour = 1 - smooth(q);
                    l.d[i] = Math.min(ref.maxDepth, l.d[i] * (1 + tune.poolFraction * scour));
                }
            for (int i = 0; i < trunk.n - 1; i++)
                for (int j = 0; j < branch.n - 1; j++) {
                    double support =
                            Math.max(trunk.r[i], trunk.r[i + 1])
                                    + Math.max(branch.r[j], branch.r[j + 1])
                                    + 2 * tune.bankSkirt;
                    if (segmentDistance(
                                    trunk.x[i],
                                    trunk.z[i],
                                    trunk.x[i + 1],
                                    trunk.z[i + 1],
                                    branch.x[j],
                                    branch.z[j],
                                    branch.x[j + 1],
                                    branch.z[j + 1])
                            < support)
                        for (int aNode = i; aNode <= i + 1; aNode++)
                            for (int bNode = j; bNode <= j + 1; bNode++)
                                if (Math.abs(trunk.w[aNode] - branch.w[bNode]) > .99)
                                    return reject(Rejection.JUNCTION_MISMATCH);
                }
        }
        return new Result(
                Rejection.ACCEPT,
                new Plan(
                        f,
                        seed,
                        main.id,
                        lines.toArray(new Line[lines.size()]),
                        tune.bankSkirt,
                        ref.maxDepth));
    }

    private static Line build(
            Route r, long key, Family f, Tuning t, int lane, double minimumWidth) {
        // Four subsegments per supplied segment, at most 128. All trigonometry is preparation-only.
        Line l = new Line(4 * (r.x.length - 1) + 1);
        double phase = unit(key, 1) * Math.PI * 2, phase2 = unit(key, 2) * Math.PI * 2;
        double cycles = 2.1 + unit(key, 3) * 1.6;
        for (int j = 0; j < l.n; j++) {
            int i = Math.min(r.x.length - 2, j / 4);
            double v = (j - 4 * i) / 4.0;
            double s = mix(r.s[i], r.s[i + 1], v), u = s / r.length;
            double dx = r.x[i + 1] - r.x[i],
                    dz = r.z[i + 1] - r.z[i],
                    len = StrictMath.hypot(dx, dz);
            double env = 16 * u * u * (1 - u) * (1 - u);
            double wave = StrictMath.sin(u * cycles * 2 * Math.PI + phase),
                    fine = StrictMath.sin(u * (cycles * 1.73) * 2 * Math.PI + phase2);
            double offset, width = r.width * (1 + t.widthVariation * env * wave), depth = r.depth;
            double pool = .5 + .5 * StrictMath.sin(u * (cycles * 2.2) * 2 * Math.PI + phase2);
            if (f == Family.BEND) {
                offset = r.width * t.bendWidths * env * (.78 * wave + .22 * fine);
                depth *= 1 + t.poolFraction * env * (2 * pool - 1);
                l.skew[j] = 0; // assigned from the actual prepared signed curvature below
            } else if (f == Family.SPLIT) {
                // Two compact lenses. Split threads coincide at pinned endpoints and the central
                // rejoin.
                double lens = StrictMath.pow(StrictMath.sin(2 * Math.PI * u), 2) * env;
                offset = lane * r.width * t.splitWidths * lens * (.82 + .18 * wave);
                width *= 1 - .53 * lens; // channel width minimum separately checked below
                width = Math.max(minimumWidth, width);
                depth *= 1 + t.poolFraction * env * (2 * pool - 1);
                l.skew[j] = -lane * t.barFraction * .28 * lens;
            } else if (f == Family.POCKET) {
                offset = r.width * .25 * env * (.7 * wave + .3 * fine);
                depth *= 1 + t.poolFraction * env * (2 * pool * pool - 1);
            } else {
                // Supplied routes are preserved at every original node, including the receiving
                // node.
                double pin = StrictMath.pow(StrictMath.sin(Math.PI * v), 2);
                offset = r.width * .35 * env * pin * wave;
                depth *= 1 + t.poolFraction * env * pool;
                l.skew[j] = t.barFraction * .25 * env * wave;
            }
            l.x[j] = mix(r.x[i], r.x[i + 1], v) - dz / len * offset;
            l.z[j] = mix(r.z[i], r.z[i + 1], v) + dx / len * offset;
            l.w[j] = mix(r.w[i], r.w[i + 1], v);
            l.r[j] = width * .5;
            l.d[j] = depth;
        }
        if (f == Family.BEND)
            for (int j = 1; j < l.n - 1; j++) {
                double ax = l.x[j] - l.x[j - 1], az = l.z[j] - l.z[j - 1];
                double bx = l.x[j + 1] - l.x[j], bz = l.z[j + 1] - l.z[j];
                double turn =
                        (ax * bz - az * bx) / (StrictMath.hypot(ax, az) * StrictMath.hypot(bx, bz));
                l.skew[j] = t.barFraction * clamp(turn * 2, -.5, .5);
            }
        return l;
    }

    private static boolean folded(Line l) {
        double[] arc = new double[l.n];
        for (int i = 0; i < l.n - 1; i++) {
            double length = StrictMath.hypot(l.x[i + 1] - l.x[i], l.z[i + 1] - l.z[i]);
            if (length < .5) return true;
            arc[i + 1] = arc[i] + length;
        }
        for (int i = 0; i < l.n - 1; i++) {
            // Adjacent swept disks naturally overlap. Use arc distance rather than a
            // vertex-count exclusion window, so input tessellation does not imply a fold.
            for (int j = i + 2; j < l.n - 1; j++) {
                double r = Math.max(l.r[i], l.r[i + 1]) + Math.max(l.r[j], l.r[j + 1]);
                double distance =
                        segmentDistance(
                                l.x[i],
                                l.z[i],
                                l.x[i + 1],
                                l.z[i + 1],
                                l.x[j],
                                l.z[j],
                                l.x[j + 1],
                                l.z[j + 1]);
                if (distance < 1e-8 || (arc[j] - arc[i + 1] > 2 * r && distance < r)) return true;
            }
        }
        return false;
    }

    /** Shared finite-segment distance for conservative whole-reservation clearance. */
    public static double segmentDistance(
            double ax,
            double az,
            double bx,
            double bz,
            double cx,
            double cz,
            double dx,
            double dz) {
        double abC = (bx - ax) * (cz - az) - (bz - az) * (cx - ax),
                abD = (bx - ax) * (dz - az) - (bz - az) * (dx - ax);
        double cdA = (dx - cx) * (az - cz) - (dz - cz) * (ax - cx),
                cdB = (dx - cx) * (bz - cz) - (dz - cz) * (bx - cx);
        if (abC * abD < 0 && cdA * cdB < 0) return 0;
        return Math.min(
                Math.min(
                        pointDistance(ax, az, cx, cz, dx, dz),
                        pointDistance(bx, bz, cx, cz, dx, dz)),
                Math.min(
                        pointDistance(cx, cz, ax, az, bx, bz),
                        pointDistance(dx, dz, ax, az, bx, bz)));
    }

    /** Point clearance from a nondegenerate finite segment. */
    public static double pointDistance(
            double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax,
                dz = bz - az,
                t = clamp(((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz), 0, 1);
        return StrictMath.hypot(x - ax - t * dx, z - az - t * dz);
    }

    private static Result reject(Rejection r) {
        return new Result(r, null);
    }

    private static void require(boolean ok) {
        if (!ok) throw new IllegalArgumentException("Invalid immutable river input");
    }

    private static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }

    private static double smooth(double x) {
        x = clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    private static double mix(double a, double b, double t) {
        return t <= 0 ? a : t >= 1 ? b : a + (b - a) * t;
    }

    private static long hash(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static double unit(long x, long salt) {
        return (hash(x + salt * 0x9e3779b97f4a7c15L) >>> 11) * 0x1.0p-53;
    }
}
