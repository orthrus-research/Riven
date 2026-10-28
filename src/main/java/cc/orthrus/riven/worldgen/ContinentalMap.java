package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contribution.continents.ContinentalKernels;
import cc.orthrus.riven.contribution.continents.ContinentalKernels.Family;

import com.google.gson.JsonObject;

/**
 * Four original outline alternatives. Every marine column holds ocean water to the sea datum;
 * marine areas enclosed by land are inland seas at that datum, never dry basins.
 */
public final class ContinentalMap {
    public static final int VERSION = 8;
    public static final String FINGERPRINT = ContinentalOutlineMap.FINGERPRINT;

    public enum Form {
        OPEN,
        BAY,
        INLET,
        HEADLAND
    }

    public final ContinentalOutlineMap outline;
    public final Family family;
    private final SeededNoise bedNoise, shoalNoise;
    private final double shelfY, deepY, bedAmplitude, deepBiomeY, shelfWidth, normalBand;
    private final double shoalWavelength, shoalStart, shoalWidth;
    private final int waterTop;
    public final double spacing, landBlend, oceanTransitionWidth;
    public final int minimumSurfaceY, seaLevel;

    public ContinentalMap(long worldSeed, TerrainHeightReference heights) {
        this(worldSeed, heights, new ContinentalOutlineMap(worldSeed, heights));
    }

    ContinentalMap(long worldSeed, TerrainHeightReference heights, ContinentalOutlineMap outline) {
        if (heights == null || outline == null) throw new NullPointerException();
        this.outline = outline;
        JsonObject j = ContinentalOutlineMap.RULES.json;
        long seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        family = outline.family;
        spacing = outline.spacing;
        landBlend = outline.landBlend;
        oceanTransitionWidth = landBlend;
        seaLevel = heights.seaLevel;
        waterTop = heights.waterTopY;
        TerrainHeightReference.Feature shelf = heights.feature("continental_shelf"),
                deep = heights.feature("deep_ocean");
        shelfY = seaLevel + shelf.elevationOffset.targetAverage;
        deepY = seaLevel + deep.elevationOffset.targetAverage;
        bedAmplitude =
                Math.min(
                        deep.relief.targetAverage,
                        Math.min(
                                deep.elevationOffset.targetAverage - deep.elevationOffset.min,
                                deep.elevationOffset.max - deep.elevationOffset.targetAverage));
        minimumSurfaceY = (int) Math.floor(Math.min(deepY - bedAmplitude, shelfY - bedAmplitude));
        deepBiomeY = seaLevel + heights.feature("continental_slope").elevationOffset.targetAverage;
        shelfWidth = oceanTransitionWidth * Rules.number(j, "shelfFraction", .1, .9);
        normalBand =
                heights.feature("coastal_plain").width.targetAverage
                        * Rules.number(j, "normalBandInCoastalPlainWidths", 1, 8);
        bedNoise = new SeededNoise(SeededNoise.mix(seed ^ 0x4f4345414e424544L));
        shoalNoise = new SeededNoise(SeededNoise.mix(seed ^ 0x53484f414c494e47L));
        JsonObject shoaling = j.getAsJsonObject("shoaling");
        shoalWavelength = Rules.number(shoaling, "wavelengthBlocks", 128, 8192);
        shoalStart = Rules.number(shoaling, "noiseStart", -1, 1);
        shoalWidth = Rules.number(shoaling, "noiseWidth", .05, 2);
    }

    public static final class Sample {
        public double coastCoordinate,
                blendWidth,
                bedHeight,
                distance,
                normalX,
                normalZ,
                shelter,
                headland,
                landWeight,
                scaffoldClearance;

        /**
         * Owner continent traits: radius multiplier, axis ratio, coast roughness and depth inside it
         * (0 at the coast, 1 at one scaled radius). {@code family} is the owner's outline family.
         */
        public double continentSize, elongation, roughness, interior;

        /** Cape weight (0..1): land on a convex coast, a headland or point. */
        public double cape;

        /**
         * Shoaling (0..1): how broad and shallow the nearshore is. A dissipative coast (1) has wide
         * shallows where sediment gathers and waves spend themselves; a reflective coast (0) drops
         * straight to the shelf. It varies in stretches of coast from one low-frequency field, so
         * the seabed it shapes is continuous; shelter, which changes within a few blocks offshore,
         * is read separately by sites. Defined on both sides of the shore.
         */
        public double shoaling;

