package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.cold.ColdRegions;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Exclusive cold formations certified against the earlier integer-column terrain. */
public final class ColdTerrain {
    private static final Rules RULES = Rules.load("riven-cold-regions.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final ColdRegions.Family[] FAMILIES = {
        ColdRegions.Family.CIRQUE,
        ColdRegions.Family.TROUGH,
        ColdRegions.Family.DRUMLINS,
        ColdRegions.Family.MORAINES
    };
    private static final int RELIEF_SIDE = 256;
    private final long seed;
    private final MajorFeatureParent earlierTerrain;
    private final BoundedWaterClearance waterClearance;
    private final Sites sites;
    private final ColdRegions.Reference reference;
    private final ColdRegions.Tuning tuning;
    private final Terrain.Admission cutAdmission, raiseAdmission;
    private final double[] weights = new double[FAMILIES.length];
    private final double radius, protectionBlend, coldFull, coldZero;
    private final int searchRadius, sets, ways, maximumColumns;
    private final AtomicReferenceArray<Entry> memo;
    private final CellMemo<Boolean> anchors = new CellMemo<>(4096);
    private final AtomicIntegerArray next;

    ColdTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            MajorFeatureParent earlierTerrain,
            BoundedWaterClearance waterClearance,
            int waterCellBlocks) {
        this.earlierTerrain = earlierTerrain;
        this.waterClearance = waterClearance;
        this.sites = sites;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature cirque = heights.feature("cirque"),
                knoll = heights.feature("knoll"),
                spur = heights.feature("ridge_spur");
        double horizontal = TerrainScale.HORIZONTAL;
        reference =
                new ColdRegions.Reference(
                        cirque.width.targetAverage * horizontal,
                        cirque.magnitude.targetAverage,
                        knoll.width.targetAverage * horizontal,
                        knoll.magnitude.targetAverage,
                        spur.width.targetAverage * horizontal,
                        spur.magnitude.targetAverage,
                        heights.feature("foothills").width.targetAverage * horizontal,
                        heights.lowestSurface,
                        heights.highestSurface);
        Properties p = new Properties();
        for (String key :
                new String[] {
                    "widthMultiplier",
                    "magnitudeMultiplier",
                    "bend",
                    "roughness",
                    "coldMinimum",
                    "occurrence",
                    "hangingFraction",
                    "skirtFraction",
                    "drumlinCount",
                    "moraineCount"
                }) p.setProperty(key, j.get(key).getAsString());
        tuning = new ColdRegions.Tuning(p);
        radius = sites.maximumRadius(Sites.Lane.COLD);
        searchRadius = sites.searchRadius(Sites.Lane.COLD);
        for (ColdRegions.Family family : FAMILIES)
            if (ColdRegions.maximumRadius(family, reference, tuning) > radius)
                throw new IllegalStateException(
                        "Cold formation exceeds its shared owner radius; adjust both definitions");
        protectionBlend =
                heights.feature("scarp").width.targetAverage
                        * horizontal
                        * Rules.number(j, "protectionInScarpWidths", .5, 8);
        if (radius + 1 > waterCellBlocks || protectionBlend > waterCellBlocks)
            throw new IllegalStateException(
                    "Cold support/skirts must fit the finite hydrology clearance query");
        coldFull = Rules.number(j, "coldFullCelsius", -40, 10);
        coldZero = Rules.number(j, "coldZeroCelsius", coldFull + .01, 25);
        cutAdmission = Terrain.Admission.create(j.getAsJsonObject("cutAdmission"));
        raiseAdmission = Terrain.Admission.create(j.getAsJsonObject("raiseAdmission"));
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        maximumColumns = integer(j, "maximumCertificateColumns", 65536, 8000000);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Cold cache sets must be a power of two");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    private static boolean cut(ColdRegions.Family family) {
        return family == ColdRegions.Family.CIRQUE || family == ColdRegions.Family.TROUGH;
    }

    private static final class Entry {
        final long key;
        final Decision decision;

        Entry(long key, Decision decision) {
            this.key = key;
            this.decision = decision;
        }
    }

    private static final class Certificate {
        double minimum = Double.POSITIVE_INFINITY,
                maximum = Double.NEGATIVE_INFINITY,
                cold = 1,
                slope,
                relief,
                clearance;
        int columns, diagnosticColumns;
    }

    public static final class Decision {
        public final ColdRegions.Plan plan;
        public final String reason;
        public final int bits, certificateColumns, diagnosticColumns;
        public final double parentMin, parentMax, cold, relief, slope, clearance;

        private Decision(ColdRegions.Plan plan, String reason, int bits, Certificate c) {
            this.plan = plan;
            this.reason = reason;
            this.bits = bits;
            certificateColumns = c.columns;
            diagnosticColumns = c.diagnosticColumns;
            parentMin = Double.isFinite(c.minimum) ? c.minimum : 0;
            parentMax = Double.isFinite(c.maximum) ? c.maximum : 0;
            cold = c.cold;
            relief = c.relief;
            slope = c.slope;
            clearance = c.clearance;
        }
    }

    /** Only immutable geometry and admission summaries survive construction; no height raster is cached. */
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

    /**
     * Anchor-local conditions that build() already requires (family, occurrence, budget, water
     * clearance and the anchor inspection), from earlier facts only.
     */
    private boolean eligible(int cx, int cz, long owner) {
        return anchors.get(
                cx,
                cz,
                () -> {
                    int chosen = Rules.choose(weights, Sites.random(owner, 0x4346414d));
                    if (chosen < 0) return false;
                    double x = sites.x(cx, owner), z = sites.z(cz, owner);
                    ColdRegions.Candidate candidate =
                            ColdRegions.proposeAt(
                                    FAMILIES[chosen], seed, cx, cz, x, z, reference, tuning);
                    double halo = candidate.radius + 1;
                    if (ColdRegions.unit(candidate.key, 6) >= tuning.occurrence
                            || candidate.radius > radius
                            || waterClearance.surfaceClearance(x, z, halo) < halo) return false;
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    earlierTerrain.sample(x, z, s);
                    return inspect(candidate, s, new Certificate()) == null;
                });
    }

    private Decision build(int cx, int cz) {
        Certificate certificate = new Certificate();
        long owner = sites.key(Sites.Lane.COLD, cx, cz);
        if (!sites.admits(cx, cz, owner, Sites.Lane.COLD, this::eligible))
            return reject("SHARED_BUDGET", 0, certificate);
        int chosen = Rules.choose(weights, Sites.random(owner, 0x4346414d));
        if (chosen < 0) return reject("NOT_SELECTED", 0, certificate);
        double x = sites.x(cx, owner), z = sites.z(cz, owner);
        ColdRegions.Candidate candidate =
                ColdRegions.proposeAt(FAMILIES[chosen], seed, cx, cz, x, z, reference, tuning);
        if (ColdRegions.unit(candidate.key, 6) >= tuning.occurrence)
            return reject("OCCURRENCE", ColdRegions.OCCURRENCE, certificate);
        double halo = candidate.radius + 1;
        if (candidate.radius > radius) return reject("SUPPORT_BUDGET", 0, certificate);
        if (Math.abs(x) + Math.max(halo, RELIEF_SIDE / 2 + 1) > 29999984
                || Math.abs(z) + Math.max(halo, RELIEF_SIDE / 2 + 1) > 29999984)
            return reject("WORLD_BOUND", 0, certificate);
        // Complete earlier water supports have an analytic distance bound. Coast
        // ownership is instead examined at every integer support column below.
        certificate.clearance = waterClearance.surfaceClearance(x, z, halo);
        if (certificate.clearance < halo)
            return reject("WATER_SUPPORT", ColdRegions.RESERVED, certificate);
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        earlierTerrain.sample(x, z, scratch);
        Decision invalid = inspect(candidate, scratch, certificate);
        if (invalid != null) return invalid;
        double anchorSlope = Math.hypot(scratch.broadDx, scratch.broadDz);
        if (cut(candidate.family) && anchorSlope < .012)
            return reject("ANCHOR_SLOPE", ColdRegions.SLOPE, certificate);
        double direction =
                cut(candidate.family)
                        ? Math.atan2(-scratch.broadDz, -scratch.broadDx)
                        : Sites.random(owner, 0x43444952) * 2 * Math.PI;
        int diagnosticX = (int) Math.floor(x) - RELIEF_SIDE / 2,
                diagnosticZ = (int) Math.floor(z) - RELIEF_SIDE / 2;
        int minX = Math.min((int) Math.floor(x - halo), diagnosticX),
                maxX = Math.max((int) Math.ceil(x + halo), diagnosticX + RELIEF_SIDE - 1),
                minZ = Math.min((int) Math.floor(z - halo), diagnosticZ),
                maxZ = Math.max((int) Math.ceil(z + halo), diagnosticZ + RELIEF_SIDE - 1);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) + 1 > maximumColumns)
            return reject("WORK_BOUND", 0, certificate);
        int step = sites.certificateStep, side = (RELIEF_SIDE + step - 1) / step + 1, sampled = 0;
        double[] heights = new double[side * side];
        for (int pz = sites.firstNode(minZ); pz <= maxZ; pz += step)
            for (int px = sites.firstNode(minX); px <= maxX; px += step) {
                double dx = px - x, dz = pz - z;
                boolean support = dx * dx + dz * dz <= halo * halo;
                boolean diagnostic =
                        px >= diagnosticX
                                && px < diagnosticX + RELIEF_SIDE
                                && pz >= diagnosticZ
                                && pz < diagnosticZ + RELIEF_SIDE;
                if (!support && !diagnostic) continue;
                earlierTerrain.sample(px, pz, scratch);
                if (!Double.isFinite(scratch.height))
                    return reject("INVALID_PARENT", 0, certificate);
                if (support) {
                    invalid = inspect(candidate, scratch, certificate);
                    if (invalid != null) return invalid;
                }
                if (diagnostic) {
                    heights[sampled++] = scratch.height;
                    certificate.diagnosticColumns++;
                }
            }
        heights = Arrays.copyOf(heights, sampled);
        Arrays.sort(heights);
        certificate.relief = percentile(heights, .95) - percentile(heights, .05);
        // This is a certified empty radius on the declared integer/anchor domain,
        // not an assertion that the local coast estimate is an exact distance field.
        certificate.clearance = Math.min(certificate.clearance, halo);
        ColdRegions.Earlier earlier =
                new ColdRegions.Earlier(
                        certificate.cold,
                        certificate.relief,
                        certificate.slope,
                        certificate.minimum - sites.certificateMargin,
                        certificate.maximum + sites.certificateMargin,
                        certificate.clearance,
                        direction,
                        false);
        int bits = ColdRegions.reject(candidate, earlier, reference, tuning);
        ColdRegions.Plan plan = ColdRegions.select(candidate, earlier, reference, tuning);
        return new Decision(plan, bits == 0 ? "ACCEPTED" : "KERNEL_REJECT", bits, certificate);
    }

    private Decision inspect(
            ColdRegions.Candidate candidate, TerrainMap.Sample base, Certificate c) {
        c.columns++;
        if (!Double.isFinite(base.height)) return reject("INVALID_PARENT", 0, c);
        // Raising forms may overhang the sea (marine columns never receive them, and the major
        // envelope eases them to the coast); cuts keep the marine rule so they open no coastal pit.
        if (base.ocean.marine)
            return cut(candidate.family) && c.columns > 1 || c.columns == 1
                    ? reject("MARINE_SUPPORT", ColdRegions.MARINE, c)
                    : null;
        c.minimum = Math.min(c.minimum, base.height);
        c.maximum = Math.max(c.maximum, base.height);
        if (base.water.wet || base.water.weight > 0)
            return reject("PROTECTED_SUPPORT", ColdRegions.RESERVED, c);
        double fit = (cut(candidate.family) ? cutAdmission : raiseAdmission).fit(base);
        // Only the anchor (the first inspected column) must be cold and fit; other columns fade
        // by their own coldness and fit in apply, so a warm edge is no longer a rejection.
        if (c.columns == 1) {
            c.cold = suitability(base) * fit;
            if (fit <= 0 || c.cold < tuning.coldMinimum)
                return reject("COLD_TERRAIN_SUPPORT", ColdRegions.WARM, c);
        }
        c.slope = Math.max(c.slope, Math.hypot(base.broadDx, base.broadDz));
        if (c.slope > (cut(candidate.family) ? .65 : .22))
            return reject("SLOPE_SUPPORT", ColdRegions.SLOPE, c);
        double margin = sites.certificateMargin;
        if (base.height - margin < reference.lowest
                || base.height + margin > reference.highest
                || (cut(candidate.family)
                        ? base.height - candidate.depth - margin < reference.lowest
                        : base.height + candidate.depth + margin > reference.highest))
            return reject("HEADROOM_SUPPORT", ColdRegions.HEADROOM, c);
        return null;
    }

    private static Decision reject(String reason, int bits, Certificate c) {
        return new Decision(null, reason, bits, c);
    }

    private static double percentile(double[] sorted, double q) {
        double index = q * (sorted.length - 1);
        int lo = (int) index, hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo);
    }

    private double suitability(TerrainMap.BaseSample base) {
        return Rules.smooth((coldZero - base.climate.celsius) / (coldZero - coldFull));
    }

    public static final class Result {
        public double height, delta, weight, anchorX, anchorZ, cold, relief, slope;
        public int candidates, contributors, certificateColumns, diagnosticColumns, rejectionBits;
        public long id;
        public ColdRegions.Family dominant;
        public String reason;

        public void clear(double parent) {
            height = parent;
            delta = weight = anchorX = anchorZ = cold = relief = slope = 0;
            candidates = contributors = certificateColumns = diagnosticColumns = rejectionBits = 0;
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
        // Production application shares the certificate's integer-column domain.
        if (!Double.isFinite(x)
                || !Double.isFinite(z)
                || x != Math.floor(x)
                || z != Math.floor(z)) {
            out.reason = "COLUMN_DOMAIN";
            return;
        }
        if (base.ocean.marine || base.water.wet || base.water.weight > 0) {
            out.reason = "COLUMN_PROTECTED";
            return;
        }
        if (suitability(base) < tuning.coldMinimum) {
            out.reason = "WARM_COLUMN";
            return;
        }
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long owner = sites.key(Sites.Lane.COLD, cx, cz);
                double ax = sites.x(cx, owner), az = sites.z(cz, owner), px = x - ax, pz = z - az;
                if (px * px + pz * pz >= radius * radius || !sites.inLane(owner, Sites.Lane.COLD))
                    continue;
                Decision decision = plan(cx, cz);
                out.candidates++;
                out.reason = decision.reason;
                out.rejectionBits = decision.bits;
                out.certificateColumns += decision.certificateColumns;
                out.diagnosticColumns += decision.diagnosticColumns;
                out.cold = decision.cold;
                out.relief = decision.relief;
                out.slope = decision.slope;
                ColdRegions.Plan plan = decision.plan;
                if (plan == null) continue;
                ColdRegions.Candidate candidate = plan.candidate;
                if (Math.hypot(x - candidate.x, z - candidate.z) >= candidate.radius) {
                    out.reason = "OUTSIDE_SUPPORT";
                    continue;
                }
                if (parent < decision.parentMin
                        || parent > decision.parentMax
                        || (cut(candidate.family)
                                ? parent - candidate.depth < reference.lowest
                                : parent + candidate.depth > reference.highest)) {
                    out.reason = "UNCERTIFIED_PARENT";
                    continue;
                }
                double fit = (cut(candidate.family) ? cutAdmission : raiseAdmission).fit(base),
                        cold = suitability(base) * fit;
                double coldFade =
                        tuning.coldMinimum == 1
                                ? (cold >= 1 ? 1 : 0)
                                : Rules.smooth(
                                        (cold - tuning.coldMinimum) / (1 - tuning.coldMinimum));
                double water = waterClearance.surfaceClearance(x, z, protectionBlend);
                double fade = coldFade * Rules.smooth(water / protectionBlend);
                double applied = plan.delta(x, z) * fade;
                if (applied == 0) continue;
                out.contributors++;
                // Shared support ownership excludes overlaps; retain a deterministic
                // maximum as defense in depth instead of ever summing full envelopes.
                if (Math.abs(applied) > Math.abs(out.delta)
                        || (Math.abs(applied) == Math.abs(out.delta)
                                && Long.compareUnsigned(candidate.key, out.id) < 0)) {
                    out.delta = applied;
                    out.weight = Math.abs(applied) / candidate.depth;
                    out.dominant = candidate.family;
                    out.id = candidate.key;
                    out.anchorX = candidate.x;
                    out.anchorZ = candidate.z;
                    out.cold = decision.cold;
                    out.relief = decision.relief;
                    out.slope = decision.slope;
                    out.reason = "ACCEPTED";
                }
            }
        out.height = out.delta == 0 ? parent : parent + out.delta;
    }
}
