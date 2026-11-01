package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Wonder strata (riven-materials.json): named palettes for forms that bring their own rock, and
 * the depth, thickness and tilt of the bands {@link CrustStrata} lays them in.
 */
final class Materials {
    private static final Rules RULES = Rules.load("riven-materials.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    static final long SEED = Rules.seed(RULES.json, "variationSeed");
    static final SeededNoise TILT = new SeededNoise(SeededNoise.mix(SEED ^ 0x54494c54L));
    static final int STRATA_DEPTH, THICKNESS;
    static final double TILT_BLOCKS, TILT_WAVELENGTH;
    private static final Map<String, TerrainBlock[]> NAMED = new HashMap<>();

    static {
        JsonObject j = RULES.json, strata = j.getAsJsonObject("strata");
        STRATA_DEPTH = (int) Rules.number(strata, "depthBlocks", 0, 64);
        THICKNESS = (int) Rules.number(strata, "thicknessBlocks", 1, 32);
        TILT_BLOCKS = Rules.number(strata, "tiltBlocks", 0, 64);
        TILT_WAVELENGTH = Rules.number(strata, "tiltWavelengthBlocks", 16, 8192);
        Map<String, TerrainBlock[]> named = NAMED;
        JsonObject palettes = strata.getAsJsonObject("palettes");
        // Minecraft's bundled Gson has no keySet(); entrySet() works on every version.
        for (Map.Entry<String, com.google.gson.JsonElement> e : palettes.entrySet()) {
            String name = e.getKey();
            JsonArray a = e.getValue().getAsJsonArray();
            TerrainBlock[] p = new TerrainBlock[a.size()];
            for (int i = 0; i < p.length; i++) p[i] = TerrainBlock.of(a.get(i).getAsString());
            if (p.length == 0) throw new IllegalStateException("Empty strata palette " + name);
            named.put(name, p);
        }
    }

    private Materials() {}

    /** A named strata palette from riven-materials.json, for forms that bring their own rock. */
    static TerrainBlock[] palette(String name) {
        TerrainBlock[] p = NAMED.get(name);
        if (p == null) throw new IllegalStateException("Unknown strata palette " + name);
        return p;
    }
}
