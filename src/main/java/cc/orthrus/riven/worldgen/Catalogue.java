package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Riven's catalogue (riven-catalogue.json): every block the generator places, one entry each,
 * named by an id whose prefix is its kind (rock.gabbro). An entry lists candidate block states;
 * mod glue takes the first that resolves, so later candidates are the stand-ins. While the
 * generator still uses {@link TerrainBlock}, every constant has exactly one entry. Loading checks
 * the whole file, so a malformed catalogue fails at startup.
 */
public final class Catalogue {
    private static final Pattern STATE =
            Pattern.compile(
                    "[a-z0-9_]+:[a-z0-9_]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?");

    /** One catalogue entry. */
    public static final class Entry {
        public final String id, kind;
        public final List<String> tags, candidates;
        public final TerrainBlock terrain;

        private Entry(
                String id,
                String kind,
                List<String> tags,
                List<String> candidates,
                TerrainBlock terrain) {
            this.id = id;
            this.kind = kind;
            this.tags = tags;
            this.candidates = candidates;
            this.terrain = terrain;
        }
    }

    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();
    private static final Entry[] BY_TERRAIN = new Entry[TerrainBlock.values().length];

    static {
        JsonObject json = Rules.load("riven-catalogue.json", 1).json;
        JsonObject kinds = json.getAsJsonObject("kinds"), tags = json.getAsJsonObject("tags");
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("entries").entrySet()) {
            String id = e.getKey();
            JsonObject j = e.getValue().getAsJsonObject();
            String kind = j.get("kind").getAsString();
            if (!kinds.has(kind)) throw new IllegalStateException(id + ": unknown kind " + kind);
            if (!id.equals(kind) && !id.startsWith(kind + "."))
                throw new IllegalStateException(
                        id + ": id must be its kind or start with " + kind + ".");
            List<String> entryTags = new ArrayList<>();
            if (j.has("tags"))
                for (JsonElement t : j.getAsJsonArray("tags")) {
                    if (!tags.has(t.getAsString()))
                        throw new IllegalStateException(id + ": undeclared tag " + t.getAsString());
                    entryTags.add(t.getAsString());
                }
            List<String> candidates = new ArrayList<>();
            for (JsonElement c : j.getAsJsonArray("block")) {
                if (!STATE.matcher(c.getAsString()).matches())
                    throw new IllegalStateException(id + ": malformed block state " + c);
                candidates.add(c.getAsString());
            }
            if (candidates.isEmpty()) throw new IllegalStateException(id + ": no block");
            TerrainBlock terrain = TerrainBlock.valueOf(j.get("terrain").getAsString());
            if (BY_TERRAIN[terrain.ordinal()] != null)
                throw new IllegalStateException(id + ": " + terrain + " has two entries");
            Entry entry =
                    new Entry(
                            id,
                            kind,
                            Collections.unmodifiableList(entryTags),
                            Collections.unmodifiableList(candidates),
                            terrain);
            BY_ID.put(id, entry);
            BY_TERRAIN[terrain.ordinal()] = entry;
        }
        for (TerrainBlock b : TerrainBlock.values())
            if (BY_TERRAIN[b.ordinal()] == null)
                throw new IllegalStateException(b + " has no catalogue entry");
    }

    private Catalogue() {}

    /** The entry that stands for a terrain block. */
    public static Entry entry(TerrainBlock block) {
        return BY_TERRAIN[block.ordinal()];
    }

    /** The entry with this id, or null. */
    public static Entry entry(String id) {
        return BY_ID.get(id);
    }

    /** Every entry, in catalogue order. */
    public static Collection<Entry> entries() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }
}