        public boolean marine, deepOcean, supplied, protectedWater, archipelago;
        public long owner;
        public Family family;
        public ContinentalKernels.Reason reason;
        public Form form = Form.OPEN;
        private final ContinentalKernels.Sample value = new ContinentalKernels.Sample(),
                probe = new ContinentalKernels.Sample();
        private final ContinentalKernels.Scratch work = new ContinentalKernels.Scratch();
    }

    /** Conservative distance outside the scaffold; nonpositive means reserved. No topology plan is queried. */
    public double scaffoldClearance(double x, double z) {
        return outline.scaffoldClearance(x, z);
    }

    public void sample(double x, double z, Sample out) {
        outline.sample(x, z, out.value, out.work);
        ContinentalKernels.Sample value = out.value;
        out.family = value.family;
        out.continentSize = value.size;
        out.elongation = value.elongation;
        out.roughness = value.roughness;
        out.interior = value.interior;
        out.cape = value.cape;
        out.reason = value.reason;
        out.owner = value.owner;
        out.archipelago = value.archipelago;
        out.coastCoordinate = value.score;
        out.blendWidth = landBlend;
        out.landWeight = value.landWeight;
        out.marine = value.marine;
        out.scaffoldClearance = value.corridorClearance / outline.kernel().warpStretch();
        out.protectedWater = value.corridorClearance <= 0;
        // Marine ownership is the supply: open and enclosed marine areas both hold water at the
        // datum, so no column can end below sea level without water.
        out.supplied = out.marine;
        out.shelter = value.shelter;
        out.headland = value.headland;
        out.shoaling =
                smooth(
                        (shoalNoise.sample(x / shoalWavelength, z / shoalWavelength) - shoalStart)
                                / shoalWidth);
        out.form =
                out.shelter > 0
                        ? Form.BAY
                        : value.rawScore > 0
                                        && value.reason == ContinentalKernels.Reason.OCEAN_CORRIDOR
                                ? Form.INLET
                                : out.headland > 0 ? Form.HEADLAND : Form.OPEN;
        double gx = 0, gz = 0;
        if (Math.abs(value.score) <= normalBand) {
            double low = Math.max(-30000000, x - .5), high = Math.min(30000000, x + .5);
            outline.sampleScore(low, z, out.probe, out.work);
            double a = out.probe.score;
            outline.sampleScore(high, z, out.probe, out.work);
            gx = (out.probe.score - a) / (high - low);
            low = Math.max(-30000000, z - .5);
            high = Math.min(30000000, z + .5);
            outline.sampleScore(x, low, out.probe, out.work);
            a = out.probe.score;
            outline.sampleScore(x, high, out.probe, out.work);
            gz = (out.probe.score - a) / (high - low);
        }
        double length = Math.hypot(gx, gz);
        // Coast distance is the score itself: it changes about one block per block (more only
        // where coast detail steepens it) and is continuous everywhere. Dividing by its gradient
        // jumped across creases, where two lobes or continents meet in the union max: the central
        // difference partly cancels and each side's gradient differs, which left spikes and
        // terrace steps in the coastal plain.
        out.distance =
                Math.abs(value.score) <= normalBand
                        ? value.score
                        : out.marine ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        out.normalX = length > 1e-8 ? gx / length : 0;
        out.normalZ = length > 1e-8 ? gz / length : 0;
        double depth = Math.max(0, -value.score),
                shelf = shelfY + bedAmplitude * bedNoise.sample(x / 640, z / 640),
                deep = deepY + bedAmplitude * bedNoise.sample(x / 1800 + .31, z / 1800 + .67);
        double floor = lerp(waterTop - 1, shelf, smooth(depth / shelfWidth));
        out.bedHeight =
                Math.min(
                        waterTop - 1,
                        lerp(
                                floor,
                                deep,
                                smooth(
                                        (depth - shelfWidth)
                                                / (oceanTransitionWidth - shelfWidth))));
        out.deepOcean = out.bedHeight < deepBiomeY;
    }

    static double smooth(double t) {
        return Rules.smooth(t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
