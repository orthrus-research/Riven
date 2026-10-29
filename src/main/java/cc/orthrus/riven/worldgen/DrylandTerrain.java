package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.dryland.Drylands;

import com.google.gson.JsonObject;

import java.util.Arrays;

/** Dryland fields read earlier column facts and share one convex displacement budget. */
public final class DrylandTerrain {
    private static final Rules RULES = Rules.load("riven-drylands.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final Drylands.Family[] FAMILIES = {
        Drylands.Family.CRESCENTS,
        Drylands.Family.CROSSING_RIDGES,
        Drylands.Family.DEFLATION_PAVEMENT,
        Drylands.Family.YARDANGS
    };
    private final long seed;
    private final Drylands kernel;
    private final Drylands.Reference reference;
    private final Drylands.Tuning tuning;
    private final SubstrateMap substrate;
    private final double[] weights = new double[FAMILIES.length];
    private final double regionSpan,
            windX,
            windZ,
            shoreReservation,
            clearanceLimit,
            headroomFeather;
    private final double featureFeather;

    DrylandTerrain(long worldSeed, TerrainHeightReference heights, SubstrateMap substrate) {
        if (substrate == null) throw new NullPointerException("substrate");
        this.substrate = substrate;
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        TerrainHeightReference.Feature dune = heights.feature("dune"),
                hollow = heights.feature("hollow_basin"),
                rib = heights.feature("ridge_spur"),
                terrace = heights.feature("terrace");
        double horizontal = TerrainScale.HORIZONTAL;
        reference =
                new Drylands.Reference(
                        dune.width.targetAverage * horizontal,
                        dune.magnitude.targetAverage,
                        hollow.width.targetAverage * horizontal,
                        hollow.magnitude.targetAverage,
                        rib.width.targetAverage * horizontal * .5,
                        hollow.magnitude.targetAverage * 1.5,
                        heights.lowestSurface,
                        heights.highestSurface);
        double aridityStart = Rules.number(j, "aridityStart", 0, .98);
        tuning =
                new Drylands.Tuning(
                        Rules.number(j, "widthScale", .75, 1.5),
                        Rules.number(j, "magnitudeScale", .5, 1.5),
                        Rules.number(j, "occurrence", 0, 1),
                        Rules.number(j, "warp", 0, .35),
                        Rules.number(j, "crossingAngle", .45, 1.35),
                        Rules.number(j, "leeFraction", .18, .4),
                        aridityStart,
                        Rules.number(j, "aridityFull", aridityStart + .01, 1));
        kernel = new Drylands(seed, reference, tuning);
        dimensions(
                reference.duneWidth * tuning.width,
                dune.width.min * horizontal,
                dune.width.max * horizontal);
        dimensions(
                reference.hollowWidth * tuning.width,
                hollow.width.min * horizontal,
                hollow.width.max * horizontal);
        dimensions(
                reference.ribWidth * tuning.width,
                rib.width.min * horizontal,
                rib.width.max * horizontal);
        dimensions(reference.duneHeight * tuning.magnitude, dune.magnitude.min, dune.magnitude.max);
        dimensions(
                reference.hollowDepth * tuning.magnitude,
                hollow.magnitude.min,
                hollow.magnitude.max);
        dimensions(
                reference.ribHeight * tuning.magnitude, hollow.magnitude.min, hollow.magnitude.max);
        regionSpan =
                heights.feature("inland_plain").width.targetAverage
                        * horizontal
                        * Rules.number(j, "regionInPlainWidths", .5, 8);
        double angle =
                SeededNoise.unit(SeededNoise.mix(seed ^ 0x44525957494e4431L)) * 2 * StrictMath.PI;
        windX = StrictMath.cos(angle);
        windZ = StrictMath.sin(angle);
        shoreReservation = Terrain.shoreReservation(heights);
        clearanceLimit =
                .75
                        * tuning.width
                        * Math.max(
                                reference.hollowWidth,
                                Math.max(reference.ribWidth, reference.duneWidth));
        headroomFeather =
                terrace.magnitude.targetAverage
                        * Rules.number(j, "headroomInTerraceHeights", .25, 4);
        featureFeather =
                terrace.magnitude.targetAverage
                        * Rules.number(j, "featureProtectionInTerraceHeights", .25, 4);
        for (int i = 0; i < FAMILIES.length; i++)
            weights[i] = Rules.number(j.getAsJsonObject("familyWeights"), FAMILIES[i].name(), 0, 1);
    }

    private static void dimensions(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalStateException("Dryland reference envelope");
    }

    /** Scratch and material intents belong to the caller, never to a terrain or kernel cache. */
    public static final class Result implements StageResult {
        public double height,
                delta,
                weight,
                sandCover,
                pavement,
                yardangCut,
                aridity,
                fit,
                shoreClearance,
                waterClearance;
        public int candidates, contributors;
        public Drylands.Family dominant;
        public String reason;
        public final double[] weights = new double[FAMILIES.length];
        public final String[] reasons = new String[FAMILIES.length];
        private final Drylands.Output sample = new Drylands.Output();
        private final SubstrateMap.Sample substrate = new SubstrateMap.Sample();

        public void clear(double parent) {
            height = parent;
            delta =
                    weight =
                            sandCover =
                                    pavement =
                                            yardangCut =
                                                    aridity =
                                                            fit =
                                                                    shoreClearance =
                                                                            waterClearance = 0;
            candidates = contributors = 0;
            dominant = null;
            reason = "NOT_SELECTED";
            Arrays.fill(weights, 0);
            Arrays.fill(reasons, "NOT_SELECTED");
        }
    }

