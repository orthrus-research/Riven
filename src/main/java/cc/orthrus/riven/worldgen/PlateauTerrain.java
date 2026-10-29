package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.plateau.PlateauKernels;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** One plateau alternative per shared owner; parent bounds precede every major feature. */
public final class PlateauTerrain {
    private static final Rules RULES = Rules.load("riven-plateaus.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final PlateauKernels.Family[] FAMILIES = {
        PlateauKernels.Family.WARPED_CAP,
        PlateauKernels.Family.MESA_REMNANTS,
        PlateauKernels.Family.BROKEN_SCARP,
        PlateauKernels.Family.BRANCH_BADLANDS
    };
    private final long seed;
    private final Sites sites;
    private final MajorFeatureParent earlierTerrain;
    private final BoundedWaterClearance waterClearance;
    private final PlateauKernels.Reference reference;
    private final PlateauKernels.Tuning tuning;
    private final double[] weights = new double[FAMILIES.length];
    private final double radius, protectionBlend, erodibility;
    private final int searchRadius, sets, ways, maximumColumns;
    private final AtomicReferenceArray<Entry> memo;
    private final CellMemo<Anchor> anchors = new CellMemo<>(4096);
    private final AtomicIntegerArray next;

    PlateauTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            MajorFeatureParent earlierTerrain,
            BoundedWaterClearance waterClearance,
            int waterCellBlocks) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        this.sites = sites;
        this.earlierTerrain = earlierTerrain;
        this.waterClearance = waterClearance;
        TerrainHeightReference.Feature plateau = heights.feature("plateau"),
                mesa = heights.feature("mesa_butte"),
                scarp = heights.feature("scarp");
        double horizontal = TerrainScale.HORIZONTAL;
        reference =
                new PlateauKernels.Reference(
                        plateau.width.targetAverage * horizontal,
                        plateau.relief.targetAverage,
                        heights.seaLevel + plateau.elevationOffset.targetAverage,
                        mesa.width.targetAverage * horizontal,
                        mesa.magnitude.targetAverage,
                        scarp.width.targetAverage * horizontal,
                        scarp.magnitude.targetAverage,
                        heights.feature("valley").magnitude.targetAverage,
                        heights.feature("terrace").magnitude.targetAverage,
                        heights.lowestSurface,
                        heights.highestSurface);
        JsonObject j = RULES.json;
        tuning =
                new PlateauKernels.Tuning(
                        Rules.number(j, "widthScale", .5, 1.5),
                        Rules.number(j, "reliefScale", .25, 1.5),
                        Rules.number(j, "skirtFraction", .08, .3),
                        Rules.number(j, "warp", 0, .18),
                        Rules.number(j, "dissection", 0, 1),
                        Rules.number(j, "aridityFloor", 0, 1));
        erodibility = Rules.number(j, "erodibility", 0, 1);
        radius = sites.maximumRadius(Sites.Lane.PLATEAUS);
        searchRadius = sites.searchRadius(Sites.Lane.PLATEAUS);
        if (Math.max(reference.plateauWidth, reference.mesaWidth * 1.25) * tuning.widthScale * .5
                > radius)
            throw new IllegalStateException(
                    "Plateau alternatives exceed their shared support budget");
        protectionBlend = reference.scarpWidth * Rules.number(j, "protectionInScarpWidths", .5, 8);
        if (!Double.isFinite(protectionBlend)
                || protectionBlend <= 0
                || protectionBlend > waterCellBlocks)
            throw new IllegalStateException(
                    "Plateau preservation skirt must fit a hydrology owner width");
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        maximumColumns = integer(j, "maximumCertificateColumns", 1024, 1000000);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Plateau cache sets must be a power of two");
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

    public static final class Decision {
        public final PlateauKernels.Plan plan;
        public final String reason;
        public final double parentMin, parentMax;
        public final int certificateColumns;

        private Decision(
                PlateauKernels.Plan plan, String reason, double min, double max, int columns) {
            this.plan = plan;
            this.reason = reason;
            parentMin = min;
            parentMax = max;
            certificateColumns = columns;
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

    /** Anchor facts from earlier terrain only; a non-null rejection makes the anchor ineligible. */
    private static final class Anchor {
        final String rejection;
        final PlateauKernels.Family family;
        final double x, z, height, fit, aridity;
        final boolean water, marine;

        Anchor(
                String rejection,
                PlateauKernels.Family family,
                double x,
                double z,
                double height,
                double fit,
                double aridity,
                boolean water,
                boolean marine) {
            this.rejection = rejection;
            this.family = family;
            this.x = x;
            this.z = z;
            this.height = height;
            this.fit = fit;
            this.aridity = aridity;
            this.water = water;
            this.marine = marine;
        }
    }

    private Anchor anchor(int cx, int cz, long owner) {
        return anchors.get(cx, cz, () -> anchorFacts(cx, cz, owner));
    }

    private Anchor anchorFacts(int cx, int cz, long owner) {
        double x = sites.x(cx, owner), z = sites.z(cz, owner);
        if (Math.abs(x) + radius + 1 > 29999984 || Math.abs(z) + radius + 1 > 29999984)
            return new Anchor("WORLD_BOUND", null, x, z, 0, 0, 0, false, false);
        int choice = Rules.choose(weights, Sites.random(owner, 0x5046414d));
        if (choice < 0) return new Anchor("NOT_SELECTED", null, x, z, 0, 0, 0, false, false);
        PlateauKernels.Family family = FAMILIES[choice];
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        earlierTerrain.sample(x, z, scratch);
        if (!Double.isFinite(scratch.height))
            return new Anchor("INVALID_PARENT", family, x, z, 0, 0, 0, false, false);
        double fit = unit(scratch.land.fit(0, 1, 1, Terrain.Fit.PLATEAU)),
                aridity = 1 - unit(scratch.climate.moisture);
        boolean water =
                scratch.water.wet
                        || scratch.water.weight > 0
                        || waterClearance.surfaceClearance(x, z, protectionBlend) <= 0;
        boolean marine = scratch.ocean.marine;
        PlateauKernels.Reason eligible =
                PlateauKernels.eligibility(
                        family, tuning, marine, water, marine, fit, aridity, erodibility);
        return new Anchor(
                eligible == PlateauKernels.Reason.ACCEPT ? null : eligible.name(),
                family,
                x,
                z,
                scratch.height,
                fit,
                aridity,
                water,
                marine);
    }

    /** Competes only against plateau anchors whose own earlier facts admit a plateau. */
    private boolean eligible(int cx, int cz, long owner) {
        return anchor(cx, cz, owner).rejection == null;
    }

    private Decision build(int cx, int cz) {
        long owner = sites.key(Sites.Lane.PLATEAUS, cx, cz);
        if (!sites.admits(cx, cz, owner, Sites.Lane.PLATEAUS, this::eligible))
            return reject("SHARED_BUDGET", 0);
        Anchor a = anchor(cx, cz, owner);
        if (a.rejection != null) return reject(a.rejection, a.family == null ? 0 : 1);
        PlateauKernels.Family family = a.family;
        double x = a.x, z = a.z, fit = a.fit, aridity = a.aridity;
        boolean water = a.water, marine = a.marine, shore = marine;
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        double support = PlateauKernels.supportRadius(family, seed, cx, cz, reference, tuning),
                halo = support + 1;
        if (!Double.isFinite(support) || support <= 0 || support > radius)
            return reject("SUPPORT_BUDGET", 1);
        int minX = (int) Math.floor(x - halo), maxX = (int) Math.ceil(x + halo);
        int minZ = (int) Math.floor(z - halo), maxZ = (int) Math.ceil(z + halo);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) + 1 > maximumColumns)
            return reject("WORK_BOUND", 1);
        double low = a.height, high = low;
        int columns = 1;
        int step = sites.certificateStep;
        for (int pz = sites.firstNode(minZ); pz <= maxZ; pz += step)
            for (int px = sites.firstNode(minX); px <= maxX; px += step) {
                double dx = px - x, dz = pz - z;
                if (dx * dx + dz * dz > halo * halo) continue;
                earlierTerrain.sample(px, pz, scratch);
                columns++;
                if (!Double.isFinite(scratch.height)) return reject("INVALID_PARENT", columns);
                low = Math.min(low, scratch.height);
                high = Math.max(high, scratch.height);
            }
        // Heights between lattice nodes lie within the shared margin of the sampled extremes.
        low -= sites.certificateMargin;
        high += sites.certificateMargin;
        PlateauKernels.Earlier earlier =
                new PlateauKernels.Earlier(
                        low, high, fit, aridity, erodibility, marine, water, shore);
        PlateauKernels.Plan plan =
                PlateauKernels.plan(family, seed, cx, cz, x, z, reference, tuning, earlier);
        return new Decision(plan, plan.reason.name(), low, high, columns);
    }

    private static Decision reject(String reason, int columns) {
        return new Decision(null, reason, 0, 0, columns);
    }

    private static double unit(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** Distance to protected water; the major envelope (TerrainMap) keeps the coast. */
    private double protectedDistance(double x, double z, TerrainMap.BaseSample base) {
        if (base.waterReserved()) return 0;
        return Math.max(0, waterClearance.surfaceClearance(x, z, protectionBlend));
    }

    public static final class Result implements StageResult {
        public double height, delta, weight, anchorX, anchorZ;
        public int candidates, contributors, certificateColumns;
        public PlateauKernels.Family dominant;
        public String reason;
        public long id;
        private final PlateauKernels.Result sample = new PlateauKernels.Result();

        public void clear(double parent) {
            height = parent;
            delta = weight = anchorX = anchorZ = 0;
            candidates = contributors = certificateColumns = 0;
            dominant = null;
            id = 0;
            reason = "NO_SITE";
        }
    }

    public void apply(double x, double z, TerrainMap.BaseSample base, double parent, Result out) {
        out.clear(parent);
        if (!Double.isFinite(parent)) {
            out.reason = "INVALID_PARENT";
            return;
        }
        double distance = protectedDistance(x, z, base);
        if (distance <= 0) {
            out.reason = "COLUMN_PROTECTED";
            return;
        }
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long owner = sites.key(Sites.Lane.PLATEAUS, cx, cz);
                double ax = sites.x(cx, owner), az = sites.z(cz, owner), px = x - ax, pz = z - az;
                if (px * px + pz * pz >= radius * radius
                        || !sites.inLane(owner, Sites.Lane.PLATEAUS)) continue;
                Decision decision = plan(cx, cz);
                out.candidates++;
                out.reason = decision.reason;
                out.certificateColumns += decision.certificateColumns;
                PlateauKernels.Plan plan = decision.plan;
                if (plan == null || plan.reason != PlateauKernels.Reason.ACCEPT) continue;
                if (Math.hypot(x - plan.x, z - plan.z) >= plan.radius) {
                    out.reason = "OUTSIDE_SUPPORT";
                    continue;
                }
                if (parent < decision.parentMin || parent > decision.parentMax) {
                    out.reason = "UNCERTIFIED_PARENT";
                    continue;
                }
                PlateauKernels.sample(plan, x, z, parent, distance, protectionBlend, out.sample);
                out.reason = out.sample.reason.name();
                if (out.sample.weight <= 0) continue;
                out.contributors++;
                // The kernel already applies both its support envelope and reservation fade.
                if (out.sample.weight > out.weight) {
                    out.weight = out.sample.weight;
                    out.delta = out.sample.delta;
                    out.dominant = plan.family;
                    out.id = plan.id();
                    out.anchorX = plan.x;
                    out.anchorZ = plan.z;
                }
            }
        out.height = out.delta == 0 ? parent : parent + out.delta;
    }
}
