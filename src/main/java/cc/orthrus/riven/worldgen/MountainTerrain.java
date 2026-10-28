package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.mountains.MountainKernels;

import com.google.gson.JsonObject;

/**
 * Mountain ranges along continent crests (mountains-v4). Each continent crest carries a chain of
 * MIT mountain plans, evenly spaced along it and aligned with its tangent; a column takes the
 * largest raise of the chain nodes within reach. The kernel's per-column fades (intent, uplift,
 * parent band, headroom, water preservation) keep every range continuous, so there is no site
 * lottery and no all-or-nothing footprint certificate.
 */
public final class MountainTerrain {
    private static final Rules RULES = Rules.load("riven-mountains.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final MountainKernels.Family[] FAMILIES = {
        MountainKernels.Family.RIDGE_TREE,
        MountainKernels.Family.FOLDED_BELTS,
        MountainKernels.Family.SUMMIT_GRAPH,
        MountainKernels.Family.WARPED_RIDGES
    };
    private final long seed;
    private final ContinentalOutlineMap outline;
    private final IntentSampler earlierIntent;
    private final BoundedWaterClearance waterClearance;
    private final MountainKernels.Reference reference;
    private final MountainKernels.Tuning tuning;
    private final double[] weights = new double[FAMILIES.length];
    private final double halfLength, spacing, waterSkirt;
    private final double massifRelief, massifWavelength, massifWarp, massifScale, massifSkirt;
    private final SeededNoise[] massif = new SeededNoise[4];
    private final CellMemo<Decision> plans = new CellMemo<>(4096);

    MountainTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            ContinentalOutlineMap outline,
            IntentSampler earlierIntent,
            BoundedWaterClearance waterClearance,
            int waterCellBlocks) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        this.outline = outline;
        this.earlierIntent = earlierIntent;
        this.waterClearance = waterClearance;
        TerrainHeightReference.Feature mountain = heights.feature("mountains"),
                spur = heights.feature("ridge_spur");
        reference =
                new MountainKernels.Reference(
                        heights.fingerprint,
                        heights.seaLevel + mountain.elevationOffset.min,
                        heights.seaLevel + mountain.elevationOffset.targetAverage,
                        heights.seaLevel + mountain.elevationOffset.max,
                        mountain.elevationFeather,
                        mountain.relief.min,
                        mountain.relief.targetAverage,
                        mountain.relief.max,
                        mountain.width.min,
                        mountain.width.targetAverage,
                        mountain.width.max,
                        spur.width.targetAverage,
                        spur.magnitude.targetAverage,
                        heights.lowestSurface,
                        heights.highestSurface,
                        TerrainScale.HORIZONTAL);
        JsonObject j = RULES.json;
        tuning =
                new MountainKernels.Tuning(
                        reference,
                        Rules.number(j, "widthMultiplier", .1, 4),
                        Rules.number(j, "aspect", 1.3, 3),
                        reference.reliefTarget * Rules.number(j, "reliefTargetMultiplier", .1, 4),
                        Rules.number(j, "sharpness", .7, 2),
                        Rules.number(j, "spurs", 0, 1),
                        Rules.number(j, "warp", 0, .25),
                        Rules.number(j, "passChance", 0, 1),
                        Rules.number(j, "occurrence", 0, 1),
                        Rules.number(j, "minMountainIntent", .001, .999),
                        Rules.number(j, "minUplift", .001, .999));
        halfLength =
                reference.widthTarget
                        * tuning.widthMultiplier
                        * reference.horizontalScale
                        / 2
                        * tuning.aspect;
        spacing = halfLength * Rules.number(j, "nodeSpacingInHalfLengths", .5, 2);
        waterSkirt = heights.feature("river_bed").width.targetAverage;
        if (!Double.isFinite(waterSkirt) || waterSkirt <= 0 || waterSkirt > waterCellBlocks)
            throw new IllegalStateException(
                    "Mountain preservation skirt must be finite and fit the hydrology owner width");
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] =
                    Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 10);
        JsonObject m = j.getAsJsonObject("massif");
        massifRelief = Rules.number(m, "reliefBlocks", 0, 160);
        massifWavelength = Rules.number(m, "wavelengthBlocks", 64, 4096);
        massifWarp = Rules.number(m, "warpBlocks", 0, 1024);
        massifScale = Rules.number(m, "crestScale", .1, 8);
        massifSkirt = Math.max(waterSkirt, Rules.number(m, "waterSkirtBlocks", 1, 512));
        for (int i = 0; i < massif.length; i++)
            massif[i] =
                    new SeededNoise(
                            SeededNoise.mix(seed ^ (0x4d415353L + i * 0x9e3779b97f4a7c15L)));
    }

    /**
     * Massif relief over a whole crest band (mountains-v5): four octaves of ridged noise, (1 -
     * |n|)^2 at the wavelength halving per octave with amplitude .5, .25, .15, .1, on coordinates
     * warped by a smooth field of up to warpBlocks so ridges wander instead of following the noise
     * lattice. Masked by 1 - exp(-crestScale * crest), so it fades to nothing off the crest. It is
     * isotropic on purpose: orienting ridges by the crest gradient flips across the crest line and
     * where the strongest crest switches, which made the surface discontinuous.
     */
    private double massif(double x, double z, double crest) {
        if (crest <= 0) return 0;
        double wx =
                x
                        + massifWarp
                                * massif[0].sample(
                                        x / (2 * massifWavelength) + .37,
                                        z / (2 * massifWavelength));
        double wz =
                z
                        + massifWarp
                                * massif[1].sample(
                                        x / (2 * massifWavelength),
                                        z / (2 * massifWavelength) + .61);
        double sum = 0, amplitude = .5, wavelength = massifWavelength;
        for (int o = 0; o < massif.length; o++) {
            double n = massif[o].sample(wx / wavelength + .19 * o, wz / wavelength - .23 * o);
            double ridge = 1 - Math.abs(n);
            sum += amplitude * ridge * ridge;
            amplitude = o == 0 ? .25 : o == 1 ? .15 : .1;
            wavelength *= .5;
        }
        return massifRelief * (1 - Math.exp(-massifScale * crest)) * sum;
    }

    public static final class Decision {
        public final MountainKernels.Plan plan;
        public final String reason;

        private Decision(MountainKernels.Plan plan, String reason) {
            this.plan = plan;
            this.reason = reason;
        }
    }

    /**
     * One crest node's plan from earlier intent at the node only. The node index and continent
     * cell fix its seed, family and direction, so every column reads the same plan.
     */
    private Decision nodePlan(int cellX, int cellZ, int node, double x, double z, double angle) {
        TerrainMap.BaseSample anchor = new TerrainMap.BaseSample();
        earlierIntent.sample(x, z, anchor);
        if (!legalParent(anchor, anchor.baseHeight)) return new Decision(null, "INVALID_PARENT");
        double clearance = waterClearance.surfaceClearance(x, z, waterSkirt);
        MountainKernels.Intent earlier = intent(anchor, anchor.baseHeight, angle, clearance);
        long ownerX = (long) cellX * 64 + node;
        // The family draw is independent of the plan's own draws.
        int family =
                Rules.choose(
                        weights,
                        Sites.random(
                                SeededNoise.mix(seed ^ ownerX ^ ((long) cellZ << 32)), 0x4d46414d));
        if (family < 0) return new Decision(null, "NOT_SELECTED");
        MountainKernels.Plan plan =
                MountainKernels.plan(
                        FAMILIES[family], seed, ownerX, cellZ, x, z, reference, tuning, earlier);
        return new Decision(plan, plan.reason.name());
    }

    private static boolean legalParent(TerrainMap.BaseSample base, double parent) {
        return Double.isFinite(parent)
                && Double.isFinite(base.minimumY)
                && Double.isFinite(base.maximumY)
                && parent >= base.minimumY
                && parent <= base.maximumY;
    }

    private MountainKernels.Intent intent(
            TerrainMap.BaseSample base, double parent, double direction, double clearance) {
        return new MountainKernels.Intent(
                base.ocean.marine,
                base.water.wet || base.water.weight > 0 || clearance <= 0,
                // The major envelope (TerrainMap) keeps the coast; shores are not excluded.
                false,
                Math.max(0, Math.min(1, base.land.high)),
                Math.max(0, Math.min(1, base.uplift)),
                parent,
                base.minimumY,
                base.maximumY,
                direction);
    }

    public static final class Result {
        public double height, delta, weight, shape, ridge, pass, anchorX, anchorZ, massif;
        public int candidates, contributors;
        public MountainKernels.Family dominant;
        public long id;
        public String reason;
        private final MountainKernels.Sample sample = new MountainKernels.Sample();
        private final ContinentalOutlineMap.CrestScratch crest =
                new ContinentalOutlineMap.CrestScratch();

        public void clear(double parent) {
            height = parent;
            delta = weight = shape = ridge = pass = anchorX = anchorZ = massif = 0;
            candidates = contributors = 0;
            dominant = null;
            id = 0;
            reason = "NO_CREST";
        }
    }

    public void apply(double x, double z, TerrainMap.BaseSample base, double parent, Result out) {
        out.clear(parent);
        if (!legalParent(base, parent)) {
            out.reason = "INVALID_PARENT";
            return;
        }
        // One clearance read serves both fades: the kernels' over waterSkirt is unchanged wherever
        // clearance exceeds it, and the massif fades over the wider massifSkirt so its relief
        // eases down to rivers instead of standing as a gorge wall.
        double clearance = waterClearance.surfaceClearance(x, z, massifSkirt);
        double preservation = 1 - Rules.smooth(clearance / waterSkirt);
        double massifKeep = Rules.smooth(clearance / massifSkirt);
        // Massif relief covers the crest band under the mountain ceiling and keeps water; kernel
        // peaks below combine with it by maximum, never adding peak budgets.
        BaseElevationMap.Sample e = base.base.elevation();
        double room = Math.max(0, reference.maxY - parent);
        double massifDelta =
                base.ocean.marine ? 0 : Math.min(room, massif(x, z, e.crest())) * massifKeep;
        out.massif = massifDelta;
        if (massifDelta > 0) {
            out.delta = massifDelta;
            out.weight = 1;
            out.reason = "MASSIF";
        }
        out.height = parent + out.delta;
        MountainKernels.Intent local = intent(base, parent, 0, Math.min(clearance, waterSkirt));
        MountainKernels.Reason why = MountainKernels.admit(reference, tuning, local);
        if (why != MountainKernels.Reason.ACCEPTED) {
            if (massifDelta <= 0) out.reason = why.name();
            return;
        }
        int count = outline.crestNodes(x, z, spacing, halfLength, out.crest);
        double[] nodes = out.crest.nodes;
        for (int i = 0; i < count; i++) {
            int cellX = (int) nodes[6 * i], cellZ = (int) nodes[6 * i + 1];
            int node = (int) nodes[6 * i + 2];
            double ax = nodes[6 * i + 3], az = nodes[6 * i + 4], angle = nodes[6 * i + 5];
            Decision decision =
                    plans.get(
                            cellX * 64 + node,
                            cellZ,
                            () -> nodePlan(cellX, cellZ, node, ax, az, angle));
            out.candidates++;
            out.reason = decision.reason;
            if (decision.plan == null) continue;
            MountainKernels.sample(decision.plan, x, z, local, preservation, out.sample);
            out.reason = out.sample.reason.name();
            if (out.sample.delta <= 0) continue;
            out.contributors++;
            if (out.sample.delta > out.delta) {
                out.delta = out.sample.delta;
                out.weight = out.sample.weight;
                out.shape = out.sample.shape;
                out.ridge = out.sample.ridge;
                out.pass = out.sample.pass;
                out.dominant = decision.plan.family;
                out.id = decision.plan.id;
                out.anchorX = decision.plan.anchorX;
                out.anchorZ = decision.plan.anchorZ;
            }
        }
        // Alternate ranges read the same earlier parent; use the maximum, never add peak budgets.
        out.height = parent + out.delta;
    }
}
