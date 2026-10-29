package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** One earlier coastal contract for selectors, hydrology, shaping and cover. No world or final-height reads. */
public final class Shore {
    public enum Kind {
        OCEAN,
        INLAND,
        PLAIN,
        BEACH,
        ROCK,
        CLIFF,
        MARSH
    }

    private static final Rules RULES = Rules.load("riven-shores.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private final long seed;
    private final SeededNoise variation;
    private final double beachWidth,
            plainWidth,
            cliffWidth,
            bluffWidth,
            plainY,
            beachY,
            plainRelief,
            beachRelief,
            littoralWidth;
    private final double cliffChance, rockStart, rockEnd, marshMoisture, marshTemperature;
    private final double envelopeBase, envelopeSlope, envelopeSteep, envelopeKnee, majorSlope;
    private final int sea, water;

    Shore(long worldSeed, TerrainHeightReference heights) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        variation = new SeededNoise(seed);
        TerrainHeightReference.Feature beach = heights.feature("beach"),
                plain = heights.feature("coastal_plain");
        beachWidth = beach.width.targetAverage * Rules.number(j, "beachWidthScale", .5, 2);
        plainWidth = plain.width.targetAverage;
        littoralWidth = beach.width.max;
        cliffWidth =
                heights.feature("scarp").width.targetAverage
                        * Rules.number(j, "cliffWidthScale", .5, 3);
        bluffWidth = cliffWidth * Rules.number(j, "bluffInCliffWidths", 1, 8);
        sea = heights.seaLevel;
        water = heights.waterTopY;
        plainY = sea + plain.elevationOffset.targetAverage;
        beachY = sea + beach.elevationOffset.targetAverage;
        plainRelief = plain.relief.targetAverage;
        beachRelief = beach.relief.targetAverage;
        cliffChance = Rules.number(j, "cliffChance", 0, 1);
        rockStart = Rules.number(j, "rockSlopeStart", 0, 1);
        rockEnd = Rules.number(j, "rockSlopeEnd", rockStart + .001, 2);
        marshMoisture = Rules.number(j, "marshMoisture", 0, .9);
        marshTemperature = Rules.number(j, "marshMinimumCelsius", 0, 20);
        JsonObject e = j.getAsJsonObject("envelope");
        envelopeBase = sea + beach.elevationOffset.max;
        double inland =
                sea
                        + heights.feature(e.get("inlandFeature").getAsString())
                                .elevationOffset
                                .targetAverage;
        double reach = plainWidth * Rules.number(e, "reachInCoastalPlainWidths", .25, 4);
        if (inland <= envelopeBase) throw new IllegalStateException("Envelope must rise inland");
        envelopeSlope = (inland - envelopeBase) / reach;
        envelopeSteep = envelopeSlope * Rules.number(e, "steepFactor", 1, 20);
        envelopeKnee = Rules.number(e, "kneeFraction", .1, .9);
        majorSlope =
                (heights.highestSurface - envelopeBase)
                        / (plainWidth * Rules.number(e, "majorReachInCoastalPlainWidths", .25, 8));
        if (!(majorSlope > 0)) throw new IllegalStateException("Major envelope must rise inland");
    }

    /**
     * Upper bound on final height near the coast, from the shoreline beach maximum rising inland;
     * steeper on rocky and cliff shores. Unbounded where the coast distance is not estimated.
     */
    public double envelopeMaximum(Sample shore) {
        if (shore.kind == Kind.OCEAN || !Double.isFinite(shore.distance))
            return Double.POSITIVE_INFINITY;
        return envelopeBase
                + (shore.rocky() ? envelopeSteep : envelopeSlope) * Math.max(0, shore.distance);
    }

    /**
     * Softly caps a feature's raise above its parent by the envelope and never lowers the parent.
     * Raises below the knee pass unchanged; above it they bend smoothly toward the cap.
     */
    public double envelope(double parent, double height, Sample shore) {
        return soften(parent, height, envelopeMaximum(shore));
    }

    /**
     * Softly caps a major feature's raise (volcanic, cold, plateau, mountain) by a ceiling that
     * rises from the beach maximum at the coast to the highest planned surface one major reach
     * inland. It reads the continental score, which is finite and continuous everywhere, so the
     * ceiling has no edge where the estimated coast distance ends.
     */
    public double majorEnvelope(double parent, double height, double coastCoordinate) {
        return soften(parent, height, envelopeBase + majorSlope * Math.max(0, coastCoordinate));
    }

    private double soften(double parent, double height, double maximum) {
        double raise = height - parent;
        if (raise <= 0) return height;
        double cap = maximum - parent;
        if (cap == Double.POSITIVE_INFINITY) return height;
        if (cap <= 0) return parent;
        double knee = cap * envelopeKnee;
        if (raise <= knee) return height;
        return parent + cap - (cap - knee) * (cap - knee) / (raise - 2 * knee + cap);
    }

