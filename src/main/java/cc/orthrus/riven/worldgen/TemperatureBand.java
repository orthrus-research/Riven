package cc.orthrus.riven.worldgen;

/** Broad temperature classification. ClimateRegion owns biome eligibility. */
public enum TemperatureBand {
    FROZEN,
    COOL,
    MILD,
    WARM,
    HOT;

    public static TemperatureBand at(double celsius) {
        if (celsius < -5) return FROZEN;
        if (celsius < 5) return COOL;
        if (celsius < 15) return MILD;
        if (celsius < 25) return WARM;
        return HOT;
    }
}
