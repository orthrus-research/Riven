package cc.orthrus.riven;

import cc.orthrus.riven.content.GroundPlant;
import cc.orthrus.riven.content.Grounds;
import cc.orthrus.riven.worldgen.Boulders;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;
import cc.orthrus.riven.worldgen.TerrainBlock;

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
 * native blocks of a neighbouring column. Each cube writes the blocks inside itself after its trees,
 * and the preview draws the same blocks, so distant ground keeps the colour the plants give it.
 */
final class GroundCover {
    private static final Rules RULES = Rules.load("riven-ground-cover.json", 1);
    static final String FINGERPRINT = RULES.fingerprint;

    /** The block native generation writes at a position before boulders and trees. */
    interface Ground {
        IBlockState at(int index, int x, int y, int z);
    }

    private static final class Entry {
        final IBlockState state;
        final GroundPlant.Tall tall;
        final GroundPlant.Cane cane;
        final int ground, shortest, tallest;
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
            // A cane's entry gives its stack's height; other plants have their own.
            if (cane != null) {
                JsonObject height = j.getAsJsonObject("height");
                if (height == null)
                    throw new IllegalStateException("A cane needs its height: " + text);
                shortest = count(height, "min", 1, 8);
                tallest = count(height, "max", shortest, 8);
            } else if (j.has("height")) {
                throw new IllegalStateException("Only a cane has a height: " + text);
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
            this.seed = seed;
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

    private final long seed;
    private final Map<Biome, Entry[]> biomes = new IdentityHashMap<>();
    private final Set<Block> blocks = new HashSet<>();

    /** How far above its surface any entry's plant reaches: its tallest stack less one. */
    private int reach;

    private final ClimateBiomeProvider provider;
    private final TreeSites trees;

    GroundCover(long worldSeed, ClimateBiomeProvider provider, TreeSites trees) {
        JsonObject j = RULES.json;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(j, "variationSeed"));
        JsonObject palettes = j.getAsJsonObject("palettes");
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("biomes").entrySet()) {
            Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(e.getKey()));
            if (biome == null) throw new IllegalStateException("Unknown biome " + e.getKey());
            // A biome lists entries and palettes, a palette optionally at a scaled density.
            List<JsonObject> list = new ArrayList<>();
            List<Double> scales = new ArrayList<>();
            for (JsonElement item : e.getValue().getAsJsonArray()) {
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
                // Each entry's draws and drifts are its own, by biome and position in the list.
                long entrySeed =
                        SeededNoise.mix(seed ^ e.getKey().hashCode() * 0x9e3779b97f4a7c15L + i);
                entries[i] = new Entry(list.get(i), scales.get(i), entrySeed);
                if (entries[i].density > 1)
                    throw new IllegalStateException("Range: scaled density in " + e.getKey());
                blocks.add(entries[i].state.getBlock());
                reach = Math.max(reach, entries[i].tallest - 1);
            }
            biomes.put(biome, entries);
        }
        this.provider = provider;
        this.trees = trees;
    }

    /** Whether a block is one ground cover places (parity checks). */
    boolean places(Block block) {
        return blocks.contains(block);
    }

    /**
     * The entry that decides a column whose surface is s, or null. canes says whether a cane may
     * decide; without it, cane entries pass as if no water lay beside the ground.
     */
    private Entry decide(Biome biome, IBlockState ground, int x, int s, int z, boolean canes) {
        Entry[] entries = biomes.get(biome);
        if (entries == null || ground.getMaterial() == Material.AIR) return null;
        for (Entry e : entries) {
            if (!e.fits(ground)) continue;
            if (SeededNoise.unit(SeededNoise.cell(e.seed, x, z)) >= e.threshold(x, z)) continue;
            if (e.cane != null && !(canes && waterBeside(x, s - 1, z))) continue;
            return e;
        }
        return null;
    }

    /** Whether a neighbouring column holds water at Y in its native blocks. */
    private boolean waterBeside(int x, int y, int z) {
        for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
            int nx = x + side.getXOffset(), nz = z + side.getZOffset();
            if (!ClimateBiomeProvider.inWorld(nx, nz)) continue;
            NativeColumns.Column column = provider.maps.columns.column(nx >> 4, nz >> 4);
            if (column.block(((nz & 15) << 4) | (nx & 15), y) == TerrainBlock.WATER) return true;
        }
        return false;
    }

    /** How many blocks an entry's plant stands at a column: a cane's height by position. */
    private int height(Entry e, int x, int z) {
        if (e.shortest == e.tallest) return e.shortest;
        long heightSeed = SeededNoise.mix(e.seed ^ 0x3c6ef372fe94f82bL);
        double unit = SeededNoise.unit(SeededNoise.cell(heightSeed, x, z));
        return e.shortest + (int) (unit * (e.tallest - e.shortest + 1));
    }

    /** Block i (from 0 at the surface) of an entry's n-block plant at a column. */
    private IBlockState block(Entry e, int i, int n, int x, int z) {
        if (n == 1) return e.state;
        // A two-block plant or a cane takes its form by position.
        long formSeed = SeededNoise.mix(e.seed ^ 0xbb67ae8584caa73bL);
        double unit = SeededNoise.unit(SeededNoise.cell(formSeed, x, z));
        if (e.tall != null) return e.tall.part(i == 1, (int) (unit * e.tall.forms()));
        return e.cane.piece(i == n - 1, (int) (unit * e.cane.forms()));
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
                if (s < y0 - reach || s >= y0 + 16) continue;
                int x = (cubeX << 4) + lx, z = (cubeZ << 4) + lz;
                if (boulders.at(x, z) != null) continue;
                Entry e =
                        decide(
                                provider.biome(column, index, x, z),
                                ground.at(index, x, s - 1, z),
                                x,
                                s,
                                z,
                                true);
                if (e == null) continue;
                int n = height(e, x, z);
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
     * blocks wide draws no cane: its column is rarely a margin, and checking one reads a native
     * chunk column.
     */
    IBlockState[] preview(
            Biome reported,
            IBlockState groundBlock,
            int x,
            int s,
            int z,
            int footprint,
            IntPredicate taken) {
        if (provider.maps.boulders.at(x, z) != null) return null;
        Entry e = decide(reported, groundBlock, x, s, z, footprint <= 2);
        if (e == null) return null;
        int n = height(e, x, z);
        for (int y = s; y < s + n; y++) if (taken.test(y)) return null;
        IBlockState[] out = new IBlockState[n];
        for (int i = 0; i < n; i++) out[i] = block(e, i, n, x, z);
        return out;
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
