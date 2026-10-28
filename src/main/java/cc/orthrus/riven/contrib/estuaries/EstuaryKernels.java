/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Riven contributors.
 * Adapted from contributions/lagoons-estuaries-15-20261019 (reviewed second pass).
 * Native additions: explicit proposal/column certificate/plan domain, physical
 * footprint scaling, actual earlier marine ownership and bank foundation feathers.
 * Original continuous entry points and shape arithmetic remain.
 * Packaged license: META-INF/licenses/estuaries/MIT.txt.
 */
package cc.orthrus.riven.contrib.estuaries;

import java.util.Properties;

/** Independently authored, dependency-free contribution. SPDX-License-Identifier: MIT. */
public final class EstuaryKernels {
    private EstuaryKernels() {}

    public enum Family {
        LOBED_LAGOON(0x4c4f42454c414731L),
        CRESCENT_LAGOON(0x435245534c414732L),
        BRANCHING_ESTUARY(0x4252414e45535433L),
        FUNNEL_ESTUARY(0x46554e4e45535434L);
        public final long salt;

        Family(long salt) {
            this.salt = salt;
        }

        public boolean lagoon() {
            return this == LOBED_LAGOON || this == CRESCENT_LAGOON;
        }
    }

    public enum Reason {
        ACCEPTED,
        INVALID_INPUT,
        UNSUPPLIED_OCEAN,
        RECEIVER_LEVEL,
        RECEIVER_TOO_NARROW,
        FOOTPRINT_UNAUTHORIZED,
        RESERVATION_CONFLICT,
        WORLD_HEADROOM,
        TOO_STEEP,
        TOO_EXPOSED,
        SEDIMENT_DEFICIT,
        MISSING_ROUTE,
        ROUTE_OUTSIDE_SUPPORT,
        REFERENCE_SCALE
    }

    /** Snapshot of the canonical packaged reference, extracted by tools/run.py. */
    public static final class Heights {
        public final int sea, water, minSurface, maxSurface;
        public final double lakeWidth, lakeWidthMin, lakeWidthMax, lakeDepth;
        public final double riverWidth, riverDepth, beachOffset;
        public final String fingerprint;

        public Heights(Properties p) {
            sea = integer(p, "sea");
            water = integer(p, "water");
            minSurface = integer(p, "minSurface");
            maxSurface = integer(p, "maxSurface");
            lakeWidth = number(p, "lakeWidth");
            lakeWidthMin = number(p, "lakeWidthMin");
            lakeWidthMax = number(p, "lakeWidthMax");
            lakeDepth = number(p, "lakeDepth");
            riverWidth = number(p, "riverWidth");
            riverDepth = number(p, "riverDepth");
            beachOffset = number(p, "beachOffset");
            fingerprint = p.getProperty("fingerprint");
            if (water != sea - 1
                    || fingerprint == null
                    || fingerprint.isEmpty()
                    || minSurface >= maxSurface
                    || lakeWidthMin <= 0
                    || lakeWidthMax < lakeWidthMin
                    || lakeDepth < 1
                    || lakeWidth < lakeWidthMin
                    || lakeWidth > lakeWidthMax
                    || sea < minSurface
                    || sea > maxSurface
                    || riverWidth < 1
                    || riverDepth < 1) throw new IllegalArgumentException("height reference");
        }
    }

    /** All dimensions derive from reference widths/depths; these are ratios, not new Y bands. */
    public static final class Tuning {
        private final Heights reference;
        private final double columnScale;
        public final double length, depth, inletHalfWidth, skirt, crestAboveWater;
        public final double lobeVariation, branchSpread, channelBend, barStrength;

        public Tuning(Heights h, Properties p) {
            this(h, p, 1);
        }

        public static Tuning nativeColumns(Heights h, Properties p, double horizontalScale) {
            if (!between(horizontalScale, Double.MIN_NORMAL, 1))
                throw new IllegalArgumentException("column scale");
            return new Tuning(h, p, horizontalScale);
        }