    /** Runs after major shapes/carvers, before the final shore and hydrology constraints. */
    public void apply(
            double x,
            double z,
            TerrainMap.Sample base,
            BoundedWaterClearance waterClearance,
            double parent,
            Result out) {
        out.clear(parent);
        if (base.ocean.marine) {
            out.reason = "MARINE";
            return;
        }
        if (tuning.occurrence == 0) {
            out.reason = "DISABLED";
            return;
        }
        if (!Double.isFinite(parent)) {
            out.reason = "INVALID_PARENT";
            return;
        }
        if (base.shore.kind != Shore.Kind.INLAND || base.shore.landWeight < 1) {
            out.reason = "SHORE_RESERVED";
            return;
        }
        out.shoreClearance =
                Math.min(clearanceLimit, Math.max(0, base.shore.distance - shoreReservation));
        if (out.shoreClearance <= 0) {
            out.reason = "SHORE_RESERVED";
            return;
        }
        if (base.water.wet || base.water.weight > 0) {
            out.reason = "WATER_RESERVED";
            return;
        }
        out.aridity = 1 - unit(base.climate.moisture);
        if (out.aridity <= tuning.aridityStart) {
            out.reason = "HUMID";
            return;
        }
        double slope = Math.hypot(base.broadDx, base.broadDz);
        if (slope >= .55) {
            out.reason = "PARENT";
            return;
        }
        // Major shapes precede this stage. Feather away from their applied relief;
        // those values are earlier facts, never queries into this stage's output.
        out.fit =
                unit(base.land.fit(1, 1, 0, Terrain.Fit.CALM))
                        * (1 - unit(base.plateaus.weight))
                        * (1 - unit(base.mountains.weight))
                        * (1 - unit(base.hills.weight))
                        * (1 - Drylands.smooth(Math.abs(base.cold.delta) / featureFeather))
                        * (1 - Drylands.smooth(Math.abs(base.volcanic.delta) / featureFeather))
                        * (1 - Drylands.smooth(base.cuts.cut / featureFeather))
                        * (1
                                - Drylands.smooth(
                                        Math.abs(base.landmarks.appliedDisplacement)
                                                / featureFeather))
                        * (1 - Drylands.smooth(base.carvers.appliedCut / featureFeather));
        if (out.fit <= 0) {
            out.reason = "PARENT";
            return;
        }
        out.waterClearance = waterClearance.surfaceClearance(x, z, clearanceLimit);
        if (out.waterClearance <= 0) {
            out.reason = "WATER_RESERVED";
            return;
        }
        double up = Math.max(0, Math.min(reference.maxY, base.maximumY) - parent),
                down = Math.max(0, parent - Math.max(reference.minY, base.minimumY));
        substrate.sample(x, z, out.substrate);
        // The immutable input is shared unchanged by every family at this column.
        Drylands.Input earlier =
                new Drylands.Input(
                        parent,
                        out.aridity,
                        out.substrate.sandPotential(),
                        out.substrate.looseMaterialPotential(),
                        out.substrate.windErodibility(),
                        slope,
                        out.fit,
                        windX,
                        windZ,
                        out.shoreClearance,
                        out.waterClearance,
                        up,
                        down,
                        false);
        select(x, z, out.weights);
        double strongest = 0;
        boolean evaluated = false;
        for (int i = 0; i < FAMILIES.length; i++) {
            double selected = out.weights[i];
            if (selected <= 0) continue;
            Drylands.Family family = FAMILIES[i];
            kernel.sample(family, x, z, earlier, out.sample);
            out.reasons[i] = out.sample.reason.name();
            out.candidates += out.sample.candidates;
            if (!evaluated) {
                out.reason = out.reasons[i];
                evaluated = true;
            }
            if (out.sample.reason != Drylands.Reason.ACCEPTED) continue;
            // Keep rejected mass empty. Renormalizing surviving families would move
            // their crests abruptly at another family's headroom boundary.
            double blend =
                    selected
                            * Drylands.smooth((up - kernel.riseBound(family)) / headroomFeather)
                            * Drylands.smooth((down - kernel.cutBound(family)) / headroomFeather);
            if (blend <= 0) {
                out.reasons[i] = "HEADROOM_FEATHER";
                if (out.contributors == 0) out.reason = "HEADROOM_FEATHER";
                continue;
            }
            double applied = blend * out.sample.delta, weight = blend * out.sample.weight;
            out.delta += applied;
            out.weight += weight;
            out.sandCover += blend * out.sample.sandCover;
            out.pavement += blend * out.sample.pavement;
            if (family == Drylands.Family.YARDANGS) out.yardangCut += Math.max(0, -applied);
            if (weight > 0) {
                out.contributors++;
                out.reason = "ACCEPTED";
            }
            if (weight > strongest) {
                strongest = weight;
                out.dominant = family;
            }
        }
        out.height = out.delta == 0 ? parent : parent + out.delta;
    }

    private void select(double x, double z, double[] out) {
        double u = x / regionSpan, v = z / regionSpan;
        double dunes = Drylands.smooth(.5 + Drylands.noise(seed ^ 0x44525953454c3031L, u, v)),
                crescents = Drylands.smooth(.5 + Drylands.noise(seed ^ 0x44525953454c3032L, u, v)),
                deflation = Drylands.smooth(.5 + Drylands.noise(seed ^ 0x44525953454c3033L, u, v));
        out[0] = weights[0] * dunes * crescents;
        out[1] = weights[1] * dunes * (1 - crescents);
        out[2] = weights[2] * (1 - dunes) * deflation;
        out[3] = weights[3] * (1 - dunes) * (1 - deflation);
        // Disabled or reduced families leave empty mass rather than magnifying
        // their neighbors where an earlier regional share reaches zero.
        double total = Math.max(1, out[0] + out[1] + out[2] + out[3]);
        for (int i = 0; i < FAMILIES.length; i++) out[i] /= total;
    }

    private static double unit(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
