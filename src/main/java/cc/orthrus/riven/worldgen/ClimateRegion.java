package cc.orthrus.riven.worldgen;

/** Broad climate classes; terrain-qualified biome palettes belong to Habitats. */
public enum ClimateRegion {
    FROZEN_DRY(TemperatureBand.FROZEN, MoistureBand.DRY),
    FROZEN_MODERATE(TemperatureBand.FROZEN, MoistureBand.MODERATE),
    FROZEN_WET(TemperatureBand.FROZEN, MoistureBand.WET),
    COOL_DRY(TemperatureBand.COOL, MoistureBand.DRY),
    COOL_MODERATE(TemperatureBand.COOL, MoistureBand.MODERATE),
    COOL_WET(TemperatureBand.COOL, MoistureBand.WET),
    MILD_DRY(TemperatureBand.MILD, MoistureBand.DRY),
    MILD_MODERATE(TemperatureBand.MILD, MoistureBand.MODERATE),
    MILD_WET(TemperatureBand.MILD, MoistureBand.WET),
    WARM_DRY(TemperatureBand.WARM, MoistureBand.DRY),
    WARM_MODERATE(TemperatureBand.WARM, MoistureBand.MODERATE),
    WARM_WET(TemperatureBand.WARM, MoistureBand.WET),
    HOT_DRY(TemperatureBand.HOT, MoistureBand.DRY),
    HOT_MODERATE(TemperatureBand.HOT, MoistureBand.MODERATE),
    HOT_WET(TemperatureBand.HOT, MoistureBand.WET);

    public enum Surface {
        GRASS,
        SNOW,
        SAND
    }

    public final TemperatureBand temperature;
    public final MoistureBand moisture;
    public final Surface surface;
    private static final ClimateRegion[][] REGIONS =
            new ClimateRegion[TemperatureBand.values().length][MoistureBand.values().length];

    static {
        for (ClimateRegion region : values()) {
            if (REGIONS[region.temperature.ordinal()][region.moisture.ordinal()] != null)
                throw new IllegalStateException("Duplicate climate region");
            REGIONS[region.temperature.ordinal()][region.moisture.ordinal()] = region;
        }
        for (ClimateRegion[] row : REGIONS)
            for (ClimateRegion region : row)
                if (region == null) throw new IllegalStateException("Missing climate region");
    }

    ClimateRegion(TemperatureBand temperature, MoistureBand moisture) {
        this.temperature = temperature;
        this.moisture = moisture;
        surface =
                temperature == TemperatureBand.FROZEN
                        ? Surface.SNOW
                        : temperature == TemperatureBand.HOT && moisture == MoistureBand.DRY
                                ? Surface.SAND
                                : Surface.GRASS;
    }

    public static ClimateRegion at(TemperatureBand temperature, MoistureBand moisture) {
        return REGIONS[temperature.ordinal()][moisture.ordinal()];
    }
}
