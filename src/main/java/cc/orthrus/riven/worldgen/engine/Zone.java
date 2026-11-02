package cc.orthrus.riven.worldgen.engine;

import cc.orthrus.riven.worldgen.TerrainBlock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One vertical level of the world (riven-zones.json): its top and bottom boundaries, its bed
 * source, the fluids that fill its open space, its cavities, the carve families that may cut it
 * and its sub-zones. Everything a zone names is from a fixed menu and resolves when definitions
 * load.
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
                if (value < 1) throw new IllegalStateException(where + ": depth below 1");
            } else if (json.has("horizon")) {
                kind = HORIZON;
                value = Horizons.index(json.get("horizon").getAsString());
            } else throw new IllegalStateException(where + ": unknown boundary " + json);
        }

        /** The depth a depth boundary lies at, or -1 for another kind. */
        public int depth() {
            return kind == DEPTH ? value : -1;
        }

        /**
         * The boundary's Y in a column, or {@link Horizons#ABSENT}: a boundary at depth n is the
         * lowest Y whose depth below the ground is under n, so n blocks from the ground down lie
         * above it.
         */
        public int resolve(int[] horizons) {
            switch (kind) {
                case Y:
                    return value;
                case DEPTH:
                    int ground = horizons[Horizons.GROUND];
                    return ground == Horizons.ABSENT ? Horizons.ABSENT : ground - value + 1;
                default:
                    return horizons[value];
            }
        }
    }

    /**
     * Open space with ground: its floor horizon (where the setting table's layers count down
     * from), the horizon its fluid fills to, its setting table, and the roof band every carve but
     * an entrance keeps under its floor (thicker where the column is wet).
     */
    public static final class Cavity {
        public final String id;
        public final int floor, level;
        public final SettingTable settings;
        private final int roofDry, roofWet;

        Cavity(JsonObject json, Map<String, SettingTable> tables, String where) {
            keys(json, where, "id", "floor", "fluid", "settings", "biome", "roof");
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
            JsonObject roof = json.getAsJsonObject("roof");
            keys(roof, where + "." + id + ".roof", "dry", "wet");
            roofDry = roof.get("dry").getAsInt();
            roofWet = roof.get("wet").getAsInt();
            if (roofDry < 1 || roofWet < roofDry)
                throw new IllegalStateException(where + ": roof band needs 1 <= dry <= wet");
        }

        /** The roof band's blocks under the floor in a column with these flags. */
        public int roof(int flags) {
            return (flags & (1 << Flags.WET)) != 0 ? roofWet : roofDry;
        }

        private static int horizon(JsonObject json, String where) {
            if (json == null || json.size() != 1 || !json.has("horizon"))
                throw new IllegalStateException(where + ": needs a horizon");
            return Horizons.index(json.get("horizon").getAsString());
        }
    }

    /**
     * A sub-zone (D8): a band of its zone between its own boundaries (the zone's where it names
     * none), naming the carve forms that may cut it. Where sub-zones overlap the first that holds
     * wins; where none holds the zone itself does.
     */
    public static final class Sub {
        public final String id;
        public final int index;
        private final Boundary top, bottom;
        private final String[] forms;

        Sub(JsonObject json, int index, String where) {
            id = json.get("id").getAsString();
            where = where + "." + id;
            for (Map.Entry<String, JsonElement> e : json.entrySet())
                if (!java.util.Arrays.asList("id", "top", "bottom", "carve").contains(e.getKey()))
                    throw new IllegalStateException(where + ": unknown key " + e.getKey());
            this.index = index;
            top = json.has("top") ? new Boundary(json.getAsJsonObject("top"), where) : null;
            bottom =
                    json.has("bottom") ? new Boundary(json.getAsJsonObject("bottom"), where) : null;
            JsonArray c = json.getAsJsonArray("carve");
            if (c == null) throw new IllegalStateException(where + ": missing carve");
            forms = new String[c.size()];
            for (int i = 0; i < forms.length; i++) forms[i] = c.get(i).getAsString();
        }

        /** The depth its bottom lies at, where its bottom is a depth boundary, or -1. */
        public int bottomDepth() {
            return bottom == null ? -1 : bottom.depth();
        }

        /** The carve forms this sub-zone allows, as its definition names them. */
        public String[] forms() {
            return forms.clone();
        }

        /** Whether this sub-zone allows a carve form. */
        public boolean allows(String form) {
            for (String f : forms) if (f.equals(form)) return true;
            return false;
        }
    }

    /** Carve families a zone may name. */
    private static final String[] FAMILIES = {"field", "sphere", "segment", "line", "entrance"};

    public final String id;
    public final int index;
    public final Boundary top, bottom;
    public final String bed;

    /** The catalogue entries of this zone's fluid, its frozen form, and lava below the lava horizon. */
    public final short fluid, frozen, lava;

    private final Cavity[] cavities;
    private final String[] carves;
    private final Sub[] subs;

    /**
     * A zone; without its own bottom it ends at {@code below}, the next zone's top. Its sub-zones
     * take indexes from {@code firstSub} on.
     */
    Zone(
            JsonObject json,
            int index,
            Boundary below,
            Map<String, SettingTable> tables,
            int firstSub) {
        id = json.get("id").getAsString();
        String where = "zone " + id;
        keys(
                json,
                where,
                "id",
                "top",
                "bottom",
                "bed",
                "fluid",
                "frozen",
                "lava",
                "carve",
                "cavities",
                "zones");
        this.index = index;
        top = new Boundary(json.getAsJsonObject("top"), where + ".top");
        bottom =
                json.has("bottom")
                        ? new Boundary(json.getAsJsonObject("bottom"), where + ".bottom")
                        : below;
        if (bottom == null) throw new IllegalStateException(where + ": needs a bottom");
        bed = json.get("bed").getAsString();
        fluid = (short) TerrainBlock.of(json.get("fluid").getAsString()).index();
        frozen = (short) TerrainBlock.of(json.get("frozen").getAsString()).index();
        lava = (short) TerrainBlock.of(json.get("lava").getAsString()).index();
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
            if (!java.util.Arrays.asList(FAMILIES).contains(carves[i]))
                throw new IllegalStateException(where + ": unknown carve " + carves[i]);
        }
        JsonArray z = json.has("zones") ? json.getAsJsonArray("zones") : new JsonArray();
        subs = new Sub[z.size()];
        if (subs.length > 15) throw new IllegalStateException(where + ": at most 15 sub-zones");
        for (int i = 0; i < subs.length; i++) {
            subs[i] = new Sub(z.get(i).getAsJsonObject(), firstSub + i, where);
            for (int j = 0; j < i; j++)
                if (subs[j].id.equals(subs[i].id))
                    throw new IllegalStateException(where + ": duplicate sub-zone " + subs[i].id);
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

    public int subs() {
        return subs.length;
    }

    public Sub sub(int i) {
        return subs[i];
    }

    /** The sub-zone an operator names; an unknown id fails. */
    public Sub sub(String id) {
        for (Sub s : subs) if (s.id.equals(id)) return s;
        throw new IllegalStateException("Zone " + this.id + " has no sub-zone " + id);
    }

    /** Upper bound on the bands {@link #bands} writes. */
    public int maximumBands() {
        return 2 * subs.length + 1;
    }

    /**
     * Resolves a column's sub-zones over [bottom, top): writes bands bottom-up, band i covering
     * [bottoms[i], bottoms[i + 1]) and held by zone index zones[i] (this zone where no sub-zone
     * holds), and returns their count. Adjacent bands differ. A sub-zone whose boundary reads an
     * absent horizon is absent (D17).
     */
    public int bands(int[] horizons, int bottom, int top, int[] bottoms, byte[] zones) {
        int n = subs.length;
        // Each sub-zone's extent, then every boundary inside the zone as a candidate band edge.
        int[] lo = new int[n], hi = new int[n], edges = new int[2 * n + 2];
        int e = 0;
        edges[e++] = bottom;
        for (int i = 0; i < n; i++) {
            int b = subs[i].bottom == null ? bottom : subs[i].bottom.resolve(horizons),
                    t = subs[i].top == null ? top : subs[i].top.resolve(horizons);
            if (b == Horizons.ABSENT || t == Horizons.ABSENT) {
                lo[i] = hi[i] = bottom;
                continue;
            }
            lo[i] = Math.max(b, bottom);
            hi[i] = Math.min(t, top);
            if (lo[i] > bottom && lo[i] < top) edges[e++] = lo[i];
            if (hi[i] > bottom && hi[i] < top) edges[e++] = hi[i];
        }
        java.util.Arrays.sort(edges, 0, e);
        int count = 0;
        for (int i = 0; i < e; i++) {
            int y = edges[i];
            if (i > 0 && y == edges[i - 1]) continue;
            int holder = index;
            for (int s = 0; s < n; s++)
                if (y >= lo[s] && y < hi[s]) {
                    holder = subs[s].index;
                    break;
                }
            if (count > 0 && zones[count - 1] == (byte) holder) continue;
            bottoms[count] = y;
            zones[count++] = (byte) holder;
        }
        bottoms[count] = top;
        return count;
    }

    static void keys(JsonObject json, String where, String... allowed) {
        for (Map.Entry<String, JsonElement> e : json.entrySet())
            if (!java.util.Arrays.asList(allowed).contains(e.getKey()))
                throw new IllegalStateException(where + ": unknown key " + e.getKey());
        for (String key : allowed)
            if (!json.has(key) && !key.equals("bottom") && !key.equals("zones"))
                throw new IllegalStateException(where + ": missing " + key);
    }
}
