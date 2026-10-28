package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.lowland.Lowlands;

import java.util.Arrays;

/** Four earlier-fact lowland fields, composed before regional passes and major features. */
public final class LowlandTerrain {
    private static final Rules RULES = Rules.load("riven-lowlands.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final Lowlands.Family[] FAMILIES = {
        Lowlands.Family.WARPED_PLAIN,
        Lowlands.Family.COMPACT_PATCHES,
        Lowlands.Family.CELLULAR_HUMMOCKS,
        Lowlands.Family.LOW_TERRACES
    };
    private final long seed;
    private final Lowlands.Reference reference;
    private final Lowlands.Tuning tuning;
    private final double shoreReservation;

    LowlandTerrain(long worldSeed, TerrainHeightReference heights) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        TerrainHeightReference.Feature plain = heights.feature("inland_plain"),
                terrace = heights.feature("terrace");
        reference =
                new Lowlands.Reference(
                        heights.seaLevel,
                        plain.elevationOffset.min,
                        plain.elevationOffset.max,
                        plain.elevationFeather,
                        plain.relief.max,
                        plain.width.targetAverage,
                        heights.feature("knoll").width.targetAverage,
                        heights.feature("hollow_basin").width.targetAverage,
                        terrace.width.targetAverage,
                        terrace.magnitude.targetAverage,
                        TerrainScale.HORIZONTAL);
        double start = Rules.number(RULES.json, "slopeStart", 0, .24);
        tuning =
                new Lowlands.Tuning(
                        reference,
                        Rules.number(RULES.json, "reliefFraction", .001, .5),
                        Rules.number(RULES.json, "broadInPlainWidths", .25, 2),
                        Rules.number(RULES.json, "patchInHollowWidths", .5, 2),
                        Rules.number(RULES.json, "cellInKnollWidths", .5, 2),
                        Rules.number(RULES.json, "warpFraction", 0, .3),
                        Rules.number(RULES.json, "patchDensity", 0, 1),
                        start,
                        Rules.number(RULES.json, "slopeEnd", start + .001, .25));
        shoreReservation = Terrain.shoreReservation(heights);
    }

    /** Caller-owned scratch; each family sees exactly the same unchanged earlier parent. */
    public static final class Result {
        public double height, delta, weight, shoreClearance, waterClearance;
        public int candidateVisits;
        public Lowlands.Reason reason;
        public final double[] weights = new double[FAMILIES.length];
        private final Lowlands.Sample sample = new Lowlands.Sample();

        public void clear(double parent) {
            height = parent;
            delta = weight = shoreClearance = waterClearance = 0;
            candidateVisits = 0;
            reason = Lowlands.Reason.NOT_SELECTED;
            Arrays.fill(weights, 0);
        }
    }

    public void apply(
            double x,
            double z,
            TerrainMap.BaseSample base,
            Hydro hydro,
            double parent,
            Result out) {
        out.clear(parent);
        if (base.ocean.marine) {
            out.reason = Lowlands.Reason.MARINE;
            return;
        }
        double fit = base.land.low * (1 - base.land.dunes),
                slope = Math.hypot(base.baseDx, base.baseDz);
        if (fit <= 0) {
            out.reason = Lowlands.Reason.NOT_LOWLAND;
            return;
        }
        if (slope >= tuning.slopeEnd) {
            out.reason = Lowlands.Reason.STEEP_PARENT;
            return;
        }
        // Preserve complete existing coastal bands; the distance feather is the earlier
        // coast model's local estimate, not an asserted global distance transform.
        if (base.shore.kind != Shore.Kind.INLAND || base.shore.landWeight < 1) {
            out.reason = Lowlands.Reason.SHORE_RESERVED;
            return;
        }
        out.shoreClearance =
                Math.min(reference.plainWidth, Math.max(0, base.shore.distance - shoreReservation));
        if (out.shoreClearance <= 0) {
            out.reason = Lowlands.Reason.SHORE_RESERVED;
            return;
        }
        out.waterClearance = hydro.surfaceClearance(x, z);
        if (out.waterClearance <= 0) {
            out.reason = Lowlands.Reason.WATER_RESERVED;
            return;
        }
        // One immutable input per eligible column. Fixed heading keeps the cellular
        // frame continuous across chunks and across changes of dominant landform.
        Lowlands.Earlier earlier =
                new Lowlands.Earlier(
                        parent,
                        fit,
                        slope,
                        false,
                        out.shoreClearance,
                        out.waterClearance,
                        base.climate.moisture,
                        1,
                        0,
                        1,
                        base.minimumY,
                        base.maximumY);
        Lowlands.selection(seed, x, z, reference, earlier, out.weights);
        for (int i = 0; i < FAMILIES.length; i++) {
            if (out.weights[i] <= 0) continue;
            Lowlands.sample(FAMILIES[i], seed, x, z, reference, tuning, earlier, out.sample);
            out.delta += out.weights[i] * out.sample.delta;
            out.weight += out.weights[i] * out.sample.weight;
            out.candidateVisits += out.sample.candidateVisits;
            out.reason = out.sample.reason;
        }
        out.height = parent + out.delta;
    }
}
