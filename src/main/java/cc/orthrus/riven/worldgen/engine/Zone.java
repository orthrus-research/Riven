package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One vertical level of the world (riven-zones.json): its top and bottom boundaries, its bed
 * source, the fluid that fills its open space and its cavities. Everything a zone names is from a
 * fixed menu and resolves when definitions load.
 */
public final class Zone {
    /** A zone boundary: a fixed Y, a depth below the column's ground, or a named horizon. */
    public static final class Boundary {
        private static final int Y = 0, DEPTH = 1, HORIZON = 2;
        private final int kind, value;

        Boundary(JsonObject json, String where) {
            if (json.size() != 1) throw new IllegalStateException(where + ": one boundary kind");
            if (json.has("y")) {
                kind = Y;
                value = json.get("y").getAsInt();
            } else if (json.has("depth")) {
                kind = DEPTH;
                value = json.get("depth").getAsInt();
                if (value < 0) throw new IllegalStateException(where + ": negative depth");
            } else if (json.has("horizon")) {
                kind = HORIZON;
                value = Horizons.index(json.get("horizon").getAsString());
            } else throw new IllegalStateException(where + ": unknown boundary " + json);
        }

        /** The boundary's Y in a column, or {@link Horizons#ABSENT}. */
        public int resolve(int[] horizons) {
            switch (kind) {
                case Y:
                    return value;
                case DEPTH:
                    int ground = horizons[Horizons.GROUND];
                    return ground == Horizons.ABSENT ? Horizons.ABSENT : ground - value;
                default:
                    return horizons[value];
            }
        }
    }

    /**
     * Open space with ground: its floor horizon (where the setting table's layers count down
     * from), the horizon its fluid fills to, and its setting table.
     */
    public static final class Cavity {
        public final String id;
        public final int floor, level;
        public final SettingTable settings;

        Cavity(JsonObject json, Map<String, SettingTable> tables, String where) {
            keys(json, where, "id", "floor", "fluid", "settings", "biome");
            id = json.get("id").getAsString();
            floor = horizon(json.getAsJsonObject("floor"), where + "." + id + ".floor");
            level =
                    horizon(
                            json.getAsJsonObject("fluid").getAsJsonObject("level"),
                            where + "." + id + ".fluid");
            String table = json.get("settings").getAsString();
            settings = tables.get(table);
            if (settings == null)
                throw new IllegalStateException(where + ": no setting table " + table);
            // Biomes come with provinces (S7): every crust cavity reports its column's biome.
            if (!json.get("biome").getAsString().equals("column"))
                throw new IllegalStateException(where + ": biome must be column");
        }

        private static int horizon(JsonObject json, String where) {
            if (json == null || json.size() != 1 || !json.has("horizon"))
                throw new IllegalStateException(where + ": needs a horizon");
            return Horizons.index(json.get("horizon").getAsString());
        }
    }

    /** Carve families a zone may name; the temporary carve wraps today's caves (B2 replaces it). */
    private static final String[] CARVES = {"caves"};

    public final String id;
    public final int index;
    public final Boundary top, bottom;
    public final String bed;

    /** The catalogue entry of the fluid in this zone's open space. */
    public final short fluid;

    private final Cavity[] cavities;
    private final String[] carves;

    /** A zone; without its own bottom it ends at {@code below}, the next zone's top. */
    Zone(JsonObject json, int index, Boundary below, Map<String, SettingTable> tables) {
        id = json.get("id").getAsString();
        String where = "zone " + id;
        keys(json, where, "id", "top", "bottom", "bed", "fluid", "carve", "cavities");
        this.index = index;
        top = new Boundary(json.getAsJsonObject("top"), where + ".top");
        bottom =
                json.has("bottom")
                        ? new Boundary(json.getAsJsonObject("bottom"), where + ".bottom")
                        : below;
        if (bottom == null) throw new IllegalStateException(where + ": needs a bottom");
        bed = json.get("bed").getAsString();
        fluid = (short) TerrainBlock.of(json.get("fluid").getAsString()).index();
        JsonArray c = json.getAsJsonArray("cavities");
        cavities = new Cavity[c.size()];
        for (int i = 0; i < cavities.length; i++)
            cavities[i] = new Cavity(c.get(i).getAsJsonObject(), tables, where);
        if (cavities.length != 1)
            throw new IllegalStateException(where + ": one cavity until realm cavities (B4)");
        JsonArray k = json.getAsJsonArray("carve");
        carves = new String[k.size()];
        for (int i = 0; i < carves.length; i++) {
            carves[i] = k.get(i).getAsString();
            if (!java.util.Arrays.asList(CARVES).contains(carves[i]))
                throw new IllegalStateException(where + ": unknown carve " + carves[i]);
        }
    }

    public int cavities() {
        return cavities.length;
    }

    public Cavity cavity(int index) {
        return cavities[index];
    }

    /** Whether this zone names a carve family. */
    public boolean carves(String family) {
        for (String c : carves) if (c.equals(family)) return true;
        return false;
    }

    static void keys(JsonObject json, String where, String... allowed) {
        for (Map.Entry<String, JsonElement> e : json.entrySet())
            if (!java.util.Arrays.asList(allowed).contains(e.getKey()))
                throw new IllegalStateException(where + ": unknown key " + e.getKey());
        for (String key : allowed)
            if (!json.has(key) && !key.equals("bottom"))
                throw new IllegalStateException(where + ": missing " + key);
    }
}
