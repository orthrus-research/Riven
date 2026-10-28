package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RivenBiomeTable;
import cc.orthrus.riven.worldgen.Rules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.oredict.OreDictionary;

import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The client half of /riven shaderprops. It copies the active shader pack to {@code <pack>-riven}
 * with Riven's world written into it. Modded blocks join the block.properties entry of the vanilla
 * block they behave like, and riven: biomes join the numeric biome lists of their vanilla twins.
 * It also reports the biome_category Demonica gives each riven: biome. The original pack is never
 * changed. Client only: the server asks for it over {@link RivenNetwork}.
 */
final class ShaderProps {
    private static final Rules RULES = Rules.load("riven-shaderprops.json", 1);

    /** Demonica defines MC_VERSION 11202; packs branch their 1.12 block names on it. */
    private static final int MC_VERSION = 11202;

    private static final String SUFFIX = "-riven", REPORT = "riven-shaderprops.txt";
    private static final String BLOCKS = "shaders/block.properties",
            SHADERS = "shaders/shaders.properties";

    private ShaderProps() {}

    /** Runs the export and tells the player; called on the client thread. */
    static void run() {
        Minecraft mc = Minecraft.getMinecraft();
        String result;
        try {
            result = export(mc.gameDir.toPath());
        } catch (IOException | RuntimeException e) {
            LogManager.getLogger("riven").error("Riven shaderprops failed", e);
            result = "Riven shaderprops failed: " + e;
        }
        LogManager.getLogger("riven").info(result);
        if (mc.player != null) mc.player.sendMessage(new TextComponentString(result));
    }

    static String export(Path game) throws IOException {
        Properties settings = new Properties();
        Path config = game.resolve("config/shaders.properties");
        if (Files.isRegularFile(config))
            try (Reader in = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                settings.load(in);
            }
        String pack = settings.getProperty("shaderPack", "").trim();
        if (!"true".equals(settings.getProperty("enableShaders")) || pack.isEmpty())
            return "Riven shaderprops: no shader pack is active (Demonica's config/shaders.properties)";
        String stem = pack.endsWith(".zip") ? pack.substring(0, pack.length() - 4) : pack;
        if (stem.endsWith(SUFFIX))
            return "Riven shaderprops: "
                    + pack
                    + " is already a Riven copy; select its original pack";
        Map<String, byte[]> files = read(game.resolve("shaderpacks").resolve(pack));
        StringBuilder report = new StringBuilder("Riven shaderprops for " + pack + "\n");

        String blocks = text(files.get(BLOCKS));
        Map<String, String> anchors = ShaderPackText.anchors(blocks, MC_VERSION);
        Map<String, Set<String>> byId = new TreeMap<>();
        Map<String, Integer> perAnchor = new TreeMap<>();
        Set<String> missing = new TreeSet<>();
        for (Block block : sorted()) {
            ResourceLocation key = block.getRegistryName();
            if (key == null || key.getNamespace().equals("minecraft")) continue;
            for (String[] match : classify(block, key)) {
                String id = anchors.get(match[0]);
                if (id == null) {
                    missing.add(match[0]);
                    continue;
                }
                byId.computeIfAbsent(id, k -> new TreeSet<>()).add(match[1]);
                perAnchor.merge(match[0], 1, Integer::sum);
            }
        }
        ShaderPackText.Result b = ShaderPackText.appendBlocks(blocks, MC_VERSION, byId);
        report.append(
                String.format(
                        "blocks: %d added to %d entries %s; anchors the pack lacks: %s; unjudged conditions: %d%n",
                        b.additions, b.lists, perAnchor, missing, b.unknownConditions));
        for (Map.Entry<String, Set<String>> e : byId.entrySet())
            report.append("  block.")
                    .append(e.getKey())
                    .append(" += ")
                    .append(e.getValue())
                    .append('\n');

        Map<Integer, Set<Integer>> twins = new TreeMap<>();
        Set<Integer> rivenIds = new TreeSet<>();
        Set<String> untwinned = new TreeSet<>();
        List<String> categories = new ArrayList<>();
        int intended = 0;
        Map<Biome, String> actual = demonicaCategories();
        for (Biome biome : RivenBiomes.list()) {
            RivenBiomeTable.Definition d = ((RivenBiomeTypes.RivenBiome) biome).definition();
            rivenIds.add(Biome.getIdForBiome(biome));
            Biome twin = twin(d);
            if (twin == null) untwinned.add(d.id);
            else
                twins.computeIfAbsent(Biome.getIdForBiome(twin), k -> new TreeSet<>())
                        .add(Biome.getIdForBiome(biome));
            if (actual != null) {
                String category = actual.get(biome);
                if (d.category.equals(category)) intended++;
                else categories.add(d.id + " " + category + " (intended " + d.category + ")");
            }
        }
        ShaderPackText.Result s =
                ShaderPackText.appendBiomes(text(files.get(SHADERS)), twins, rivenIds);
        report.append(
                String.format(
                        "biomes: %d IDs added to and %d colliding IDs removed from %d numeric biome lists; twins %s; no twin: %s%n",
                        s.additions, s.removals, s.lists, twins, untwinned));
        String categoryLine =
                actual == null
                        ? "Demonica is not installed"
                        : intended + "/" + RivenBiomes.list().size() + " as intended";
        report.append("biome_category: ").append(categoryLine).append('\n');
        for (String line : categories) report.append("  ").append(line).append('\n');
        // The biome Demonica feeds shader packs right now, which in a cubic world follows the
        // camera's Y.
        String seen = demonicaCameraBiome();
        if (seen != null) {
            report.append("camera biome per Demonica: ").append(seen).append('\n');
            categoryLine += "; Demonica sees " + seen + " at the camera";
        }

        if (files.containsKey(BLOCKS)) files.put(BLOCKS, b.text.getBytes(StandardCharsets.UTF_8));
        if (files.containsKey(SHADERS)) files.put(SHADERS, s.text.getBytes(StandardCharsets.UTF_8));
        files.put(REPORT, report.toString().getBytes(StandardCharsets.UTF_8));
        Path out = game.resolve("shaderpacks").resolve(stem + SUFFIX);
        write(out, files);
        return String.format(
                "Riven shaderprops: wrote shaderpacks/%s (%d block names, %d biome IDs; biome_category %s). Select it in the shader pack menu.",
                out.getFileName(), b.additions, s.additions, categoryLine);
    }

