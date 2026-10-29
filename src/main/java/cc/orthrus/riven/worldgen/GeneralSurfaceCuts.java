package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.carvers.SurfaceCuts;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Four exclusive dry cuts using certified earlier native columns and declared saddle probes. */
public final class GeneralSurfaceCuts {
    private static final Rules RULES = Rules.load("riven-general-surface-cuts.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final SurfaceCuts.Family[] FAMILIES = {
        SurfaceCuts.Family.RIBBON_RAVINE,
        SurfaceCuts.Family.BRANCH_GULLY,
        SurfaceCuts.Family.PINCHED_SLOT,
        SurfaceCuts.Family.BREACH_SADDLE
    };
    private static final double HALF_CORRIDOR = 128;
    private final long seed;
    private final IntentSampler earlierIntent;
    private final MajorFeatureParent earlierTerrain;
    private final BoundedWaterClearance waterClearance;
    private final Sites sites;
    private final SurfaceCuts.Reference reference;
    private final SurfaceCuts.Tuning[] profiles = new SurfaceCuts.Tuning[FAMILIES.length];
    private final double[] weights = new double[FAMILIES.length];
    private final double radius, shoreReservation, erodibility, saddleRelief, minimumLandWeight;
    private final int searchRadius, sets, ways, maximumColumns;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;

    GeneralSurfaceCuts(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            MajorFeatureParent earlierTerrain,
            BoundedWaterClearance waterClearance,
            int waterCellBlocks) {
        this.earlierIntent = earlierIntent;
        this.earlierTerrain = earlierTerrain;
        this.waterClearance = waterClearance;
        this.sites = sites;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature gorge = heights.feature("gorge"),
                hollow = heights.feature("hollow_basin");
        reference =
                new SurfaceCuts.Reference(
                        range(gorge.width, TerrainScale.HORIZONTAL),
                        range(gorge.magnitude, 1),
                        range(hollow.width, TerrainScale.HORIZONTAL),
                        range(hollow.magnitude, 1),
                        heights.lowestSurface,
                        heights.fingerprint);
        radius = sites.maximumRadius(Sites.Lane.CUTS);
        searchRadius = sites.searchRadius(Sites.Lane.CUTS);
        // Cuts keep their own, narrower shore reservation: the outline score grows slower than true
        // distance inland, so the shared half plain width covered most of an 8 km continent.
        shoreReservation =
                heights.feature("coastal_plain").width.targetAverage
                        * Rules.number(RULES.json, "shoreReservationInCoastalPlainWidths", 0, 4);
        minimumLandWeight = Rules.number(RULES.json, "minimumLandWeight", 0, 1);
        erodibility = Rules.number(j, "erodibility", 0, 1);
        saddleRelief = heights.feature("terrace").magnitude.min;
        for (int i = 0; i < FAMILIES.length; i++) {
            JsonObject p = j.getAsJsonObject("profiles").getAsJsonObject(FAMILIES[i].name());
            profiles[i] =
                    new SurfaceCuts.Tuning(
                            Rules.number(p, "occurrence", 0, 1),
                            Rules.number(p, "widthScale", .001, 2),
                            Rules.number(p, "depthScale", .001, 2),
                            Rules.number(p, "lengthRatio", 1, 12),
                            Rules.number(p, "reliefShare", .001, 1),
                            Rules.number(p, "coastGuard", 0, 512),
                            Rules.number(p, "waterGuard", 0, 512));
            if (SurfaceCuts.maximumColumnRadius(FAMILIES[i], reference, profiles[i]) > radius)
                throw new IllegalStateException(
                        "General cut exceeds the existing shared carver radius");
            double guard = profiles[i].waterGuard;
            if (radius + guard + Math.max(1, guard) + 2 > waterCellBlocks)
                throw new IllegalStateException(
                        "General-cut water certificate exceeds finite clearance reach");
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        }
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        maximumColumns = integer(j, "maximumCertificateColumns", 65536, 2000000);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Cut cache sets must be a power of two");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static SurfaceCuts.Range range(TerrainHeightReference.Range r, double scale) {
        return new SurfaceCuts.Range(r.min * scale, r.targetAverage * scale, r.max * scale);
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
        double min = Double.POSITIVE_INFINITY,
                max = Double.NEGATIVE_INFINITY,
                aridity = 1,
                relief,
                saddleFit,
                saddleAxis,
                shore,
                water;
        int supportColumns, protectedColumns, corridorColumns, probeColumns;
    }

