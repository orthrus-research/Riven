package cc.orthrus.riven.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Riven's own biomes (riven-biome-registry.json): their definitions and which one each habitat key
 * reports. No Minecraft types; the mod registers one biome per definition. Loading validates that
 * every habitat key Riven produces selects a biome and that every name reads as its parent's
 * category to shader packs.
 */
public final class RivenBiomeTable {
    private static final Rules RULES = Rules.load("riven-biome-registry.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    /** One biome definition. */
    public static final class Definition {
        public final int index;
        public final String id, name, parent, category, precipitation;
        public final float temperature, rainfall;
        public final List<String> types;

        /** An underground biome's vanilla mobs (riven-biome-registry.json spawnTables). */
        public final List<Spawn> monsters, ambient;

        Definition(int index, JsonObject j, Map<String, String> categories, JsonObject tables) {
            this.index = index;
            id = j.get("id").getAsString();
            name = j.get("name").getAsString();
            parent = j.get("parent").getAsString();
            category = categories.get(parent);
            if (category == null)
                throw new IllegalStateException("Unknown biome parent: " + parent);
            temperature = (float) Rules.number(j, "temperature", -1, 2.5);
            rainfall = (float) Rules.number(j, "rainfall", 0, 1);
            precipitation = j.get("precipitation").getAsString();
            if (!precipitation.equals("rain")
                    && !precipitation.equals("snow")
                    && !precipitation.equals("none"))
                throw new IllegalStateException("Precipitation of " + id + ": " + precipitation);
            List<String> t = new ArrayList<>();
            for (JsonElement e : j.getAsJsonArray("types")) t.add(e.getAsString());
            types = Collections.unmodifiableList(t);
            JsonObject table = null;
            if (j.has("spawns")) {
                // Other parents keep their vanilla class's own spawns.
                if (!parent.equals("void"))
                    throw new IllegalStateException("Only underground biomes take spawns: " + id);
                table = tables.getAsJsonObject(j.get("spawns").getAsString());
                if (table == null) throw new IllegalStateException("Unknown spawn table for " + id);
            }
            monsters = spawns(table, "monster", MONSTERS);
            ambient = spawns(table, "ambient", AMBIENT);
        }

        private static List<Spawn> spawns(JsonObject table, String kind, Set<String> allowed) {
            List<Spawn> list = new ArrayList<>();
            if (table != null && table.has(kind))
                for (JsonElement e : table.getAsJsonArray(kind)) {
                    Spawn s = new Spawn(e.getAsJsonArray());
                    if (!allowed.contains(s.mob))
                        throw new IllegalStateException(
                                "Not a " + kind + " Riven spawns: " + s.mob);
                    list.add(s);
                }
            return Collections.unmodifiableList(list);
        }
    }

    /** One spawn entry: a vanilla mob's name, its weight and its group size. */
    public static final class Spawn {
        public final String mob;
        public final int weight, minimum, maximum;

        Spawn(JsonArray a) {
            mob = a.get(0).getAsString();
            weight = a.get(1).getAsInt();
            minimum = a.get(2).getAsInt();
            maximum = a.get(3).getAsInt();
            if (weight <= 0 || minimum < 1 || maximum < minimum)
                throw new IllegalStateException("Spawn entry for " + mob);
        }
    }

    /** The vanilla mobs underground biomes may list (the mod maps each name to its class). */
    public static final Set<String>
            MONSTERS =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    java.util.Arrays.asList(
                                            "zombie",
                                            "zombie_villager",
                                            "husk",
                                            "skeleton",
                                            "stray",
                                            "spider",
                                            "cave_spider",
                                            "creeper",
                                            "slime",
                                            "enderman",
                                            "witch"))),
            AMBIENT = Collections.singleton("bat");

    private static final List<Definition> DEFINITIONS;
    private static final Map<String, Definition> BY_ID = new HashMap<>();
    private static final Map<String, String> INLAND_LOW = new HashMap<>(),
            INLAND_HIGH = new HashMap<>(),
            HILLS = new HashMap<>(),
            PEAKS = new HashMap<>(),
            COAST = new HashMap<>(),
            MOUTHS = new HashMap<>();
    private static final Set<String> INLAND = new LinkedHashSet<>();
    private static final Map<String, Definition> SELECTED = new ConcurrentHashMap<>();

    static {
        JsonObject j = RULES.json;
        Map<String, String> categories = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("categories").entrySet())
            if (!e.getKey().equals("about")) categories.put(e.getKey(), e.getValue().getAsString());
        List<Definition> list = new ArrayList<>();
        JsonArray biomes = j.getAsJsonArray("biomes");
        JsonObject tables = j.getAsJsonObject("spawnTables");
        for (int i = 0; i < biomes.size(); i++) {
            Definition d = new Definition(i, biomes.get(i).getAsJsonObject(), categories, tables);
            if (BY_ID.put(d.id, d) != null)
                throw new IllegalStateException("Duplicate biome id: " + d.id);
            list.add(d);
        }
        DEFINITIONS = Collections.unmodifiableList(list);
        List<String> unsafe = new ArrayList<>();
        for (JsonElement e : j.getAsJsonArray("unsafeNameParts"))
            unsafe.add(e.getAsString().toLowerCase(Locale.ROOT));
        for (Definition d : DEFINITIONS)
            for (String part : unsafe)
                if (d.name.toLowerCase(Locale.ROOT).contains(part))
                    throw new IllegalStateException(
                            "Biome name "
                                    + d.name
                                    + " contains '"
                                    + part
                                    + "', which shader packs misread");
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("inland").entrySet()) {
            JsonObject pair = e.getValue().getAsJsonObject();
            INLAND_LOW.put(e.getKey(), known(pair.get("lowland").getAsString()));
            INLAND_HIGH.put(e.getKey(), known(pair.get("highland").getAsString()));
        }
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("rugged").entrySet()) {
            JsonObject pair = e.getValue().getAsJsonObject();
            HILLS.put(e.getKey(), known(pair.get("hills").getAsString()));
            PEAKS.put(e.getKey(), known(pair.get("peaks").getAsString()));
        }
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("coast").entrySet())
            COAST.put(e.getKey(), known(e.getValue().getAsString()));
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("mouths").entrySet())
            MOUTHS.put(e.getKey(), known(e.getValue().getAsString()));
        // Every key Riven produces must select a biome.
        for (ClimateRegion region : ClimateRegion.values())
            if (!INLAND_LOW.containsKey(region.name()))
                throw new IllegalStateException("No inland biome for " + region);
        for (TemperatureBand band : TemperatureBand.values())
            if (!HILLS.containsKey(band.name()))
                throw new IllegalStateException("No rugged biome for " + band);
        for (Map.Entry<String, JsonElement> e : Biomes.vanillaTable().entrySet())
            if (!COAST.containsKey(e.getKey()))
                throw new IllegalStateException("No coast or water biome for " + e.getKey());
        for (String mouth : Biomes.mouthNames())
            if (!MOUTHS.containsKey(mouth))
                throw new IllegalStateException("No river-mouth biome for " + mouth);
        INLAND.addAll(INLAND_LOW.values());
        INLAND.addAll(INLAND_HIGH.values());
        INLAND.addAll(HILLS.values());
        INLAND.addAll(PEAKS.values());
    }

    private RivenBiomeTable() {}

    private static String known(String id) {
        if (!BY_ID.containsKey(id)) throw new IllegalStateException("Unknown biome: " + id);
        return id;
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Definition byId(String id) {
        return BY_ID.get(id);
    }

    /** Ids of the biomes inland keys select (spawn search stays on land). */
    public static Set<String> inland() {
        return Collections.unmodifiableSet(INLAND);
    }

    /**
     * The biome a habitat key ({@link Habitats#key}) reports: {@code MOUTH/<name>}, a coast or
     * water key with an optional {@code #<band>} suffix, or {@code <REGION>/<LEVEL>/<calm|rugged>}.
     */
    public static Definition select(String key) {
        Definition d = SELECTED.get(key);
        if (d == null) {
            d = BY_ID.get(selectId(key));
            SELECTED.put(key, d);
        }
        return d;
    }

    private static String selectId(String key) {
        if (key.startsWith("MOUTH/")) return required(MOUTHS, key.substring(6), key);
        int hash = key.indexOf('#');
        if (hash > 0) return required(COAST, key.substring(0, hash), key);
        String[] parts = key.split("/");
        if (parts.length != 3) return required(COAST, key, key);
        boolean high = parts[1].equals("HIGH"), rugged = parts[2].equals("rugged");
        if (!rugged) return required(high ? INLAND_HIGH : INLAND_LOW, parts[0], key);
        String band = ClimateRegion.valueOf(parts[0]).temperature.name();
        return required(high ? PEAKS : HILLS, band, key);
    }

    private static String required(Map<String, String> table, String name, String key) {
        String id = table.get(name);
        if (id == null) throw new IllegalStateException("No biome for habitat key " + key);
        return id;
    }
}
