package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resource mappings (riven-mappings.json, integration layers, config/riven/mappings/*.json),
 * resolved once per world into its snapshot (data/riven/definitions.json). Content is a pure
 * function of the seed and the world's recorded definitions: a world always loads its snapshot,
 * whatever the configuration says later.
 */
@SuppressWarnings("deprecation") // Keep the Gson API shared with the Forge fallback, as Rules does.
final class Mappings {
    private static final Rules RULES = Rules.load("riven-mappings.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;
    private static final Logger LOG = LogManager.getLogger("riven");
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A world's resolved definitions. */
    static final class Snapshot {
        final String fingerprint;
        final List<String> layers;
        final Map<String, List<String>> candidates;
        final Map<String, IBlockState> roles;
        final Map<String, String> fallbacks;
        final Map<String, int[]> colours;

        private Snapshot(
                List<String> layers,
                Map<String, List<String>> candidates,
                Map<String, int[]> colours) {
            this.layers = Collections.unmodifiableList(layers);
            this.candidates = Collections.unmodifiableMap(candidates);
            this.colours = Collections.unmodifiableMap(colours);
            Map<String, IBlockState> resolved = new TreeMap<>();
            Map<String, String> fell = new TreeMap<>();
            for (Map.Entry<String, List<String>> e : candidates.entrySet()) {
                IBlockState state = null;
                for (int i = 0; i < e.getValue().size() && state == null; i++) {
                    state = parse(e.getValue().get(i));
                    if (state != null && i > 0) fell.put(e.getKey(), e.getValue().get(i));
                }
                if (state == null)
                    throw new IllegalStateException(
                            "Role " + e.getKey() + " resolves to no loaded block: " + e.getValue());
                resolved.put(e.getKey(), state);
            }
            roles = Collections.unmodifiableMap(resolved);
            fallbacks = Collections.unmodifiableMap(fell);
            fingerprint = sha256(GSON.toJson(canonical()));
        }

        /** The block a role resolves to. */
        IBlockState role(String key) {
            IBlockState state = roles.get(key);
            if (state == null) throw new IllegalStateException("Unknown role: " + key);
            return state;
        }

        /** The snapshot as stored: layers, role candidates, colours (fingerprint excluded). */
        private JsonObject canonical() {
            JsonObject out = new JsonObject();
            out.addProperty("schema", 1);
            JsonArray l = new JsonArray();
            for (String layer : layers) l.add(layer);
            out.add("layers", l);
            JsonObject r = new JsonObject();
            for (Map.Entry<String, List<String>> e : candidates.entrySet()) {
                JsonArray c = new JsonArray();
                for (String s : e.getValue()) c.add(s);
                r.add(e.getKey(), c);
            }
            out.add("roles", r);
            JsonObject b = new JsonObject();
            for (Map.Entry<String, int[]> e : new TreeMap<>(colours).entrySet()) {
                JsonObject c = new JsonObject();
                if (e.getValue()[0] >= 0) c.addProperty("grass", hex(e.getValue()[0]));
                if (e.getValue()[1] >= 0) c.addProperty("foliage", hex(e.getValue()[1]));
                b.add(e.getKey(), c);
            }
            out.add("biomes", b);
            return out;
        }
    }

    private Mappings() {}

    /**
     * The world's snapshot: loaded from its save when present, else resolved from the current
     * layers and written there. Applies its colours to Riven's biomes.
     */
    static Snapshot load(World world) {
        File file =
                new File(
                        world.getSaveHandler().getWorldDirectory(),
                        "data" + File.separator + "riven" + File.separator + "definitions.json");
        Snapshot snapshot;
        try {
            if (file.isFile()) {
                snapshot =
                        read(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                LOG.info(
                        "Riven definitions {} loaded from the world ({})",
                        snapshot.fingerprint,
                        file);
            } else {
                snapshot = resolve();
                JsonObject stored = snapshot.canonical();
                stored.addProperty("fingerprint", snapshot.fingerprint);
                Files.createDirectories(file.getParentFile().toPath());
                Files.write(file.toPath(), GSON.toJson(stored).getBytes(StandardCharsets.UTF_8));
                LOG.info(
                        "Riven definitions {} resolved from layers {} and recorded in the world ({})",
                        snapshot.fingerprint,
                        snapshot.layers,
                        file);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read or record Riven definitions at " + file, e);
        }
        if (!snapshot.fallbacks.isEmpty())
            LOG.warn("Riven roles resolved to fallbacks: {}", snapshot.fallbacks);
        RivenBiomes.applyColours(snapshot.colours);
        return snapshot;
    }

    /** A snapshot from the current layers, without touching any world (for /riven mappings). */
    static Snapshot resolve() {
        List<String> layers = new ArrayList<>();
        Map<String, List<String>> roles = new TreeMap<>();
        Map<String, int[]> colours = new TreeMap<>();
        apply(RULES.json, layers, roles, colours);
        for (JsonElement e : RULES.json.getAsJsonArray("integrations")) {
            JsonObject integration = e.getAsJsonObject();
            if (!requires(integration)) continue;
            apply(packaged(integration.get("resource").getAsString()), layers, roles, colours);
        }
        File[] pack =
                new File(Loader.instance().getConfigDir(), "riven" + File.separator + "mappings")
                        .listFiles((dir, name) -> name.endsWith(".json"));
        if (pack != null) {
            Arrays.sort(pack);
            for (File f : pack) {
                JsonObject layer;
                try {
                    layer =
                            new JsonParser()
                                    .parse(
                                            new String(
                                                    Files.readAllBytes(f.toPath()),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject();
                } catch (IOException | RuntimeException ex) {
                    throw new IllegalStateException("Unreadable mapping layer " + f, ex);
                }
                if (!requires(layer)) {
                    LOG.info(
                            "Skipped mapping layer {}: requires {}",
                            f.getName(),
                            layer.get("requires"));
                    continue;
                }
                apply(layer, layers, roles, colours);
            }
        }
        return new Snapshot(layers, roles, colours);
    }

    private static Snapshot read(String text) {
        JsonObject j = new JsonParser().parse(text).getAsJsonObject();
        List<String> layers = new ArrayList<>();
        for (JsonElement e : j.getAsJsonArray("layers")) layers.add(e.getAsString());
        Map<String, List<String>> roles = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("roles").entrySet())
            roles.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
        Map<String, int[]> colours = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet())
            colours.put(e.getKey(), colours(e.getValue().getAsJsonObject(), new int[] {-1, -1}));
        Snapshot s = new Snapshot(layers, roles, colours);
        if (j.has("fingerprint") && !j.get("fingerprint").getAsString().equals(s.fingerprint))
            LOG.warn("Riven definitions were edited after recording; using them as they stand");
        return s;
    }

    private static void apply(
            JsonObject layer,
            List<String> layers,
            Map<String, List<String>> roles,
            Map<String, int[]> colours) {
        layers.add(layer.get("layer").getAsString());
        if (layer.has("roles"))
            for (Map.Entry<String, JsonElement> e : layer.getAsJsonObject("roles").entrySet())
                roles.put(e.getKey(), strings(e.getValue().getAsJsonArray()));
        if (layer.has("biomes"))
            for (Map.Entry<String, JsonElement> e : layer.getAsJsonObject("biomes").entrySet()) {
                if (cc.orthrus.riven.worldgen.RivenBiomeTable.byId(e.getKey()) == null)
                    throw new IllegalStateException(
                            "Mapping layer "
                                    + layer.get("layer")
                                    + " names unknown biome "
                                    + e.getKey());
                int[] c = colours.getOrDefault(e.getKey(), new int[] {-1, -1});
                colours.put(e.getKey(), colours(e.getValue().getAsJsonObject(), c.clone()));
            }
    }

    private static boolean requires(JsonObject layer) {
        if (!layer.has("requires")) return true;
        for (JsonElement mod : layer.getAsJsonArray("requires"))
            if (!Loader.isModLoaded(mod.getAsString())) return false;
        return true;
    }

    private static JsonObject packaged(String resource) {
        try (InputStream in = Mappings.class.getResourceAsStream("/" + resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new JsonParser()
                    .parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    private static List<String> strings(JsonArray a) {
        List<String> r = new ArrayList<>();
        for (JsonElement e : a) r.add(e.getAsString());
        return r;
    }

    private static int[] colours(JsonObject j, int[] into) {
        if (j.has("grass")) into[0] = colour(j.get("grass").getAsString());
        if (j.has("foliage")) into[1] = colour(j.get("foliage").getAsString());
        return into;
    }

    private static int colour(String hex) {
        if (!hex.matches("#[0-9A-Fa-f]{6}"))
            throw new IllegalStateException("Colour must be #RRGGBB: " + hex);
        return Integer.parseInt(hex.substring(1), 16);
    }

    private static String hex(int colour) {
        return String.format("#%06X", colour & 0xFFFFFF);
    }

    /**
     * A block state from {@code modid:block} or {@code modid:block[property=value,...]}, or null when
     * the block is not loaded or a property does not fit it.
     */
    static IBlockState parse(String text) {
        int open = text.indexOf('[');
        String name = open < 0 ? text : text.substring(0, open);
        Block block = Block.getBlockFromName(name);
        if (block == null) return null;
        IBlockState state = block.getDefaultState();
        if (open < 0) return state;
        if (!text.endsWith("]")) return null;
        for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) return null;
            IProperty<?> property = block.getBlockState().getProperty(kv[0].trim());
            if (property == null) return null;
            state = with(state, property, kv[1].trim());
            if (state == null) return null;
        }
        return state;
    }

    private static <T extends Comparable<T>> IBlockState with(
            IBlockState state, IProperty<T> property, String value) {
        com.google.common.base.Optional<T> parsed = property.parseValue(value);
        return parsed.isPresent() ? state.withProperty(property, parsed.get()) : null;
    }

    private static String sha256(String text) {
        try {
            StringBuilder hash = new StringBuilder();
            for (byte b :
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)))
                hash.append(String.format("%02x", b & 255));
            return hash.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