    /** Filled after broad terrain classification; later stages treat these facts as read-only. */
    public static final class Sample {
        public Kind kind = Kind.OCEAN;
        public double distance,
                normalX,
                normalZ,
                shelter,
                grade,
                proximity,
                beach,
                plain,
                cliff,
                rock,
                marsh;
        public double landWeight, height;
        public boolean sandy, littoral;

        /** The same broad coastal surface used by planners and by final shaping. */
        public double shape(double inlandHeight) {
            double blended = waterDatum + landWeight * (inlandHeight - waterDatum);
            blended += (plainTarget - blended) * plain;
            return blended + (beachTarget - blended) * beach;
        }

        private double waterDatum, plainTarget, beachTarget;

        public boolean rocky() {
            return kind == Kind.ROCK || kind == Kind.CLIFF;
        }

        /** Local admission cannot erase a shoreline reservation. Inland behavior is unchanged. */
        public double detail() {
            return 1 - Math.max(beach, plain * .85);
        }
    }

    public void classify(double x, double z, TerrainMap.BaseSample base, Sample out) {
        ContinentalMap.Sample ocean = base.ocean;
        Terrain.Sample land = base.land;
        out.distance = ocean.distance;
        out.normalX = ocean.normalX;
        out.normalZ = ocean.normalZ;
        out.shelter = ocean.shelter;
        out.beach = out.plain = out.cliff = out.rock = out.marsh = out.grade = out.proximity = 0;
        out.waterDatum = water;
        // Set before the marine return, where zero plain and beach weights leave them unused, so a
        // reused sample keeps nothing from an earlier column.
        out.plainTarget = plainY;
        out.beachTarget = beachY;
        out.sandy = false;
        out.littoral = !ocean.marine && ocean.distance <= littoralWidth;
        if (ocean.marine) {
            out.kind = Kind.OCEAN;
            out.landWeight = 0;
            out.height = ocean.bedHeight;
            return;
        }
        out.kind = Kind.INLAND;
        out.landWeight = ocean.landWeight;
        double distance = Math.max(0, ocean.distance);
        if (distance >= plainWidth * 1.5) {
            out.height = out.shape(base.baseHeight);
            return;
        }
        double n = variation.sample(x / plainWidth, z / plainWidth), patch = .5 + .5 * n;
        double width = beachWidth * (.75 + .5 * patch), plainSpan = plainWidth * (.75 + .5 * patch);
        out.proximity = 1 - Rules.smooth(distance / plainSpan);
        out.grade = Math.abs(base.broadDx * ocean.normalX + base.broadDz * ocean.normalZ);
        // Shelter is geometric indentation, not wind, fetch or a simulated wave field.
        double sheltered = Rules.smooth(ocean.shelter * 3);
        double cliffFit =
                Rules.smooth(
                        (cliffChance + .12 * ocean.headland - .1 * sheltered - patch) / .18 + .5);
        double cliff = land.high * cliffFit;
        out.cliff = cliff * out.proximity;
        double sharp = Rules.smooth(distance / (cliffWidth * (.8 + .4 * patch)));
        out.landWeight += (sharp - out.landWeight) * cliff;
        // Steep shore grade or a cape (convex coast, from the outline) on raised land is rock.
        out.rock =
                Math.max(Rules.smooth((out.grade - rockStart) / (rockEnd - rockStart)), ocean.cape)
                        * (land.middle + land.high)
                        * out.proximity;
        // A rock shore is a bluff: its land rises over a few cliff widths instead of the long
        // continental ramp, so rocky coast features cut into the relief they stand on.
        double bluff = Rules.smooth(distance / (bluffWidth * (.8 + .4 * patch)));
        out.landWeight += Math.max(0, bluff - out.landWeight) * out.rock;
        double rocky = Math.max(out.rock, out.cliff);
        out.marsh =
                out.proximity
                        * sheltered
                        * land.low
                        * (1 - land.rugged)
                        * Rules.smooth((base.climate.moisture - marshMoisture) / .18)
                        * Rules.smooth((base.climate.celsius - marshTemperature) / 8);
        out.plain = out.proximity * land.low * (1 - land.rugged) * (1 - rocky);
        out.beach = (1 - Rules.smooth(distance / width)) * (1 - rocky);
        out.plainTarget = plainY + plainRelief * n;
        out.beachTarget =
                sea + Math.max(0, beachY - sea + beachRelief * n) * Rules.smooth(distance / width);
        if (out.cliff > .35) out.kind = Kind.CLIFF;
        else if (out.rock > .45) out.kind = Kind.ROCK;
        else if (out.beach > .35) out.kind = Kind.BEACH;
        else if (out.marsh > .2) out.kind = Kind.MARSH;
        else if (out.plain > .35) out.kind = Kind.PLAIN;
        out.sandy = out.beach > .35 && !out.rocky();
        out.height = out.shape(base.baseHeight);
    }
}