        private Tuning(Heights h, Properties p, double columnScale) {
            reference = h;
            this.columnScale = columnScale;
            length = h.lakeWidth * number(p, "lengthInLakeWidths");
            depth = h.lakeDepth * number(p, "depthInLakeDepths");
            inletHalfWidth = h.riverWidth * number(p, "inletHalfWidthInRiverWidths");
            skirt = h.riverWidth * number(p, "skirtInRiverWidths");
            crestAboveWater = h.sea + h.beachOffset - h.water;
            lobeVariation = number(p, "lobeVariation");
            branchSpread = number(p, "branchSpread");
            channelBend = number(p, "channelBend");
            barStrength = number(p, "barStrength");
            if (length < 128 * columnScale
                    || length > 1024 * columnScale
                    || depth < 1
                    || depth > h.lakeDepth
                    || inletHalfWidth < 4 * columnScale
                    || inletHalfWidth > .04 * length
                    || skirt < 2
                    || skirt > .08 * length
                    || crestAboveWater < 1
                    || !between(lobeVariation, 0, .15)
                    || !between(branchSpread, .2, .4)
                    || !between(channelBend, 0, .08)
                    || !between(barStrength, 0, .8))
                throw new IllegalArgumentException("tuning outside bounded authored envelope");
        }
    }

    /**
     * Earlier immutable facts. Certificate covers the entire support disk (1.5*length),
     * not just anchor samples. Production must derive these facts before planning.
     * routeU/routeV are existing route points in normalized shore-frame coordinates;
     * the first is the supplied ocean receiver, the last the same-head upstream reach.
     */
    public static final class Inputs {
        public final long seed, ownerX, ownerZ;
        public final double x, z, nx, nz, curvature, shelter, sediment, maximumSlope;
        public final double minimumParent, maximumParent, authorizedRadius, receiverHalfWidth;
        public final boolean oceanSupplied, footprintAuthorized, barrierAuthorized, conflict;
        public final int receiverWaterY, incomingWaterY;
        private final double[] routeU, routeV;

        public Inputs(
                long seed,
                long ownerX,
                long ownerZ,
                double x,
                double z,
                double nx,
                double nz,
                double curvature,
                double shelter,
                double sediment,
                double maximumSlope,
                double minimumParent,
                double maximumParent,
                double authorizedRadius,
                double receiverHalfWidth,
                boolean oceanSupplied,
                boolean footprintAuthorized,
                boolean barrierAuthorized,
                boolean conflict,
                int receiverWaterY,
                int incomingWaterY,
                double[] routeU,
                double[] routeV) {
            if ((routeU != null && routeU.length > 9) || (routeV != null && routeV.length > 9))
                throw new IllegalArgumentException("at most nine earlier route points");
            this.seed = seed;
            this.ownerX = ownerX;
            this.ownerZ = ownerZ;
            this.x = x;
            this.z = z;
            this.nx = nx;
            this.nz = nz;
            this.curvature = curvature;
            this.shelter = shelter;
            this.sediment = sediment;
            this.maximumSlope = maximumSlope;
            this.minimumParent = minimumParent;
            this.maximumParent = maximumParent;
            this.authorizedRadius = authorizedRadius;
            this.receiverHalfWidth = receiverHalfWidth;
            this.oceanSupplied = oceanSupplied;
            this.footprintAuthorized = footprintAuthorized;
            this.barrierAuthorized = barrierAuthorized;
            this.conflict = conflict;
            this.receiverWaterY = receiverWaterY;
            this.incomingWaterY = incomingWaterY;
            this.routeU = routeU == null ? new double[0] : routeU.clone();
            this.routeV = routeV == null ? new double[0] : routeV.clone();
        }
    }

    public static final class Decision {
        public final Reason reason;
        public final Plan plan;

        private Decision(Reason r, Plan p) {
            reason = r;
            plan = p;
        }
    }

    public static Decision plan(Family family, Heights h, Tuning t, Inputs i) {
        Reason reason = admit(family, h, t, i);
        return new Decision(reason, reason == Reason.ACCEPTED ? new Plan(family, h, t, i) : null);
    }

    /** Cheap earlier opportunity gate, shared by continuous/native admission and callers. */
    public static Reason opportunity(Family family, double shelter, double sediment) {
        if (family == null || !between(shelter, 0, 1) || !between(sediment, 0, 1))
            return Reason.INVALID_INPUT;
        if (shelter
                < (family == Family.LOBED_LAGOON
                        ? .65
                        : family == Family.CRESCENT_LAGOON ? .55 : .25)) return Reason.TOO_EXPOSED;
        if (sediment < (family.lagoon() ? .55 : family == Family.FUNNEL_ESTUARY ? .30 : .10))
            return Reason.SEDIMENT_DEFICIT;
        return Reason.ACCEPTED;
    }

    /** Geometry only. False permission fields cannot become a continuous certificate. */
    public static final class ColumnLayout {
        private final Inputs geometry;

