package cc.orthrus.riven.worldgen;

/** Relative climatic wetness, independent of water bodies or measured rainfall. */
public enum MoistureBand {
    DRY,
    MODERATE,
    WET;
    public static final double DRY_LIMIT = 0.35, WET_LIMIT = 0.65;

    public static MoistureBand at(double moisture) {
        if (moisture < DRY_LIMIT) return DRY;
        if (moisture < WET_LIMIT) return MODERATE;
        return WET;
    }
}
