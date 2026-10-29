package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Surface climate only: no altitude, plates, water or biome feedback in this first pass. */
public final class TemperatureMap {
    private static final Rules RULES = Rules.load("riven-climate.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private final SeededNoise broad, detail, warpX, warpZ;
    private final double mean, amplitude, scale, detailWeight, detailScale;
    private final double warpScale, warpAmplitude, beltPeriod, beltAmplitude, beltOffset;
    final double layout;

    public TemperatureMap(long worldSeed) {
        this(worldSeed, WorldScale.DEFAULT);
    }

    /** Wavelengths, warp and belt follow the world's layout scale; amplitudes do not. */
    public TemperatureMap(long worldSeed, WorldScale world) {
        layout = world.layout;
        broad = new SeededNoise(SeededNoise.mix(worldSeed ^ 0x54454d5042524f41L));
        detail = new SeededNoise(SeededNoise.mix(worldSeed ^ 0x54454d5044455441L));
        warpX = new SeededNoise(SeededNoise.mix(worldSeed ^ 0x54454d5057415258L));
        warpZ = new SeededNoise(SeededNoise.mix(worldSeed ^ 0x54454d505741525aL));
        JsonObject t = RULES.json.getAsJsonObject("temperature");
        mean = Rules.number(t, "meanCelsius", -30, 40);
        amplitude = Rules.number(t, "noiseAmplitudeCelsius", 0, 60);
        scale = layout * Rules.number(t, "scaleBlocks", 256, 262144);
        detailWeight = Rules.number(t, "detailWeight", 0, 1);
        detailScale = scale * Rules.number(t, "detailScaleFraction", .05, 1);
        warpScale = scale * Rules.number(t, "warpScaleInScales", .1, 10);
        warpAmplitude = scale * Rules.number(t, "warpAmplitudeInScales", 0, 1);
        beltPeriod = layout * Rules.number(t, "beltPeriodBlocks", 0, 4194304);
        beltAmplitude = Rules.number(t, "beltAmplitudeCelsius", 0, 60);
        beltOffset =
                t.has("beltOffsetBlocks")
                        ? layout * Rules.number(t, "beltOffsetBlocks", 0, 4194304)
                        : 0;
    }

    public double celsius(int blockX, int blockZ) {
        return celsiusWarped(warpedX(blockX, blockZ), warpedZ(blockX, blockZ));
    }

    // ClimateMap shares these exact coordinates.
    double warpedX(double blockX, double blockZ) {
        return blockX + warpAmplitude * warpX.sample(blockX / warpScale, blockZ / warpScale);
    }

    double warpedZ(double blockX, double blockZ) {
        return blockZ + warpAmplitude * warpZ.sample(blockX / warpScale, blockZ / warpScale);
    }

    double celsiusWarped(double x, double z) {
        double field =
                (broad.sample(x / scale, z / scale)
                                + detailWeight * detail.sample(x / detailScale, z / detailScale))
                        / (1 + detailWeight);
        double belt =
                beltPeriod > 0
                        ? beltAmplitude
                                * StrictMath.cos(2 * Math.PI * (z + beltOffset) / beltPeriod)
                        : 0;
        return mean + belt + amplitude * field;
    }

    public TemperatureBand band(int x, int z) {
        return TemperatureBand.at(celsius(x, z));
    }
}