    public static final class Decision {
        public final SurfaceCuts.ColumnPlan plan;
        public final String reason;
        public final double minParent,
                maxParent,
                shoreDistance,
                waterDistance,
                relief,
                saddleFit,
                saddleAxis;
        public final int supportColumns, protectedColumns, corridorColumns, probeColumns;

        private Decision(SurfaceCuts.ColumnPlan plan, String reason, Evidence e) {
            this.plan = plan;
            this.reason = reason;
            minParent = Double.isFinite(e.min) ? e.min : 0;
            maxParent = Double.isFinite(e.max) ? e.max : 0;
            shoreDistance = e.shore;
            waterDistance = e.water;
            relief = e.relief;
            saddleFit = e.saddleFit;
            saddleAxis = e.saddleAxis;
            supportColumns = e.supportColumns;
            protectedColumns = e.protectedColumns;
            corridorColumns = e.corridorColumns;
            probeColumns = e.probeColumns;
        }
    }

    public Decision plan(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        Decision built = build(cx, cz);
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.decision;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    private final CellMemo<Boolean> eligibility = new CellMemo<>(4096);

    /**
     * A site's own cheap tests (family, support budget, material and occurrence) before it competes,
     * so a site that would fail them cannot veto a neighbour that passes. Pure per site.
     */
    private boolean eligible(int cx, int cz, long owner) {
        return eligibility.get(
                cx,
                cz,
                () -> {
                    int selected = Rules.choose(weights, Sites.random(owner, 0x47434641));
                    if (selected < 0 || erodibility < .25) return false;
                    SurfaceCuts.Tuning tuning = profiles[selected];
                    SurfaceCuts.ColumnProposal p =
                            SurfaceCuts.proposeColumnsAt(
                                    seed,
                                    cx,
                                    cz,
                                    FAMILIES[selected],
                                    sites.x(cx, owner),
                                    sites.z(cz, owner),
                                    reference,
                                    tuning);
                    if (p.supportRadius > radius
                            || SurfaceCuts.unit(p.id, 6)
                                    >= tuning.occurrence * (.55 + .45 * erodibility)) return false;
                    // The anchor must stand on open land: not water, a bank or protected shore.
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    Evidence probe = new Evidence();
                    return Double.isFinite(
                            earlierHeight((int) Math.round(p.x), (int) Math.round(p.z), s, probe));
                });
    }

    private Decision build(int cx, int cz) {
        Evidence e = new Evidence();
        long owner = sites.key(Sites.Lane.CUTS, cx, cz);
        if (sites.inLane(owner, Sites.Lane.CUTS) && !eligible(cx, cz, owner))
            return reject("NOT_ELIGIBLE", e);
        if (!sites.admits(cx, cz, owner, Sites.Lane.CUTS, this::eligible))
            return reject("SHARED_BUDGET", e);
        int selected = Rules.choose(weights, Sites.random(owner, 0x47434641));
        if (selected < 0) return reject("NOT_SELECTED", e);
        SurfaceCuts.Tuning tuning = profiles[selected];
        SurfaceCuts.ColumnProposal p =
                SurfaceCuts.proposeColumnsAt(
                        seed,
                        cx,
                        cz,
                        FAMILIES[selected],
                        sites.x(cx, owner),
                        sites.z(cz, owner),
                        reference,
                        tuning);
        if (p.supportRadius > radius) return reject("SUPPORT_BUDGET", e);
        // Necessary independent failures can stop before expensive earlier scans.
        // They do not pretend to be the original planner's exhaustive gate ordering.
        if (erodibility < .25) return reject("MATERIAL_PRECHECK", e);
        if (SurfaceCuts.unit(p.id, 6) >= tuning.occurrence * (.55 + .45 * erodibility))
            return reject("OCCURRENCE_PRECHECK", e);
        e.shore = p.supportRadius + tuning.coastGuard + Math.max(1, tuning.coastGuard) + 2;
        double waterReach =
                p.supportRadius + tuning.waterGuard + Math.max(1, tuning.waterGuard) + 2;
        double reach = Math.max(e.shore, waterReach);
        if (Math.abs(p.x) + reach > 29999984 || Math.abs(p.z) + reach > 29999984)
            return reject("WORLD_BOUND", e);
        e.water = waterClearance.surfaceClearance(p.x, p.z, waterReach);
        if (e.water < waterReach) return reject("WATER_SUPPORT", e);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        int anchorX = (int) Math.round(p.x), anchorZ = (int) Math.round(p.z);
        double center = earlierHeight(anchorX, anchorZ, scratch, e);
        double gradientX =
                (earlierHeight(anchorX + 1, anchorZ, scratch, e)
                                - earlierHeight(anchorX - 1, anchorZ, scratch, e))
                        * .5;
        double gradientZ =
                (earlierHeight(anchorX, anchorZ + 1, scratch, e)
                                - earlierHeight(anchorX, anchorZ - 1, scratch, e))
                        * .5;
        if (!Double.isFinite(center) || !Double.isFinite(gradientX) || !Double.isFinite(gradientZ))
            return reject("INVALID_PARENT", e);
        if (p.family == SurfaceCuts.Family.BREACH_SADDLE) {
            saddle(p, anchorX, anchorZ, center, scratch, e);
            if (e.saddleFit < .6) return reject("SADDLE_PROBES", e);
        }
        double angle = SurfaceCuts.columnAngle(p, gradientX, gradientZ, e.saddleAxis),
                c = StrictMath.cos(angle),
                s = StrictMath.sin(angle);
        double cross = p.width * .5,
                extentX = Math.abs(c) * cross + Math.abs(s) * HALF_CORRIDOR,
                extentZ = Math.abs(s) * cross + Math.abs(c) * HALF_CORRIDOR;
        int corridorMinX = (int) Math.floor(p.x - extentX),
                corridorMaxX = (int) Math.ceil(p.x + extentX),
                corridorMinZ = (int) Math.floor(p.z - extentZ),
                corridorMaxZ = (int) Math.ceil(p.z + extentZ);
        int minX = Math.min((int) Math.floor(p.x - e.shore), corridorMinX),
                maxX = Math.max((int) Math.ceil(p.x + e.shore), corridorMaxX),
                minZ = Math.min((int) Math.floor(p.z - e.shore), corridorMinZ),
                maxZ = Math.max((int) Math.ceil(p.z + e.shore), corridorMaxZ);
        if (minX < -29999984 || maxX > 29999984 || minZ < -29999984 || maxZ > 29999984)
            return reject("WORLD_BOUND", e);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > maximumColumns)
            return reject("WORK_BOUND", e);
        double[] corridor =
                new double[(corridorMaxX - corridorMinX + 1) * (corridorMaxZ - corridorMinZ + 1)];
        double support = p.supportRadius + 1;
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                double dx = x - p.x,
                        dz = z - p.z,
                        r2 = dx * dx + dz * dz,
                        u = dx * c + dz * s,
                        v = -dx * s + dz * c;
                boolean protection = r2 <= e.shore * e.shore,
                        inSupport = r2 <= support * support,
                        diagnostic =
                                x >= corridorMinX
                                        && x <= corridorMaxX
                                        && z >= corridorMinZ
                                        && z <= corridorMaxZ
                                        && Math.abs(u) <= cross
                                        && v >= -HALF_CORRIDOR
                                        && v < HALF_CORRIDOR;
                if (!protection && !inSupport && !diagnostic) continue;
                if (inSupport || diagnostic) earlierTerrain.sample(x, z, scratch);
                else earlierIntent.sample(x, z, scratch);
                if (protection) e.protectedColumns++;
                if (scratch.ocean.marine || protectedShore(scratch))
                    return reject("SHORE_SUPPORT", e);
                if ((inSupport || diagnostic) && (scratch.water.wet || scratch.water.weight > 0))
                    return reject("WATER_COLUMN", e);
                if (inSupport) {
                    if (!Double.isFinite(scratch.height)) return reject("INVALID_PARENT", e);
                    e.supportColumns++;
                    e.min = Math.min(e.min, scratch.height);
                    e.max = Math.max(e.max, scratch.height);
                    e.aridity =
                            Math.min(
                                    e.aridity,
                                    1 - Math.max(0, Math.min(1, scratch.climate.moisture)));
                }
                if (diagnostic) {
                    if (!Double.isFinite(scratch.height)) return reject("INVALID_DIAGNOSTIC", e);
                    corridor[e.corridorColumns++] = scratch.height;
                }
            }
        if (e.supportColumns == 0 || e.corridorColumns == 0) return reject("UNKNOWN_SUPPORT", e);
        Arrays.sort(corridor, 0, e.corridorColumns);
        e.relief =
                percentile(corridor, e.corridorColumns, .95)
                        - percentile(corridor, e.corridorColumns, .05);
        SurfaceCuts.ColumnFacts facts =
                new SurfaceCuts.ColumnFacts(
                        p,
                        false,
                        e.min,
                        e.relief,
                        gradientX,
                        gradientZ,
                        e.shore,
                        e.water,
                        erodibility,
                        e.aridity,
                        e.saddleFit,
                        e.saddleAxis,
                        e.supportColumns);
        SurfaceCuts.ColumnDecision decision = SurfaceCuts.selectColumns(p, facts);
        return new Decision(decision.plan, decision.reason.name(), e);
    }

    private void saddle(
            SurfaceCuts.ColumnProposal p,
            int x,
            int z,
            double center,
            TerrainMap.Sample scratch,
            Evidence e) {
        double along = Math.min(HALF_CORRIDOR, p.length * .35), across = p.width * .45;
        for (int direction = 0; direction < 16; direction++) {
            double axis = direction * Math.PI / 16,
                    c = StrictMath.cos(axis),
                    s = StrictMath.sin(axis),
                    fit = 1,
                    positive = 0,
                    negative = 0;
            for (int scale = 1; scale <= 2; scale++) {
                double f = scale * .5;
                double a = probe(x + along * f * c, z + along * f * s, scratch, e),
                        b = probe(x - along * f * c, z - along * f * s, scratch, e),
                        left = probe(x - across * f * s, z + across * f * c, scratch, e),
                        right = probe(x + across * f * s, z - across * f * c, scratch, e);
                if (!Double.isFinite(a)
                        || !Double.isFinite(b)
                        || !Double.isFinite(left)
                        || !Double.isFinite(right)) {
                    fit = 0;
                    break;
                }
                double shoulders = Math.min(left, right) - center, lower = center - Math.max(a, b);
                fit =
                        Math.min(
                                fit,
                                SurfaceCuts.smooth(shoulders / (saddleRelief * f))
                                        * SurfaceCuts.smooth(lower / (saddleRelief * f)));
                positive = a;
                negative = b;
            }
            if (fit > e.saddleFit) {
                e.saddleFit = fit;
                e.saddleAxis = positive <= negative ? axis : axis + Math.PI;
            }
        }
    }

    private double probe(double x, double z, TerrainMap.Sample scratch, Evidence e) {
        return earlierHeight((int) Math.round(x), (int) Math.round(z), scratch, e);
    }

    private double earlierHeight(int x, int z, TerrainMap.Sample scratch, Evidence e) {
        earlierTerrain.sample(x, z, scratch);
        e.probeColumns++;
        return scratch.ocean.marine
                        || protectedShore(scratch)
                        || scratch.water.wet
                        || scratch.water.weight > 0
                ? Double.NaN
                : scratch.height;
    }

    private static double percentile(double[] a, int count, double q) {
        double position = q * (count - 1);
        int lo = (int) position, hi = Math.min(lo + 1, count - 1);
        return a[lo] + (a[hi] - a[lo]) * (position - lo);
    }

    private boolean protectedShore(TerrainMap.BaseSample base) {
        // The shore reservation protects the coast. Land weight below 1 covered most of every
        // continent once continents were sized for 8 km worlds, so only the low part counts.
        return base.shore.kind != Shore.Kind.INLAND
                || base.shore.landWeight < minimumLandWeight
                || base.shore.distance <= shoreReservation;
    }

    private static Decision reject(String reason, Evidence e) {
        return new Decision(null, reason, e);
    }

    public static final class Result implements StageResult {
        public double height, cut, weight, anchorX, anchorZ, relief, saddleFit;
        public int candidates,
                contributors,
                supportColumns,
                protectedColumns,
                corridorColumns,
                probeColumns;
        public long id;
        public SurfaceCuts.Family dominant;
        public String reason;

        public void clear(double parent) {
            height = parent;
            cut = weight = anchorX = anchorZ = relief = saddleFit = 0;
            candidates =
                    contributors =
                            supportColumns = protectedColumns = corridorColumns = probeColumns = 0;
            id = 0;
            dominant = null;
            reason = "NO_SITE";
        }
    }

    public void apply(double x, double z, TerrainMap.BaseSample base, double parent, Result out) {
        out.clear(parent);
        if (!Double.isFinite(parent)) {
            out.reason = "INVALID_PARENT";
            return;
        }
        if (x != Math.floor(x)
                || z != Math.floor(z)
                || Math.abs(x) > 29999984
                || Math.abs(z) > 29999984) {
            out.reason = "COLUMN_DOMAIN";
            return;
        }
        if (base.waterReserved() || protectedShore(base)) {
            out.reason = "COLUMN_PROTECTED";
            return;
        }
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long owner = sites.key(Sites.Lane.CUTS, cx, cz);
                double ax = sites.x(cx, owner),
                        az = sites.z(cz, owner),
                        distance = Math.hypot(x - ax, z - az);
                if (distance >= radius || !sites.admits(cx, cz, owner, Sites.Lane.CUTS)) continue;
                Decision d = plan(cx, cz);
                out.candidates++;
                out.reason = d.reason;
                out.supportColumns += d.supportColumns;
                out.protectedColumns += d.protectedColumns;
                out.corridorColumns += d.corridorColumns;
                out.probeColumns += d.probeColumns;
                out.relief = d.relief;
                out.saddleFit = d.saddleFit;
                SurfaceCuts.ColumnPlan plan = d.plan;
                if (plan == null || distance >= plan.proposal.supportRadius) continue;
                if (parent < d.minParent || parent > d.maxParent) {
                    out.reason = "UNCERTIFIED_PARENT";
                    continue;
                }
                double cut =
                        plan.cut(
                                (int) x,
                                (int) z,
                                parent,
                                reference.lowestSurface,
                                false,
                                Math.max(0, d.shoreDistance - distance),
                                Math.max(0, d.waterDistance - distance));
                if (cut <= 0) continue;
                out.contributors++;
                // Every alternative reads one unmodified parent; never add full cut budgets.
                if (cut > out.cut
                        || (cut == out.cut && Long.compareUnsigned(plan.proposal.id, out.id) < 0)) {
                    out.cut = cut;
                    out.weight = cut / plan.depth;
                    out.dominant = plan.proposal.family;
                    out.id = plan.proposal.id;
                    out.anchorX = plan.proposal.x;
                    out.anchorZ = plan.proposal.z;
                    out.reason = "ACCEPTED";
                }
            }
        out.height = out.cut == 0 ? parent : parent - out.cut;
    }
}
