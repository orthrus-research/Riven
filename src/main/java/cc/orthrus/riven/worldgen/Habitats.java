package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Terrain-qualified palettes. Consumes earlier habitat or final authorized emergence, without terrain queries. */
public final class Habitats {
    private static final Rules RULES = Rules.load("riven-habitats.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final String[][][] PALETTES = create();

    private static String[][][] create() {
        String[][][] result = new String[ClimateRegion.values().length][3][];
        JsonObject profiles = RULES.json.getAsJsonObject("palettes");
        for (ClimateRegion region : ClimateRegion.values()) {
            JsonArray row = profiles.getAsJsonArray(region.name());
            if (row == null || row.size() != 3)
                throw new IllegalStateException("Habitat terrain coverage: " + region);
            for (int level = 0; level < 3; level++) {
                JsonArray palette = row.get(level).getAsJsonArray();
                if (palette.size() < 1 || palette.size() > 2)
                    throw new IllegalStateException("Calm/rugged habitat pair required");
                result[region.ordinal()][level] = new String[palette.size()];
                int i = 0;
                for (JsonElement path : palette)
                    result[region.ordinal()][level][i++] = path.getAsString();
            }
        }
        return result;
    }

    private static final JsonObject VANILLA = Biomes.vanillaTable();

    /**
     * Vanilla biome for the final logical biome (Biomes.resolve). Inland cells use the palette of
     * their core climate region and elevation level, taking the core on the value's side of each
     * transition; coastal and water cells use the vanilla table in riven-biomes.json.
     */
    public static String at(TerrainMap.Sample sample) {
        int id = Biomes.resolve(sample);
        String mouth = Biomes.mouthVanilla(id);
        if (mouth != null) return mouth;
        if (!Biomes.isInland(id)) {
            String key = Biomes.name(id).split("/")[0];
            if (key.equals("BEACH") && sample.climate.celsius < 0) key = "BEACH_COLD";
            if (key.equals("RIVER") && sample.biome.temperatureCore == 0) key = "RIVER_FROZEN";
            return VANILLA.get(key).getAsString();
        }
        int e = Biomes.elevationOf(id);
        int level = (e & 1) == 0 ? e / 2 : Biomes.ELEVATION.core(sample.blockY());
        level = Math.max(e / 2, Math.min((e + 1) / 2, level));
        ClimateRegion region =
                ClimateRegion.at(
                        TemperatureBand.values()[sample.biome.temperatureCore],
                        MoistureBand.values()[sample.biome.moistureCore]);
        String[] palette = PALETTES[region.ordinal()][level];
        return palette[palette.length > 1 && sample.land.rugged > .5 ? 1 : 0];
    }

    /**
     * The decoration-provider key of the final logical biome: {@code MOUTH/<name>} for a river
     * mouth, the coastal or water key of the vanilla table (with {@code BEACH_COLD} and
     * {@code RIVER_FROZEN}), or {@code <REGION>/<LOW|MID|HIGH>/<calm|rugged>} inland, from the same
     * region, level and rugged test as {@link #at}. A provider maps keys to its own biomes.
     */
    public static String key(TerrainMap.Sample sample) {
        int id = Biomes.resolve(sample);
        String mouth = Biomes.mouthName(id);
        if (mouth != null) return "MOUTH/" + mouth;
        if (!Biomes.isInland(id)) {
            String key = Biomes.name(id).split("/")[0];
            if (key.equals("BEACH") && sample.climate.celsius < 0) key = "BEACH_COLD";
            if (key.equals("RIVER") && sample.biome.temperatureCore == 0) key = "RIVER_FROZEN";
            // Coastal and water keys carry their temperature core; providers fall back to the key.
            return key + "#" + TemperatureBand.values()[sample.biome.temperatureCore].name();
        }
        int e = Biomes.elevationOf(id);
        int level = (e & 1) == 0 ? e / 2 : Biomes.ELEVATION.core(sample.blockY());
        level = Math.max(e / 2, Math.min((e + 1) / 2, level));
        ClimateRegion region =
                ClimateRegion.at(
                        TemperatureBand.values()[sample.biome.temperatureCore],
                        MoistureBand.values()[sample.biome.moistureCore]);
        return region.name()
                + "/"
                + LEVELS[level]
                + (sample.land.rugged > .5 ? "/rugged" : "/calm");
    }

    private static final String[] LEVELS = {"LOW", "MID", "HIGH"};

    private static final JsonObject GRID =
            Rules.load("riven-bop.json", 1).json.getAsJsonObject("climateGrid");
    private static final double GRID_COLD = Rules.number(GRID, "coldestCelsius", -40, 10),
            GRID_STEP = Rules.number(GRID, "celsiusPerStep", 1, 20),
            GRID_WET = Rules.number(GRID, "wettestMoisture", 0, 1),
            GRID_DRY = Rules.number(GRID, "driestMoisture", 0, 1);

    /**
     * A provider's climate grid cell (temperature 0-8 cold to hot, rainfall 0-11 wet to dry,
     * code {@code temperature * 12 + rainfall}) from the column's continuous climate, so a provider
     * can choose biomes by its own climate table.
     */
    public static int climate(TerrainMap.Sample sample) {
        int t = (int) Math.floor((sample.climate.celsius - GRID_COLD) / GRID_STEP);
        int r = (int) Math.floor((GRID_WET - sample.climate.moisture) / (GRID_WET - GRID_DRY) * 12);
        return Math.max(0, Math.min(8, t)) * 12 + Math.max(0, Math.min(11, r));
    }

    public static Set<String> paths() {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(
                result,
                "ocean",
                "deep_ocean",
                "river",
                "swampland",
                "jungle_edge",
                "beaches",
                "stone_beach",
                "cold_beach");
        for (String[][] row : PALETTES)
            for (String[] palette : row) Collections.addAll(result, palette);
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : VANILLA.entrySet())
            result.add(e.getValue().getAsString());
        result.addAll(Biomes.mouthVanillas());
        return Collections.unmodifiableSet(result);
    }

    private Habitats() {}
}
