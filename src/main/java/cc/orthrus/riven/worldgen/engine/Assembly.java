package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The engine's definitions, read once and built into immutable, array-backed structures: the zone
 * tree (riven-zones.json) and the setting tables and setting thresholds (riven-surface.json).
 * Nothing here is read at generation time but arrays and numbers.
 */
public final class Assembly {
    private static final class Packaged {
        static final Assembly ASSEMBLY =
                new Assembly(
                        Rules.load("riven-zones.json", 1).json,
                        Rules.load("riven-surface.json", 1).json);
    }

    public final Zones zones;
    private final Map<String, SettingTable> tables = new HashMap<>();
    private final JsonObject thresholds;

    Assembly(JsonObject zones, JsonObject surface) {
        Zone.keys(
                surface,
                "riven-surface.json",
                "schema",
                "revision",
                "thresholds",
                "tables",
                "contract");
        for (Map.Entry<String, JsonElement> e : surface.getAsJsonObject("tables").entrySet())
            tables.put(e.getKey(), new SettingTable(e.getKey(), e.getValue().getAsJsonObject()));
        thresholds = surface.getAsJsonObject("thresholds");
        Zone.keys(zones, "riven-zones.json", "schema", "revision", "zones", "contract");
        this.zones = new Zones(zones, tables);
    }

    /** The packaged definitions' assembly. */
    public static Assembly packaged() {
        return Packaged.ASSEMBLY;
    }

    /**
     * The thresholds riven-surface.json gives a classifier, read when the classifier is built
     * (never at generation time).
     */
    public JsonObject thresholds(String classifier) {
        JsonObject t = thresholds.getAsJsonObject(classifier);
        if (t == null) throw new IllegalStateException("No thresholds for " + classifier);
        return t;
    }

    public SettingTable table(String id) {
        SettingTable t = tables.get(id);
        if (t == null) throw new IllegalStateException("No setting table " + id);
        return t;
    }
}
