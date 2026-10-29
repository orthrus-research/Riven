package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.coast.SedimentaryCoasts;
import cc.orthrus.riven.contrib.coast.SedimentaryCoasts.Anchor;
import cc.orthrus.riven.contrib.coast.SedimentaryCoasts.Family;
import cc.orthrus.riven.contrib.coast.SedimentaryCoasts.Reservation;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Exclusive native sediment bodies. All planning reads the frozen pre-sediment surface. */
public final class SedimentTerrain {
    private static final Rules RULES = Rules.load("riven-sedimentary-coasts.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final Family[] FAMILIES = {
        Family.BEACH_BENCH, Family.SPIT, Family.BARRIER, Family.TOMBOLO
    };
    private static final Reservation[] NO_MASKS = new Reservation[0];

    /** Spacing of the coarse pre-certificate lattice, in blocks. */
    private static final int LATTICE = 16;

    private final long seed;
    private final IntentSampler earlierIntent;
    private final SedimentCoastParent earlierTerrain;
    private final SurfaceWaterPlanSampler waterPlans;
    private final int waterCellBlocks;
    private final double waterBankScale;
    private final TerrainHeightReference heights;
    private final Sites sites;
    private final SedimentaryCoasts.Reference reference;
    private final SedimentaryCoasts.Tuning tuning;
    private final double[] weights = new double[FAMILIES.length];
    private final double radius, shoalingThreshold;

    /** Anchors farther from the shore than this cannot reach it inside the lane radius. */
    private final double anchorReach;

    private final int searchRadius, maximumColumns, maximumIslandColumns, sets, ways;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;
    private final SharedBuild<Decision> builds = new SharedBuild<>();

    SedimentTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            SedimentCoastParent earlierTerrain,
            SurfaceWaterPlanSampler waterPlans,
            int waterCellBlocks,
            double waterBankScale) {
        this.earlierIntent = earlierIntent;
        this.earlierTerrain = earlierTerrain;
        this.waterPlans = waterPlans;
        this.waterCellBlocks = waterCellBlocks;
        this.waterBankScale = waterBankScale;
        this.heights = heights;
        this.sites = sites;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature beach = heights.feature("beach"),
                plain = heights.feature("coastal_plain"),
                shoal = heights.feature("shoal");
        double scale = TerrainScale.HORIZONTAL;
        reference =
                new SedimentaryCoasts.Reference(
                        heights.seaLevel,
                        beach.elevationOffset.min,
                        beach.elevationOffset.targetAverage,
                        beach.elevationOffset.max,
                        beach.width.min * scale,
                        beach.width.targetAverage * scale,
                        beach.width.max * scale,
                        beach.relief.targetAverage,
                        plain.width.targetAverage * scale,
                        -shoal.elevationOffset.min,
                        heights.fingerprint);
        JsonObject t = j.getAsJsonObject("tuning");
        tuning =
                new SedimentaryCoasts.Tuning(
                        Rules.number(t, "widthScale", .35, 2),
                        Rules.number(t, "lengthScale", .2, 1.5),
                        Rules.number(t, "offsetScale", .08, .45),
                        Rules.number(t, "hook", .1, .8),
                        Rules.number(t, "roughness", 0, 1),
                        Rules.number(t, "bench", 0, 1));
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        radius = sites.maximumRadius(Sites.Lane.SEDIMENT);
        searchRadius = sites.searchRadius(Sites.Lane.SEDIMENT);
        maximumColumns = integer(j, "maximumCertificateColumns", 65536, 1048576);
        maximumIslandColumns = integer(j, "maximumIslandColumns", 1024, 65536);
        shoalingThreshold = Rules.number(j, "minimumShoaling", 0, 1);
        anchorReach = reference.plainWidth * Rules.number(j, "anchorReachInPlainWidths", .1, 2);
        sets = integer(j, "cacheSets", 16, 4096);
        ways = integer(j, "cacheWays", 1, 4);
        if ((sets & (sets - 1)) != 0 || radius > waterCellBlocks)
            throw new IllegalStateException("Sediment cache or finite hydrology reach");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    private static final class Entry {
        final long key;
        final Decision decision;

        Entry(long key, Decision decision) {
            this.key = key;
            this.decision = decision;
        }
    }

    private static final class Evidence {
        int boxColumns, activeColumns, maskedColumns, emergentColumns, probeColumns, islandColumns;
        double sediment = 1,
                slope,
                exposure,
                minParent = Double.POSITIVE_INFINITY,
                maxParent = Double.NEGATIVE_INFINITY;

        Evidence copy() {
            Evidence c = new Evidence();
            c.boxColumns = boxColumns;
            c.activeColumns = activeColumns;
            c.maskedColumns = maskedColumns;
            c.emergentColumns = emergentColumns;
            c.probeColumns = probeColumns;
            c.islandColumns = islandColumns;
            c.sediment = sediment;
            c.slope = slope;
            c.exposure = exposure;
            c.minParent = minParent;
            c.maxParent = maxParent;
            return c;
        }
    }

    public static final class Decision {
        public final SedimentaryCoasts.ColumnPlan plan;
        public final Family family;
        public final String reason;
        public final int boxColumns,
                activeColumns,
                maskedColumns,
                emergentColumns,
                probeColumns,
                islandColumns;
        public final double sediment, slope, exposure, minParent, maxParent;

        private Decision(
                SedimentaryCoasts.ColumnPlan plan, Family family, String reason, Evidence e) {
            this.plan = plan;
            this.family = family;
            this.reason = reason;
            boxColumns = e.boxColumns;
            activeColumns = e.activeColumns;
            maskedColumns = e.maskedColumns;
            emergentColumns = e.emergentColumns;
            probeColumns = e.probeColumns;
            islandColumns = e.islandColumns;
            sediment = e.sediment;
            slope = e.slope;
            exposure = e.exposure;
            minParent = Double.isFinite(e.minParent) ? e.minParent : 0;
            maxParent = Double.isFinite(e.maxParent) ? e.maxParent : 0;
        }
    }

    public Decision plan(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        Decision built = builds.get(key, () -> build(cx, cz));
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    private static Decision reject(Family family, String reason, Evidence e) {
        return new Decision(null, family, reason, e);
    }

    /** An anchor's checks that its own earlier facts decide, before any competition. */
    private static final class Prepared {
        final String reason;
        final Family family;
        final SedimentaryCoasts.ColumnProposal proposal;
        final Anchor mainland, island;
        final Evidence evidence;

        Prepared(
                String reason,
                Family family,
                SedimentaryCoasts.ColumnProposal proposal,
                Anchor mainland,
                Anchor island,
                Evidence evidence) {
            this.reason = reason;
            this.family = family;
            this.proposal = proposal;
            this.mainland = mainland;
            this.island = island;
            this.evidence = evidence;
        }

        Prepared(String reason, Family family, Evidence evidence) {
            this(reason, family, null, null, null, evidence);
        }
    }

    private final CellMemo<Prepared> prepared = new CellMemo<>(4096);

    /**
     * Everything an anchor's own earlier facts decide: family, a shoaling or sheltered coast or a
     * river mouth, shoreline root and guide, attachments, proposal, support and water
     * reservations. Only anchors that pass compete for the sediment lane, so one that could never
     * build cannot veto one that can. Reads earlier intent and the pre-sediment surface only,
     * never another site.
     */
    private Prepared prepare(int cx, int cz, long owner) {
        return prepared.get(cx, cz, () -> prepareUncached(cx, cz, owner));
    }

    private Prepared prepareUncached(int cx, int cz, long owner) {
        Evidence e = new Evidence();
        int selected = Rules.choose(weights, Sites.random(owner, 0x53454449));
        if (selected < 0) return new Prepared("NOT_SELECTED", null, e);
        Family f = FAMILIES[selected];
        double ax = sites.x(cx, owner), az = sites.z(cz, owner);
        if (Math.abs(ax) + radius > 29999984 || Math.abs(az) + radius > 29999984)
            return new Prepared("WORLD_BOUND", f, e);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        earlierIntent.sample(ax, az, scratch);
        double nx = scratch.ocean.normalX, nz = scratch.ocean.normalZ, n = Math.hypot(nx, nz);
        if (n < .5 || Math.abs(scratch.ocean.distance) > anchorReach)
            return new Prepared("NO_NEAR_SHORE", f, e);
        if (scratch.ocean.shoaling < shoalingThreshold && scratch.ocean.shelter < .18) {
            earlierTerrain.sample(ax, az, scratch);
            if (!(scratch.water.mouth > 0)) return new Prepared("NO_SHOALING", f, e);
        }
        nx /= n;
        nz /= n;
        double tx = nz, tz = -nx;
        // The coast distance is the outline score, which can understate true distance, so the
        // search reaches half again past the admission band.
        double[] root = shore(ax, az, nx, nz, 1.5 * anchorReach, scratch);
        if (root == null) return new Prepared("SHORE_BRACKET", f, e);
        double[] gx = new double[9], gz = new double[9];
        if (f == Family.BEACH_BENCH || f == Family.BARRIER) {
            for (int i = 0; i < gx.length; i++) {
                double u = reference.plainWidth * (i / 8.0 - .5);
                double[] node =
                        shore(
                                root[0] + tx * u,
                                root[1] + tz * u,
                                nx,
                                nz,
                                reference.plainWidth * .5,
                                scratch);
                if (node == null) return new Prepared("SHORE_GUIDE", f, e);
                gx[i] = node[0];
                gz[i] = node[1];
            }
        }
        Anchor mainland = attachment(root[0], root[1], nx, nz, tx, tz, scratch, e);
        if (mainland == null) return new Prepared("MAINLAND_ATTACHMENT", f, e);
        Anchor island = null;
        if (f == Family.TOMBOLO) {
            island = island(mainland, nx, nz, scratch, e, ax, az);
            if (island == null) return new Prepared("EARLIER_ISLAND", f, e);
        }
        // The factory owns geometry; it does not certify its own foundation with a synthetic true
        // flag.
        SedimentaryCoasts.ColumnProposal p;
        try {
            p =
                    SedimentaryCoasts.proposeColumns(
                            f, seed, owner, reference, tuning, gx, gz, mainland, island, NO_MASKS);
        } catch (IllegalArgumentException invalidLayout) {
            return new Prepared("KERNEL_LAYOUT_BOUNDS", f, e);
        }
        if (!inside(p, ax, az)) return new Prepared("SUPPORT_BUDGET", f, e);
        if (p.boxColumns > maximumColumns) return new Prepared("WORK_BOUND", f, e);
        // Only barriers may keep a reserved column (an inlet), so for the other families a
        // lattice failure without reservations is a failure with them, and it costs less.
        String coarse = f == Family.BARRIER ? null : lattice(f, p, e, scratch);
        if (coarse != null) return new Prepared(coarse, f, e);
        Reservation[] masks = reservations(p);
        if (masks == null) return new Prepared("RESERVATION_BOUND", f, e);
        p =
                SedimentaryCoasts.proposeColumns(
                        f, seed, owner, reference, tuning, gx, gz, mainland, island, masks);
        coarse = f == Family.BARRIER ? lattice(f, p, e, scratch) : null;
        if (coarse != null) return new Prepared(coarse, f, e);
        return new Prepared(null, f, p, mainland, island, e);
    }

    /**
     * The certificate's per-column failures on a world-aligned lattice, before the exhaustive
     * scan: each is a necessary condition at an integer column of the box, so a body rejected
     * here would be rejected there too, and one that passes still certifies every column. The
     * whole-body supply, slope and exposure bounds are extremes over active columns, so the
     * lattice's extremes (with the attachment's, already in the evidence) are necessary too. Most
     * bodies fail on deep foundations or high parents, so this keeps them out of competition at a
     * fraction of the cost.
     */
    private String lattice(
            Family f,
            SedimentaryCoasts.ColumnProposal p,
            Evidence attached,
            TerrainMap.Sample scratch) {
        SedimentaryCoasts.Sample proposed = new SedimentaryCoasts.Sample();
        double sediment = attached.sediment, slope = attached.slope, exposure = attached.exposure;
        int step = LATTICE,
                minX = Math.floorDiv((int) Math.floor(p.minX) + step - 1, step) * step,
                minZ = Math.floorDiv((int) Math.floor(p.minZ) + step - 1, step) * step;
        for (int z = minZ; z <= Math.ceil(p.maxZ); z += step)
            for (int x = minX; x <= Math.ceil(p.maxX); x += step) {
                earlierTerrain.sample(x, z, scratch);
                if (!Double.isFinite(scratch.height)) return "INVALID_PARENT";
                SedimentaryCoasts.probeColumns(
                        p,
                        x,
                        z,
                        scratch.height,
                        scratch.ocean.marine,
                        true,
                        reserved(scratch),
                        proposed);
                if (proposed.proposedWeight <= 0) continue;
                if (proposed.reason == SedimentaryCoasts.Reason.WATER_RESERVED) {
                    if (f != Family.BARRIER || !SedimentaryCoasts.masked(p, x, z))
                        return "WATER_COLUMN";
                    continue;
                }
                if (proposed.reason != SedimentaryCoasts.Reason.ACCEPTED)
                    return "COLUMN_" + proposed.reason;
                if (f == Family.BARRIER && !scratch.ocean.marine) return "BARRIER_REACHES_LAND";
                slope = Math.max(slope, Math.hypot(scratch.broadDx, scratch.broadDz));
                exposure = Math.max(exposure, exposure(scratch));
                if (!scratch.ocean.marine) sediment = Math.min(sediment, supply(scratch));
            }
        if (sediment < SedimentaryCoasts.MINIMUM_SEDIMENT) return "NO_SEDIMENT";
        if (slope > SedimentaryCoasts.MAXIMUM_SLOPE) return "TOO_STEEP";
        if (exposure > SedimentaryCoasts.MAXIMUM_EXPOSURE) return "TOO_EXPOSED";
        return null;
    }

    private Decision build(int cx, int cz) {
        long owner = sites.key(Sites.Lane.SEDIMENT, cx, cz);
        if (!sites.inLane(owner, Sites.Lane.SEDIMENT))
            return reject(null, "SHARED_BUDGET", new Evidence());
        Prepared c = prepare(cx, cz, owner);
        Evidence e = c.evidence.copy();
        Family f = c.family;
        if (c.reason != null) return reject(f, c.reason, e);
        if (!sites.admits(
                cx, cz, owner, Sites.Lane.SEDIMENT, (x, z, o) -> prepare(x, z, o).reason == null))
            return reject(f, "SHARED_BUDGET", e);
        SedimentaryCoasts.ColumnProposal p = c.proposal;
        Anchor mainland = c.mainland, island = c.island;
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        int minX = (int) Math.floor(p.minX),
                maxX = (int) Math.ceil(p.maxX),
                minZ = (int) Math.floor(p.minZ),
                maxZ = (int) Math.ceil(p.maxZ),
                width = maxX - minX + 1,
                height = maxZ - minZ + 1;
        // Transient occupancy only, for a native connection certificate; never retained in the
        // memo.
        byte[] dry = f == Family.SPIT || f == Family.TOMBOLO ? new byte[p.boxColumns] : null;
        byte[] water = f != Family.BEACH_BENCH ? new byte[p.boxColumns] : null;
        SedimentaryCoasts.Sample proposed = new SedimentaryCoasts.Sample();
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                earlierTerrain.sample(x, z, scratch);
                e.boxColumns++;
                if (!Double.isFinite(scratch.height)) return reject(f, "INVALID_PARENT", e);
                SedimentaryCoasts.probeColumns(
                        p,
                        x,
                        z,
                        scratch.height,
                        scratch.ocean.marine,
                        true,
                        reserved(scratch),
                        proposed);
                if (water != null
                        && scratch.ocean.marine
                        && scratch.waterY() != Integer.MIN_VALUE
                        && Math.floor(proposed.heightY) < scratch.waterY())
                    water[(z - minZ) * width + x - minX] = 1;
                if (proposed.proposedWeight <= 0) continue;
                if (proposed.reason == SedimentaryCoasts.Reason.WATER_RESERVED) {
                    // Only barriers have deliberately protected inlet breaks. Unmapped reservations
                    // reject the whole body.
                    if (f != Family.BARRIER || !SedimentaryCoasts.masked(p, x, z))
                        return reject(f, "WATER_COLUMN", e);
                    e.maskedColumns++;
                    continue;
                }
                if (proposed.reason != SedimentaryCoasts.Reason.ACCEPTED)
                    return reject(f, "COLUMN_" + proposed.reason, e);
                if (f == Family.BARRIER && !scratch.ocean.marine)
                    return reject(f, "BARRIER_REACHES_LAND", e);
                e.activeColumns++;
                e.minParent = Math.min(e.minParent, scratch.height);
                e.maxParent = Math.max(e.maxParent, scratch.height);
                e.slope = Math.max(e.slope, Math.hypot(scratch.broadDx, scratch.broadDz));
                e.exposure = Math.max(e.exposure, exposure(scratch));
                if (!scratch.ocean.marine) e.sediment = Math.min(e.sediment, supply(scratch));
                if (proposed.emergentSediment) e.emergentColumns++;
                if (dry != null && dry(proposed.heightY, scratch.waterY()))
                    dry[(z - minZ) * width + x - minX] = (byte) (proposed.emergentSediment ? 2 : 1);
            }
        if (e.activeColumns == 0 || (f != Family.BEACH_BENCH && e.emergentColumns == 0))
            return reject(f, "NO_EFFECTIVE_BODY", e);
        if (water != null && !openWater(water, width, height))
            return reject(f, "ENCLOSED_NATIVE_WATER", e);
        boolean connected =
                dry == null
                        || connected(
                                dry,
                                width,
                                height,
                                minX,
                                minZ,
                                mainland,
                                island,
                                e.emergentColumns);
        SedimentaryCoasts.ColumnFacts facts =
                new SedimentaryCoasts.ColumnFacts(
                        p,
                        e.sediment,
                        e.slope,
                        e.exposure,
                        e.boxColumns,
                        e.activeColumns,
                        true,
                        connected,
                        e.islandColumns);
        SedimentaryCoasts.ColumnDecision decision = SedimentaryCoasts.admitColumns(p, facts);
        return new Decision(decision.plan, f, decision.reason.name(), e);
    }

    private boolean inside(SedimentaryCoasts.ColumnProposal p, double x, double z) {
        double dx = Math.max(Math.abs(Math.floor(p.minX) - x), Math.abs(Math.ceil(p.maxX) - x)),
                dz = Math.max(Math.abs(Math.floor(p.minZ) - z), Math.abs(Math.ceil(p.maxZ) - z));
        return Math.hypot(dx, dz) + 2 <= radius;
    }

    /**
     * The shoreline nearest the point along the coast normal, within reach either way: walks out in
     * 8-block steps on both sides until land and sea flip, bisects that step, and requires sea
     * seaward and land inland two blocks either side of the root. The former end-point bracket
     * failed beside narrow peninsulas, inlets and islets, where both ends fall on one side.
     */
    private double[] shore(
            double x, double z, double nx, double nz, double reach, TerrainMap.BaseSample scratch) {
        earlierIntent.sample(x, z, scratch);
        boolean here = scratch.ocean.marine;
        double low = Double.NaN, high = Double.NaN;
        for (double t = 8; t <= reach + 7.999 && Double.isNaN(low); t += 8) {
            double step = Math.min(t, reach);
            for (int side = 1; side >= -1 && Double.isNaN(low); side -= 2) {
                earlierIntent.sample(x + nx * side * step, z + nz * side * step, scratch);
                if (scratch.ocean.marine != here) {
                    double inner = side * Math.max(0, step - 8), outer = side * step;
                    // Keep "low" on the sea side and "high" on the land side of the bracket.
                    if (here) {
                        low = inner;
                        high = outer;
                    } else {
                        low = outer;
                        high = inner;
                    }
                }
            }
        }
        if (Double.isNaN(low)) return null;
        for (int i = 0; i < 16; i++) {
            double m = (low + high) * .5;
            earlierIntent.sample(x + nx * m, z + nz * m, scratch);
            if (scratch.ocean.marine) low = m;
            else high = m;
        }
        double q = (low + high) * .5, px = x + nx * q, pz = z + nz * q;
        earlierIntent.sample(px + nx * 2, pz + nz * 2, scratch);
        if (scratch.ocean.marine) return null;
        earlierIntent.sample(px - nx * 2, pz - nz * 2, scratch);
        if (!scratch.ocean.marine) return null;
        return new double[] {px, pz};
    }

    private Anchor attachment(
            double x,
            double z,
            double nx,
            double nz,
            double tx,
            double tz,
            TerrainMap.Sample scratch,
            Evidence e) {
        int steps = (int) Math.ceil(reference.widthMax * 2);
        for (int i = 1; i <= steps; i++) {
            int px = (int) Math.round(x + nx * i), pz = (int) Math.round(z + nz * i);
            earlierTerrain.sample(px, pz, scratch);
            e.probeColumns++;
            if (!scratch.ocean.marine
                    && dry(scratch.height, scratch.waterY())
                    && !reserved(scratch)
                    && scratch.height >= reference.sea + reference.beachMin
                    && scratch.height <= reference.sea + reference.beachMax) {
                e.sediment = Math.min(e.sediment, supply(scratch));
                e.exposure = Math.max(e.exposure, exposure(scratch));
                return new Anchor(px, pz, scratch.height, tx, tz, true);
            }
        }
        return null;
    }

    /** First seaward dry component after occupied ocean water; a failed component is not replaced by another. */
    private Anchor island(
            Anchor mainland,
            double nx,
            double nz,
            TerrainMap.Sample scratch,
            Evidence e,
            double ownerX,
            double ownerZ) {
        boolean crossedWater = false, foundDry = false;
        int limit = (int) Math.floor(reference.plainWidth * 1.5);
        for (int i = 1; i <= limit; i++) {
            int x = (int) Math.round(mainland.x - nx * i),
                    z = (int) Math.round(mainland.z - nz * i);
            earlierTerrain.sample(x, z, scratch);
            e.probeColumns++;
            boolean land = dry(scratch.height, scratch.waterY());
            if (!land) {
                if (foundDry) return null;
                if (scratch.ocean.marine && scratch.waterY() > scratch.blockY())
                    crossedWater = true;
                continue;
            }
            if (!crossedWater) continue;
            foundDry = true;
            if (reserved(scratch)
                    || scratch.ocean.marine
                    || scratch.height > reference.sea + reference.beachMax) return null;
            if (scratch.height < reference.sea + reference.beachMin) continue;
            double distance = Math.hypot(x - mainland.x, z - mainland.z);
            if (distance < reference.widthMean * 2 || distance > reference.plainWidth * 1.5)
                return null;
            double top = scratch.height;
            if (!isolatedIsland(x, z, mainland, scratch, e, ownerX, ownerZ)) return null;
            return new Anchor(x, z, top, mainland.tangentX, mainland.tangentZ, true);
        }
        return null;
    }

    /** Exhaust an eight-neighbor native dry component. Reaching the finite boundary means unknown, not island. */
    private boolean isolatedIsland(
            int x,
            int z,
            Anchor mainland,
            TerrainMap.Sample scratch,
            Evidence e,
            double ownerX,
            double ownerZ) {
        int reach = (int) Math.ceil(reference.widthMean * 4),
                side = 2 * reach + 1,
                count = side * side;
        if ((long) side * side > maximumIslandColumns
                || Math.hypot(x - ownerX, z - ownerZ) + Math.sqrt(2) * reach + 2 > radius)
            return false;
        byte[] visited = new byte[count];
        int[] queue = new int[count];
        int first = 0, last = 1;
        queue[0] = reach * side + reach;
        visited[queue[0]] = 1;
        while (first < last) {
            int at = queue[first++], u = at % side, v = at / side;
            if (u == 0 || v == 0 || u == side - 1 || v == side - 1) return false;
            int px = x + u - reach, pz = z + v - reach;
            if (px == (int) mainland.x && pz == (int) mainland.z) return false;
            e.islandColumns++;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int q = (v + dz) * side + u + dx;
                    if (visited[q] != 0) continue;
                    visited[q] = 1;
                    earlierTerrain.sample(px + dx, pz + dz, scratch);
                    e.probeColumns++;
                    if (dry(scratch.height, scratch.waterY())) queue[last++] = q;
                }
        }
        return e.islandColumns > 0;
    }

    private static boolean connected(
            byte[] dry,
            int width,
            int height,
            int minX,
            int minZ,
            Anchor a,
            Anchor b,
            int emerged) {
        int x = (int) a.x - minX, z = (int) a.z - minZ;
        if (x < 0 || z < 0 || x >= width || z >= height || dry[z * width + x] == 0) return false;
        int end = -1;
        if (b != null) {
            int bx = (int) b.x - minX, bz = (int) b.z - minZ;
            if (bx < 0 || bz < 0 || bx >= width || bz >= height) return false;
            end = bz * width + bx;
        }
        int[] queue = new int[dry.length];
        int first = 0, last = 1, found = 0;
        boolean reached = end < 0;
        int start = z * width + x;
        queue[0] = start;
        if (dry[start] == 2) found++;
        dry[start] = 3;
        while (first < last) {
            int at = queue[first++], u = at % width, v = at / width;
            if (at == end) reached = true;
            for (int direction = 0; direction < 4; direction++) {
                int px = u + (direction == 0 ? 1 : direction == 1 ? -1 : 0),
                        pz = v + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                if (px < 0 || pz < 0 || px >= width || pz >= height) continue;
                int q = pz * width + px;
                byte value = dry[q];
                if (value == 0 || value == 3) continue;
                if (value == 2) found++;
                dry[q] = 3;
                queue[last++] = q;
            }
        }
        return reached && found == emerged;
    }

    /** Every retained native marine water column must reach the unchanged exterior of this finite box. */
    private static boolean openWater(byte[] water, int width, int height) {
        int[] queue = new int[water.length];
        int first = 0, last = 0, wet = 0;
        for (int at = 0; at < water.length; at++)
            if (water[at] == 1) {
                wet++;
                int x = at % width, z = at / width;
                if (x == 0 || z == 0 || x == width - 1 || z == height - 1) {
                    water[at] = 2;
                    queue[last++] = at;
                }
            }
        while (first < last) {
            int at = queue[first++], x = at % width, z = at / width;
            for (int direction = 0; direction < 4; direction++) {
                int px = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0),
                        pz = z + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                if (px < 0 || pz < 0 || px >= width || pz >= height) continue;
                int q = pz * width + px;
                if (water[q] != 1) continue;
                water[q] = 2;
                queue[last++] = q;
            }
        }
        return last == wet;
    }

    private Reservation[] reservations(SedimentaryCoasts.ColumnProposal p) {
        List<Reservation> masks = new ArrayList<>();
        int cell = waterCellBlocks;
        // Neighbor-owner conservative supports may cross a cell edge even when their native water
        // does not.
        int minX = (int) Math.floor(p.minX / cell) - 1,
                maxX = (int) Math.floor(p.maxX / cell) + 1,
                minZ = (int) Math.floor(p.minZ / cell) - 1,
                maxZ = (int) Math.floor(p.maxZ / cell) + 1;
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                Hydro.Plan owner = waterPlans.plan(x, z);
                for (int k = -1; k < owner.others(); k++) {
                    Hydro.Plan h = k < 0 ? owner : owner.other(k);
                    if (!h.present()) continue;
                    double bank = heights.feature("river_bed").width.targetAverage * waterBankScale;
                    if (!add(masks, p, h.lakeX, h.lakeZ, h.lakeX, h.lakeZ, h.radius + bank))
                        return null;
                    for (int i = 0; i < h.nodes() - 1; i++)
                        if (!add(
                                masks,
                                p,
                                h.x(i),
                                h.z(i),
                                h.x(i + 1),
                                h.z(i + 1),
                                Math.max(h.width(i), h.width(i + 1)) * (1 + waterBankScale) + 2))
                            return null;
                }
                for (int i = 0; i < owner.basins(); i++) {
                    Basin b = owner.basin(i);
                    double support =
                            b.kind == Hydro.Kind.LAGOON
                                    ? Math.hypot(b.support(), b.skirt * .25)
                                    : b.support();
                    if (!add(masks, p, b.x, b.z, b.x, b.z, support)) return null;
                }
            }
        return masks.toArray(new Reservation[0]);
    }

    private boolean add(
            List<Reservation> masks,
            SedimentaryCoasts.ColumnProposal p,
            double ax,
            double az,
            double bx,
            double bz,
            double support) {
        double skirt = Math.max(1, reference.widthMin * .5), r = support + skirt;
        if (Math.max(ax, bx) + r < p.minX
                || Math.min(ax, bx) - r > p.maxX
                || Math.max(az, bz) + r < p.minZ
                || Math.min(az, bz) - r > p.maxZ) return true;
        if (masks.size() == 16) return false;
        masks.add(new Reservation(ax, az, bx, bz, support, skirt));
        return true;
    }

    private static boolean reserved(TerrainMap.BaseSample sample) {
        return sample.ocean.protectedWater || sample.water.wet || sample.water.weight > 0;
    }

    private boolean dry(double height, int waterY) {
        return Math.floor(height) >= Math.max(heights.waterTopY, waterY);
    }

    /** Low gentle land supplies sediment, and so does a river near its mouth. */
    private static double supply(TerrainMap.BaseSample sample) {
        return Math.max(sample.land.low * (1 - sample.land.rugged), sample.water.mouth);
    }

    /**
     * A shoaling coast spends its waves on the shallows, shelter calms a bay, and a river mouth's
     * outflow calms the coast too.
     */
    private static double exposure(TerrainMap.BaseSample sample) {
        double calm = Math.max(sample.ocean.shoaling, Math.min(1, sample.ocean.shelter * 3));
        return 1 - Rules.smooth(Math.max(calm, sample.water.mouth));
    }

    public boolean hasGeometry(double x, double z) {
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984) return false;
        int cx = (int) Math.floor(x / sites.spacing), cz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                long owner = sites.key(Sites.Lane.SEDIMENT, cx + dx, cz + dz);
                if (Math.hypot(x - sites.x(cx + dx, owner), z - sites.z(cz + dz, owner)) > radius)
                    continue;
                Decision d = plan(cx + dx, cz + dz);
                if (d.plan != null && d.plan.contains(x, z)) return true;
            }
        return false;
    }

    public static final class Result implements StageResult {
        public double height, parentHeight, weight;
        public boolean emergentSediment;
        public long id;
        public Family family;
        public String reason;
        public int candidates,
                boxColumns,
                activeColumns,
                maskedColumns,
                probeColumns,
                islandColumns;
        private final SedimentaryCoasts.Sample geometry = new SedimentaryCoasts.Sample();

        public void clear(double parent) {
            height = parentHeight = parent;
            weight = 0;
            emergentSediment = false;
            id = 0;
            family = null;
            reason = "NO_SITE";
            candidates =
                    boxColumns = activeColumns = maskedColumns = probeColumns = islandColumns = 0;
        }
    }

    public void apply(double x, double z, TerrainMap.Sample sample) {
        Result out = sample.sediment;
        out.clear(sample.height);
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984) {
            out.reason = "COLUMN_DOMAIN";
            return;
        }
        int cx = (int) Math.floor(x / sites.spacing), cz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                long owner = sites.key(Sites.Lane.SEDIMENT, cx + dx, cz + dz);
                if (Math.hypot(x - sites.x(cx + dx, owner), z - sites.z(cz + dz, owner)) > radius)
                    continue;
                Decision d = plan(cx + dx, cz + dz);
                out.candidates++;
                if (d.family != null) {
                    out.reason = d.reason;
                    out.id = owner;
                    out.family = d.family;
                    out.boxColumns = d.boxColumns;
                    out.activeColumns = d.activeColumns;
                    out.maskedColumns = d.maskedColumns;
                    out.probeColumns = d.probeColumns;
                    out.islandColumns = d.islandColumns;
                }
                if (d.plan == null || !d.plan.contains(x, z)) continue;
                SedimentaryCoasts.sampleColumns(
                        d.plan,
                        x,
                        z,
                        sample.height,
                        sample.ocean.marine,
                        true,
                        reserved(sample),
                        out.geometry);
                out.reason = out.geometry.reason.name();
                out.weight = out.geometry.weight;
                out.height = out.geometry.heightY;
                out.emergentSediment = out.geometry.emergentSediment;
                sample.height = out.height;
                sample.emergentSediment = out.emergentSediment;
                return;
            }
    }
}