        public ColumnLayout(
                long seed,
                long ownerX,
                long ownerZ,
                double x,
                double z,
                double nx,
                double nz,
                double[] routeU,
                double[] routeV) {
            geometry =
                    new Inputs(
                            seed, ownerX, ownerZ, x, z, nx, nz, 0, 0, 0, 0, 0, 0, 0, 0, false,
                            false, false, false, 0, 0, routeU, routeV);
        }
    }

    /** A tentative geometry can be inspected for certificate construction, never used as an admitted plan. */
    public static final class ColumnProposal {
        public final Reason reason;
        public final Family family;
        public final double x, z, nx, nz, length, supportRadius;
        private final Plan geometry;

        private ColumnProposal(Family family, Heights h, Tuning t, ColumnLayout layout) {
            Inputs i = layout.geometry;
            this.family = family;
            x = i.x;
            z = i.z;
            nx = i.nx;
            nz = i.nz;
            length = t.length;
            supportRadius = 1.5 * t.length;
            reason = columnLayoutReason(family, h, t, i);
            geometry = reason == Reason.ACCEPTED ? new Plan(family, h, t, i) : null;
        }

        /** Tentative geometry inspection for full-support conflict certification, not admission. */
        public void inspectColumn(double x, double z, double parent, boolean marine, Sample out) {
            if (geometry == null) throw new IllegalStateException("Invalid coastal proposal");
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || x != Math.floor(x)
                    || z != Math.floor(z))
                throw new IllegalArgumentException("Native inspection requires integer columns");
            geometry.sample(x, z, parent, false, geometry.h.water, 0, out, true, marine);
        }
    }

    public static ColumnProposal proposeColumns(
            Family family, Heights h, Tuning t, ColumnLayout layout) {
        return new ColumnProposal(family, h, t, layout);
    }

    /** Complete native integer support, including actual ocean receiver and prior reservations.
     * Dry parent slope is distinct from the marine/land step. No continuous-plane claim.
     */
    public static final class ColumnFacts {
        private final ColumnProposal proposal;
        public final int columns, receiverWaterY, incomingWaterY;
        public final double minimumParent,
                maximumParent,
                drySlope,
                shelter,
                sediment,
                receiverHalfWidth;
        public final boolean oceanSupplied, footprintAuthorized, barrierAuthorized, conflict;

        public ColumnFacts(
                ColumnProposal proposal,
                int columns,
                double minimumParent,
                double maximumParent,
                double drySlope,
                double shelter,
                double sediment,
                double receiverHalfWidth,
                boolean oceanSupplied,
                boolean footprintAuthorized,
                boolean barrierAuthorized,
                boolean conflict,
                int receiverWaterY,
                int incomingWaterY) {
            this.proposal = proposal;
            this.columns = columns;
            this.minimumParent = minimumParent;
            this.maximumParent = maximumParent;
            this.drySlope = drySlope;
            this.shelter = shelter;
            this.sediment = sediment;
            this.receiverHalfWidth = receiverHalfWidth;
            this.oceanSupplied = oceanSupplied;
            this.footprintAuthorized = footprintAuthorized;
            this.barrierAuthorized = barrierAuthorized;
            this.conflict = conflict;
            this.receiverWaterY = receiverWaterY;
            this.incomingWaterY = incomingWaterY;
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

    public static final class ColumnPlan {
        public final Family family;
        public final long id;
        public final double x, z, supportRadius;
        private final Plan geometry;
        private final double minimumParent, maximumParent;

        private ColumnPlan(ColumnProposal p, ColumnFacts f) {
            geometry = p.geometry;
            family = p.family;
            id = geometry.id;
            x = p.x;
            z = p.z;
            supportRadius = p.supportRadius;
            minimumParent = f.minimumParent;
            maximumParent = f.maximumParent;
        }

        public void sample(
                double x,
                double z,
                double parent,
                boolean marine,
                boolean reserved,
                int waterY,
                double bed,
                Sample out) {
            if (!Double.isFinite(parent)
                    || parent < minimumParent
                    || parent > maximumParent
                    || (!marine && parent < geometry.h.water + 1))
                throw new IllegalArgumentException("parent outside native certificate");
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || x != Math.floor(x)
                    || z != Math.floor(z)) {
                out.clear(parent, geometry.h.water);
                return;
            }
            geometry.sample(x, z, parent, reserved, waterY, bed, out, true, marine);
        }
    }

    public static ColumnDecision planColumns(ColumnProposal p, ColumnFacts f) {
        Reason reason = p.reason;
        if (f.proposal != p || f.columns <= 0)
            return new ColumnDecision(Reason.INVALID_INPUT, null);
        if (reason == Reason.ACCEPTED) {
            Plan g = p.geometry;
            Heights h = g.h;
            Tuning t = g.t;
            double[] finite = {
                f.minimumParent,
                f.maximumParent,
                f.drySlope,
                f.shelter,
                f.sediment,
                f.receiverHalfWidth
            };
            for (double value : finite)
                if (!Double.isFinite(value)) return new ColumnDecision(Reason.INVALID_INPUT, null);
            if (!between(f.shelter, 0, 1)
                    || !between(f.sediment, 0, 1)
                    || f.drySlope < 0
                    || f.minimumParent > f.maximumParent) reason = Reason.INVALID_INPUT;
            else if (!f.oceanSupplied) reason = Reason.UNSUPPLIED_OCEAN;
            else if (f.receiverWaterY != h.water || f.incomingWaterY != h.water)
                reason = Reason.RECEIVER_LEVEL;
            else if (f.receiverHalfWidth
                    < (p.family.lagoon()
                            ? .16 * t.length + t.inletHalfWidth
                            : t.length * (p.family == Family.FUNNEL_ESTUARY ? .40 : .23)))
                reason = Reason.RECEIVER_TOO_NARROW;
            else if (!f.footprintAuthorized || (p.family.lagoon() && !f.barrierAuthorized))
                reason = Reason.FOOTPRINT_UNAUTHORIZED;
            else if (f.conflict) reason = Reason.RESERVATION_CONFLICT;
            else if (f.minimumParent < h.minSurface
                    || f.maximumParent > h.maxSurface
                    || h.water - t.depth < h.minSurface
                    || h.water + t.crestAboveWater > h.maxSurface) reason = Reason.WORLD_HEADROOM;
            else if (f.drySlope > (p.family.lagoon() ? .13 : .28)) reason = Reason.TOO_STEEP;
            else reason = opportunity(p.family, f.shelter, f.sediment);
        }
        return new ColumnDecision(reason, reason == Reason.ACCEPTED ? new ColumnPlan(p, f) : null);
    }

    private static Reason columnLayoutReason(Family family, Heights h, Tuning t, Inputs i) {
        if (family == null
                || t.reference != h
                || !Double.isFinite(i.x)
                || !Double.isFinite(i.z)
                || !Double.isFinite(i.nx)
                || !Double.isFinite(i.nz)
                || Math.abs(i.nx * i.nx + i.nz * i.nz - 1) > 1e-8
                || Math.abs(i.x) + 1.5 * t.length > 29999984
                || Math.abs(i.z) + 1.5 * t.length > 29999984) return Reason.INVALID_INPUT;
        if (t.length < h.lakeWidthMin
                || t.length > h.lakeWidthMax
                || t.skirt < 4
                || t.inletHalfWidth < 2) return Reason.REFERENCE_SCALE;
        // These native frames are geometry axes. Actual marine ownership is supplied separately per
        // column.
        if (!family.lagoon()) {
            if (i.routeU.length != 5 || i.routeV.length != 5) return Reason.MISSING_ROUTE;
            for (int n = 0; n < 5; n++)
                if (!Double.isFinite(i.routeU[n])
                        || !Double.isFinite(i.routeV[n])
                        || Math.abs(i.routeU[n]) > .12
                        || Math.abs(i.routeV[n] - (-.25 + 1.1 * n / 4.0)) > 1e-9)
                    return Reason.ROUTE_OUTSIDE_SUPPORT;
        }
        return Reason.ACCEPTED;
    }

    private static Reason admit(Family f, Heights h, Tuning t, Inputs i) {
        if (f == null || h == null || t == null || i == null) return Reason.INVALID_INPUT;
        if (t.reference != h || t.columnScale != 1) return Reason.INVALID_INPUT;
        double norm = i.nx * i.nx + i.nz * i.nz;
        double[] vals = {
            i.x,
            i.z,
            i.nx,
            i.nz,
            i.curvature,
            i.shelter,
            i.sediment,
            i.maximumSlope,
            i.minimumParent,
            i.maximumParent,
            i.authorizedRadius,
            i.receiverHalfWidth
        };
        for (double v : vals) if (!Double.isFinite(v)) return Reason.INVALID_INPUT;
        if (Math.abs(norm - 1) > 1e-8
                || Math.abs(i.x) > 30000000
                || Math.abs(i.z) > 30000000
                || !between(i.shelter, 0, 1)
                || !between(i.sediment, 0, 1)
                || i.maximumSlope < 0
                || i.minimumParent > i.maximumParent
                || Math.abs(i.curvature) * t.length > .12) return Reason.INVALID_INPUT;
        if (!i.oceanSupplied) return Reason.UNSUPPLIED_OCEAN;
        if (i.receiverWaterY != h.water || i.incomingWaterY != i.receiverWaterY)
            return Reason.RECEIVER_LEVEL;
        // Receiver certificate is centered at u=0, v=-0.25 L and covers all seeded mouths.
        double requiredReceiver =
                f.lagoon()
                        ? .16 * t.length + t.inletHalfWidth
                        : t.length * (f == Family.FUNNEL_ESTUARY ? .40 : .23);
        if (i.receiverHalfWidth < requiredReceiver) return Reason.RECEIVER_TOO_NARROW;
        if (!i.footprintAuthorized
                || i.authorizedRadius < 1.5 * t.length
                || Math.abs(i.x) + 1.5 * t.length > 30000000
                || Math.abs(i.z) + 1.5 * t.length > 30000000
                || (f.lagoon() && !i.barrierAuthorized)) return Reason.FOOTPRINT_UNAUTHORIZED;
        if (i.conflict) return Reason.RESERVATION_CONFLICT;
        if (i.minimumParent < h.minSurface
                || i.maximumParent > h.maxSurface
                || h.water - t.depth < h.minSurface
                || h.water + t.crestAboveWater > h.maxSurface) return Reason.WORLD_HEADROOM;
        if (i.maximumSlope > (f.lagoon() ? .13 : .28)) return Reason.TOO_STEEP;
        Reason opportunity = opportunity(f, i.shelter, i.sediment);
        if (opportunity != Reason.ACCEPTED) return opportunity;
        if (t.length < h.lakeWidthMin || t.length > h.lakeWidthMax) return Reason.REFERENCE_SCALE;
        if (!f.lagoon()) {
            if (i.routeU.length < 3 || i.routeU.length > 9 || i.routeU.length != i.routeV.length)
                return Reason.MISSING_ROUTE;
            for (int n = 0; n < i.routeU.length; n++) {
                if (!Double.isFinite(i.routeU[n])
                        || !Double.isFinite(i.routeV[n])
                        || Math.abs(i.routeU[n]) > .12
                        || i.routeV[n] < -.25
                        || i.routeV[n] > .85
                        || (n > 0 && i.routeV[n] <= i.routeV[n - 1]))
                    return Reason.ROUTE_OUTSIDE_SUPPORT;
            }
            if (Math.abs(i.routeV[0] + .25) > 1e-9
                    || Math.abs(i.routeV[i.routeV.length - 1] - .85) > 1e-9)
                return Reason.ROUTE_OUTSIDE_SUPPORT;
        }
        return Reason.ACCEPTED;
    }

    /** Caller-owned result; a query allocates no arrays, objects, random generators or caches. */
    public static final class Sample {
        public double height, weight, depth;

        /** Native outer foundation feather; the original influence weight is never applied twice. */
        public double foundationWeight;

        public int waterY;
        public boolean wet, channel, barrier, earlierReservation;

        /** Native geometry permission probe, before actual marine ownership suppresses construction. */
        public boolean barrierRequired;

        private void clear(double parent, int head) {
            height = parent;
            weight = 0;
            foundationWeight = 0;
            depth = 0;
            waterY = head;
            wet = false;
            channel = false;
            barrier = false;
            barrierRequired = false;
            earlierReservation = false;
        }
    }

    public static final class Plan {
        public final Family family;
        public final long id;
        public final double supportRadius;
        private final Heights h;
        private final Tuning t;
        private final Inputs i;
        private final double phase, inletU, arc;
        private final double[] lobeU, lobeV, lobeA, lobeB;
        private final double[] pathU, pathV, pathR;
        private final double[] branchU,
                branchV,
                branchEndU,
                branchEndV,
                branchR,
                branchMidU,
                branchMidV;

        private Plan(Family family, Heights h, Tuning t, Inputs i) {
            this.family = family;
            this.h = h;
            this.t = t;
            this.i = i;
            supportRadius = 1.5 * t.length;
            id = mix(i.seed ^ family.salt ^ mix(i.ownerX) ^ Long.rotateLeft(mix(i.ownerZ), 29));
            phase = unit(id + 1) * 2 * Math.PI;
            inletU = (unit(id + 2) - .5) * .32;
            arc = .09 + .08 * unit(id + 3);
            lobeU = new double[3];
            lobeV = new double[3];
            lobeA = new double[3];
            lobeB = new double[3];
            for (int n = 0; n < 3; n++) {
                lobeU[n] = (n - 1) * .25;
                lobeV[n] = .32 + t.lobeVariation * (unit(id + 10 + n) - .5);
                lobeA[n] = .21 + .03 * unit(id + 20 + n);
                lobeB[n] = .17 + .045 * unit(id + 30 + n);
            }
            if (family.lagoon()) {
                pathU = new double[] {inletU, inletU + .018 * sine(phase), inletU};
                pathV = new double[] {-.25, .11, .36};
                pathR =
                        new double[] {
                            t.inletHalfWidth / t.length,
                            t.inletHalfWidth / t.length,
                            t.inletHalfWidth / t.length
                        };
            } else {
                pathU = i.routeU.clone();
                pathV = i.routeV.clone();
                pathR = new double[pathU.length];
                for (int n = 0; n < pathU.length; n++) {
                    double q = (pathV[n] + .25) / 1.1;
                    pathR[n] = Math.max(t.inletHalfWidth / t.length, .11 * (1 - q) + .022 * q);
                }
            }
            branchU = new double[4];
            branchV = new double[4];
            branchEndU = new double[4];
            branchEndV = new double[4];
            branchR = new double[4];
            branchMidU = new double[4];
            branchMidV = new double[4];
            for (int n = 0; n < 4; n++) {
                double v = .20 + .13 * n;
                branchV[n] = v;
                branchU[n] = routeAt(v);
                branchEndU[n] =
                        branchU[n]
                                + (n % 2 == 0 ? -1 : 1)
                                        * (t.branchSpread + .035 * unit(id + 50 + n));
                branchEndV[n] = v + .12 + .06 * unit(id + 60 + n);
                branchR[n] = .034 + .012 * unit(id + 70 + n);
                branchMidU[n] = (branchU[n] + branchEndU[n]) * .5 + .025 * sine(phase + n);
                branchMidV[n] = (branchV[n] + branchEndV[n]) * .5;
            }
        }

        /** Local v follows the earlier curved shoreline; positive v is inland. */
        public double worldX(double u, double v) {
            return i.x + i.nz * u + i.nx * (v + i.curvature * u * u);
        }

        public double worldZ(double u, double v) {
            return i.z - i.nx * u + i.nz * (v + i.curvature * u * u);
        }

        public double localU(double x, double z) {
            return (x - i.x) * i.nz - (z - i.z) * i.nx;
        }

        public double localV(double x, double z) {
            double u = localU(x, z);
            return (x - i.x) * i.nx + (z - i.z) * i.nz - i.curvature * u * u;
        }

        /** Diagnostic/design curve, in blocks, before the earlier shoreline curvature. */
        public double barrierAxisV(double uBlocks) {
            double u = uBlocks / t.length;
            return t.length
                    * (family == Family.CRESCENT_LAGOON
                            ? .105 + arc * (1 - square(u / .57)) + .022 * sine(4 * u + phase)
                            : .055 + .022 * sine(5 * u + phase));
        }

        /**
         * parent is an earlier immutable height, not the recursively shaped surface.
         * An earlier reservation is preserved exactly; admission must have certified
         * that incompatible reservations/protected solids do not cross required paths.
         * No block write, fluid simulation, biome selection or neighboring query occurs.
         */
        public void sample(
                double x,
                double z,
                double parent,
                boolean reserved,
                int reservedWaterY,
                double reservedBed,
                Sample out) {
            sample(x, z, parent, reserved, reservedWaterY, reservedBed, out, false, false);
        }

        private void sample(
                double x,
                double z,
                double parent,
                boolean reserved,
                int reservedWaterY,
                double reservedBed,
                Sample out,
                boolean nativeColumns,
                boolean marine) {
            out.clear(parent, h.water);
            if (reserved) {
                out.height = reservedBed;
                out.waterY = reservedWaterY;
                out.wet = reservedBed < reservedWaterY;
                out.earlierReservation = true;
                out.depth = Math.max(0, reservedWaterY - reservedBed);
                return;
            }
            double dx = x - i.x, dz = z - i.z;
            if (dx * dx + dz * dz > supportRadius * supportRadius) return;
            double u = localU(x, z) / t.length, v = localV(x, z) / t.length;
            double extra = nativeColumns ? 2 * t.skirt / t.length : 0;
            if (Math.abs(u) > .72 + extra || v < -.42 - extra || v > 1.02 + extra) return;
            double q = -10,
                    basin = -10,
                    channel = pathDistance(u, v),
                    barrier = 0,
                    barrierClearance = Double.NEGATIVE_INFINITY;
            if (family == Family.LOBED_LAGOON) {
                for (int n = 0; n < 3; n++) {
                    double a = (u - lobeU[n]) / lobeA[n], b = (v - lobeV[n]) / lobeB[n];
                    basin =
                            Math.max(
                                    basin,
                                    (1 - StrictMath.sqrt(a * a + b * b))
                                            * Math.min(lobeA[n], lobeB[n]));
                }
                // A continuous broad back-barrier ridge; its only wet crossing is the supplied
                // mouth.
                double along = 1 - smooth((Math.abs(u) - .44) / .12);
                double crestV = .055 + .022 * sine(5 * u + phase);
                barrier = along * (1 - smooth(Math.abs(v - crestV) / .06));
                barrierClearance =
                        Math.min(.56 - Math.abs(u), .06 - Math.abs(v - crestV)) * t.length;
                q = Math.max(basin, channel);
            } else if (family == Family.CRESCENT_LAGOON) {
                // Rounded ribbon end caps avoid acute cut-off slivers on the voxel grid.
                double cu = Math.max(-.46, Math.min(.46, u));
                double s = cu / .57;
                double curve = .105 + arc * (1 - s * s) + .022 * sine(4 * cu + phase);
                double half = .075 + .018 * sine(7 * cu + phase);
                basin = half - StrictMath.hypot(u - cu, v - (curve + .11));
                double ridge = .105 + arc * (1 - square(u / .57)) + .022 * sine(4 * u + phase);
                barrier =
                        (1 - smooth((Math.abs(u) - .47) / .12))
                                * (1 - smooth(Math.abs(v - ridge) / .048));
                barrierClearance =
                        Math.min(.59 - Math.abs(u), .048 - Math.abs(v - ridge)) * t.length;
                // Extend the inlet inland to the crescent at its seeded alongshore position.
                double at =
                        .105
                                + arc * (1 - square(inletU / .57))
                                + .022 * sine(4 * inletU + phase)
                                + .11;
                channel =
                        Math.max(
                                channel,
                                capsule(
                                        u,
                                        v,
                                        inletU,
                                        .25,
                                        inletU,
                                        at,
                                        t.inletHalfWidth / t.length));
                q = Math.max(basin, channel);
            } else if (family == Family.BRANCHING_ESTUARY) {
                q = channel;
                for (int n = 0; n < 4; n++) {
                    // Two joined segments per tributary, finite and rooted in the earlier trunk.
                    double mu = branchMidU[n], mv = branchMidV[n];
                    double r = branchR[n];
                    q =
                            Math.max(
                                    q,
                                    Math.max(
                                            capsule(u, v, branchU[n], branchV[n], mu, mv, r),
                                            capsule(
                                                    u,
                                                    v,
                                                    mu,
                                                    mv,
                                                    branchEndU[n],
                                                    branchEndV[n],
                                                    r * .85)));
                }
            } else {
                // A monotone inlet funnel, with one connected sinuous thalweg and side runnels.
                double center = routeAt(v), progress = clamp((v + .25) / 1.1);
                double reach = 1 - progress;
                double width = .035 + .24 * reach * (.65 + .35 * reach);
                double bend = t.channelBend * sine(7 * v + phase) * sine(Math.PI * progress);
                basin = Math.min(width - Math.abs(u - center - bend), Math.min(v + .25, .85 - v));
                q = Math.max(basin, channel);
                for (int n = 0; n < 2; n++)
                    q =
                            Math.max(
                                    q,
                                    capsule(
                                            u,
                                            v,
                                            branchU[n],
                                            branchV[n],
                                            branchEndU[n],
                                            branchEndV[n],
                                            .018));
            }
            q *= t.length;
            channel *= t.length;
            if (nativeColumns) out.barrierRequired = family.lagoon() && barrier > .5 && q <= 0;
            // Barrier is evaluated before wet cuts. Reserved mouths always win.
            if (family.lagoon() && barrier > 0 && (nativeColumns ? !marine : v >= 0)) {
                double crest = h.water + t.crestAboveWater;
                out.height = lerp(out.height, Math.max(out.height, crest), barrier);
                out.weight = barrier;
                out.barrier = barrier > .5;
            }
            if (q >= -t.skirt) {
                double edge = smooth((q + t.skirt) / t.skirt);
                double d = t.depth * (.25 + .75 * smooth(Math.max(0, q) / (t.length * .07)));
                if (family == Family.FUNNEL_ESTUARY) {
                    // Paired submerged shoals. The earlier route is a protected deep thalweg.
                    double bars =
                            square(sine(11 * v + phase))
                                    * smooth((Math.abs(u - routeAt(v)) - .04) / .08);
                    d *= 1 - t.barStrength * bars;
                }
                if (channel > 0) d = Math.max(d, Math.min(t.depth, h.riverDepth));
                double target = q > 0 ? h.water - Math.max(1, d) : h.water + 1;
                // Earlier marine ownership keeps open water outside the new bed.
                // Taper bed edits inside that existing water, never raise a dry bank skirt there.
                if (nativeColumns ? marine : v < 0) edge = q > 0 ? smooth(q / t.skirt) : 0;
                out.height = lerp(out.height, target, edge);
                out.weight = Math.max(out.weight, edge);
                out.wet = q > 0;
                out.channel = channel > 0;
                if (out.wet) out.barrier = false;
            }
            if (nativeColumns && !marine)
                out.foundationWeight =
                        Math.max(
                                1 - smooth((-q - t.skirt) / t.skirt),
                                1 - smooth(-barrierClearance / t.skirt));
            out.depth = out.wet ? h.water - out.height : 0;
        }

        private double pathDistance(double u, double v) {
            double q = -10;
            for (int n = 1; n < pathU.length; n++)
                q =
                        Math.max(
                                q,
                                taperedCapsule(
                                        u,
                                        v,
                                        pathU[n - 1],
                                        pathV[n - 1],
                                        pathU[n],
                                        pathV[n],
                                        pathR[n - 1],
                                        pathR[n]));
            return q;
        }

        private double routeAt(double v) {
            for (int n = 1; n < pathV.length; n++)
                if (v <= pathV[n])
                    return lerp(
                            pathU[n - 1],
                            pathU[n],
                            clamp((v - pathV[n - 1]) / (pathV[n] - pathV[n - 1])));
            return pathU[pathU.length - 1];
        }
    }

    private static double taperedCapsule(
            double u, double v, double ax, double av, double bx, double bv, double ar, double br) {
        double du = bx - ax, dv = bv - av, den = du * du + dv * dv;
        double t = den == 0 ? 0 : clamp(((u - ax) * du + (v - av) * dv) / den);
        return lerp(ar, br, t) - StrictMath.hypot(u - lerp(ax, bx, t), v - lerp(av, bv, t));
    }

    private static double capsule(
            double u, double v, double ax, double av, double bx, double bv, double r) {
        return taperedCapsule(u, v, ax, av, bx, bv, r, r);
    }

    private static double square(double v) {
        return v * v;
    }

    /** Bounded-angle Taylor polynomial, independently derived from sin's power series.
     * Shape arguments stay inside [-20,20]; no tables, fdlibm objects or platform trig.
     * Degree 19 on [-pi/2,pi/2], after periodic reduction/reflection.
     */
    static double sine(double angle) {
        double x = angle - Math.floor(angle / (2 * Math.PI) + .5) * (2 * Math.PI);
        if (x > Math.PI * .5) x = Math.PI - x;
        else if (x < -Math.PI * .5) x = -Math.PI - x;
        double q = x * x;
        double p = -1.0 / 121645100408832000.0;
        p = 1.0 / 355687428096000.0 + q * p;
        p = -1.0 / 1307674368000.0 + q * p;
        p = 1.0 / 6227020800.0 + q * p;
        p = -1.0 / 39916800.0 + q * p;
        p = 1.0 / 362880.0 + q * p;
        p = -1.0 / 5040.0 + q * p;
        p = 1.0 / 120.0 + q * p;
        p = -1.0 / 6.0 + q * p;
        return x * (1 + q * p);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double smooth(double x) {
        x = clamp(x);
        return x * x * (3 - 2 * x);
    }

    private static boolean between(double v, double lo, double hi) {
        return Double.isFinite(v) && v >= lo && v <= hi;
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public static double unit(long z) {
        return (mix(z) >>> 11) * 0x1.0p-53;
    }

    private static double number(Properties p, String key) {
        double v = Double.parseDouble(p.getProperty(key));
        if (!Double.isFinite(v)) throw new IllegalArgumentException(key);
        return v;
    }

    private static int integer(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key));
    }
}
