package cc.orthrus.riven;

import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.SeededNoise;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.Variants;
import cc.orthrus.riven.worldgen.WorldScale;

import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Tree census through the production tree sites (game bootstrap; runDiagnostics). Usage:
 * TreeCensus <new output dir> [seed [layout [windows
 * per biome [window blocks]]]] (defaults: 2485331, 4, 5, 256). Samples each column's riven: biome
 * and variant on a 1024-block grid, takes up to the given number of windows per biome or variant
 * (chosen by hash, so the selection is a pure function of the seed), and in each window records
 * every valid tree site of every layer with its drawn shape (height, crown spread, leaves, logs,
 * roots), plus each biome and variant's dry-land area on a 4-block lattice and how much of it
 * leaves a walker two clear blocks under the trees (headroom). Writes windows.csv,
 * area.csv and sites.csv; tools/tree_census.py reports them.
 */
public final class TreeCensus {
    private static final int GRID = 1024, LATTICE = 4;

    private TreeCensus() {}

    private static void bootstrap() {
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "riven";
        metadata.name = "Riven";
        metadata.version = "0.0.1";
        Bootstrap.register();
        Loader.instance().setupTestHarness(new DummyModContainer(metadata));
        RivenContent.registerBlocks(
                new RegistryEvent.Register<>(
                        new ResourceLocation("minecraft", "blocks"), ForgeRegistries.BLOCKS));
        RivenBiomes.register(
                new RegistryEvent.Register<>(
                        new ResourceLocation("minecraft", "biomes"), ForgeRegistries.BIOMES));
    }

    private static String key(ClimateBiomeProvider p, TerrainMap.Sample s, int x, int z) {
        Biome biome = p.biome(s, x, z);
        int v = p.variant(biome, x, z);
        String name = String.valueOf(biome.getRegistryName()).replace("riven:", "");
        return v == Variants.NONE ? name : name + "/" + Variants.id(v);
    }

