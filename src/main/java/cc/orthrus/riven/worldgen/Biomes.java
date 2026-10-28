package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Logical biome catalog and director. Positions on three climate/elevation axes are locked at the
 * end of {@link TerrainMap#intent}; {@link #resolve} applies only the water, shore and height
 * overrides. Pure functions of packaged definitions and sample facts.
 */
public final class Biomes {
    private static final Rules RULES = Rules.load("riven-biomes.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** One input axis: sorted core boundaries with a transition position around each. */
    static final class Axis {
        final String[] cores;
        final double[] boundaries;
        final double half;

        Axis(JsonObject j, String boundaryKey, double offset) {
            JsonArray c = j.getAsJsonArray("cores"), b = j.getAsJsonArray(boundaryKey);
            cores = new String[c.size()];
            for (int i = 0; i < cores.length; i++) cores[i] = c.get(i).getAsString();
            boundaries = new double[b.size()];
            for (int i = 0; i < boundaries.length; i++)
                boundaries[i] = b.get(i).getAsDouble() + offset;
            half = Rules.number(j, "transitionHalfWidth", 0, 1e6);
            if (boundaries.length != cores.length - 1)
                throw new IllegalStateException("One boundary between each pair of cores");
            for (int i = 1; i < boundaries.length; i++)
                if (!(boundaries[i] - half > boundaries[i - 1] + half))
                    throw new IllegalStateException("Transition bands must not overlap");
        }

        int positions() {
            return 2 * cores.length - 1;
        }

        /** Even positions are cores, odd positions the transition at a boundary. */
        int position(double v) {
            int core = 0;
            for (int k = 0; k < boundaries.length; k++) {
                if (Math.abs(v - boundaries[k]) <= half) return 2 * k + 1;
                if (v > boundaries[k]) core = k + 1;
            }
            return 2 * core;
        }

        /** The core on the value's side of every boundary. */
        int core(double v) {
            int core = 0;
            for (double b : boundaries) if (v >= b) core++;
            return core;
        }

        String name(int p) {
            return (p & 1) == 0 ? cores[p / 2] : cores[p / 2] + "_" + cores[p / 2 + 1];
        }
    }

    static final Axis TEMPERATURE, MOISTURE, ELEVATION;
    static final String[] COASTAL, WATER;
    private static final Mouth[] MOUTHS;
    private static final double MOUTH_FAVOUR, MOUTH_COAST_BAND;
    private static final int INLAND, COUNT, FIRST_MOUTH;
    private static final String[] NAMES;

    static {
        JsonObject j = RULES.json;
        int sea = TerrainHeightReference.DEFAULT.seaLevel;
        TEMPERATURE = new Axis(j.getAsJsonObject("temperature"), "boundaries", 0);
        MOISTURE = new Axis(j.getAsJsonObject("moisture"), "boundaries", 0);
        ELEVATION = new Axis(j.getAsJsonObject("elevation"), "boundariesAboveSea", sea);
        COASTAL = strings(j.getAsJsonArray("coastal"));
        WATER = strings(j.getAsJsonArray("water"));
        INLAND = TEMPERATURE.positions() * MOISTURE.positions() * ELEVATION.positions();
        JsonArray mouths = j.getAsJsonArray("mouths");
        MOUTHS = new Mouth[mouths.size()];
        for (int i = 0; i < MOUTHS.length; i++)
            MOUTHS[i] = new Mouth(mouths.get(i).getAsJsonObject());
        MOUTH_FAVOUR = Rules.number(j, "mouthFavour", 0, 1);
        MOUTH_COAST_BAND = Rules.number(j, "mouthCoastBandBlocks", 0, 1024);
        FIRST_MOUTH = INLAND + COASTAL.length * TEMPERATURE.positions() + WATER.length;
        COUNT = FIRST_MOUTH + MOUTHS.length;
        NAMES = new String[COUNT];
        for (int t = 0; t < TEMPERATURE.positions(); t++)
            for (int m = 0; m < MOISTURE.positions(); m++)
                for (int e = 0; e < ELEVATION.positions(); e++)
                    NAMES[inland(t, m, e)] =
                            TEMPERATURE.name(t) + "/" + MOISTURE.name(m) + "/" + ELEVATION.name(e);
        for (int c = 0; c < COASTAL.length; c++)
            for (int t = 0; t < TEMPERATURE.positions(); t++)
                NAMES[coastal(c, t)] = COASTAL[c] + "/" + TEMPERATURE.name(t);
        for (int w = 0; w < WATER.length; w++)
            NAMES[INLAND + COASTAL.length * TEMPERATURE.positions() + w] = WATER[w];
        for (int i = 0; i < MOUTHS.length; i++) NAMES[FIRST_MOUTH + i] = MOUTHS[i].name;
    }

    /** One river-mouth rule: climate cores it covers and its vanilla look. */
    static final class Mouth {
        final String name, vanilla;
        final boolean[] temperature, moisture;
        final boolean rockyShore;

        Mouth(JsonObject j) {
            name = j.get("name").getAsString();
            vanilla = j.get("vanilla").getAsString();
            rockyShore = j.has("rockyShore") && j.get("rockyShore").getAsBoolean();
            temperature = cores(j.getAsJsonArray("temperature"), TEMPERATURE);
            moisture = cores(j.getAsJsonArray("moisture"), MOISTURE);
        }

        private static boolean[] cores(JsonArray names, Axis axis) {
            boolean[] r = new boolean[axis.cores.length];
            for (int i = 0; i < names.size(); i++) {
                int k = java.util.Arrays.asList(axis.cores).indexOf(names.get(i).getAsString());
                if (k < 0) throw new IllegalStateException("Unknown core " + names.get(i));
                r[k] = true;
            }
            return r;
        }
    }

    /** Vanilla look of a river-mouth biome, or null for other biomes. */
    static String mouthVanilla(int id) {
        return id >= FIRST_MOUTH ? MOUTHS[id - FIRST_MOUTH].vanilla : null;
    }

    /** Name of a river-mouth biome, or null for other biomes. */
    static String mouthName(int id) {
        return id >= FIRST_MOUTH ? MOUTHS[id - FIRST_MOUTH].name : null;
    }

    static java.util.List<String> mouthVanillas() {
        java.util.List<String> r = new java.util.ArrayList<>();
        for (Mouth m : MOUTHS) r.add(m.vanilla);
        return r;
    }

    static java.util.List<String> mouthNames() {
        java.util.List<String> r = new java.util.ArrayList<>();
        for (Mouth m : MOUTHS) r.add(m.name);
        return r;
    }

    private static String[] strings(JsonArray a) {
        String[] r = new String[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsString();
        return r;
    }

    private Biomes() {}

    static JsonObject vanillaTable() {
        return RULES.json.getAsJsonObject("vanilla");
    }

    public static int count() {
        return COUNT;
    }

    public static String name(int id) {
        return NAMES[id];
    }

    static int inland(int t, int m, int e) {
        return (t * MOISTURE.positions() + m) * ELEVATION.positions() + e;
    }

    static int coastal(int kind, int t) {
        return INLAND + kind * TEMPERATURE.positions() + t;
    }

    static int water(String kind) {
        for (int w = 0; w < WATER.length; w++)
            if (WATER[w].equals(kind)) return INLAND + COASTAL.length * TEMPERATURE.positions() + w;
        throw new IllegalArgumentException(kind);
    }

    static boolean isInland(int id) {
        return id < INLAND;
    }

    static int temperatureOf(int id) {
        return id / (MOISTURE.positions() * ELEVATION.positions());
    }

    static int moistureOf(int id) {
        return id / ELEVATION.positions() % MOISTURE.positions();
    }

    static int elevationOf(int id) {
        return id % ELEVATION.positions();
    }

    /** The locked director decision for one column; filled once per intent. */
    public static final class Lock {
        public int temperature, moisture, elevation;
        public int temperatureCore, moistureCore;

        /** Logical inland biome before overrides. */
        public int id() {
            return inland(temperature, moisture, elevation);
        }
    }

    /** Locks the axis positions from earlier climate and base-terrain facts. */
    static void lock(TerrainMap.BaseSample s) {
        Lock l = s.biome;
        l.temperature = TEMPERATURE.position(s.climate.celsius);
        l.moisture = MOISTURE.position(s.climate.moisture);
        l.elevation = ELEVATION.position(s.baseHeight);
        l.temperatureCore = TEMPERATURE.core(s.climate.celsius);
        l.moistureCore = MOISTURE.core(s.climate.moisture);
    }

    /** Final logical biome: water, then shore, then the locked inland cell with height refinement. */
    public static int resolve(TerrainMap.Sample s) {
        Lock l = s.biome;
        int t = l.temperature;
        if (s.emergentSediment) return coastal(0, t);
        if (s.emergentRock) return coastal(1, t);
        // River mouths strongly favour real estuary biomes over shore and shallow water.
        // The zone follows the coast and the channel rather than filling a disc.
        if (s.water.mouth >= MOUTH_FAVOUR
                && !s.ocean.deepOcean
                && (s.ocean.marine
                        || s.water.kind == Hydro.Kind.RIVER
                        || s.shore.distance <= MOUTH_COAST_BAND))
            for (int i = 0; i < MOUTHS.length; i++) {
                Mouth m = MOUTHS[i];
                if (m.temperature[l.temperatureCore]
                        && m.moisture[l.moistureCore]
                        && (!m.rockyShore || s.shore.rocky())) return FIRST_MOUTH + i;
            }
        Hydro.Kind kind = s.water.kind;
        if (kind == Hydro.Kind.LAGOON) return s.water.wet ? water("WARM_SHALLOWS") : coastal(0, t);
        if (kind == Hydro.Kind.WETLAND && s.water.weight > .5) return water("WETLAND");
        if (kind == Hydro.Kind.OASIS && s.water.weight > .5) return water("OASIS");
        if (s.ocean.marine) {
            if (l.temperatureCore == 0) return water("FROZEN_OCEAN");
            if (s.ocean.deepOcean) return water("DEEP_OCEAN");
            return l.temperatureCore == TEMPERATURE.cores.length - 1
                    ? water("WARM_SHALLOWS")
                    : water("OCEAN");
        }
        if (s.water.wet && !s.water.stream)
            return kind == Hydro.Kind.RIVER ? water("RIVER") : water("LAKE");
        if (s.shore.littoral && s.shore.rocky()) return coastal(1, t);
        if (s.shore.sandy) return coastal(0, t);
        if (s.shore.littoral && s.shore.kind == Shore.Kind.MARSH) return coastal(2, t);
        // Height override: at most one elevation step toward the final surface.
        int locked = l.elevation, finalPosition = ELEVATION.position(s.blockY());
        int e = locked + Integer.signum(finalPosition - locked);
        return inland(t, l.moisture, e);
    }
}
