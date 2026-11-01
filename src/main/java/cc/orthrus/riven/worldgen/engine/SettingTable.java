package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One cavity's setting table (riven-surface.json tables): rows tried in order, each naming a
 * setting and the entries for its three layers counted down from the cavity floor: top, soil and
 * subsoil, with their depths. A layer may be "bed" (the zone's rock shows there). A row without a
 * subsoil takes the subsoil of the next row that holds and names one, so a cover laid over the
 * ground (a bar, green margins, pavement, beach sand) keeps the ground's subsoil. The last row
 * must be {@code default}, which always holds and names every layer.
 */
public final class SettingTable {
    public static final int TOP = 0, SOIL = 1, SUBSOIL = 2, LAYERS = 3;

    /** A layer whose entry is the zone's bed. */
    public static final short BED = -1;

    /** A subsoil the row takes from the next holding row that names one. */
    static final short INHERIT = -2;

    private static final String[] LAYER_NAMES = {"top", "soil", "subsoil"};
    private static final Role[] LAYER_ROLES = {Role.TOP, Role.SOIL, Role.SUBSOIL};

    public final String id;
    private final int[] settings;
    private final short[] entries;
    private final int[] depths;
    private final int deepest;

    SettingTable(String id, JsonObject json) {
        this.id = id;
        int[] defaults = depths(json.getAsJsonObject("depths"), null, id);
        int rows = json.getAsJsonArray("rows").size();
        settings = new int[rows];
        entries = new short[rows * LAYERS];
        depths = new int[rows * LAYERS];
        long named = 0;
        int deepest = 0;
        for (int r = 0; r < rows; r++) {
            JsonObject row = json.getAsJsonArray("rows").get(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : row.entrySet())
                if (!e.getKey().equals("setting")
                        && !e.getKey().equals("depths")
                        && !e.getKey().equals("top")
                        && !e.getKey().equals("soil")
                        && !e.getKey().equals("subsoil"))
                    throw new IllegalStateException(id + ": unknown row key " + e.getKey());
            int setting = Settings.index(row.get("setting").getAsString());
            if ((named & Settings.bit(setting)) != 0)
                throw new IllegalStateException(
                        id + ": setting listed twice " + row.get("setting"));
            named |= Settings.bit(setting);
            settings[r] = setting;
            int[] d = depths(row.getAsJsonObject("depths"), defaults, id);
            int total = 0;
            for (int layer = 0; layer < LAYERS; layer++) {
                JsonElement entry = row.get(LAYER_NAMES[layer]);
                short value;
                if (entry == null) {
                    if (layer != SUBSOIL)
                        throw new IllegalStateException(
                                id
                                        + ": "
                                        + Settings.name(setting)
                                        + " needs "
                                        + LAYER_NAMES[layer]);
                    value = INHERIT;
                } else if (entry.getAsString().equals("bed")) value = BED;
                else value = (short) TerrainBlock.of(entry.getAsString()).index();
                entries[r * LAYERS + layer] = value;
                depths[r * LAYERS + layer] = d[layer];
                total += d[layer];
            }
            deepest = Math.max(deepest, total);
        }
        if (rows == 0 || settings[rows - 1] != Settings.DEFAULT)
            throw new IllegalStateException(id + ": the last row must be default");
        if (entries[(rows - 1) * LAYERS + SUBSOIL] == INHERIT)
            throw new IllegalStateException(id + ": default must name its subsoil");
        this.deepest = deepest;
    }

    private static int[] depths(JsonObject json, int[] defaults, String id) {
        if (json == null) {
            if (defaults == null) throw new IllegalStateException(id + ": needs depths");
            return defaults;
        }
        int[] d = new int[LAYERS];
        for (int layer = 0; layer < LAYERS; layer++) {
            JsonElement v = json.get(LAYER_NAMES[layer]);
            if (v == null) {
                if (defaults == null)
                    throw new IllegalStateException(id + ": depths need " + LAYER_NAMES[layer]);
                d[layer] = defaults[layer];
                continue;
            }
            d[layer] = v.getAsInt();
            if (d[layer] < 0 || d[layer] > 64 || d[layer] != v.getAsDouble())
                throw new IllegalStateException(id + ": depth range " + LAYER_NAMES[layer]);
        }
        return d;
    }

    /** The row a column takes: the first whose setting holds. */
    public int row(long holds) {
        for (int r = 0; r < settings.length; r++)
            if ((holds & Settings.bit(settings[r])) != 0) return r;
        return settings.length - 1;
    }

    /** The row whose subsoil the column at this row takes. */
    public int subsoilRow(long holds, int row) {
        for (int r = row; r < settings.length; r++)
            if (entries[r * LAYERS + SUBSOIL] != INHERIT
                    && (r == row || (holds & Settings.bit(settings[r])) != 0)) return r;
        return settings.length - 1;
    }

    public int rows() {
        return settings.length;
    }

    /** The row naming a setting, or -1. */
    public int rowOf(int setting) {
        for (int r = 0; r < settings.length; r++) if (settings[r] == setting) return r;
        return -1;
    }

    /** The setting a row names. */
    public int setting(int row) {
        return settings[row];
    }

    /** A layer's entry index, or {@link #BED}; never inherited for a subsoil row. */
    public short entry(int row, int layer) {
        return entries[row * LAYERS + layer];
    }

    public int depth(int row, int layer) {
        return depths[row * LAYERS + layer];
    }

    public static Role role(int layer) {
        return LAYER_ROLES[layer];
    }

    /** The deepest any row's layers reach below its floor. */
    public int deepest() {
        return deepest;
    }
}
