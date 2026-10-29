package cc.orthrus.riven.worldgen;

/** Background climate plus bounded earlier structural forcing, classified once after composition.
 * Relative moisture does not reconstruct weather, drainage or supplied water. */
public final class ClimateMap {
    private static final com.google.gson.JsonObject MOISTURE =
            Rules.load("riven-climate.json", 1).json.getAsJsonObject("moisture");
    private static final double BROAD_BLOCKS = Rules.number(MOISTURE, "broadBlocks", 256, 262144),
            DETAIL_BLOCKS = Rules.number(MOISTURE, "detailBlocks", 64, 262144),
            DETAIL_WEIGHT = Rules.number(MOISTURE, "detailWeight", 0, 1),
            BELT_PERIOD =
                    MOISTURE.has("beltPeriodBlocks")
                            ? Rules.number(MOISTURE, "beltPeriodBlocks", 0, 4194304)
                            : 0,
            BELT_AMPLITUDE =
                    MOISTURE.has("beltAmplitude")
                            ? Rules.number(MOISTURE, "beltAmplitude", 0, 0.5)
                            : 0,
            BELT_OFFSET =
                    MOISTURE.has("beltOffsetBlocks")
                            ? Rules.number(MOISTURE, "beltOffsetBlocks", 0, 4194304)
                            : 0;
    private final TemperatureMap temperature;
    private final ClimateForcingMap forcing;
    private final SeededNoise broad, detail;
    private final double broadBlocks, detailBlocks, beltPeriod, beltOffset;

    ClimateMap(long seed, TemperatureMap temperature, ClimateForcingMap forcing) {
        if (temperature == null || forcing == null) throw new NullPointerException();
        this.temperature = temperature;
        this.forcing = forcing;
        broad = new SeededNoise(SeededNoise.mix(seed ^ 0x4d4f495354425244L));
        detail = new SeededNoise(SeededNoise.mix(seed ^ 0x4d4f49535444544cL));
        // Same layout scale as the warped coordinates the temperature map shares.
        broadBlocks = BROAD_BLOCKS * temperature.layout;
        detailBlocks = DETAIL_BLOCKS * temperature.layout;
        beltPeriod = BELT_PERIOD * temperature.layout;
        beltOffset = BELT_OFFSET * temperature.layout;
    }

    /** Call-sequence scratch, never shared by the immutable map. */
    public static final class Sample {
        public double celsius, moisture, warpedX, warpedZ;
        public double backgroundMoisture, moistureCorrection;
        public final ClimateForcingMap.Sample forcing = new ClimateForcingMap.Sample();
        public TemperatureBand temperatureBand;
        public MoistureBand moistureBand;
        public ClimateRegion region;
    }

    public void sample(int x, int z, Sample out) {
        evaluate(x, z, out);
    }

    public void sample(double x, double z, Sample out) {
        evaluate(x, z, out);
    }

    private void evaluate(double x, double z, Sample out) {
        if (out == null) throw new NullPointerException("out");
        if (!Double.isFinite(x) || !Double.isFinite(z))
            throw new IllegalArgumentException("Finite climate coordinates required");
        double wx = temperature.warpedX(x, z), wz = temperature.warpedZ(x, z);
        double celsius = temperature.celsiusWarped(wx, wz);
        double field =
                (broad.sample(wx / broadBlocks, wz / broadBlocks)
                                + DETAIL_WEIGHT
                                        * detail.sample(wx / detailBlocks, wz / detailBlocks))
                        / (1 + DETAIL_WEIGHT);
        // An east-west belt, across the temperature belt, so every climate band spans dry to wet.
        double belt =
                beltPeriod > 0
                        ? BELT_AMPLITUDE
                                * StrictMath.cos(2 * Math.PI * (wx + beltOffset) / beltPeriod)
                        : 0;
        double backgroundMoisture = Math.max(0, Math.min(1, 0.5 + 0.5 * field + belt));
        forcing.sample(x, z, out.forcing);
        double factor = out.forcing.moistureFactor();
        double moisture = backgroundMoisture;
        if (factor != 0)
            moisture = Math.max(0, Math.min(1, backgroundMoisture + backgroundMoisture * factor));
        TemperatureBand t = TemperatureBand.at(celsius);
        MoistureBand m = MoistureBand.at(moisture);
        out.celsius = celsius;
        out.backgroundMoisture = backgroundMoisture;
        out.moisture = moisture;
        out.moistureCorrection = factor == 0 ? 0 : moisture - backgroundMoisture;
        out.warpedX = wx;
        out.warpedZ = wz;
        out.temperatureBand = t;
        out.moistureBand = m;
        out.region = ClimateRegion.at(t, m);
    }
}
