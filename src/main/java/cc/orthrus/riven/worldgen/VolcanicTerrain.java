package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.volcanic.VolcanicKernels;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Shared-owner volcanic geometry with exhaustive earlier native-column certificates. */
public final class VolcanicTerrain {
    private static final Rules RULES = Rules.load("riven-volcanic.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final VolcanicKernels.Family[] FAMILIES = {
        VolcanicKernels.Family.CONE,
        VolcanicKernels.Family.CALDERA,
        VolcanicKernels.Family.FISSURE,
        VolcanicKernels.Family.LOBE_PLATEAU
    };
    private final long seed;
    private final MajorFeatureParent parent;
    private final BoundedWaterClearance water;
    private final Sites sites;
    private final VolcanicKernels.Reference reference;
    private final VolcanicKernels.Tuning tuning;

    /** Multiplies each family's parent-slope limit, tuned for flatter land than Riven's hot hills. */
    private final double slopeTolerance = Rules.number(RULES.json, "parentSlopeTolerance", 1, 4);

    private final SeededNoise opportunity;
    private final double[] weights = new double[FAMILIES.length];
    private final double radius, potentialSpan, compressionWeight;
    private final TemperatureBand supportBand;
    private final double fadeZero, fadeFull;
    private final int searchRadius, sets, ways, maximumColumns;
    private final AtomicReferenceArray<Entry> memo;
    private final CellMemo<Boolean> anchors = new CellMemo<>(4096);
    private final AtomicIntegerArray next;

    VolcanicTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            MajorFeatureParent parent,
            BoundedWaterClearance water,
            int waterCellBlocks) {
        this(worldSeed, heights, sites, parent, water, waterCellBlocks, PlateMap.CELL_BLOCKS);
    }

    /** Volcanic provinces span plate widths of this world's (layout-scaled) plates. */
    VolcanicTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            MajorFeatureParent parent,
            BoundedWaterClearance water,
            int waterCellBlocks,
            double plateBlocks) {
        this.parent = parent;
        this.water = water;
        this.sites = sites;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature cone = heights.feature("volcanic_cone"),
                crater = heights.feature("crater"),
                spur = heights.feature("ridge_spur"),
                mesa = heights.feature("mesa_butte"),
                plateau = heights.feature("plateau");
        double horizontal = TerrainScale.HORIZONTAL;
        reference =
                new VolcanicKernels.Reference(
                        range(cone.magnitude, 1),
                        range(cone.width, horizontal),
                        range(crater.magnitude, 1),
                        range(crater.width, horizontal),
                        range(spur.magnitude, 1),
                        range(spur.width, horizontal),
                        range(mesa.magnitude, 1),
                        range(plateau.width, horizontal),
                        heights.lowestSurface,
                        heights.highestSurface,
                        heights.fingerprint);
        tuning =
                new VolcanicKernels.Tuning(
                        Rules.number(j, "size", .5, 1.3),
                        Rules.number(j, "relief", .5, 1.5),
                        Rules.number(j, "crater", .5, 1.5),
                        Rules.number(j, "irregularity", 0, 1),
                        Rules.number(j, "occurrence", 0, 1),
                        Rules.number(j, "minimumPotential", 0, 1));
        radius = sites.maximumRadius(Sites.Lane.VOLCANIC);
        searchRadius = sites.searchRadius(Sites.Lane.VOLCANIC);
        for (VolcanicKernels.Family family : FAMILIES)
            if (VolcanicKernels.maximumColumnRadius(family, reference, tuning) > radius)
                throw new IllegalStateException("Volcanic support exceeds its shared owner radius");
        if (radius + 2 > waterCellBlocks)
            throw new IllegalStateException(
                    "Volcanic support must fit the finite water-clearance query");
        supportBand = TemperatureBand.valueOf(j.get("supportTemperatureBand").getAsString());
        fadeFull = Rules.number(j, "climateFadeFullCelsius", -40, 60);
        fadeZero = Rules.number(j, "climateFadeZeroCelsius", -40, fadeFull - .5);
        potentialSpan = plateBlocks * Rules.number(j, "potentialInPlateWidths", .25, 4);
        compressionWeight = Rules.number(j, "compressionWeight", 0, 1);
        opportunity = new SeededNoise(SeededNoise.mix(seed ^ 0x564f4c504f54454eL));
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        maximumColumns = integer(j, "maximumCertificateColumns", 1024, 2000000);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Volcanic cache sets must be a power of two");
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
    }

    private static VolcanicKernels.Range range(TerrainHeightReference.Range r, double scale) {
        return new VolcanicKernels.Range(r.min * scale, r.targetAverage * scale, r.max * scale);
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

    private static final class Bounds {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, slope, potential = 1;
        int columns, stencils;
    }

    public static final class Decision {
        public final VolcanicKernels.ColumnPlan plan;
        public final String reason;
        public final double minParent, maxParent, maxColumnSlope, minPotential;
        public final int columns, stencils;

        private Decision(VolcanicKernels.ColumnPlan plan, String reason, Bounds b) {
            this.plan = plan;
            this.reason = reason;
            minParent = Double.isFinite(b.min) ? b.min : 0;
            maxParent = Double.isFinite(b.max) ? b.max : 0;
            maxColumnSlope = b.slope;
            minPotential = b.potential;
            columns = b.columns;
            stencils = b.stencils;
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

    /**
     * Anchor-local conditions that build() already requires (family, proposal, budget, water
     * clearance and the certified anchor column), from earlier facts only.
     */
    private boolean eligible(int cx, int cz, long owner) {
        return anchors.get(
                cx,
                cz,
                () -> {
                    int selected = Rules.choose(weights, Sites.random(owner, 0x5646414d));
                    if (selected < 0) return false;
                    double x = sites.x(cx, owner), z = sites.z(cz, owner);
                    VolcanicKernels.ColumnProposal p =
                            VolcanicKernels.proposeColumnsAt(
                                    seed, cx, cz, FAMILIES[selected], x, z, reference, tuning);
                    if (VolcanicKernels.columnPrerequisite(p) != VolcanicKernels.Reason.ACCEPTED
                            || p.radius > radius
                            || water.surfaceClearance(x, z, p.radius + 2) < p.radius + 2)
                        return false;
                    TerrainMap.Sample s = new TerrainMap.Sample();
                    parent.sample(Math.rint(x), Math.rint(z), s);
                    return Double.isFinite(s.height)
                            && !s.ocean.marine
                            && !s.water.wet
                            && s.water.weight <= 0
                            && s.climate.temperatureBand == supportBand
                            && potential(x, z, s) >= tuning.minimumPotential;
                });
    }

    private Decision build(int cx, int cz) {
        Bounds bounds = new Bounds();
        long owner = sites.key(Sites.Lane.VOLCANIC, cx, cz);
        // An anchor that could not build here never competes, and is reported as such.
        if (sites.inLane(owner, Sites.Lane.VOLCANIC) && !eligible(cx, cz, owner))
            return reject("NOT_ELIGIBLE", bounds);
        if (!sites.admits(cx, cz, owner, Sites.Lane.VOLCANIC, this::eligible))
            return reject("SHARED_BUDGET", bounds);
        int selected = Rules.choose(weights, Sites.random(owner, 0x5646414d));
        if (selected < 0) return reject("NOT_SELECTED", bounds);
        double x = sites.x(cx, owner), z = sites.z(cz, owner);
        VolcanicKernels.ColumnProposal p =
                VolcanicKernels.proposeColumnsAt(
                        seed, cx, cz, FAMILIES[selected], x, z, reference, tuning);
        VolcanicKernels.Reason first = VolcanicKernels.columnPrerequisite(p);
        if (first != VolcanicKernels.Reason.ACCEPTED) return reject(first.name(), bounds);
        double halo = p.radius + 2;
        if (p.radius > radius) return reject("SUPPORT_BUDGET", bounds);
        if (Math.abs(x) + halo > 29999984 || Math.abs(z) + halo > 29999984)
            return reject("WORLD_BOUND", bounds);
        if (water.surfaceClearance(x, z, halo) < halo) return reject("WATER_SUPPORT", bounds);
        int minX = (int) Math.floor(x - halo),
                maxX = (int) Math.ceil(x + halo),
                minZ = (int) Math.floor(z - halo),
                maxZ = (int) Math.ceil(z + halo);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > maximumColumns)
            return reject("WORK_BOUND", bounds);
        // The shared certificate lattice: nodes every step blocks, slopes per block between nodes.
        int step = sites.certificateStep,
                startX = sites.firstNode(minX),
                startZ = sites.firstNode(minZ),
                width = startX > maxX ? 0 : (maxX - startX) / step + 1;
        double margin = sites.certificateMargin;
        // Three temporary rows suffice for every native column's four axis neighbors.
        // Nothing from this height raster is retained by the published decision.
        double[][] rows = new double[3][width];
        TerrainMap.Sample scratch = new TerrainMap.Sample();
        // Volcanoes need a hot anchor; cooler support columns fade the cone in apply (v55).
        parent.sample(Math.rint(x), Math.rint(z), scratch);
        if (scratch.climate.temperatureBand != supportBand)
            return reject("CLIMATE_SUPPORT", bounds);
        for (int pz = startZ; pz <= maxZ; pz += step) {
            int rowIndex = (pz - startZ) / step;
            double[] row = rows[rowIndex % 3];
            Arrays.fill(row, Double.NaN);
            for (int px = startX; px <= maxX; px += step) {
                double dx = px - x, dz = pz - z;
                if (dx * dx + dz * dz > halo * halo) continue;
                parent.sample(px, pz, scratch);
                bounds.columns++;
                if (!Double.isFinite(scratch.height)) return reject("INVALID_PARENT", bounds);
                // Marine columns never receive the cone; they leave the certificate and stencil.
                if (scratch.ocean.marine) continue;
                if (scratch.water.wet || scratch.water.weight > 0)
                    return reject("PROTECTED_SUPPORT", bounds);
                bounds.min = Math.min(bounds.min, scratch.height);
                bounds.max = Math.max(bounds.max, scratch.height);
                bounds.potential = Math.min(bounds.potential, potential(px, pz, scratch));
                if (bounds.potential < tuning.minimumPotential)
                    return reject("LOW_POTENTIAL_SUPPORT", bounds);
                if (scratch.height + p.rise + margin > reference.highestSurface)
                    return reject("TOP_HEADROOM_SUPPORT", bounds);
                if (scratch.height - p.cut - margin < reference.lowestSurface)
                    return reject("BOTTOM_HEADROOM_SUPPORT", bounds);
                row[(px - startX) / step] = scratch.height;
            }
            if (rowIndex < 2) continue;
            int centerZ = pz - step;
            double[] middle = rows[(rowIndex - 1) % 3], previous = rows[(rowIndex - 2) % 3];
            for (int i = 1; i < width - 1; i++) {
                double dx = startX + i * step - x, dz = centerZ - z;
                if (dx * dx + dz * dz > p.radius * p.radius) continue;
                if (Double.isNaN(middle[i])) continue;
                double
                        sx =
                                Math.max(
                                        step(middle[i], middle[i - 1]),
                                        step(middle[i + 1], middle[i])),
                        sz = Math.max(step(middle[i], previous[i]), step(row[i], middle[i]));
                double slope = Math.hypot(sx, sz) / step;
                if (!Double.isFinite(slope)) return reject("UNKNOWN_STENCIL", bounds);
                bounds.slope = Math.max(bounds.slope, slope);
                bounds.stencils++;
                if (bounds.slope > p.family.maxParentSlope * slopeTolerance)
                    return reject("COLUMN_SLOPE", bounds);
            }
        }
        if (bounds.stencils == 0) return reject("UNKNOWN_SUPPORT", bounds);
        // Columns between nodes lie within the shared margin; apply checks the widened bounds.
        bounds.min -= margin;
        bounds.max += margin;
        VolcanicKernels.ColumnCertificate certificate =
                new VolcanicKernels.ColumnCertificate(
                        p,
                        false,
                        false,
                        false,
                        bounds.min,
                        bounds.max,
                        bounds.slope,
                        bounds.potential,
                        bounds.columns);
        VolcanicKernels.ColumnDecision decision = VolcanicKernels.selectColumns(p, certificate);
        return new Decision(decision.plan, decision.reason.name(), bounds);
    }

    /** Full at climateFadeFullCelsius and above, zero at climateFadeZeroCelsius and below. */
    private double climateFade(TerrainMap.BaseSample base) {
        return Rules.smooth((base.climate.celsius - fadeZero) / (fadeFull - fadeZero));
    }

    /** Height step to a certified neighbour; a skipped marine neighbour contributes nothing. */
    private static double step(double a, double b) {
        return Double.isNaN(a) || Double.isNaN(b) ? 0 : Math.abs(a - b);
    }

    private static Decision reject(String reason, Bounds bounds) {
        return new Decision(null, reason, bounds);
    }

    private double potential(double x, double z, TerrainMap.BaseSample base) {
        // Authored regional opportunity plus earlier smooth compression intent.
        // This is a selector, not evidence of magma, geology or a physical heat source.
        double regional = .5 + .5 * opportunity.sample(x / potentialSpan, z / potentialSpan);
        return Math.max(
                0,
                Math.min(1, regional * (1 - compressionWeight) + base.uplift * compressionWeight));
    }

    public static final class Result implements StageResult {
        public double height, delta, weight, anchorX, anchorZ, minPotential, maxColumnSlope;
        public int candidates, contributors, certificateColumns, stencilColumns;
        public long id;
        public VolcanicKernels.Family dominant;
        public String reason;

        public void clear(double parent) {
            height = parent;
            delta = weight = anchorX = anchorZ = minPotential = maxColumnSlope = 0;
            candidates = contributors = certificateColumns = stencilColumns = 0;
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
        if (base.ocean.marine || base.water.wet || base.water.weight > 0) {
            out.reason = "COLUMN_PROTECTED";
            return;
        }
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        for (int dz = -searchRadius; dz <= searchRadius; dz++)
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long owner = sites.key(Sites.Lane.VOLCANIC, cx, cz);
                double ax = sites.x(cx, owner), az = sites.z(cz, owner), px = x - ax, pz = z - az;
                if (px * px + pz * pz >= radius * radius
                        || !sites.inLane(owner, Sites.Lane.VOLCANIC)) continue;
                Decision decision = plan(cx, cz);
                out.candidates++;
                out.reason = decision.reason;
                out.certificateColumns += decision.columns;
                out.stencilColumns += decision.stencils;
                out.minPotential = decision.minPotential;
                out.maxColumnSlope = decision.maxColumnSlope;
                VolcanicKernels.ColumnPlan plan = decision.plan;
                if (plan == null || !plan.contains((int) x, (int) z)) continue;
                VolcanicKernels.ColumnProposal p = plan.proposal;
                if (parent < decision.minParent
                        || parent > decision.maxParent
                        || parent + p.rise > reference.highestSurface
                        || parent - p.cut < reference.lowestSurface) {
                    out.reason = "UNCERTIFIED_PARENT";
                    continue;
                }
                double applied = plan.delta((int) x, (int) z) * climateFade(base);
                if (applied == 0) continue;
                out.contributors++;
                if (Math.abs(applied) > Math.abs(out.delta)
                        || (Math.abs(applied) == Math.abs(out.delta)
                                && Long.compareUnsigned(p.seed, out.id) < 0)) {
                    out.delta = applied;
                    out.weight = Math.abs(applied) / (applied >= 0 ? p.rise : p.cut);
                    out.dominant = p.family;
                    out.id = p.seed;
                    out.anchorX = p.x;
                    out.anchorZ = p.z;
                    out.reason = "ACCEPTED";
                }
            }
        out.height = out.delta == 0 ? parent : parent + out.delta;
    }
}
