package cc.orthrus.riven.worldgen.engine;

/**
 * The setting vocabulary: named conditions the engine computes per cavity floor, in Java, with
 * their thresholds in data (riven-surface.json). A setting reads facts at its own column only.
 * Each condition stands on its own; a setting table's row order decides which one a column takes.
 */
public final class Settings {
    private static final String[] NAMES = {
        "sediment",
        "rocky",
        "marine-shallow",
        "marine-deep",
        "lagoon-shallow",
        "lagoon-deep",
        "water-bed-sand",
        "water-bed-clay",
        "water-bed-dirt",
        "water-bed-gravel",
        "bar-sand",
        "bar-gravel",
        "green",
        "bare-stone",
        "shore-reflective",
        "shore-sandy",
        "dryland-pavement",
        "dryland-sand",
        "climate-sand",
        "snow",
        "steep",
        "rough",
        "default",
        "snowcap",
        "mud",
        "peat",
        "podzol",
        "silt",
        "sandy-loam",
        "loam",
        "wetland-mud",
        "hot-sand"
    };

    public static final int SEDIMENT = 0,
            ROCKY = 1,
            MARINE_SHALLOW = 2,
            MARINE_DEEP = 3,
            LAGOON_SHALLOW = 4,
            LAGOON_DEEP = 5,
            WATER_BED_SAND = 6,
            WATER_BED_CLAY = 7,
            WATER_BED_DIRT = 8,
            WATER_BED_GRAVEL = 9,
            BAR_SAND = 10,
            BAR_GRAVEL = 11,
            GREEN = 12,
            BARE_STONE = 13,
            SHORE_REFLECTIVE = 14,
            SHORE_SANDY = 15,
            DRYLAND_PAVEMENT = 16,
            DRYLAND_SAND = 17,
            CLIMATE_SAND = 18,
            SNOW = 19,
            STEEP = 20,
            ROUGH = 21,
            DEFAULT = 22,
            SNOWCAP = 23,
            MUD = 24,
            PEAT = 25,
            PODZOL = 26,
            SILT = 27,
            SANDY_LOAM = 28,
            LOAM = 29,
            WETLAND_MUD = 30,
            HOT_SAND = 31;

    static {
        if (NAMES.length > 64) throw new IllegalStateException("Setting masks hold 64 settings");
    }

    private Settings() {}

    /**
     * Computes which settings hold at a cavity floor. A classifier reads only the column's own
     * facts and returns every setting that holds as a mask of {@link #bit}s; {@link #DEFAULT}
     * always holds.
     */
    public interface Classifier<S> {
        long holds(S sample, int floor);
    }

    public static long bit(int setting) {
        return 1L << setting;
    }

    public static int count() {
        return NAMES.length;
    }

    /** The index of a setting a definition names; an unknown name fails. */
    public static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalStateException("Unknown setting " + name);
    }

    public static String name(int setting) {
        return NAMES[setting];
    }
}
