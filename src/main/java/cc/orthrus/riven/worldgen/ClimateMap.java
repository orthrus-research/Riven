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
    private static final com.google.gson.JsonObject LAPSE =
            Rules.load("riven-climate.json", 1).json.getAsJsonObject("lapse");
    private static final double LAPSE_RATE = Rules.number(LAPSE, "celsiusPerBlock", 0, 1),
            LAPSE_START = Rules.number(LAPSE, "startAboveSeaBlocks", -64, 512);

    /**
     * Moisture by temperature: {celsius, shift, contrast} stops, ascending, held beyond the ends.
     * Shift moves moisture; contrast (default 1) scales the noise's departure from the mean.
     */
    private static final double[][] PROFILE = profile(MOISTURE);

    private static double[][] profile(com.google.gson.JsonObject moisture) {
        if (!moisture.has("temperatureProfile")) return new double[0][];
        com.google.gson.JsonArray stops = moisture.getAsJsonArray("temperatureProfile");
        double[][] out = new double[stops.size()][];
        for (int i = 0; i < out.length; i++) {
            com.google.gson.JsonArray stop = stops.get(i).getAsJsonArray();
            if (stop.size() != 2 && stop.size() != 3)
                throw new IllegalStateException("temperatureProfile stop " + i + ": " + stop);
            double celsius = stop.get(0).getAsDouble(), shift = stop.get(1).getAsDouble();
            double contrast = stop.size() == 3 ? stop.get(2).getAsDouble() : 1;
            if (!(celsius >= -60 && celsius <= 60)
                    || !(shift >= -0.5 && shift <= 0.5)
                    || !(contrast >= 0 && contrast <= 3)
                    || i > 0 && !(celsius > out[i - 1][0]))
                throw new IllegalStateException("temperatureProfile stop " + i + ": " + stop);
            out[i] = new double[] {celsius, shift, contrast};
        }
        return out;
    }

    /** The profile's value k (1 shift, 2 contrast) at a temperature, linear between stops. */
    private static double profile(double celsius, int k, double none) {
        double[][] p = PROFILE;
        if (p.length == 0) return none;
        if (celsius <= p[0][0]) return p[0][k];
        for (int i = 1; i < p.length; i++)
            if (celsius <= p[i][0]) {
                double t = (celsius - p[i - 1][0]) / (p[i][0] - p[i - 1][0]);
                return p[i - 1][k] + (p[i][k] - p[i - 1][k]) * t;
            }
        return p[p.length - 1][k];
    }

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

        /** How much the lapse rate cooled this column (0 at sea and on low ground). */
        public double lapseCelsius;
    }

    /** The column's temperature at another height above the sea, by the same lapse rate. */
    public static double celsiusAt(Sample climate, double heightAboveSea) {
        double sea = climate.celsius + climate.lapseCelsius;
        return sea - LAPSE_RATE * Math.max(0, heightAboveSea - LAPSE_START);
    }

    /**
     * Cools a land column by the lapse rate on its smooth height above the sea, from the start
     * height up, and reclassifies it. Called once at intent, after the province offsets.
     */
    public static void lapse(Sample out, double heightAboveSea) {
        double cooling = LAPSE_RATE * Math.max(0, heightAboveSea - LAPSE_START);
        out.lapseCelsius = cooling;
        if (cooling == 0) return;
        out.celsius -= cooling;
        out.temperatureBand = TemperatureBand.at(out.celsius);
        out.region = ClimateRegion.at(out.temperatureBand, out.moistureBand);
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
        // Zonal moisture by temperature: dry subtropics beside a wet hottest edge, and sharper
        // contrast where deserts and rainforests should both stand.
        double shift = profile(celsius, 1, 0), contrast = profile(celsius, 2, 1);
        double backgroundMoisture =
                Math.max(0, Math.min(1, 0.5 + 0.5 * contrast * field + belt + shift));
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
        out.lapseCelsius = 0;
    }
}
