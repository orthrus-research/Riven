package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** Riven-owned block variety (riven-materials.json): wonder strata, sandstone, gravel beaches, slopes, clay. */
final class Materials {
    private static final Rules RULES = Rules.load("riven-materials.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    private static final long SEED = Rules.seed(RULES.json, "variationSeed");
    private static final SeededNoise TILT = new SeededNoise(SeededNoise.mix(SEED ^ 0x54494c54L)),
            CLAY = new SeededNoise(SeededNoise.mix(SEED ^ 0x434c4159L));
    static final int STRATA_DEPTH, SANDSTONE_DEPTH, THICKNESS;
    private static final double TILT_BLOCKS, TILT_WAVELENGTH, CLAY_WAVELENGTH, CLAY_THRESHOLD;
    static final double GRAVEL_BEACH_SHOALING, COARSE_SLOPE, GRAVEL_SLOPE;
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
            for (int i = 0; i < p.length; i++) p[i] = TerrainBlock.valueOf(a.get(i).getAsString());
            if (p.length == 0) throw new IllegalStateException("Empty strata palette " + name);
            named.put(name, p);
        }
        SANDSTONE_DEPTH = (int) Rules.number(j, "sandstoneDepthBlocks", 0, 16);
        GRAVEL_BEACH_SHOALING = Rules.number(j, "gravelBeachBelowShoaling", 0, 1);
        COARSE_SLOPE = Rules.number(j, "coarseDirtAboveSlope", 0, 16);
        GRAVEL_SLOPE = Rules.number(j, "gravelAboveSlope", 0, 16);
        JsonObject clay = j.getAsJsonObject("clayPatch");
        CLAY_WAVELENGTH = Rules.number(clay, "wavelengthBlocks", 4, 4096);
        CLAY_THRESHOLD = Rules.number(clay, "threshold", -1, 1);
    }

    private Materials() {}

    /** A named strata palette from riven-materials.json, for forms that bring their own rock. */
    static TerrainBlock[] palette(String name) {
        TerrainBlock[] p = NAMED.get(name);
        if (p == null) throw new IllegalStateException("Unknown strata palette " + name);
        return p;
    }

    /**
     * The rock at Y: the column's geology, or within a wonder's strata depth its own banded palette,
     * so a wonder's walls show strata top to foot.
     */
    static TerrainBlock stratum(TerrainMap.Sample s, long y, int surface) {
        TerrainBlock[] palette = s.wonders.palette;
        if (palette == null || y <= strataFloor(s, surface) - STRATA_DEPTH)
            return s.geology.block(y, surface);
        return palette[
                (int) Long.remainderUnsigned(SeededNoise.mix(SEED + band(s, y)), palette.length)];
    }

    /** The level strata depth counts down from: the surface, or a wonder's lower floor. */
    static int strataFloor(TerrainMap.Sample s, int surface) {
        return s.wonders.palette == null
                ? surface
                : Math.min(surface, (int) Math.floor(s.wonders.base));
    }

    /** The stratum index at Y: tilted bands thicknessBlocks thick. */
    static long band(TerrainMap.Sample s, long y) {
        double tilt = TILT_BLOCKS * TILT.sample(s.x / TILT_WAVELENGTH, s.z / TILT_WAVELENGTH);
        return (long) Math.floor((y + tilt) / THICKNESS);
    }

    /** Whether a dirt bed is clay here. */
    static boolean clay(TerrainMap.Sample s) {
        return CLAY.sample(s.x / CLAY_WAVELENGTH, s.z / CLAY_WAVELENGTH) > CLAY_THRESHOLD;
    }
}
