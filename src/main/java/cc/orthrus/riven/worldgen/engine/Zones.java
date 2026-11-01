package cc.orthrus.riven.worldgen.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The zone tree (riven-zones.json), top-down. A zone's bottom is the next zone's top unless it
 * says otherwise, so each boundary is declared once; the last zone names its own bottom. The
 * crust is the only zone until the realms join (B4). Zones take indexes 0 to count - 1 in order,
 * and their sub-zones the indexes after them, so a run's zone byte names either.
 */
public final class Zones {
    private final Zone[] zones;
    private final String[] names;

    Zones(JsonObject json, Map<String, SettingTable> tables) {
        JsonArray a = json.getAsJsonArray("zones");
        if (a.size() == 0) throw new IllegalStateException("Zones: at least one");
        zones = new Zone[a.size()];
        int[] first = new int[zones.length];
        int next = zones.length;
        for (int i = 0; i < zones.length; i++) {
            first[i] = next;
            JsonObject z = a.get(i).getAsJsonObject();
            next += z.has("zones") ? z.getAsJsonArray("zones").size() : 0;
        }
        if (next > 127) throw new IllegalStateException("Zones: at most 127 with sub-zones");
        // Bottom-up, so a zone without its own bottom ends at the next zone's top.
        for (int i = zones.length - 1; i >= 0; i--)
            zones[i] =
                    new Zone(
                            a.get(i).getAsJsonObject(),
                            i,
                            i + 1 < zones.length ? zones[i + 1].top : null,
                            tables,
                            first[i]);
        names = new String[next];
        for (Zone z : zones) {
            names[z.index] = z.id;
            for (int s = 0; s < z.subs(); s++) names[z.sub(s).index] = z.sub(s).id;
        }
        for (int i = 0; i < names.length; i++)
            for (int j = 0; j < i; j++)
                if (names[j].equals(names[i]))
                    throw new IllegalStateException("Zones: duplicate " + names[i]);
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

    /** The id of a zone or sub-zone index, as a run's zone byte holds it. */
    public String name(int index) {
        return names[index];
    }
}
