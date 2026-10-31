package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.CellMemo;
import cc.orthrus.riven.worldgen.ColumnBlocks;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockSand;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tree sites (riven-trees.json, trees-v3): where trees stand, their species, and the blocks of each
 * tree, as pure functions of seed, definitions and coordinates. Each layer (emergent, canopy,
 * understory) is a grid of candidate cells; a riven: biome's stands give each layer a chance and
 * species weights, the first stand whose climate holds at a candidate deciding. A site is valid on
 * its species' ground, clear of earlier layers' trunks, with its whole tree in open sky. Each cube
 * writes the tree blocks inside itself during generation ({@link #place}); the distant preview draws
 * the same blocks over fine cells ({@link #canopy}) and the stand's canopy cover over coarse cells.
 * Sites are visited layer by layer, cells in order: leaves fill only air, logs fill air or leaves,
 * grass under a trunk becomes dirt. So every cube and every preview column agree, in any order.
 */
final class TreeSites {
    private static final Rules RULES = Rules.load("riven-trees.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;

    /** What a tree block is, which decides what it may replace. */
    static final int LEAVES = 0, LOG = 1, SOIL = 2;

    private static final IBlockState DIRT = net.minecraft.init.Blocks.DIRT.getDefaultState();

    /** Receives one block of a tree. */
    interface BlockSink {
        void accept(int x, int y, int z, IBlockState state, int kind);
    }

    /**
     * One valid site: its layer, trunk corner, the first air Y above its ground, species, its
     * trunk height, the lowest block (soil under the trunk, or a hanging strand below the base)
     * and the end of its highest block over base, the trunk columns over grass (bit i + 2 j) and
     * the root chains that stand on ground.
     */
    static final class Site {
        final int layer, x, z, base, height, low, top, grass, roots;
        final long id;
        final TreeShapes.Species species;

        Site(
                int layer,
                int x,
                int z,
                int base,
                int height,
                int low,
                int top,
                int grass,
                int roots,
                long id,
                TreeShapes.Species species) {
            this.layer = layer;
            this.x = x;
            this.z = z;
            this.base = base;
            this.height = height;
            this.low = low;
            this.top = top;
            this.grass = grass;
            this.roots = roots;
            this.id = id;
            this.species = species;
        }
    }

    private static final Site NONE = new Site(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

    private static final class Layer {
        final String name;
        final int index, cell, spacing;
        final long seed;
        final CellMemo<Site> sites;

        Layer(String name, int index, JsonObject j, long seed) {
            this.name = name;
            this.index = index;
            this.seed = seed;
            cell = (int) Rules.number(j, "cellBlocks", 4, 64);
            // Neighbouring cells hold the only candidates closer than the spacing.
            spacing = (int) Rules.number(j, "trunkSpacingBlocks", 1, cell + 1);
            int capacity = Integer.highestOneBit(Math.max(1024, (1 << 14) * 36 / (cell * cell)));
            sites = new CellMemo<>(capacity);
        }
    }

    /** One layer of a stand: its chance per cell and its species. */
    private static final class Part {
        final double chance, cover;
        final TreeShapes.Species[] species;
        final double[] weights;
        final int crownBottom, crownTop;
        final IBlockState leaves;

        Part(JsonObject j, Map<String, TreeShapes.Species> all, int cell) {
            chance = Rules.number(j, "chance", 0, 1);
            List<TreeShapes.Species> list = new ArrayList<>();
            List<Double> w = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("species").entrySet()) {
                TreeShapes.Species s = all.get(e.getKey());
                if (s == null) throw new IllegalStateException("Unknown species " + e.getKey());
                list.add(s);
                w.add(e.getValue().getAsDouble());
            }
            species = list.toArray(new TreeShapes.Species[0]);
            weights = new double[w.size()];
            double total = 0, area = 0, bottom = 0, top = 0, most = -1;
            IBlockState main = null;
            for (int i = 0; i < weights.length; i++) {
                weights[i] = w.get(i);
                total += weights[i];
                area += weights[i] * species[i].footprint;
                bottom += weights[i] * species[i].crownBottom;
                top += weights[i] * species[i].crownTop;
                if (weights[i] > most) {
                    most = weights[i];
                    main = species[i].leaves;
                }
            }
            if (total <= 0) throw new IllegalStateException("Stand without species weight");
            cover = Math.min(1, chance * area / total / ((double) cell * cell));
            crownBottom = (int) Math.round(bottom / total);
            crownTop = Math.max(crownBottom + 1, (int) Math.round(top / total));
            leaves = main;
        }
    }

    /** A biome's trees where its climate holds: one part per layer, or none. */
    private static final class Stand {
        final double[] celsius, moisture;
        final Part[] parts;

        /** Combined cover of the layers, and the layer with the most (the distant canopy). */
        final double cover;

        final int dominant;

        Stand(JsonObject j, List<Layer> layers, Map<String, TreeShapes.Species> all) {
            JsonObject when = j.has("when") ? j.getAsJsonObject("when") : new JsonObject();
            celsius = when.has("celsius") ? pair(when, "celsius", -100, 100) : null;
            moisture = when.has("moisture") ? pair(when, "moisture", 0, 1) : null;
            parts = new Part[layers.size()];
            double open = 1, most = 0;
            int dominant = -1;
            for (Map.Entry<String, JsonElement> e : j.entrySet()) {
                if (e.getKey().equals("when")) continue;
                Layer layer = null;
                for (Layer l : layers) if (l.name.equals(e.getKey())) layer = l;
                if (layer == null) throw new IllegalStateException("Unknown layer " + e.getKey());
                Part p = new Part(e.getValue().getAsJsonObject(), all, layer.cell);
                parts[layer.index] = p;
                open *= 1 - p.cover;
                if (p.cover > most) {
                    most = p.cover;
                    dominant = layer.index;
                }
            }
            cover = 1 - open;
            this.dominant = dominant;
        }

        boolean holds(TerrainMap.Sample sample) {
            double c = sample.climate.celsius, m = sample.climate.moisture;
            return (celsius == null || (c >= celsius[0] && c < celsius[1]))
                    && (moisture == null || (m >= moisture[0] && m < moisture[1]));
        }
    }

    private static double[] pair(JsonObject j, String key, double min, double max) {
        JsonArray a = j.getAsJsonArray(key);
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (!(lo >= min && hi > lo && hi <= max)) throw new IllegalStateException("Range: " + key);
        return new double[] {lo, hi};
    }

    private final int coarse, reach;
    private final double coverThreshold;
    private final Map<String, TreeShapes.Species> species;
    private final List<Layer> layers = new ArrayList<>();
    private final Map<Biome, Stand[]> stands = new IdentityHashMap<>();
    private final ClimateBiomeProvider biomes;
    private final SurfaceTranscription surfaces;
    private final TerrainHeightReference heights;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
    private final ThreadLocal<Shapes> shapes = ThreadLocal.withInitial(Shapes::new);

    private static final class Scratch {
        final TerrainMap.Sample sample = new TerrainMap.Sample();
        final Recorder recorder = new Recorder();
        final TreeShapes.Shape shape = new TreeShapes.Shape();
    }

    /** Recently drawn trees of one thread; neighbouring cubes and columns share most sites. */
    private static final class Shapes {
        final long[] ids = new long[64];
        final TreeShapes.Species[] species = new TreeShapes.Species[64];
        final TreeShapes.Shape[] shapes = new TreeShapes.Shape[64];
    }

    TreeSites(
            long worldSeed,
            ClimateBiomeProvider biomes,
            SurfaceTranscription surfaces,
            TerrainHeightReference heights,
            Mappings.Snapshot snapshot) {
        JsonObject j = RULES.json;
        long seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        coarse = (int) Rules.number(j, "coarseFootprintBlocks", 2, 4096);
        coverThreshold = Rules.number(j, "coverThreshold", 0, 1);
        species = TreeShapes.species(j, snapshot);
        int widest = 0;
        for (TreeShapes.Species s : species.values()) widest = Math.max(widest, s.reach);
        reach = widest;
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("layers").entrySet())
            layers.add(
                    new Layer(
                            e.getKey(),
                            layers.size(),
                            e.getValue().getAsJsonObject(),
                            SeededNoise.mix(seed + 0x9e3779b97f4a7c15L * (layers.size() + 1))));
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet()) {
            Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(e.getKey()));
            if (biome == null) throw new IllegalStateException("Unknown biome " + e.getKey());
            JsonArray list =
                    e.getValue().isJsonArray() ? e.getValue().getAsJsonArray() : new JsonArray();
            if (!e.getValue().isJsonArray()) list.add(e.getValue());
            Stand[] array = new Stand[list.size()];
            for (int i = 0; i < array.length; i++)
                array[i] = new Stand(list.get(i).getAsJsonObject(), layers, species);
            stands.put(biome, array);
        }
        this.biomes = biomes;
        this.surfaces = surfaces;
        this.heights = heights;
    }

    /** Every species, by id, as this world resolves them. */
    Map<String, TreeShapes.Species> species() {
        return Collections.unmodifiableMap(species);
    }

    private static volatile Map<String, TreeShapes.Species> defaults;

    /** Every species with the current mapping layers (sapling growth outside Riven worlds). */
    static Map<String, TreeShapes.Species> defaultSpecies() {
        Map<String, TreeShapes.Species> d = defaults;
        if (d == null) defaults = d = TreeShapes.species(RULES.json, Mappings.resolve());
        return d;
    }

    /** The first stand of a riven: biome whose climate holds at a sample, or null. */
    private Stand stand(Biome biome, TerrainMap.Sample sample) {
        Stand[] list = biome == null ? null : stands.get(biome);
        if (list == null) return null;
        for (Stand s : list) if (s.holds(sample)) return s;
        return null;
    }

    /** Every valid site of every layer whose trunk corner lies in [minX, maxX) x [minZ, maxZ). */
    void sites(int minX, int minZ, int maxX, int maxZ, Consumer<Site> out) {
        for (Layer layer : layers) sites(layer, minX, minZ, maxX, maxZ, out);
    }

    /** One layer's valid sites in a box, in cell order. */
    private void sites(Layer layer, int minX, int minZ, int maxX, int maxZ, Consumer<Site> out) {
        int cell = layer.cell;
        for (int gz = Math.floorDiv(minZ, cell); gz * cell < maxZ; gz++)
            for (int gx = Math.floorDiv(minX, cell); gx * cell < maxX; gx++) {
                Site s = site(layer, gx, gz);
                if (s != NONE && s.x >= minX && s.x < maxX && s.z >= minZ && s.z < maxZ)
                    out.accept(s);
            }
    }

    private Site site(Layer layer, int gx, int gz) {
        return layer.sites.get(gx, gz, () -> build(layer, gx, gz, null));
    }

    /** Counts of each cell outcome per layer over the cells overlapping a box (diagnostics). */
    Map<String, Integer> reasons(int minX, int minZ, int maxX, int maxZ) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (Layer layer : layers)
            for (int gz = Math.floorDiv(minZ, layer.cell); gz * layer.cell < maxZ; gz++)
                for (int gx = Math.floorDiv(minX, layer.cell); gx * layer.cell < maxX; gx++) {
                    String[] out = {"SITE"};
                    build(layer, gx, gz, out);
                    counts.merge(layer.name + ":" + out[0], 1, Integer::sum);
                }
        return counts;
    }

    private static Site reject(String[] out, String reason) {
        if (out != null) out[0] = reason;
        return NONE;
    }

    private Site build(Layer layer, int gx, int gz, String[] out) {
        long id = SeededNoise.cell(layer.seed, gx, gz);
        int x = candidate(gx, layer.cell, id, 1), z = candidate(gz, layer.cell, id, 2);
        // A candidate closer than the trunk spacing to a higher-priority neighbour stands down,
        // whatever that neighbour becomes: symmetric, so the outcome is the same from either cell.
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                long other = SeededNoise.cell(layer.seed, gx + dx, gz + dz);
                int ox = candidate(gx + dx, layer.cell, other, 1);
                int oz = candidate(gz + dz, layer.cell, other, 2);
                if (Math.max(Math.abs(ox - x), Math.abs(oz - z)) < layer.spacing
                        && TreeShapes.unit(other, 6) > TreeShapes.unit(id, 6))
                    return reject(out, "SPACING");
            }
        if (!ClimateBiomeProvider.inWorld(x - reach - 16L, z - reach - 16L)
                || !ClimateBiomeProvider.inWorld(x + reach + 16L, z + reach + 16L))
            return reject(out, "WORLD");
        Scratch s = scratch.get();
        biomes.maps.terrain.sample(x, z, s.sample);
        Stand stand = stand(biomes.biome(s.sample, x, z), s.sample);
        if (stand == null) return reject(out, "BIOME");
        Part part = stand.parts[layer.index];
        if (part == null) return reject(out, "LAYER");
        if (TreeShapes.unit(id, 3) >= part.chance) return reject(out, "CHANCE");
        TreeShapes.Species species =
                part.species[Math.max(0, Rules.choose(part.weights, TreeShapes.unit(id, 4)))];
        int w = species.width;
        if (crowded(layer, x, z, w)) return reject(out, "UNDER");
        // Each trunk column's ground, as the preview and native cubes see it, must be the
        // species' ground at one height, with open sky above. (The earlier layers' sites built
        // above share this thread's scratch, so every column samples afresh.)
        int base = Integer.MIN_VALUE, grass = 0;
        for (int j = 0; j < w; j++)
            for (int i = 0; i < w; i++) {
                biomes.maps.terrain.sample(x + i, z + j, s.sample);
                Biome look = biomes.look(s.sample, x + i, z + j);
                Recorder r = s.recorder;
                r.reset();
                TerrainColumn.preview(
                        s.sample,
                        look,
                        look,
                        ColumnBlocks.bareStone(s.sample, biomes.maps.surfaces),
                        biomes.maps.surfaces,
                        heights,
                        surfaces,
                        biomes.maps.boulders,
                        null,
                        null,
                        1,
                        x + i,
                        z + j,
                        r);
                int top = r.count - 1;
                while (top >= 0 && r.states[top].getMaterial() == Material.AIR) top--;
                if (top < 0 || !grows(species, r.states[top])) return reject(out, "GROUND");
                if (base != Integer.MIN_VALUE && r.tops[top] != base) return reject(out, "GROUND");
                base = r.tops[top];
                if (r.states[top].getBlock() instanceof BlockGrass) grass |= 1 << (i + 2 * j);
            }
        TreeShapes.Shape shape = s.shape;
        TreeShapes.shape(species, id, shape);
        int low = Math.min(-1, shape.minY);
        if (base + low < heights.minimumY || base + shape.maxY + 1 > heights.maximumYExclusive)
            return reject(out, "HEIGHT");
        // Clearance: every column the tree reaches is open sky from its lowest block up, so no
        // tree grows into a cliff, an overhang or water.
        for (int dz = shape.minZ; dz <= shape.maxZ; dz++)
            for (int dx = shape.minX; dx <= shape.maxX; dx++) {
                int lowest = Integer.MAX_VALUE;
                for (int i = shape.from(dx, dz); i < shape.to(dx, dz); i++) {
                    int p = shape.ordered(i);
                    if (TreeShapes.kind(p) != TreeShapes.ROOT)
                        lowest = Math.min(lowest, TreeShapes.dy(p));
                }
                if (lowest != Integer.MAX_VALUE && surface(x + dx, z + dz) > base + lowest)
                    return reject(out, "SPACE");
            }
        // A root chain stands where its foot is on the trunk's ground and its blocks in open sky.
        int roots = 0;
        for (int c = 0; c < shape.chains; c++)
            if (surface(x + shape.footX[c], z + shape.footZ[c]) == base) roots |= 1 << c;
        for (int i = 0; i < shape.count; i++) {
            int p = shape.blocks[i];
            if (TreeShapes.kind(p) == TreeShapes.ROOT
                    && surface(x + TreeShapes.dx(p), z + TreeShapes.dz(p))
                            > base + TreeShapes.dy(p)) roots &= ~(1 << TreeShapes.chain(p));
        }
        return new Site(
                layer.index,
                x,
                z,
                base,
                species.height(id),
                low,
                shape.maxY + 1,
                grass,
                roots,
                id,
                species);
    }

    /** Whether an earlier layer's trunk stands within this layer's spacing of a trunk here. */
    private boolean crowded(Layer layer, int x, int z, int w) {
        int d = layer.spacing + 2;
        boolean[] near = {false};
        for (int e = 0; e < layer.index && !near[0]; e++)
            sites(
                    layers.get(e),
                    x - d - 1,
                    z - d - 1,
                    x + w + d,
                    z + w + d,
                    s -> {
                        int sw = s.species.width;
                        int gapX = Math.max(0, Math.max(s.x - (x + w - 1), x - (s.x + sw - 1)));
                        int gapZ = Math.max(0, Math.max(s.z - (z + w - 1), z - (s.z + sw - 1)));
                        if (Math.max(gapX, gapZ) < layer.spacing) near[0] = true;
                    });
        return near[0];
    }

    /**
     * Top of the highest non-air run of a column: its ground, or water over it. One column's query,
     * so a distant preview's tree sites do not build whole native chunk columns.
     */
    private int surface(int x, int z) {
        return biomes.maps.columns.surface(x, z);
    }

    private static int candidate(int g, int cell, long id, int salt) {
        return g * cell + (int) Math.floor(TreeShapes.unit(id, salt) * cell);
    }

    /** A site's tree, drawn once per thread for as long as its slot keeps it. */
    private TreeShapes.Shape shape(Site site) {
        Shapes c = shapes.get();
        int slot = (int) SeededNoise.mix(site.id) & 63;
        TreeShapes.Shape shape = c.shapes[slot];
        if (shape != null && c.ids[slot] == site.id && c.species[slot] == site.species)
            return shape;
        if (shape == null) c.shapes[slot] = shape = new TreeShapes.Shape();
        TreeShapes.shape(site.species, site.id, shape);
        c.ids[slot] = site.id;
        c.species[slot] = site.species;
        return shape;
    }

    /**
     * Every block of a site's tree, in a fixed order: leaves, logs, the root chains that stand,
     * then the soil under each trunk column over grass.
     */
    void blocks(Site site, BlockSink sink) {
        TreeShapes.Shape shape = shape(site);
        for (int i = 0; i < shape.count; i++) emit(site, shape.blocks[i], sink);
        soil(site, sink);
    }

    private static void emit(Site site, int p, BlockSink sink) {
        int kind = TreeShapes.kind(p);
        if (kind == TreeShapes.ROOT && (site.roots & 1 << TreeShapes.chain(p)) == 0) return;
        TreeShapes.Species s = site.species;
        sink.accept(
                site.x + TreeShapes.dx(p),
                site.base + TreeShapes.dy(p),
                site.z + TreeShapes.dz(p),
                kind == TreeShapes.LEAVES ? s.leaves : s.log(TreeShapes.axis(p)),
                kind == TreeShapes.LEAVES ? LEAVES : LOG);
    }

    private static void soil(Site site, BlockSink sink) {
        int w = site.species.width;
        for (int j = 0; j < w; j++)
            for (int i = 0; i < w; i++)
                if ((site.grass & 1 << (i + 2 * j)) != 0)
                    sink.accept(site.x + i, site.base - 1, site.z + j, DIRT, SOIL);
    }

    /**
     * Writes the tree blocks inside one cube: leaves into air, logs into air or leaves, dirt over
     * grass under a trunk. Sites are visited layer by layer in cell order, as the preview visits
     * them.
     */
    void place(
            io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer primer,
            int cubeX,
            int cubeY,
            int cubeZ) {
        int x0 = cubeX << 4, y0 = cubeY << 4, z0 = cubeZ << 4;
        sites(
                x0 - reach,
                z0 - reach,
                x0 + 16 + reach,
                z0 + 16 + reach,
                site -> {
                    if (site.base + site.top <= y0 || site.base + site.low >= y0 + 16) return;
                    blocks(
                            site,
                            (x, y, z, state, kind) -> {
                                int lx = x - x0, ly = y - y0, lz = z - z0;
                                if (lx < 0 || lx > 15 || ly < 0 || ly > 15 || lz < 0 || lz > 15)
                                    return;
                                IBlockState existing = primer.getBlockState(lx, ly, lz);
                                if (fits(existing, kind)) primer.setBlockState(lx, ly, lz, state);
                            });
                });
    }

    /** Whether a tree block (leaves, log or standing root) is at (x, y, z), as the cubes write. */
    boolean occupied(int x, int y, int z) {
        boolean[] hit = {false};
        sites(
                x - reach,
                z - reach,
                x + reach + 1,
                z + reach + 1,
                s -> {
                    if (hit[0] || y < s.base + s.low || y >= s.base + s.top) return;
                    TreeShapes.Shape shape = shape(s);
                    int dx = x - s.x, dz = z - s.z;
                    for (int i = shape.from(dx, dz); i < shape.to(dx, dz) && !hit[0]; i++) {
                        int p = shape.ordered(i);
                        if (TreeShapes.kind(p) == TreeShapes.ROOT
                                && (s.roots & 1 << TreeShapes.chain(p)) == 0) continue;
                        hit[0] = s.base + TreeShapes.dy(p) == y;
                    }
                });
        return hit[0];
    }

    /** Leaves fill air; logs fill air or leaves; soil replaces grass. */
    private static boolean fits(IBlockState existing, int kind) {
        Material m = existing.getMaterial();
        if (kind == LEAVES) return m == Material.AIR;
        if (kind == LOG) return m == Material.AIR || m == Material.LEAVES;
        return existing.getBlock() instanceof BlockGrass;
    }

    /**
     * Whether a species grows on a ground block: grass, dirt (any ground material, so the
     * provider's dirts and mud too), sand or a snow block.
     */
    private static boolean grows(TreeShapes.Species species, IBlockState state) {
        Block block = state.getBlock();
        Material m = state.getMaterial();
        return (species.onGrass && block instanceof BlockGrass)
                || (species.onDirt && (block instanceof BlockDirt || m == Material.GROUND))
                || (species.onSand && block instanceof BlockSand)
                || (species.onSnow && m == Material.CRAFTED_SNOW);
    }

    /**
     * Tree blocks of one column above its terrain, bottom to top, without overlaps, and the soil
     * that replaces the column's top block under a trunk (or null).
     */
    static final class Canopy {
        int count;
        int[] bottoms = new int[4], tops = new int[4];
        IBlockState[] states = new IBlockState[4];
        IBlockState soil;

        void clear() {
            count = 0;
            soil = null;
        }

        private void add(int bottom, int top, IBlockState state) {
            if (count > 0 && tops[count - 1] == bottom && states[count - 1] == state) {
                tops[count - 1] = top;
                return;
            }
            if (count == states.length) {
                bottoms = java.util.Arrays.copyOf(bottoms, count * 2);
                tops = java.util.Arrays.copyOf(tops, count * 2);
                states = java.util.Arrays.copyOf(states, count * 2);
            }
            bottoms[count] = bottom;
            tops[count] = top;
            states[count] = state;
            count++;
        }
    }

    /**
     * The trees over column (x, z) whose open sky starts at ground: exact trunks and crowns for
     * fine cells; for cells at least coarseFootprintBlocks wide, the canopy of the stand's most
     * covering layer where the stand's combined cover reaches the threshold.
     */
    void canopy(
            int x,
            int z,
            int footprint,
            int ground,
            Biome reported,
            TerrainMap.Sample sample,
            IBlockState top,
            Canopy out) {
        out.clear();
        if (footprint >= coarse) {
            Stand stand = stand(reported, sample);
            if (stand == null || stand.dominant < 0 || stand.cover < coverThreshold) return;
            Part part = stand.parts[stand.dominant];
            boolean ground0 = false;
            for (TreeShapes.Species s : part.species) ground0 |= grows(s, top);
            if (!ground0
                    || ground - 1 < heights.minimumY
                    || ground + part.crownTop > heights.maximumYExclusive) return;
            out.add(ground + part.crownBottom, ground + part.crownTop, part.leaves);
            return;
        }
        List<Site> near = new ArrayList<>(8);
        sites(x - reach, z - reach, x + reach + 1, z + reach + 1, near::add);
        if (near.isEmpty()) return;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (Site s : near) {
            lowest = Math.min(lowest, s.base + s.low);
            highest = Math.max(highest, s.base + s.top);
        }
        IBlockState[] column = new IBlockState[highest - lowest];
        boolean[] logs = new boolean[highest - lowest];
        boolean soil = false;
        // The same blocks the cubes write, filtered to this column, under the same rules.
        for (Site s : near) {
            TreeShapes.Shape shape = shape(s);
            int dx = x - s.x, dz = z - s.z;
            for (int i = shape.from(dx, dz); i < shape.to(dx, dz); i++) {
                int p = shape.ordered(i);
                int kind = TreeShapes.kind(p);
                if (kind == TreeShapes.ROOT && (s.roots & 1 << TreeShapes.chain(p)) == 0) continue;
                int at = s.base + TreeShapes.dy(p) - lowest;
                if (kind == TreeShapes.LEAVES) {
                    if (column[at] == null) column[at] = s.species.leaves;
                } else if (column[at] == null || !logs[at]) {
                    column[at] = s.species.log(TreeShapes.axis(p));
                    logs[at] = true;
                }
            }
            int w = s.species.width;
            if (dx >= 0 && dx < w && dz >= 0 && dz < w && (s.grass & 1 << (dx + 2 * dz)) != 0)
                soil |= s.base - 1 == ground - 1;
        }
        if (soil && top.getBlock() instanceof BlockGrass) out.soil = DIRT;
        // Leaves and logs replace only air: nothing grows into this column's terrain or water.
        for (int y = Math.max(lowest, ground); y < highest; y++) {
            IBlockState state = column[y - lowest];
            if (state != null) out.add(y, y + 1, state);
        }
    }

    /** The full-detail preview column at (x, z), trees included (for parity checks). */
    Recorder previewColumn(int x, int z) {
        return previewColumn(x, z, null);
    }

    /** The full-detail preview column at (x, z), trees and a ground cover included. */
    Recorder previewColumn(int x, int z, GroundCover cover) {
        TerrainMap.Sample sample = new TerrainMap.Sample();
        biomes.maps.terrain.sample(x, z, sample);
        Recorder r = new Recorder();
        TerrainColumn.preview(
                sample,
                biomes.look(sample, x, z),
                biomes.biome(sample, x, z),
                ColumnBlocks.bareStone(sample, biomes.maps.surfaces),
                biomes.maps.surfaces,
                heights,
                surfaces,
                biomes.maps.boulders,
                this,
                cover,
                1,
                x,
                z,
                r);
        return r;
    }

    /** Records a preview column's runs. */
    static final class Recorder implements ICubicTerrainPreview.ColumnConsumer {
        int count;
        int[] bottoms = new int[16], tops = new int[16];
        IBlockState[] states = new IBlockState[16];

        void reset() {
            count = 0;
        }

        /** Top of the highest run that is neither air nor tree, or MIN_VALUE. */
        int ground() {
            for (int i = count - 1; i >= 0; i--) {
                IBlockState s = states[i];
                if (s.getMaterial() != Material.AIR
                        && !(s.getBlock() instanceof net.minecraft.block.BlockLog)
                        && !(s.getBlock() instanceof net.minecraft.block.BlockLeaves))
                    return tops[i];
            }
            return Integer.MIN_VALUE;
        }

        IBlockState at(int y) {
            for (int i = 0; i < count; i++) if (y >= bottoms[i] && y < tops[i]) return states[i];
            return null;
        }

        /** Replaces [bottom, top) with one state, keeping every run around it (boulders). */
        void overwrite(int bottom, int top, IBlockState state) {
            int n = count;
            int[] b = java.util.Arrays.copyOf(bottoms, n), t = java.util.Arrays.copyOf(tops, n);
            IBlockState[] s = java.util.Arrays.copyOf(states, n);
            count = 0;
            for (int i = 0; i < n; i++)
                if (b[i] < bottom) addRun(b[i], Math.min(t[i], bottom), s[i]);
            addRun(bottom, top, state);
            for (int i = 0; i < n; i++) if (t[i] > top) addRun(Math.max(b[i], top), t[i], s[i]);
        }

        @Override
        public void begin(Biome biome) {
            count = 0;
        }

        @Override
        public void addRun(int bottom, int top, IBlockState state) {
            if (count == states.length) {
                bottoms = java.util.Arrays.copyOf(bottoms, count * 2);
                tops = java.util.Arrays.copyOf(tops, count * 2);
                states = java.util.Arrays.copyOf(states, count * 2);
            }
            bottoms[count] = bottom;
            tops[count] = top;
            states[count] = state;
            count++;
        }
    }
}
