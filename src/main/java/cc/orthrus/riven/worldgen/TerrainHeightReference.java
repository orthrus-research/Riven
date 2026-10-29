package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small reader for the adopted height contract; this imports data, not the former generator. */
public final class TerrainHeightReference {
    public static final TerrainHeightReference DEFAULT = load();

    /**
     * minimumY is the crust floor, which every crust rule keeps as its bottom; worldMinimumY is the
     * world floor below the realms (RealmMap).
     */
    public final int seaLevel,
            waterTopY,
            minimumY,
            worldMinimumY,
            maximumYExclusive,
            lowestSurface,
            highestSurface,
            cloudBaseY;

    public final String fingerprint;
    private final Map<String, Feature> features;

    public static final class Range {
        public final double min, targetAverage, max;

        private Range(JsonObject json) {
            min = json.get("min").getAsDouble();
            targetAverage = json.get("targetAverage").getAsDouble();
            max = json.get("max").getAsDouble();
            if (!(min <= targetAverage && targetAverage <= max))
                throw new IllegalStateException("Invalid height-reference range");
        }
    }

    public static final class Feature {
        public final String id;
        public final Range elevationOffset, relief, magnitude, width;
        public final double elevationFeather;

        private Feature(JsonObject json) {
            id = json.get("id").getAsString();
            elevationFeather =
                    json.has("elevationFeather") ? json.get("elevationFeather").getAsDouble() : 0;
            if (!Double.isFinite(elevationFeather) || elevationFeather < 0)
                throw new IllegalStateException("Invalid elevation feather: " + id);
            elevationOffset = range(json, "elevationOffset");
            relief = range(json, "relief");
            magnitude = range(json, "magnitude");
            width = range(json, "width");
        }

        private static Range range(JsonObject json, String key) {
            return json.has(key) ? new Range(json.getAsJsonObject(key)) : null;
        }
    }

    private TerrainHeightReference(JsonObject json, String fingerprint) {
        JsonObject datum = json.getAsJsonObject("datum");
        seaLevel = datum.get("engineSeaLevelY").getAsInt();
        waterTopY = datum.get("highestOceanWaterBlockY").getAsInt();
        if (waterTopY + 1 != seaLevel || datum.get("waterSurfaceBoundaryY").getAsInt() != seaLevel)
            throw new IllegalStateException("Sea level is the exclusive water boundary");
        JsonObject allocation = json.getAsJsonObject("allocation");
        minimumY = allocation.get("minY").getAsInt();
        worldMinimumY = allocation.get("worldMinY").getAsInt();
        if (worldMinimumY > minimumY || (worldMinimumY & 15) != 0 || (minimumY & 15) != 0)
            throw new IllegalStateException(
                    "The world floor lies on a cube boundary below the crust floor");
        maximumYExclusive = allocation.get("maxYExclusive").getAsInt();
        lowestSurface = allocation.get("lowestReferenceSurfaceY").getAsInt();
        highestSurface = allocation.get("highestReferenceSurfaceY").getAsInt();
        cloudBaseY = json.getAsJsonObject("atmosphere").get("cloudBaseY").getAsInt();
        if (cloudBaseY <= seaLevel || cloudBaseY >= maximumYExclusive)
            throw new IllegalStateException(
                    "Cloud base must lie between sea level and the world top");
        this.fingerprint = fingerprint;
        Map<String, Feature> parsed = new LinkedHashMap<>();
        for (String group : new String[] {"primaryFeatures", "relativeFeatures"})
            for (JsonElement item : json.getAsJsonArray(group)) {
                Feature feature = new Feature(item.getAsJsonObject());
                if (parsed.put(feature.id, feature) != null)
                    throw new IllegalStateException("Duplicate feature " + feature.id);
            }
        features = Collections.unmodifiableMap(parsed);
    }

    public Feature feature(String id) {
        Feature result = features.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown terrain feature " + id);
        return result;
    }

    private static TerrainHeightReference load() {
        Rules rules = Rules.load("riven-terrain-height-reference.json", 1);
        return new TerrainHeightReference(rules.json, rules.fingerprint);
    }
}