    private record Window(int id, int cx, int cz, String key) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("TreeCensus <new output dir> ...");
        Path out = Paths.get(args[0]);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 2485331L;
        double layout = args.length > 2 ? Double.parseDouble(args[2]) : 4;
        int perKey = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int size = args.length > 4 ? Integer.parseInt(args[4]) : 256;
        Files.createDirectories(out);
        if (Files.list(out).findAny().isPresent())
            throw new IllegalArgumentException("Output directory is not empty: " + out);
        bootstrap();
        long started = System.nanoTime();
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        try (ForkJoinPool workers = new ForkJoinPool(threads)) {
            WorldScale world = new WorldScale(layout);
            ClimateBiomeProvider provider = new ClimateBiomeProvider(seed, world, workers, null);
            TreeSites trees =
                    new TreeSites(
                            seed, provider, TerrainHeightReference.DEFAULT, Mappings.resolve());

            // Window candidates: the grid points' biome and variant, dry land only.
            int half = (int) (world.side() / 2), n = (int) (world.side() / GRID);
            String[] keys = new String[n * n];
            workers.submit(
                            () ->
                                    IntStream.range(0, n * n)
                                            .parallel()
                                            .forEach(
                                                    i -> {
                                                        int x = -half + (i % n) * GRID + GRID / 2;
                                                        int z = -half + (i / n) * GRID + GRID / 2;
                                                        TerrainMap.Sample s =
                                                                new TerrainMap.Sample();
                                                        provider.maps.terrain.sample(x, z, s);
                                                        if (s.ocean.marine || s.waterOverSurface())
                                                            return;
                                                        keys[i] = key(provider, s, x, z);
                                                    }))
                    .get();
            Map<String, List<Integer>> byKey = new TreeMap<>();
            for (int i = 0; i < keys.length; i++)
                if (keys[i] != null) byKey.computeIfAbsent(keys[i], k -> new ArrayList<>()).add(i);
            List<Window> windows = new ArrayList<>();
            for (Map.Entry<String, List<Integer>> e : byKey.entrySet()) {
                List<Integer> points = new ArrayList<>(e.getValue());
                points.sort(Comparator.comparingLong(i -> SeededNoise.mix(seed ^ i)));
                for (int k = 0; k < Math.min(perKey, points.size()); k++) {
                    int i = points.get(k);
                    windows.add(
                            new Window(
                                    windows.size(),
                                    -half + (i % n) * GRID + GRID / 2,
                                    -half + (i / n) * GRID + GRID / 2,
                                    e.getKey()));
                }
            }
            System.out.printf(
                    Locale.ROOT,
                    "seed %d layout %.1f: %d biome/variant keys, %d windows of %d blocks%n",
                    seed,
                    layout,
                    byKey.size(),
                    windows.size(),
                    size);

            String[] area = new String[windows.size()], sites = new String[windows.size()];
            workers.submit(
                            () ->
                                    IntStream.range(0, windows.size())
                                            .parallel()
                                            .forEach(
                                                    w -> {
                                                        List<TreeSites.Site> found =
                                                                found(trees, windows.get(w), size);
                                                        area[w] =
                                                                area(
                                                                        provider,
                                                                        trees,
                                                                        found,
                                                                        windows.get(w),
                                                                        size);
                                                        sites[w] =
                                                                sites(
                                                                        provider,
                                                                        found,
                                                                        windows.get(w));
                                                    }))
                    .get();

            StringBuilder w = new StringBuilder("window,cx,cz,size,key\n");
            for (Window win : windows)
                w.append(win.id)
                        .append(',')
                        .append(win.cx)
                        .append(',')
                        .append(win.cz)
                        .append(',')
                        .append(size)
                        .append(',')
                        .append(win.key)
                        .append('\n');
            Files.writeString(out.resolve("windows.csv"), w, StandardCharsets.UTF_8);
            Files.writeString(
                    out.resolve("area.csv"),
                    "window,key,columns,lattice,clear\n" + String.join("", area),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    out.resolve("sites.csv"),
                    "window,x,z,layer,species,family,width,base,trunk,height,spreadX,spreadZ,leaves,logs,roots,key,celsius,moisture\n"
                            + String.join("", sites),
                    StandardCharsets.UTF_8);
        }
        System.out.printf(
                Locale.ROOT,
                "TreeCensus wrote %s in %.1f s%n",
                out,
                (System.nanoTime() - started) / 1e9);
    }

    /** Every valid tree site in a window. */
    private static List<TreeSites.Site> found(TreeSites trees, Window w, int size) {
        int x0 = w.cx - size / 2, z0 = w.cz - size / 2;
        List<TreeSites.Site> found = new ArrayList<>();
        trees.sites(x0, z0, x0 + size, z0 + size, found::add);
        return found;
    }

    /**
     * Dry-land columns per biome and variant on the lattice inside a window, and how many of them
     * leave a walker headroom: no tree block (leaves, log or standing root) in the two blocks over
     * the ground. Trees whose site lies outside the window are not counted.
     */
    private static String area(
            ClimateBiomeProvider p,
            TreeSites trees,
            List<TreeSites.Site> found,
            Window w,
            int size) {
        int x0 = w.cx - size / 2, z0 = w.cz - size / 2, side = size / LATTICE;
        // The heights of tree blocks in each lattice column.
        Map<Integer, List<Integer>> heights = new HashMap<>();
        for (TreeSites.Site site : found)
            trees.blocks(
                    site,
                    (x, y, z, state, kind) -> {
                        int i = x - x0 - LATTICE / 2, j = z - z0 - LATTICE / 2;
                        if (i < 0 || j < 0 || i % LATTICE != 0 || j % LATTICE != 0) return;
                        if (i / LATTICE >= side || j / LATTICE >= side) return;
                        heights.computeIfAbsent(
                                        j / LATTICE * side + i / LATTICE, k -> new ArrayList<>())
                                .add(y);
                    });
        Map<String, int[]> counts = new LinkedHashMap<>();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (int z = z0 + LATTICE / 2; z < z0 + size; z += LATTICE)
            for (int x = x0 + LATTICE / 2; x < x0 + size; x += LATTICE) {
                p.maps.terrain.sample(x, z, s);
                if (s.ocean.marine || s.waterOverSurface()) continue;
                int[] c = counts.computeIfAbsent(key(p, s, x, z), k -> new int[2]);
                c[0]++;
                int ground = s.blockY();
                boolean clear = true;
                List<Integer> ys =
                        heights.get(
                                (z - z0 - LATTICE / 2) / LATTICE * side
                                        + (x - x0 - LATTICE / 2) / LATTICE);
                if (ys != null)
                    for (int y : ys) if (y == ground + 1 || y == ground + 2) clear = false;
                if (clear) c[1]++;
            }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, int[]> e : new TreeMap<>(counts).entrySet())
            b.append(w.id)
                    .append(',')
                    .append(e.getKey())
                    .append(',')
                    .append(e.getValue()[0])
                    .append(',')
                    .append(LATTICE)
                    .append(',')
                    .append(e.getValue()[1])
                    .append('\n');
        return b.toString();
    }

    /** Every valid site in a window with its drawn shape's measures. */
    private static String sites(ClimateBiomeProvider p, List<TreeSites.Site> found, Window w) {
        StringBuilder b = new StringBuilder();
        TreeShapes.Shape shape = new TreeShapes.Shape();
        TerrainMap.Sample s = new TerrainMap.Sample();
        for (TreeSites.Site site : found) {
            TreeShapes.shape(site.species, site.id, site.height, shape);
            int leaves = 0, logs = 0, roots = 0;
            for (int i = 0; i < shape.count; i++) {
                int kind = TreeShapes.kind(shape.blocks[i]);
                if (kind == TreeShapes.LEAVES) leaves++;
                else if (kind == TreeShapes.LOG) logs++;
                else roots++;
            }
            p.maps.terrain.sample(site.x, site.z, s);
            b.append(w.id)
                    .append(',')
                    .append(site.x)
                    .append(',')
                    .append(site.z)
                    .append(',')
                    .append(site.layer)
                    .append(',')
                    .append(site.species.id)
                    .append(',')
                    .append(site.species.family.name().toLowerCase(Locale.ROOT))
                    .append(',')
                    .append(site.species.width)
                    .append(',')
                    .append(site.base)
                    .append(',')
                    .append(site.height)
                    .append(',')
                    .append(shape.maxY - shape.minY + 1)
                    .append(',')
                    .append(shape.maxX - shape.minX + 1)
                    .append(',')
                    .append(shape.maxZ - shape.minZ + 1)
                    .append(',')
                    .append(leaves)
                    .append(',')
                    .append(logs)
                    .append(',')
                    .append(roots)
                    .append(',')
                    .append(key(p, s, site.x, site.z))
                    .append(',')
                    .append(String.format(Locale.ROOT, "%.2f", s.climate.celsius))
                    .append(',')
                    .append(String.format(Locale.ROOT, "%.3f", s.climate.moisture))
                    .append('\n');
        }
        return b.toString();
    }
}
