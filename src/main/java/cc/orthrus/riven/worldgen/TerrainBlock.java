package cc.orthrus.riven.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One entry of Riven's catalogue (riven-catalogue.json), which lists every block the generator
 * places. An entry's id is its kind, or the kind, a dot and a name (rock.gabbro); definitions name
 * entries by id ({@link #of}), and the constants are the entries the generator's own code names.
 * An entry lists candidate block states: mod glue takes the first that resolves, so later
 * candidates are the stand-ins. Each entry has one instance, so entries compare by identity.
 * Loading checks the whole file, so a malformed catalogue fails at startup.
 */
public final class TerrainBlock {
    private static final Pattern STATE =
            Pattern.compile(
                    "[a-z0-9_]+:[a-z0-9_]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\\])?");
    private static final Map<String, TerrainBlock> BY_ID = new LinkedHashMap<>();
    private static final TerrainBlock[] ALL = load();

    public static final TerrainBlock AIR = of("air"),
            WATER = of("fluid.water"),
            LAVA = of("fluid.lava"),
            DIRT = of("soil.dirt"),
            GRASS = of("soil.grass"),
            COARSE_DIRT = of("soil.coarse_dirt"),
            MUD = of("soil.mud"),
            SAND = of("sediment.sand"),
            GRAVEL = of("sediment.gravel"),
            CLAY = of("sediment.clay"),
            SALT_SAND = of("sediment.salt_sand"),
            VOLCANIC_ASH = of("sediment.volcanic_ash"),
            SNOW = of("ice.snow"),
            ICE = of("ice.ice"),
            PACKED_ICE = of("ice.packed_ice"),
            BEDROCK = of("rock.bedrock"),
            STONE = of("rock.stone"),
            GRANITE = of("rock.granite"),
            DIORITE = of("rock.diorite"),
            ANDESITE = of("rock.andesite"),
            SANDSTONE = of("rock.sandstone"),
            RED_SANDSTONE = of("rock.red_sandstone"),
            MAGMA = of("rock.magma"),
            DRIPSTONE_BLOCK = of("rock.dripstone_block"),
            DRIPSTONE_UP = of("formation.dripstone_up"),
            DRIPSTONE_DOWN = of("formation.dripstone_down"),
            ICE_UP = of("formation.ice_up"),
            ICE_DOWN = of("formation.ice_down"),
            SALT_UP = of("formation.salt_up"),
            SALT_DOWN = of("formation.salt_down"),
            BASALT_UP = of("formation.basalt_up"),
            BASALT_DOWN = of("formation.basalt_down"),
            ROOT = of("plant.root"),
            INK_CAP = of("plant.ink_cap"),
            PALE_CAVE_MUSHROOM = of("plant.pale_cave_mushroom"),
            BROWN_MUSHROOM = of("plant.brown_mushroom"),
            RED_MUSHROOM = of("plant.red_mushroom"),
            MINERS_DELIGHT = of("plant.miners_delight"),
            GLOWING_MUSHROOM = of("plant.glowing_mushroom"),
            ALGAE = of("plant.algae"),
            COBWEB = of("decor.cobweb");
    public final String id, kind;
    public final List<String> tags, candidates;
    private final int index;
    private final String stoneId, rockId;

    private TerrainBlock(
            String id,
            String kind,
            List<String> tags,
            List<String> candidates,
            int index,
            String stoneId,
            String rockId) {
        this.id = id;
        this.kind = kind;
        this.tags = tags;
        this.candidates = candidates;
        this.index = index;
        this.stoneId = stoneId;
        this.rockId = rockId;
    }

    private static TerrainBlock[] load() {
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
            // A host stone's or realm rock's content id is its own block's: its first candidate.
            String first = candidates.get(0);
            String riven =
                    first.startsWith("riven:") ? first.substring(6).split("\\[", 2)[0] : null;
            boolean stone = entryTags.contains("host-stone"), rock = entryTags.contains("realm");
            if ((stone || rock) && riven == null)
                throw new IllegalStateException(id + ": its first candidate must be Riven's block");
            BY_ID.put(
                    id,
                    new TerrainBlock(
                            id,
                            kind,
                            Collections.unmodifiableList(entryTags),
                            Collections.unmodifiableList(candidates),
                            BY_ID.size(),
                            stone ? riven : null,
                            rock ? riven : null));
        }
        return BY_ID.values().toArray(new TerrainBlock[0]);
    }

    /** The entry with this id; an unknown id fails, as a definition naming it must. */
    public static TerrainBlock of(String id) {
        TerrainBlock block = BY_ID.get(id);
        if (block == null) throw new IllegalArgumentException("No catalogue entry " + id);
        return block;
    }

    /** Every entry, in catalogue order. */
    public static TerrainBlock[] values() {
        return ALL.clone();
    }

    /** The entry's position in catalogue order, for tables indexed by entry. */
    public int index() {
        return index;
    }

    /** The riven-content.json id of a Riven host stone, or null. */
    public String stoneId() {
        return stoneId;
    }

    /** The riven-content.json rocks id of a deep-realm or boundary rock, or null. */
    public String rockId() {
        return rockId;
    }

    @Override
    public String toString() {
        return id;
    }
}
