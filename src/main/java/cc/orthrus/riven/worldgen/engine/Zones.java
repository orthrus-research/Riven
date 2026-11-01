package cc.orthrus.riven.worldgen.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The zone tree (riven-zones.json), top-down. A zone's bottom is the next zone's top unless it
 * says otherwise, so each boundary is declared once; the last zone names its own bottom. The
 * crust is the only zone until the realms join (B4), and its sub-zones come in B2.
 */
public final class Zones {
    private final Zone[] zones;

    Zones(JsonObject json, Map<String, SettingTable> tables) {
        JsonArray a = json.getAsJsonArray("zones");
        if (a.size() == 0 || a.size() > 255) throw new IllegalStateException("Zones: 1 to 255");
        zones = new Zone[a.size()];
        // Bottom-up, so a zone without its own bottom ends at the next zone's top.
        for (int i = zones.length - 1; i >= 0; i--)
            zones[i] =
                    new Zone(
                            a.get(i).getAsJsonObject(),
                            i,
                            i + 1 < zones.length ? zones[i + 1].top : null,
                            tables);
        for (int i = 0; i < zones.length; i++)
            for (int j = 0; j < i; j++)
                if (zones[j].id.equals(zones[i].id))
                    throw new IllegalStateException("Zones: duplicate " + zones[i].id);
    }

    public int count() {
        return zones.length;
    }

    public Zone zone(int index) {
        return zones[index];
    }

    /** The zone a definition or operator names; an unknown id fails. */
    public Zone zone(String id) {
        for (Zone z : zones) if (z.id.equals(id)) return z;
        throw new IllegalStateException("Unknown zone " + id);
    }
}
