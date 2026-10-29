package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.ocean.OceanFloors;

import com.google.gson.JsonObject;

/** Adapts the isolated MIT geometry kernels to earlier Riven marine intent. */
public final class OceanBed {
    private static final Rules RULES = Rules.load("riven-ocean-floors.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private final OceanFloors.ShelfSlope shelf;
    private final OceanFloors.RidgeChain ridge;
    private final OceanFloors.TrenchCorridor trench;
    private final OceanFloors.BankComplex bank;
    private final double transitionWidth, completeDistance, bankStart, bankWidth;

    /** Shallow ramp reach on reflective (0) and dissipative (1) coasts, and its floor depth. */
    private final double shoalReachMin, shoalReachMax, shoalDepth;

    public final int minimumSurfaceY;
    private final int seaWaterTop;

    public OceanBed(long seed, TerrainHeightReference heights, ContinentalMap continents) {
        seaWaterTop = heights.waterTopY;
        OceanFloors.Reference reference =
                new OceanFloors.Reference(
                        heights.seaLevel,
                        heights.waterTopY,
                        heights.lowestSurface,
                        feature(heights, "continental_shelf"),
                        feature(heights, "continental_slope"),
                        feature(heights, "deep_ocean"),
                        feature(heights, "ocean_trench"),
                        feature(heights, "shoal"));
        JsonObject j = RULES.json;
        OceanFloors.Tuning tuning =
                new OceanFloors.Tuning(
                        Rules.number(j, "shelfScale", .8, 1.5),
                        Rules.number(j, "slopeScale", .75, 1.5),
                        Rules.number(j, "detail", 0, 1),
                        Rules.number(j, "ridgeChance", 0, 1),
                        Rules.number(j, "trenchChance", 0, 1),
                        Rules.number(j, "bankChance", 0, 1),
                        Rules.number(j, "relief", .25, 1));
        long variation = seed ^ Rules.seed(j, "variationSeed");
        shelf = new OceanFloors.ShelfSlope(variation, reference, tuning);
        ridge = new OceanFloors.RidgeChain(variation, reference, tuning);
        trench = new OceanFloors.TrenchCorridor(variation, reference, tuning);
        bank = new OceanFloors.BankComplex(variation, reference, tuning);
        transitionWidth = continents.oceanTransitionWidth;
        // All distance gates must finish inside the existing proven deep-ocean band.
        // Local gradient-normalized distance is discontinuous at face/owner changes.
        completeDistance =
                Math.max(
                        1.15
                                * (reference.shelf.width * tuning.shelfScale
                                        + reference.slope.width * tuning.slopeScale),
                        reference.shelf.width + 2 * reference.slope.width);
        bankStart = reference.shelf.width;
        JsonObject shoaling = j.getAsJsonObject("shoaling");
        double shoalWidth = heights.feature("shoal").width.targetAverage;
        shoalReachMin = shoalWidth * Rules.number(shoaling, "minimumReachInShoalWidths", 0, 4);
        shoalReachMax = shoalWidth * Rules.number(shoaling, "maximumReachInShoalWidths", 0, 4);
        if (shoalReachMax < shoalReachMin) throw new IllegalStateException("Shoal reach order");
        shoalDepth = -heights.feature("shoal").elevationOffset.min;
        bankWidth = reference.slope.width;
        minimumSurfaceY =
                (int)
                        Math.floor(
                                Math.min(
                                        reference.trench.lo,
                                        Math.min(
                                                reference.deep.lo,
                                                Math.min(reference.slope.lo, reference.shelf.lo))));
    }

    private static OceanFloors.Feature feature(TerrainHeightReference heights, String id) {
        TerrainHeightReference.Feature f = heights.feature(id);
        return new OceanFloors.Feature(
                heights.seaLevel + f.elevationOffset.min,
                heights.seaLevel + f.elevationOffset.targetAverage,
                heights.seaLevel + f.elevationOffset.max,
                f.width.min,
                f.width.targetAverage,
                f.width.max,
                f.relief.targetAverage);
    }

    /** Caller-owned outputs; immutable kernel inputs are short-lived per marine query. */
    public static final class Result implements StageResult {
        public final OceanFloors.Output shelf = new OceanFloors.Output(),
                ridge = new OceanFloors.Output(),
                trench = new OceanFloors.Output(),
                bank = new OceanFloors.Output();
        public double height, distance;

        public void clear(double parent) {
            height = parent;
            distance = 0;
            clear(shelf, parent);
            clear(ridge, parent);
            clear(trench, parent);
            clear(bank, parent);
        }

        private static void clear(OceanFloors.Output out, double parent) {
            out.bedY = parent;
            out.weight = 0;
            out.siteId = 0;
            out.reason = OceanFloors.Reason.NON_MARINE;
        }
    }

    public void apply(
            double x, double z, ContinentalMap.Sample ocean, boolean reserved, Result out) {
        double progress = Math.max(0, Math.min(1, -ocean.coastCoordinate / transitionWidth));
        out.distance = completeDistance * progress;
        // Quintic roundoff near one can make the complement slightly negative.
        // Keep the strict contribution input contract without accepting nonfinite facts.
        double bankAffinity =
                Math.max(0, Math.min(1, 1 - Rules.smooth((out.distance - bankStart) / bankWidth)));
        OceanFloors.Input input =
                new OceanFloors.Input(
                        ocean.marine, reserved, out.distance, ocean.bedHeight, 1, 1, bankAffinity);
        shelf.sample(x, z, input, out.shelf);
        // All detail families read the same frozen shelf parent, never one another.
        OceanFloors.Input parent = input.withParent(out.shelf.bedY);
        ridge.sample(x, z, parent, out.ridge);
        trench.sample(x, z, parent, out.trench);
        bank.sample(x, z, parent, out.bank);
        double raised = Math.max(out.ridge.bedY - parent.parentY, out.bank.bedY - parent.parentY);
        out.height =
                Math.min(
                        parent.parentY
                                + raised * (1 - out.trench.weight)
                                + (out.trench.bedY - parent.parentY),
                        seaWaterTop - 1);
        out.height = shoal(ocean, out.height);
    }

    /**
     * The nearshore follows the coast's shoaling trait: the seabed rises toward a shallow ramp
     * from the shore to the shoal floor, over a reach from the reflective to the dissipative
     * width. The ramp is blended out by the reach, so the bed meets the shelf without a step,
     * and it only raises the bed.
     */
    private double shoal(ContinentalMap.Sample ocean, double bed) {
        double d = -ocean.distance,
                reach = shoalReachMin + (shoalReachMax - shoalReachMin) * ocean.shoaling;
        if (!(d > 0 && d < reach)) return bed;
        double ramp = seaWaterTop - 1 - (shoalDepth - 1) * d / reach;
        return bed + Math.max(0, ramp - bed) * (1 - Rules.smooth(d / reach));
    }
}
