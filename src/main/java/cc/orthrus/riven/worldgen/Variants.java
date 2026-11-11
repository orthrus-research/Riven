package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Biome variants (riven-variants.json, terrain-v134): named country inside parent riven: biomes
 * that changes only what is decided after the biome (tree stands, ground cover, boulders). One
 * world-level field of seeded patches about {@code patchBlocks} across, a Voronoi diagram of
 * jittered points over warped coordinates, gives each patch one variant by weight from those its
 * point's intent admits, or none; a column takes its patch's variant where its own biome is one of
 * the variant's parents, and near the bisector with the second-nearest patch may take that patch's
 * instead, so edges dither as ecotones do. Every answer is a pure function of seed, definitions
 * and coordinates; patch choices sit in a bounded memo whose eviction only recomputes them.
 */
public final class Variants {
    private static final Rules RULES = Rules.load("riven-variants.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** The variant a column has none of. */
    public static final int NONE = -1;

    private static final double JITTER = 0.8;

    private static final class Variant {
        final String id, name;
        final double weight;
        final boolean[] parents;
        final double[] celsius, moisture;
        final boolean[] landforms;
        final List<String> provinces;

        Variant(String id, JsonObject j) {
            this.id = id;
            name = j.get("name").getAsString();
            weight = Rules.number(j, "weight", 0, 100);
            List<RivenBiomeTable.Definition> all = RivenBiomeTable.definitions();
            parents = new boolean[all.size()];
            JsonArray list = j.getAsJsonArray("parents");
            if (list == null || list.size() == 0)
                throw new IllegalStateException("A variant needs its parents: " + id);
            for (JsonElement p : list) {
                String biome = p.getAsString().replace("riven:", "");
                RivenBiomeTable.Definition d = RivenBiomeTable.byId(biome);
                if (d == null) throw new IllegalStateException("Unknown parent biome " + biome);
                parents[d.index] = true;
            }
            JsonObject when = j.has("when") ? j.getAsJsonObject("when") : new JsonObject();
            celsius = when.has("celsius") ? pair(when, "celsius", -100, 100) : null;
            moisture = when.has("moisture") ? pair(when, "moisture", 0, 1) : null;
            Terrain.Landform[] forms = Terrain.Landform.values();
            if (when.has("landforms")) {
                landforms = new boolean[forms.length];
                for (JsonElement f : when.getAsJsonArray("landforms"))
                    landforms[Terrain.Landform.valueOf(f.getAsString()).ordinal()] = true;
            } else landforms = null;
            if (when.has("provinces")) {
                List<String> types = new ArrayList<>();
                for (JsonElement t : when.getAsJsonArray("provinces")) types.add(t.getAsString());
                provinces = types;
            } else provinces = null;
        }

        boolean admits(TerrainMap.BaseSample b) {
            if (b.ocean.marine) return false;
            double c = b.climate.celsius, m = b.climate.moisture;
            if (celsius != null && !(c >= celsius[0] && c < celsius[1])) return false;
            if (moisture != null && !(m >= moisture[0] && m < moisture[1])) return false;
            if (landforms != null && !landforms[b.land.landform.ordinal()]) return false;
            return provinces == null
                    || (b.province.type != null && provinces.contains(b.province.type.id));
        }
    }

    private static double[] pair(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo >= min && hi > lo && hi <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    private static final List<Variant> VARIANTS;
    private static final Map<String, Integer> INDEX = new HashMap<>();
    private static final double PATCH, WARP_WAVELENGTH, WARP_AMPLITUDE, EDGE, PLAIN_SHARE;

    static {
        JsonObject j = RULES.json;
        PATCH = Rules.number(j, "patchBlocks", 64, 4096);
        JsonObject warp = j.getAsJsonObject("warp");
        WARP_WAVELENGTH = Rules.number(warp, "wavelengthBlocks", 16, 8192);
        WARP_AMPLITUDE = Rules.number(warp, "amplitudeBlocks", 0, PATCH / 2);
        EDGE = Rules.number(j, "edgeBlocks", 0, PATCH / 2);
        PLAIN_SHARE = Rules.number(j, "plainShare", 0, 1);
        List<Variant> list = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("variants").entrySet()) {
            INDEX.put(e.getKey(), list.size());
            list.add(new Variant(e.getKey(), e.getValue().getAsJsonObject()));
        }
        VARIANTS = Collections.unmodifiableList(list);
    }

    /** The index of a variant id, as readers key their own data; unknown ids fail. */
    public static int index(String id) {
        Integer i = INDEX.get(id);
        if (i == null) throw new IllegalStateException("Unknown variant " + id);
        return i;
    }

    public static int count() {
        return VARIANTS.size();
    }

    /** A variant's id (atlas rasters and commands). */
    public static String id(int variant) {
        return VARIANTS.get(variant).id;
    }

    /** A variant's display name. */
    public static String name(int variant) {
        return VARIANTS.get(variant).name;
    }

    /** Whether a biome (its {@link RivenBiomeTable.Definition#index}) is one of a variant's parents. */
    public static boolean parent(int variant, int biome) {
        return VARIANTS.get(variant).parents[biome];
    }

    public static double patchBlocks() {
        return PATCH;
    }

    private final long pointSeed, choiceSeed, ditherSeed;
    private final SeededNoise warpX, warpZ, warpX2, warpZ2;
    private final TerrainMap terrain;
    private final CellMemo<Integer> patches = new CellMemo<>(8192);
    private final ThreadLocal<TerrainMap.BaseSample> scratch =
            ThreadLocal.withInitial(TerrainMap.BaseSample::new);

    public Variants(long worldSeed, TerrainMap terrain) {
        long seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        pointSeed = SeededNoise.mix(seed + 1);
        choiceSeed = SeededNoise.mix(seed + 2);
        ditherSeed = SeededNoise.mix(seed + 3);
        warpX = new SeededNoise(SeededNoise.mix(seed + 4));
        warpZ = new SeededNoise(SeededNoise.mix(seed + 5));
        warpX2 = new SeededNoise(SeededNoise.mix(seed + 6));
        warpZ2 = new SeededNoise(SeededNoise.mix(seed + 7));
        this.terrain = terrain;
    }

    private double pointX(int gx, int gz) {
        long h = SeededNoise.cell(pointSeed, gx, gz);
        return (gx + .5 + JITTER * (SeededNoise.unit(h) - .5)) * PATCH;
    }

    private double pointZ(int gx, int gz) {
        long h = SeededNoise.mix(SeededNoise.cell(pointSeed, gx, gz));
        return (gz + .5 + JITTER * (SeededNoise.unit(h) - .5)) * PATCH;
    }

    /** The variant a patch takes from its point's intent, or NONE. */
    public int patch(int gx, int gz) {
        return patches.get(gx, gz, () -> choose(gx, gz));
    }

    private int choose(int gx, int gz) {
        long h = SeededNoise.cell(choiceSeed, gx, gz);
        if (SeededNoise.unit(h) < PLAIN_SHARE || VARIANTS.isEmpty()) return NONE;
        TerrainMap.BaseSample b = scratch.get();
        terrain.intent(pointX(gx, gz), pointZ(gx, gz), b);
        double[] weights = new double[VARIANTS.size()];
        boolean any = false;
        for (int i = 0; i < weights.length; i++) {
            Variant v = VARIANTS.get(i);
            if (v.weight > 0 && v.admits(b)) {
                weights[i] = v.weight;
                any = true;
            }
        }
        return any ? Rules.choose(weights, SeededNoise.unit(SeededNoise.mix(h))) : NONE;
    }

    /** The variant of column (x, z), whose riven: biome has this {@code biome} index, or NONE. */
    public int at(int x, int z, int biome) {
        int v = patchAt(x, z);
        return v != NONE && VARIANTS.get(v).parents[biome] ? v : NONE;
    }

    /** The variant whose patch covers column (x, z), whatever the column's biome, or NONE. */
    public int patchAt(int x, int z) {
        double wx = x, wz = z;
        if (WARP_AMPLITUDE > 0) {
            double u = x / WARP_WAVELENGTH, w = z / WARP_WAVELENGTH;
            wx += WARP_AMPLITUDE * (warpX.sample(u, w) + .5 * warpX2.sample(2 * u, 2 * w)) / 1.5;
            wz += WARP_AMPLITUDE * (warpZ.sample(u, w) + .5 * warpZ2.sample(2 * u, 2 * w)) / 1.5;
        }
        int cx = (int) Math.floor(wx / PATCH), cz = (int) Math.floor(wz / PATCH);
        int ax = 0, az = 0, bx = 0, bz = 0;
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx, gz = cz + dz;
                double px = pointX(gx, gz) - wx, pz = pointZ(gx, gz) - wz;
                double d = px * px + pz * pz;
                if (d < a) {
                    b = a;
                    bx = ax;
                    bz = az;
                    a = d;
                    ax = gx;
                    az = gz;
                } else if (d < b) {
                    b = d;
                    bx = gx;
                    bz = gz;
                }
            }
        int first = patch(ax, az);
        if (EDGE <= 0) return first;
        // Distance to the bisector between the two nearest points.
        double sx = pointX(bx, bz) - pointX(ax, az), sz = pointZ(bx, bz) - pointZ(ax, az);
        double gap = (b - a) / (2 * Math.sqrt(sx * sx + sz * sz));
        double half = EDGE / 2;
        if (gap >= half) return first;
        int second = patch(bx, bz);
        if (second == first) return first;
        double chance = .5 * (1 - gap / half);
        return SeededNoise.unit(SeededNoise.cell(ditherSeed, x, z)) < chance ? second : first;
    }
}
