package cc.orthrus.riven.worldgen.engine;

/**
 * The named heights a column publishes. Operators compute them (adding a horizon is Java); zones,
 * cavities and fills name them in data, and the names resolve to these indexes when definitions
 * load, so at run time a horizon is one int of the column's array. A column that lacks a horizon
 * holds {@link #ABSENT} there, and whatever reads it is absent too.
 */
public final class Horizons {
    /** A horizon the column lacks. */
    public static final int ABSENT = Integer.MIN_VALUE;

    private static final String[] NAMES = {
        "top",
        "sea",
        "crust-floor",
        "world-floor",
        "ground",
        "ground-before-deposit",
        "ground-before-rocky",
        "water",
        "pocket-floor",
        "pocket-roof",
        "pocket-level",
        "deep",
        "lava",
        "unconformity",
        "flood-floor",
        "flood-level"
    };

    /** The frame: one above the highest block, the sea, the crust's lowest Y and the world's. */
    public static final int TOP = 0, SEA = 1, CRUST_FLOOR = 2, WORLD_FLOOR = 3;

    /**
     * The height chain: the sky's floor, and its ground under a laid deposit and under rocky
     * shaping (where one acts).
     */
    public static final int GROUND = 4, GROUND_BEFORE_DEPOSIT = 5, GROUND_BEFORE_ROCKY = 6;

    /**
     * Hydrology: the highest water Y over the ground, and a sealed pocket's lowest open Y, the Y
     * above its highest and its highest water.
     */
    public static final int WATER = 7, POCKET_FLOOR = 8, POCKET_ROOF = 9, POCKET_LEVEL = 10;

    /**
     * Geology: the lowest Y above the deep surface (the root lies below it), the highest Y lava
     * fills, and the lowest Y above the unconformity.
     */
    public static final int DEEP = 11, LAVA = 12, UNCONFORMITY = 13;

    /** A flood cell's band: water holds where flood-floor < y <= flood-level. */
    public static final int FLOOD_FLOOR = 14, FLOOD_LEVEL = 15;

    private Horizons() {}

    public static int count() {
        return NAMES.length;
    }

    /** The index of a horizon a definition names; an unknown name fails. */
    public static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalStateException("Unknown horizon " + name);
    }

    public static String name(int index) {
        return NAMES[index];
    }
}
