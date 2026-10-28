// SPDX-License-Identifier: MIT
// Riven integration, 2026-10-20: added a separate shared-anchor native-column
// proposal/facts/plan API. Original planning contracts and geometry remain.
package cc.orthrus.riven.contrib.carvers;

/** Independently authored, finite dry surface cuts. No game, noise or loader dependencies. */
@SuppressWarnings("strictfp") // Retain strict evaluation for the Java 8 fallback.
public final strictfp class SurfaceCuts {
    private SurfaceCuts() {}

    public enum Family {
        RIBBON_RAVINE(0x4355541252415631L),
        BRANCH_GULLY(0x4355541247554c31L),
        PINCHED_SLOT(0x43555412534c5431L),
        BREACH_SADDLE(0x4355541242524331L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }
    }

    public enum Reason {
        ACCEPTED,
        MARINE,
        SHORE,
        WATER,
        RELIEF,
        HEADROOM,
        MATERIAL,
        OPPORTUNITY,
        OCCURRENCE
    }

    /** Populate directly from context.terrainHeights.feature(id); no independent height constants. */
    public static final class Range {
        public final double min, target, max;

        public Range(double min, double target, double max) {
            if (!finite(min, target, max) || min <= 0 || min > target || target > max)
                throw new IllegalArgumentException("Invalid reference range");
            this.min = min;
            this.target = target;
            this.max = max;
        }

        double varied(double scale, double random) {
            return clamp(target * scale * (.75 + .5 * random), min, max);
        }
    }

    public static final class Reference {
        public final Range gorgeWidth, gorgeDepth, hollowWidth, hollowDepth;
        public final double lowestSurface;
        public final String fingerprint;

        public Reference(
                Range gw, Range gd, Range hw, Range hd, double lowestSurface, String fingerprint) {
            if (gw == null
                    || gd == null
                    || hw == null
                    || hd == null
                    || !finite(lowestSurface)
                    || fingerprint == null)
                throw new IllegalArgumentException("Missing height reference");
            gorgeWidth = gw;
            gorgeDepth = gd;
            hollowWidth = hw;
            hollowDepth = hd;
            this.lowestSurface = lowestSurface;
            this.fingerprint = fingerprint;
        }
    }

    public static final class Tuning {
        public final double occurrence,
                widthScale,
                depthScale,
                lengthRatio,
                reliefShare,
                coastGuard,
                waterGuard;

        public Tuning(
                double occurrence,
                double widthScale,
                double depthScale,
                double lengthRatio,
                double reliefShare,
                double coastGuard,
                double waterGuard) {
            if (!finite(
                            occurrence,
                            widthScale,
                            depthScale,
                            lengthRatio,
                            reliefShare,
                            coastGuard,
                            waterGuard)
                    || occurrence < 0
                    || occurrence > 1
                    || widthScale <= 0
                    || widthScale > 2
                    || depthScale <= 0
                    || depthScale > 2
                    || lengthRatio < 1
                    || lengthRatio > 12
                    || reliefShare <= 0
                    || reliefShare > 1
                    || coastGuard < 0
                    || waterGuard < 0) throw new IllegalArgumentException("Invalid carver tuning");
            this.occurrence = occurrence;
            this.widthScale = widthScale;
            this.depthScale = depthScale;
            this.lengthRatio = lengthRatio;
            this.reliefShare = reliefShare;
            this.coastGuard = coastGuard;
            this.waterGuard = waterGuard;
        }

        public static Tuning defaults(Family f) {
            switch (f) {
                case RIBBON_RAVINE:
                    return new Tuning(.13, 1, 1, 5.8, .65, 64, 32);
                case BRANCH_GULLY:
                    return new Tuning(.11, 1, 1.5, 2.5, .35, 64, 32);
                case PINCHED_SLOT:
                    return new Tuning(.08, .80, 1.1, 7, .75, 64, 32);
                default:
                    return new Tuning(.09, 1, 1.8, 2.6, .50, 64, 32);
            }
        }
    }

    /**
     * Immutable earlier facts at an anchor. minSurface bounds the support disk; relief uses the
     * reference's earlier 256-block diagnostic corridor, not an anchor-only slope estimate.
     * Distances are conservative lower bounds to protected shoreline/reservation SETS.
     * saddleAxis is an earlier parent saddle's through-axis; saddleFit certifies two shoulders
     * and two lower sides. Neither is inferred from a final surface here.
     */
    public static final class Facts {
        public final boolean marine;
        public final double minSurface, relief, gradientX, gradientZ, shoreDistance, waterDistance;
        public final double erodibility, aridity, saddleFit, saddleAxis;

        public Facts(
                boolean marine,
                double minSurface,
                double relief,
                double gradientX,
                double gradientZ,
                double shoreDistance,
                double waterDistance,
                double erodibility,
                double aridity,
                double saddleFit,
                double saddleAxis) {
            if (!finite(
                            minSurface,
                            relief,
                            gradientX,
                            gradientZ,
                            shoreDistance,
                            waterDistance,
                            erodibility,
                            aridity,
                            saddleFit,
                            saddleAxis)
                    || relief < 0
                    || erodibility < 0
                    || erodibility > 1
                    || aridity < 0
                    || aridity > 1
                    || saddleFit < 0
                    || saddleFit > 1) throw new IllegalArgumentException("Invalid earlier facts");
            this.marine = marine;
            this.minSurface = minSurface;
            this.relief = relief;
            this.gradientX = gradientX;
            this.gradientZ = gradientZ;
            this.shoreDistance = shoreDistance;
            this.waterDistance = waterDistance;
            this.erodibility = erodibility;
            this.aridity = aridity;
            this.saddleFit = saddleFit;
            this.saddleAxis = saddleAxis;
        }
    }

    public interface Earlier {
        Facts at(double absoluteX, double absoluteZ, double supportRadius);
    }

    public static final class Decision {
        public final Reason reason;
        public final Plan plan;

        Decision(Reason reason, Plan plan) {
            this.reason = reason;
            this.plan = plan;
        }
    }

    /** One immutable finite feature; private arrays are allocated once during planning. */
    public static final class Plan {
        public final Family family;
        public final long id;
        public final double x,
                z,
                angle,
                width,
                length,
                depth,
                supportRadius,
                coastGuard,
                waterGuard;
        private final double c, s, phase, bend, asymmetry, coastFeather, waterFeather;
        private final double[] sx, sz, ex, ez, sw, sd, ed, knots;
        private final int segments;

        private Plan(
                Family f,
                long id,
                double x,
                double z,
                double angle,
                double width,
                double length,
                double depth,
                Tuning tuning) {
            family = f;
            this.id = id;
            this.x = x;
            this.z = z;
            this.angle = angle;
            this.width = width;
            this.length = length;
            this.depth = depth;
            coastGuard = tuning.coastGuard;
            waterGuard = tuning.waterGuard;
            coastFeather = Math.max(1, coastGuard);
            waterFeather = Math.max(1, waterGuard);
            c = StrictMath.cos(angle);
            s = StrictMath.sin(angle);
            phase = unit(id, 8) * Math.PI * 2;
            bend = .07 + .05 * unit(id, 9);
            asymmetry = .15 + .25 * unit(id, 10);
            // All geometry lies in [-width/2,width/2] x [-length/2,length/2].
            supportRadius = .5 * StrictMath.hypot(width, length);
            if (f == Family.BRANCH_GULLY) {
                sx = new double[20];
                sz = new double[20];
                ex = new double[20];
                ez = new double[20];
                sw = new double[20];
                sd = new double[20];
                ed = new double[20];
                knots = null;
                double[] tx = new double[9], tz = new double[9], td = new double[9];
                for (int i = 0; i < 9; i++) {
                    double t = i / 8.;
                    tz[i] = (t - .5) * length * .88;
                    tx[i] = width * .075 * StrictMath.sin(t * 6 + phase);
                    td[i] = StrictMath.sin(Math.PI * t) * (.65 + .35 * t);
                    if (i > 0)
                        edge(
                                i - 1,
                                tx[i - 1],
                                tz[i - 1],
                                tx[i],
                                tz[i],
                                width * .065,
                                td[i - 1],
                                td[i]);
                }
                int k = 8;
                // Six feeders, each two edges. Attach to shared trunk vertices, never grow
                // recursively.
                for (int b = 0; b < 6; b++) {
                    int node = 1 + (int) (unit(id, 60 + b) * 6);
                    double side = (b % 2 == 0 ? -1 : 1);
                    double tipX = side * width * (.34 + .08 * unit(id, 40 + b));
                    double tipZ =
                            Math.max(
                                    -length * .42,
                                    tz[node] - length * (.10 + .08 * unit(id, 50 + b)));
                    double midX = (tipX + tx[node]) * .5 + width * .025 * StrictMath.sin(b + phase);
                    double midZ = (tipZ + tz[node]) * .5;
                    edge(k++, tipX, tipZ, midX, midZ, width * .037, 0, td[node] * .58);
                    edge(
                            k++,
                            midX,
                            midZ,
                            tx[node],
                            tz[node],
                            width * .048,
                            td[node] * .58,
                            td[node]);
                }
                segments = k;
            } else {
                sx = sz = ex = ez = sw = sd = ed = null;
                segments = 0;
                if (f == Family.PINCHED_SLOT) {
                    knots = new double[11];
                    for (int i = 0; i < knots.length; i++)
                        knots[i] = (.5 - unit(id, 80 + i)) * .34 * width;
                } else knots = null;
            }
        }

        private void edge(
                int i, double ax, double az, double bx, double bz, double w, double a, double b) {
            sx[i] = ax;
            sz[i] = az;
            ex[i] = bx;
            ez[i] = bz;
            sw[i] = w;
            sd[i] = a;
            ed[i] = b;
        }

        /** Allocation-free dry lowering in blocks. 0 outside compact support. */
        public double rawCut(double absoluteX, double absoluteZ) {
            double dx = absoluteX - x, dz = absoluteZ - z;
            double u = dx * c + dz * s, v = -dx * s + dz * c;
            if (Math.abs(u) >= width * .5 || Math.abs(v) >= length * .5) return 0;
            double along = 1 - smooth((Math.abs(v) / (length * .5) - .74) / .26), value;
            if (family == Family.BRANCH_GULLY) {
                value = 0;
                for (int i = 0; i < segments; i++) {
                    double ax = ex[i] - sx[i], az = ez[i] - sz[i];
                    double t =
                            clamp(
                                    ((u - sx[i]) * ax + (v - sz[i]) * az) / (ax * ax + az * az),
                                    0,
                                    1);
                    double px = u - sx[i] - t * ax, pz = v - sz[i] - t * az;
                    double distance2 = px * px + pz * pz;
                    if (distance2 >= sw[i] * sw[i]) continue;
                    double cross = 1 - smooth(StrictMath.sqrt(distance2) / sw[i]);
                    value = Math.max(value, cross * (sd[i] + (ed[i] - sd[i]) * t));
                }
            } else if (family == Family.RIBBON_RAVINE) {
                double t = v / length;
                double center =
                        width
                                * bend
                                * (StrictMath.sin(t * 7 + phase)
                                        + .36 * StrictMath.sin(t * 17 + phase * 1.7));
                double offset = u - center;
                double half =
                        width
                                * (.27 + .04 * StrictMath.sin(t * 12 + phase))
                                * (offset < 0 ? 1 - asymmetry : 1);
                double q = Math.abs(offset) / half;
                double a = Math.max(0, 1 - q);
                // Unequal V flanks, compact C1 shoulders, a gently varying thalweg.
                value = a * a * (3 - 2 * a) * (.78 + .22 * StrictMath.cos(t * 5 + phase));
            } else if (family == Family.PINCHED_SLOT) {
                double t = (v / length + .5) * 10;
                int i = Math.min(9, (int) t);
                double q = t - i;
                double center = knots[i] + (knots[i + 1] - knots[i]) * smooth(q);
                double half = width * (.20 + .035 * StrictMath.sin(v / length * 19 + phase));
                double r = Math.abs(u - center) / half;
                // Narrow flat bed and steep walls, feathered only at top and floor.
                value = (1 - smooth((r - .24) / .76));
                value =
                        StrictMath.sqrt(Math.max(0, value))
                                * (.90 + .10 * StrictMath.sin(v / length * 8 + phase));
            } else {
                double a = u / (width * .5), b = v / (length * .5);
                // Off-center lobed bowl joined to a through-cut across an earlier saddle.
                double center = .13 * StrictMath.sin(b * 4 + phase);
                double r =
                        StrictMath.hypot(
                                (a - center) / (.73 + .07 * StrictMath.sin(b * 5 + phase)),
                                b / .57);
                double bowl = 1 - smooth(r);
                double neck = 1 - smooth(Math.abs(a - .10 * StrictMath.sin(b * 5 + phase)) / .30);
                double downstream = smooth((b + .3) / .35);
                value = Math.max(bowl, neck * (.55 + .45 * downstream));
            }
            return depth * value * along;
        }

        /**
         * Point guard consumes the UNMODIFIED parent and earlier distances. No terrain callback.
         * Feathers only toward protected sets and available solid headroom, never raises terrain.
         */
        public double cut(
                double absoluteX,
                double absoluteZ,
                double parentY,
                double minimumSolidY,
                boolean marine,
                double shoreDistance,
                double waterDistance) {
            if (!Double.isFinite(absoluteX)
                    || !Double.isFinite(absoluteZ)
                    || !Double.isFinite(parentY)
                    || !Double.isFinite(minimumSolidY)
                    || !Double.isFinite(shoreDistance)
                    || !Double.isFinite(waterDistance))
                throw new IllegalArgumentException("Non-finite column");
            if (marine || shoreDistance <= coastGuard || waterDistance <= waterGuard) return 0;
            double mask =
                    smooth((shoreDistance - coastGuard) / coastFeather)
                            * smooth((waterDistance - waterGuard) / waterFeather);
            double room = Math.max(0, parentY - minimumSolidY);
            // Full-plan headroom admission normally leaves this feather inactive.
            return Math.min(room, rawCut(absoluteX, absoluteZ) * mask * smooth(room / depth));
        }
    }

    /**
     * One candidate per absolute grid cell per family. Candidate support must fit spacing/2.
     * Anchors jitter by <=.18 spacing; a column therefore needs only the surrounding 3x3 cells.
     * Earlier.at is called exactly once for every valid candidate, before occurrence selection.
     */
    public static Decision plan(
            long seed,
            long cellX,
            long cellZ,
            double spacing,
            Family family,
            Reference reference,
            Tuning tuning,
            Earlier earlier) {
        if (family == null || reference == null || tuning == null || earlier == null)
            throw new IllegalArgumentException("Missing planning input");
        if (!finite(spacing)
                || spacing <= 0
                || Math.abs((double) cellX) > 1e9
                || Math.abs((double) cellZ) > 1e9)
            throw new IllegalArgumentException("Invalid owner grid");
        long id =
                mix(
                        seed
                                ^ family.salt
                                ^ mix(cellX + 0x6a09e667f3bcc909L)
                                ^ Long.rotateLeft(mix(cellZ), 29));
        double x = (cellX + .5 + (unit(id, 1) - .5) * .36) * spacing;
        double z = (cellZ + .5 + (unit(id, 2) - .5) * .36) * spacing;
        boolean gorge = family == Family.RIBBON_RAVINE || family == Family.PINCHED_SLOT;
        Range wr = gorge ? reference.gorgeWidth : reference.hollowWidth;
        Range dr = gorge ? reference.gorgeDepth : reference.hollowDepth;
        double width = wr.varied(tuning.widthScale, unit(id, 3));
        double length = width * tuning.lengthRatio * (.82 + .36 * unit(id, 4));
        double radius = .5 * StrictMath.hypot(width, length);
        if (radius > spacing * .5)
            throw new IllegalArgumentException("Increase spacing to contain configured support");
        Facts facts = earlier.at(x, z, radius);
        if (facts == null) throw new IllegalArgumentException("Missing immutable facts");
        if (facts.marine) return new Decision(Reason.MARINE, null);
        if (facts.shoreDistance < radius + tuning.coastGuard + Math.max(1, tuning.coastGuard))
            return new Decision(Reason.SHORE, null);
        if (facts.waterDistance < radius + tuning.waterGuard + Math.max(1, tuning.waterGuard))
            return new Decision(Reason.WATER, null);
        double depth = dr.varied(tuning.depthScale, unit(id, 5));
        // Resize before evaluation; reject if it cannot meet the reference minimum.
        depth =
                Math.min(
                        depth,
                        Math.min(
                                facts.relief * tuning.reliefShare,
                                (facts.minSurface - reference.lowestSurface) * .8));
        if (facts.relief * tuning.reliefShare < dr.min) return new Decision(Reason.RELIEF, null);
        if (depth < dr.min) return new Decision(Reason.HEADROOM, null);
        if (facts.erodibility < .25) return new Decision(Reason.MATERIAL, null);
        if (family == Family.PINCHED_SLOT && facts.aridity < .55
                || family == Family.BREACH_SADDLE && facts.saddleFit < .6)
            return new Decision(Reason.OPPORTUNITY, null);
        double fit = .55 + .45 * facts.erodibility;
        if (unit(id, 6) >= tuning.occurrence * fit) return new Decision(Reason.OCCURRENCE, null);
        double gradient = StrictMath.hypot(facts.gradientX, facts.gradientZ);
        double angle =
                gradient > .015
                        ? StrictMath.atan2(facts.gradientZ, facts.gradientX) + Math.PI * .5
                        : unit(id, 7) * Math.PI * 2;
        if (family == Family.BREACH_SADDLE) angle = facts.saddleAxis - Math.PI * .5;
        else angle += (unit(id, 7) - .5) * .5;
        return new Decision(
                Reason.ACCEPTED, new Plan(family, id, x, z, angle, width, length, depth, tuning));
    }

    /** Native-column proposal; the shared owner supplies its anchor and support budget. */
    public static final class ColumnProposal {
        public final Family family;
        public final long id;
        public final double x, z, width, length, supportRadius, nominalDepth;
        private final Reference reference;
        private final Tuning tuning;

        private ColumnProposal(
                long seed,
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
                    || Math.abs((double) cx) > 1e9
                    || Math.abs((double) cz) > 1e9)
                throw new IllegalArgumentException("Invalid native-column proposal");
            this.family = family;
            this.x = x;
            this.z = z;
            reference = ref;
            this.tuning = tuning;
            id =
                    mix(
                            seed
                                    ^ family.salt
                                    ^ mix(cx + 0x6a09e667f3bcc909L)
                                    ^ Long.rotateLeft(mix(cz), 29));
            boolean gorge = family == Family.RIBBON_RAVINE || family == Family.PINCHED_SLOT;
            width =
                    (gorge ? ref.gorgeWidth : ref.hollowWidth)
                            .varied(tuning.widthScale, unit(id, 3));
            length = width * tuning.lengthRatio * (.82 + .36 * unit(id, 4));
            supportRadius = .5 * StrictMath.hypot(width, length);
            nominalDepth =
                    (gorge ? ref.gorgeDepth : ref.hollowDepth)
                            .varied(tuning.depthScale, unit(id, 5));
        }
    }

    public static ColumnProposal proposeColumnsAt(
            long seed,
            long cx,
            long cz,
            Family family,
            double x,
            double z,
            Reference ref,
            Tuning tuning) {
        return new ColumnProposal(seed, cx, cz, family, x, z, ref, tuning);
    }

    public static double maximumColumnRadius(Family family, Reference ref, Tuning tuning) {
        boolean gorge = family == Family.RIBBON_RAVINE || family == Family.PINCHED_SLOT;
        double width = (gorge ? ref.gorgeWidth : ref.hollowWidth).varied(tuning.widthScale, 1);
        return .5 * StrictMath.hypot(width, width * tuning.lengthRatio * 1.18);
    }

    /** Earlier evidence covering every native integer support column. Distances are
     * lower bounds to the declared protected column sets; relief/saddle diagnostics
     * must come from the earlier parent. This cannot replace continuous Facts. */
    public static final class ColumnFacts {
        private final ColumnProposal proposal;
        public final boolean marine;
        public final double minSurface,
                relief,
                gradientX,
                gradientZ,
                shoreDistance,
                waterDistance,
                erodibility,
                aridity,
                saddleFit,
                saddleAxis;
        public final int columns;

        public ColumnFacts(
                ColumnProposal proposal,
                boolean marine,
                double minSurface,
                double relief,
                double gradientX,
                double gradientZ,
                double shoreDistance,
                double waterDistance,
                double erodibility,
                double aridity,
                double saddleFit,
                double saddleAxis,
                int columns) {
            if (proposal == null
                    || !finite(
                            minSurface,
                            relief,
                            gradientX,
                            gradientZ,
                            shoreDistance,
                            waterDistance,
                            erodibility,
                            aridity,
                            saddleFit,
                            saddleAxis)
                    || relief < 0
                    || erodibility < 0
                    || erodibility > 1
                    || aridity < 0
                    || aridity > 1
                    || saddleFit < 0
                    || saddleFit > 1
                    || columns <= 0)
                throw new IllegalArgumentException("Invalid native-column facts");
            this.proposal = proposal;
            this.marine = marine;
            this.minSurface = minSurface;
            this.relief = relief;
            this.gradientX = gradientX;
            this.gradientZ = gradientZ;
            this.shoreDistance = shoreDistance;
            this.waterDistance = waterDistance;
            this.erodibility = erodibility;
            this.aridity = aridity;
            this.saddleFit = saddleFit;
            this.saddleAxis = saddleAxis;
            this.columns = columns;
        }
    }

    /** Same orientation recipe as the original planner, exposed for earlier corridor diagnostics. */
    public static double columnAngle(
            ColumnProposal p, double gradientX, double gradientZ, double saddleAxis) {
        double gradient = StrictMath.hypot(gradientX, gradientZ);
        double angle =
                gradient > .015
                        ? StrictMath.atan2(gradientZ, gradientX) + Math.PI * .5
                        : unit(p.id, 7) * Math.PI * 2;
        if (p.family == Family.BREACH_SADDLE) return saddleAxis - Math.PI * .5;
        return angle + (unit(p.id, 7) - .5) * .5;
    }

    public static final class ColumnPlan {
        public final ColumnProposal proposal;
        public final double depth;
        private final Plan geometry;

        private ColumnPlan(ColumnProposal p, double angle, double depth) {
            proposal = p;
            this.depth = depth;
            geometry =
                    new Plan(p.family, p.id, p.x, p.z, angle, p.width, p.length, depth, p.tuning);
        }

        public double cut(
                int x,
                int z,
                double parent,
                double minimumSolid,
                boolean marine,
                double shore,
                double water) {
            return geometry.cut(x, z, parent, minimumSolid, marine, shore, water);
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

    public static ColumnDecision selectColumns(ColumnProposal p, ColumnFacts f) {
        if (p == null || f == null || f.proposal != p)
            throw new IllegalArgumentException("Reuse the exact column proposal and facts");
        Tuning t = p.tuning;
        Reference r = p.reference;
        if (f.marine) return new ColumnDecision(Reason.MARINE, null);
        if (f.shoreDistance < p.supportRadius + t.coastGuard + Math.max(1, t.coastGuard))
            return new ColumnDecision(Reason.SHORE, null);
        if (f.waterDistance < p.supportRadius + t.waterGuard + Math.max(1, t.waterGuard))
            return new ColumnDecision(Reason.WATER, null);
        boolean gorge = p.family == Family.RIBBON_RAVINE || p.family == Family.PINCHED_SLOT;
        Range dr = gorge ? r.gorgeDepth : r.hollowDepth;
        double depth =
                Math.min(
                        p.nominalDepth,
                        Math.min(f.relief * t.reliefShare, (f.minSurface - r.lowestSurface) * .8));
        if (f.relief * t.reliefShare < dr.min) return new ColumnDecision(Reason.RELIEF, null);
        if (depth < dr.min) return new ColumnDecision(Reason.HEADROOM, null);
        if (f.erodibility < .25) return new ColumnDecision(Reason.MATERIAL, null);
        if (p.family == Family.PINCHED_SLOT && f.aridity < .55
                || p.family == Family.BREACH_SADDLE && f.saddleFit < .6)
            return new ColumnDecision(Reason.OPPORTUNITY, null);
        if (unit(p.id, 6) >= t.occurrence * (.55 + .45 * f.erodibility))
            return new ColumnDecision(Reason.OCCURRENCE, null);
        double angle = columnAngle(p, f.gradientX, f.gradientZ, f.saddleAxis);
        return new ColumnDecision(Reason.ACCEPTED, new ColumnPlan(p, angle, depth));
    }

    public static double smooth(double t) {
        t = clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Standard public-domain SplitMix64 finalizer; Vigna, https://prng.di.unimi.it/splitmix64.c */
    public static long mix(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    public static double unit(long id, long stream) {
        return (mix(id + stream * 0x9e3779b97f4a7c15L) >>> 11) * 0x1.0p-53;
    }

    private static boolean finite(double... values) {
        for (double v : values) if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        return true;
    }
}
