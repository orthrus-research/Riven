package cc.orthrus.riven;

import cc.orthrus.riven.content.GroundPlant;
import cc.orthrus.riven.content.Grounds;
import cc.orthrus.riven.worldgen.Boulders;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;
import cc.orthrus.riven.worldgen.Variants;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Ground cover (riven-ground-cover.json): the plants and decals on a column's ground, as a pure
 * function of seed, definitions, the column's riven: biome and the block it stands on. A biome lists
 * entries in order; the first whose ground fits and whose draw falls under its density, shaped into
 * drifts by its patch noise, decides. A plant stands on the column's surface (the top of its highest
 * non-air run) where native generation, boulders and trees leave air; a two-block plant or a cane
 * needs all its blocks clear of terrain and trees, and a cane also water beside its ground in the
 * native blocks of a neighbouring column. A column under still water is decided as well: its ground
 * is the water, which only floating plants fit, and an entry may name the water depths it takes;
 * a submerged plant stands on the bed instead, under at least one block of water, a stack of
 * stems under its top piece reaching at most to the block below the surface.
 * Each cube writes the blocks inside itself after its trees, and the preview draws the same blocks,
 * so distant ground keeps the colour the plants give it.
 */
final class GroundCover {
    private static final Rules RULES = Rules.load("riven-ground-cover.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;

    /** The block native generation writes at a position before boulders and trees. */
    interface Ground {
        IBlockState at(int index, int x, int y, int z);
    }

    private static final class Entry {
        final IBlockState state, top;
        final boolean submerged;
        final GroundPlant.Tall tall;
        final GroundPlant.Cane cane;
        final int ground, shortest, tallest, shallowest, deepest, cell;
        final GroundPlant plant;
        final double density, patch, share;
        final long seed;

        Entry(JsonObject j, double scale, long seed) {
            String text = j.get("block").getAsString();
            IBlockState parsed = Mappings.parse(text);
            if (parsed == null) throw new IllegalStateException("Unknown ground cover " + text);
            state = parsed;
            Block block = parsed.getBlock();
            plant = block instanceof GroundPlant ? (GroundPlant) block : null;
            tall = block instanceof GroundPlant.Tall ? (GroundPlant.Tall) block : null;
            cane = block instanceof GroundPlant.Cane ? (GroundPlant.Cane) block : null;
            submerged = block instanceof cc.orthrus.riven.content.SubmergedPlant;
            // A submerged stack ends in its own top piece.
            top = j.has("top") ? Mappings.parse(j.get("top").getAsString()) : null;
            if (j.has("top") && (top == null || !submerged))
                throw new IllegalStateException("Only a submerged stack has a top: " + text);
            // A cane's or a submerged stack's entry gives its height; other plants have their own.
            // A cane, a submerged stack or a plain block stacked (a cactus) gives its height; a
            // two-block plant and other plants have their own.
            if (cane != null || top != null || (tall == null && j.has("height"))) {
                JsonObject height = j.getAsJsonObject("height");
                if (height == null)
                    throw new IllegalStateException("A stack needs its height: " + text);
                shortest = count(height, "min", 1, MAXIMUM_DEPTH);
                tallest = count(height, "max", shortest, MAXIMUM_DEPTH);
            } else if (j.has("height")) {
                throw new IllegalStateException("A two-block plant has no height: " + text);
            } else shortest = tallest = tall != null ? 2 : 1;
            List<String> kinds = new ArrayList<>();
            if (j.has("ground"))
                for (JsonElement g : j.getAsJsonArray("ground")) kinds.add(g.getAsString());
            else if (plant == null)
                throw new IllegalStateException("Ground cover needs its ground: " + text);
            ground = kinds.isEmpty() ? 0 : Grounds.mask(kinds);
            density = Rules.number(j, "density", 0, 1) * scale;
            patch = j.has("patch") ? Rules.number(j, "patch", 0, 512) : 0;
            share = j.has("share") ? Rules.number(j, "share", 0.05, 1) : 0.35;
            // Water depth an entry takes, in blocks of water over the bed (any by default).
            JsonObject depth = j.getAsJsonObject("depth");
            shallowest = depth == null ? 0 : count(depth, "min", 0, MAXIMUM_DEPTH);
            deepest =
                    depth == null ? MAXIMUM_DEPTH : count(depth, "max", shallowest, MAXIMUM_DEPTH);
            // One candidate column per cell of this many blocks, inside its inner square, so two
            // never stand side by side (a cactus breaks beside anything solid).
            cell = j.has("cell") ? count(j, "cell", 3, 64) : 0;
            this.seed = seed;
        }

        /** Whether a column is its cell's candidate (every column without cells). */
        boolean candidate(int x, int z) {
            if (cell == 0) return true;
            int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
            long h = SeededNoise.cell(SeededNoise.mix(seed ^ 0x510e527fade682d1L), cx, cz);
            int inner = cell - 2;
            int ox = 1 + (int) Math.floorMod(h, (long) inner);
            int oz = 1 + (int) Math.floorMod(h >>> 32, (long) inner);
            return x - cx * cell == ox && z - cz * cell == oz;
        }

        private static int count(JsonObject j, String key, int min, int max) {
            double n = Rules.number(j, key, min, max);
            if (n != Math.rint(n)) throw new IllegalStateException("Range: " + key);
            return (int) n;
        }

        boolean fits(IBlockState ground) {
            return this.ground != 0 ? Grounds.on(this.ground, ground) : plant.growsOn(ground);
        }

        /** The draw's threshold at a column: the density, gathered into drifts by the patch noise. */
        double threshold(int x, int z) {
            if (patch <= 0) return density;
            double n = noise(SeededNoise.mix(seed ^ 0x6a09e667f3bcc909L), x / patch, z / patch);
            double ramp = Math.max(0, Math.min(1, (n - (1 - share)) / 0.1));
            return Math.min(1, density * ramp / share);
        }
    }

    /** The deepest water a column's depth is counted to. */
    static final int MAXIMUM_DEPTH = 32;

    private final long seed;
    private final Map<Biome, Entry[]> biomes = new IdentityHashMap<>();

    /** Entries by variant index (riven-ground-cover.json variants), replacing the biome's. */
    private final Entry[][] variants = new Entry[Variants.count()][];

    private final Set<Block> blocks = new HashSet<>();

    /** How far above its surface any entry's plant reaches: its tallest stack less one. */
    private int reach;

    private int maximumHeight, maximumDepth;

    private final ClimateBiomeProvider provider;
    private final TreeSites trees;

    GroundCover(long worldSeed, ClimateBiomeProvider provider, TreeSites trees) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        JsonObject palettes = j.getAsJsonObject("palettes");
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet()) {
            Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(e.getKey()));
            if (biome == null) throw new IllegalStateException("Unknown biome " + e.getKey());
            biomes.put(biome, entries(e.getKey(), e.getValue().getAsJsonArray(), palettes));
        }
        if (j.has("variants"))
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("variants").entrySet())
                variants[Variants.index(e.getKey())] =
                        entries("variant:" + e.getKey(), e.getValue().getAsJsonArray(), palettes);
        this.provider = provider;
        this.trees = trees;
    }

    /**
     * A biome's or a variant's entries: entries and palettes, a palette optionally at a scaled
     * density. Each entry's draws and drifts are its own, by owner key and position in the list.
     */
    private Entry[] entries(String key, JsonArray items, JsonObject palettes) {
        List<JsonObject> list = new ArrayList<>();
        List<Double> scales = new ArrayList<>();
        for (JsonElement item : items) {
            JsonObject o = item.isJsonObject() ? item.getAsJsonObject() : null;
            if (o != null && o.has("block")) {
                list.add(o);
                scales.add(1.0);
                continue;
            }
            String name = o != null ? o.get("palette").getAsString() : item.getAsString();
            double scale = o != null && o.has("scale") ? Rules.number(o, "scale", 0, 4) : 1;
            JsonArray palette = palettes.getAsJsonArray(name);
            if (palette == null) throw new IllegalStateException("Unknown palette " + name);
            for (JsonElement p : palette) {
                list.add(p.getAsJsonObject());
                scales.add(scale);
            }
        }
        Entry[] entries = new Entry[list.size()];
        for (int i = 0; i < entries.length; i++) {
            long entrySeed = SeededNoise.mix(seed ^ key.hashCode() * 0x9e3779b97f4a7c15L + i);
            entries[i] = new Entry(list.get(i), scales.get(i), entrySeed);
            if (entries[i].density > 1)
                throw new IllegalStateException("Range: scaled density in " + key);
            blocks.add(entries[i].state.getBlock());
            reach = Math.max(reach, entries[i].tallest - 1);
            maximumHeight = Math.max(maximumHeight, entries[i].tallest);
            if (entries[i].submerged) maximumDepth = Math.max(maximumDepth, entries[i].deepest);
        }
        return entries;
    }

    /** Whether a block is one ground cover places (parity checks). */
    boolean places(Block block) {
        return blocks.contains(block);
    }

    /** Tallest configured stack, including entries that stand underwater. */
    int maximumHeight() {
        return maximumHeight;
    }

    /** Deepest configured submerged bed below a native surface. */
    int maximumDepth() {
        return maximumDepth;
    }

    /**
     * The entry that decides a column whose surface is s, or null; depth is the water over its bed
     * (0 on dry ground). canes says whether a cane may decide; without it, cane entries pass as if
     * no water lay beside the ground.
     */
    private Entry decide(
            Biome biome,
            IBlockState ground,
            IBlockState bed,
            int depth,
            int x,
            int s,
            int z,
            boolean canes) {
        int variant = provider.variant(biome, x, z);
        Entry[] entries =
                variant != Variants.NONE && variants[variant] != null
                        ? variants[variant]
                        : biomes.get(biome);
        if (entries == null || ground.getMaterial() == Material.AIR) return null;
        for (Entry e : entries) {
            if (depth < e.shallowest || depth > e.deepest) continue;
            // A submerged plant stands on the bed, under at least one block of water.
            if (e.submerged ? depth < 2 || bed == null || !e.fits(bed) : !e.fits(ground)) continue;
            if (!e.candidate(x, z)) continue;
            if (SeededNoise.unit(SeededNoise.cell(e.seed, x, z)) >= e.threshold(x, z)) continue;
            if (e.cane != null && !(canes && waterBeside(x, s - 1, z))) continue;
            return e;
        }
        return null;
    }

    /**
     * Whether a neighbouring column holds water at Y in its native blocks: one column's query each
     * (NativeColumns.water), so a full-detail preview builds no native chunk column for it.
     */
    private boolean waterBeside(int x, int y, int z) {
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            int nx = x + side.getXOffset(), nz = z + side.getZOffset();
            if (!ClimateBiomeProvider.inWorld(nx, nz)) continue;
            if (provider.maps.columns.water(nx, y, nz)) return true;
        }
        return false;
    }

    /**
     * How many blocks an entry's plant stands at a column: a stack's height by position, a
     * submerged stack's no higher than the block below the surface.
     */
    private int height(Entry e, int x, int z, int depth) {
        int n = height(e, x, z);
        return e.submerged ? Math.min(n, depth - 1) : n;
    }

    private int height(Entry e, int x, int z) {
        if (e.shortest == e.tallest) return e.shortest;
        long heightSeed = SeededNoise.mix(e.seed ^ 0x3c6ef372fe94f82bL);
        double unit = SeededNoise.unit(SeededNoise.cell(heightSeed, x, z));
        return e.shortest + (int) (unit * (e.tallest - e.shortest + 1));
    }

    /** Block i (from 0 at the surface) of an entry's n-block plant at a column. */
    private IBlockState block(Entry e, int i, int n, int x, int z) {
        if (e.top != null) return i == n - 1 ? e.top : e.state;
        if (n == 1) return e.state;
        // A two-block plant or a cane takes its form by position.
        long formSeed = SeededNoise.mix(e.seed ^ 0xbb67ae8584caa73bL);
        double unit = SeededNoise.unit(SeededNoise.cell(formSeed, x, z));
        if (e.tall != null) return e.tall.part(i == 1, (int) (unit * e.tall.forms()));
        if (e.cane != null) return e.cane.piece(i == n - 1, (int) (unit * e.cane.forms()));
        return e.state;
    }

    /**
     * Writes the ground cover inside one cube, after its boulders and trees: a plant goes into air
     * on its column's surface; a two-block plant's or a cane's blocks go where all are clear.
     */
    void place(
            io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer primer,
            int cubeX,
            int cubeY,
            int cubeZ,
            NativeColumns.Column column,
            Ground ground) {
        int y0 = cubeY << 4;
        Boulders boulders = provider.maps.boulders;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int index = (lz << 4) | lx, s = column.surface(index);
                // Cover on the surface reaches up from it; a submerged plant stands below it.
                boolean surface = s >= y0 - reach && s < y0 + 16;
                if (!surface && (s < y0 + 16 || s >= y0 + 16 + MAXIMUM_DEPTH)) continue;
                int x = (cubeX << 4) + lx, z = (cubeZ << 4) + lz;
                if (boulders.at(x, z) != null) continue;
                IBlockState top = ground.at(index, x, s - 1, z);
                int depth = 0;
                if (top.getMaterial() == Material.WATER)
                    while (depth < MAXIMUM_DEPTH
                            && ground.at(index, x, s - 1 - depth, z).getMaterial()
                                    == Material.WATER) depth++;
                IBlockState bed = depth > 0 ? ground.at(index, x, s - 1 - depth, z) : null;
                Entry e =
                        decide(provider.biome(column, index, x, z), top, bed, depth, x, s, z, true);
                if (e == null) continue;
                int n = height(e, x, z, depth);
                if (e.submerged) {
                    // From the bed up, in place of the water.
                    int base = s - depth;
                    for (int i = 0; i < n; i++)
                        if (base + i >= y0 && base + i < y0 + 16)
                            primer.setBlockState(lx, base + i - y0, lz, block(e, i, n, x, z));
                    continue;
                }
                if (!surface) continue;
                if (n == 1) {
                    if (s >= y0
                            && primer.getBlockState(lx, s - y0, lz).getMaterial() == Material.AIR)
                        primer.setBlockState(lx, s - y0, lz, e.state);
                    continue;
                }
                if (!clear(ground, index, x, s, z, n)) continue;
                for (int i = 0; i < n; i++)
                    if (s + i >= y0 && s + i < y0 + 16)
                        primer.setBlockState(lx, s + i - y0, lz, block(e, i, n, x, z));
            }
    }

    /** The n blocks from the surface up are free of terrain and trees. */
    private boolean clear(Ground ground, int index, int x, int s, int z, int n) {
        for (int y = s; y < s + n; y++)
            if (ground.at(index, x, y, z).getMaterial() != Material.AIR || trees.occupied(x, y, z))
                return false;
        return true;
    }

    /**
     * The ground cover of one preview column: its blocks from the surface up, given the column's
     * own runs before boulders and trees, and which heights terrain or trees hold. A cell over two
     * blocks wide draws no cane: its column is rarely a margin, and checking one resolves its
     * neighbours' native blocks. From the footprint where the preview leaves boulders out, cover no longer
     * avoids them either, so a coarse cell asks no boulder lookup.
     */
    Placement preview(
            Biome reported,
            IBlockState groundBlock,
            IBlockState bed,
            int depth,
            int x,
            int s,
            int z,
            int footprint,
            IntPredicate taken) {
        if (footprint < TerrainColumn.BOULDER_FOOTPRINT && provider.maps.boulders.at(x, z) != null)
            return null;
        Entry e = decide(reported, groundBlock, bed, depth, x, s, z, footprint <= 2);
        if (e == null) return null;
        int n = height(e, x, z, depth);
        int base = e.submerged ? s - depth : s;
        if (!e.submerged) for (int y = s; y < s + n; y++) if (taken.test(y)) return null;
        IBlockState[] out = new IBlockState[n];
        for (int i = 0; i < n; i++) out[i] = block(e, i, n, x, z);
        return new Placement(base, out);
    }

    /** A preview column's cover: its blocks from base up (under the surface when submerged). */
    static final class Placement {
        final int base;
        final IBlockState[] states;

        Placement(int base, IBlockState[] states) {
            this.base = base;
            this.states = states;
        }
    }

    private static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = x - ix, fz = z - iz;
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = corner(seed, ix, iz), b = corner(seed, ix + 1, iz);
        double c = corner(seed, ix, iz + 1), d = corner(seed, ix + 1, iz + 1);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
    }

    private static double corner(long seed, int x, int z) {
        return SeededNoise.unit(SeededNoise.cell(seed, x, z));
    }
}