    /**
     * The vanilla biome a riven: biome stands in for, by parent/precipitation, then parent; null
     * where the definitions give it none on purpose.
     */
    private static Biome twin(RivenBiomeTable.Definition d) {
        JsonObject twins = RULES.json.getAsJsonObject("biomeTwins");
        String key = d.parent + "/" + d.precipitation;
        if (!twins.has(key)) key = d.parent;
        if (!twins.has(key)) throw new IllegalStateException("No twin entry for " + d.id);
        if (twins.get(key).isJsonNull()) return null;
        Biome twin = Biome.REGISTRY.getObject(new ResourceLocation(twins.get(key).getAsString()));
        if (twin == null)
            throw new IllegalStateException("Unknown twin for " + d.id + ": " + twins.get(key));
        return twin;
    }

    /** The registry name of the biome Demonica's uniforms read now, or null without Demonica. */
    private static String demonicaCameraBiome() {
        try {
            Method cached =
                    Class.forName("net.coderbot.iris.uniforms.BiomeUniforms")
                            .getDeclaredMethod("getCachedBiome");
            cached.setAccessible(true);
            Object biome = cached.invoke(null);
            return biome == null ? null : String.valueOf(((Biome) biome).getRegistryName());
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    /** Demonica's own biome_category for each riven: biome, or null without Demonica. */
    private static Map<Biome, String> demonicaCategories() {
        try {
            Class<?> uniforms = Class.forName("net.coderbot.iris.uniforms.BiomeUniforms");
            Method category = uniforms.getDeclaredMethod("getBiomeCategory", Biome.class);
            category.setAccessible(true);
            Object[] names =
                    Class.forName("net.coderbot.iris.parsing.BiomeCategories").getEnumConstants();
            Map<Biome, String> result = new LinkedHashMap<>();
            for (Biome biome : RivenBiomes.list())
                result.put(biome, names[(Integer) category.invoke(null, biome)].toString());
            return result;
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    /** Anchor and block.properties name pairs for a block; empty when no rule applies. */
    private static List<String[]> classify(Block block, ResourceLocation key) {
        IBlockState state = block.getDefaultState();
        List<String[]> matches = new ArrayList<>();
        for (JsonElement element : RULES.json.getAsJsonArray("blockRules")) {
            JsonObject rule = element.getAsJsonObject();
            if (!matches(rule, block, state, key)) continue;
            String anchor = rule.get("anchor").getAsString();
            if (!rule.has("property")) {
                if (matches.isEmpty()) matches.add(new String[] {anchor, key.toString()});
                return matches;
            }
            String[] property = rule.get("property").getAsString().split("=", 2);
            if (hasValue(block, property[0], property[1]))
                matches.add(new String[] {anchor, key + ":" + property[0] + "=" + property[1]});
        }
        return matches;
    }

    @SuppressWarnings("deprecation") // A default state's own light, with no world to ask.
    private static boolean matches(
            JsonObject rule, Block block, IBlockState state, ResourceLocation key) {
        if (rule.has("class")) {
            try {
                if (!Class.forName(rule.get("class").getAsString()).isInstance(block)) return false;
            } catch (ClassNotFoundException absent) {
                return false;
            }
        }
        if (rule.has("materials")) {
            boolean any = false;
            for (JsonElement m : rule.getAsJsonArray("materials"))
                any |= material(m.getAsString()) == state.getMaterial();
            if (!any) return false;
        }
        if (rule.has("nameContains")
                && !key.getPath().contains(rule.get("nameContains").getAsString())) return false;
        if (rule.has("fullCube") && state.isFullCube() != rule.get("fullCube").getAsBoolean())
            return false;
        if (rule.has("lightAtLeast") && state.getLightValue() < rule.get("lightAtLeast").getAsInt())
            return false;
        if (rule.has("property")
                && !hasProperty(block, rule.get("property").getAsString().split("=")[0]))
            return false;
        return !rule.has("oreDictPrefix")
                || oreDict(block, rule.get("oreDictPrefix").getAsString());
    }

    private static Material material(String name) {
        switch (name) {
            case "plants":
                return Material.PLANTS;
            case "vine":
                return Material.VINE;
            case "leaves":
                return Material.LEAVES;
            default:
                throw new IllegalStateException(
                        "Unknown material in riven-shaderprops.json: " + name);
        }
    }

    private static boolean hasProperty(Block block, String name) {
        for (IProperty<?> p : block.getBlockState().getProperties())
            if (p.getName().equals(name)) return true;
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // A property names its own values.
    private static boolean hasValue(Block block, String name, String value) {
        for (IProperty p : block.getBlockState().getProperties())
            if (p.getName().equals(name))
                for (Object v : p.getAllowedValues())
                    if (p.getName((Comparable) v).equals(value)) return true;
        return false;
    }

    /** Whether any of the block's item forms carries an ore dictionary name with this prefix. */
    private static boolean oreDict(Block block, String prefix) {
        Item item = Item.getItemFromBlock(block);
        if (item == Items.AIR) return false;
        Set<Integer> metas = new HashSet<>();
        for (IBlockState s : block.getBlockState().getValidStates())
            metas.add(block.getMetaFromState(s));
        for (int meta : metas)
            for (int id : OreDictionary.getOreIDs(new ItemStack(item, 1, meta)))
                if (OreDictionary.getOreName(id).startsWith(prefix)) return true;
        return false;
    }

    private static List<Block> sorted() {
        List<Block> blocks = new ArrayList<>();
        for (Block block : Block.REGISTRY) blocks.add(block);
        blocks.sort(Comparator.comparing(b -> String.valueOf(b.getRegistryName())));
        return blocks;
    }

    private static String text(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    /** Every file of a pack, zipped or a folder, by its path inside the pack. */
    private static Map<String, byte[]> read(Path pack) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        if (Files.isDirectory(pack)) {
            try (Stream<Path> walk = Files.walk(pack)) {
                for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList()))
                    files.put(
                            pack.relativize(p).toString().replace('\\', '/'),
                            Files.readAllBytes(p));
            }
            return files;
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    files.put(entry.getName(), readAll(in));
                }
            }
        }
        return files;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** Replaces an earlier Riven copy; refuses a folder Riven did not write. */
    private static void write(Path out, Map<String, byte[]> files) throws IOException {
        if (Files.exists(out)) {
            if (!Files.isRegularFile(out.resolve(REPORT)))
                throw new IOException(out + " exists and is not a Riven shaderprops copy");
            try (Stream<Path> walk = Files.walk(out)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                    Files.delete(p);
            }
        }
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path target = out.resolve(e.getKey()).normalize();
            if (!target.startsWith(out))
                throw new IOException("Pack entry outside the pack: " + e.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
    }
}
