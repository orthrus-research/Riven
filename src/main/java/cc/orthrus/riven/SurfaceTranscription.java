package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.TerrainBlock;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.NoiseGeneratorPerlin;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A biome's own surface routine ({@code genTerrainBlocks}: BoP's mud patches, alternate top blocks,
 * podzol, vanilla's coarse dirt) applied to the soil Riven placed, never to the land's shape. The
 * routine runs on a scratch column where Riven's soil reads as stone (the only block it converts),
 * Riven's own stone as a block it leaves alone, and every other block as itself; only changes on
 * Riven's soil to solid blocks are kept, and stone where the routine itself chose stone for its top
 * or filler (chaparral's rocky patches). Pure per column given the seed: the routine gets a
 * column-seeded random and the vanilla surface noise, the same for native cubes and the DH preview.
 * Many routines rewrite their biome's top and filler fields per column, so each runs under its
 * biome's lock from the biome's own blocks as built, and leaves them so.
 */
final class SurfaceTranscription {
    /** Highest Y a vanilla-height routine can address, and the band kept below the ground top. */
    private static final int TOP = 255, BAND = 8;

    /** Stands in for Riven's stone: a block surface routines never convert. */
    private static final IBlockState HELD = Blocks.BEDROCK.getDefaultState();

    /** One chunk column's kept surface: per column the ground top and its band, or null. */
    static final class Surface {
        final long key;
        final int[] tops = new int[256];
        final IBlockState[][] bands = new IBlockState[256][];

        Surface(long key) {
            this.key = key;
        }

        /** Bottom of a column's band, or a large value where none was kept. */
        int bandBottom(int index) {
            return bands[index] == null ? Integer.MAX_VALUE : tops[index] - BAND;
        }

        int bandTop(int index) {
            return tops[index];
        }

        /** The kept state at a column and Y, or null to use Riven's own. */
        IBlockState at(int index, int y) {
            IBlockState[] band = bands[index];
            int i = y - (tops[index] - BAND);
            return band == null || i < 0 || i >= BAND ? null : band[i];
        }
    }

    private final ClimateBiomeProvider biomes;
    private final World world;
    private final NoiseGeneratorPerlin noise;
    private final long seed;
    private final AtomicReferenceArray<Surface> memo = new AtomicReferenceArray<>(1024);

    /** Top and filler of looks the provider did not build, as first seen (vanilla habitats). */
    private static final java.util.Map<Biome, IBlockState[]> SEEN =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final ThreadLocal<ChunkPrimer> primers = ThreadLocal.withInitial(ChunkPrimer::new);

    SurfaceTranscription(ClimateBiomeProvider biomes, World world) {
        this.biomes = biomes;
        this.world = world;
        seed = world.getSeed();
        noise = new NoiseGeneratorPerlin(new Random(seed ^ 0x5355524641434531L), 4);
    }

    Surface surface(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
        int slot = (int) (mix(key) & (memo.length() - 1));
        Surface s = memo.get(slot);
        if (s != null && s.key == key) return s;
        s = build(key, chunkX, chunkZ);
        memo.set(slot, s);
        return s;
    }

    private Surface build(long key, int chunkX, int chunkZ) {
        Surface out = new Surface(key);
        NativeColumns.Column column = biomes.maps.columns.column(chunkX, chunkZ);
        TerrainBlock[] line = new TerrainBlock[16];
        TerrainBlock[] riven = new TerrainBlock[TOP + 1];
        for (int index = 0; index < 256; index++) {
            int x = chunkX * 16 + (index & 15), z = chunkZ * 16 + (index >> 4);
            int ground = groundTop(column, index, line);
            out.tops[index] = ground;
            if (ground - BAND < 1 || ground > TOP) continue;
            for (int base = 0; base <= TOP; base += 16) {
                column.cube(index, base, line);
                System.arraycopy(line, 0, riven, base, 16);
            }
            out.bands[index] = band(riven, ground, biomes.look(column, index, x, z), x, z);
        }
        return out;
    }

    /**
     * One column for the distant preview, from the blocks it already resolved (Y 0 to 255) and its
     * ground top: the same routine and the same kept band as the chunk path, without resolving the
     * other 255 columns of its chunk. The result has the column at index 0.
     */
    Surface column(TerrainBlock[] riven, int ground, Biome biome, int x, int z) {
        Surface out = new Surface(0);
        out.tops[0] = ground;
        if (ground - BAND >= 1 && ground <= TOP) out.bands[0] = band(riven, ground, biome, x, z);
        return out;
    }

    /** The biome routine's kept band for one column, or null where it kept nothing. */
    private IBlockState[] band(TerrainBlock[] riven, int ground, Biome biome, int x, int z) {
        ChunkPrimer primer = primers.get();
        int lx = x & 15, lz = z & 15;
        for (int y = 0; y <= TOP; y++) {
            IBlockState state = scratch(riven[y]);
            // Routines index the primer (z, y, x) or (x, y, z); fill both, read back either.
            primer.setBlockState(lz, y, lx, state);
            primer.setBlockState(lx, y, lz, state);
        }
        Random random =
                new Random(mix(seed ^ mix(x * 0x9e3779b97f4a7c15L) ^ (z * 0xc2b2ae3d27d4eb4fL)));
        IBlockState[] soil = soil(biome);
        IBlockState chosenTop, chosenFiller;
        synchronized (biome) {
            biome.topBlock = soil[0];
            biome.fillerBlock = soil[1];
            try {
                biome.genTerrainBlocks(
                        world, random, primer, x, z, noise.getValue(x * .0625, z * .0625));
            } catch (RuntimeException ignored) {
                // A routine that cannot run on this column leaves Riven's surface.
                return null;
            } finally {
                chosenTop = biome.topBlock;
                chosenFiller = biome.fillerBlock;
                biome.topBlock = soil[0];
                biome.fillerBlock = soil[1];
            }
        }
        IBlockState[] band = null;
        for (int i = 0; i < BAND; i++) {
            int y = ground - BAND + i;
            if (riven[y] != TerrainBlock.GRASS && riven[y] != TerrainBlock.DIRT) continue;
            IBlockState a = primer.getBlockState(lz, y, lx), b = primer.getBlockState(lx, y, lz);
            IBlockState kept = a != STONE ? a : b;
            // Stone left in place is Riven's soil untouched, unless the routine chose stone.
            boolean chose = (riven[y] == TerrainBlock.GRASS ? chosenTop : chosenFiller) == STONE;
            if ((kept == STONE && !chose) || !kept.getMaterial().isSolid() || !kept.isFullCube())
                continue;
            if (band == null) band = new IBlockState[BAND];
            band[i] = kept;
        }
        return band;
    }

    private static final IBlockState STONE = Blocks.STONE.getDefaultState();

    /** A look's top and filler as built (the provider's), or as first seen (vanilla habitats). */
    static IBlockState[] soil(Biome biome) {
        IBlockState[] built = BiomeProvision.isProviderLook(biome) ? BopLooks.soil(biome) : null;
        if (built != null) return built;
        return SEEN.computeIfAbsent(biome, b -> new IBlockState[] {b.topBlock, b.fillerBlock});
    }

    private static IBlockState scratch(TerrainBlock block) {
        switch (block) {
            case GRASS:
            case DIRT:
                return STONE;
            case STONE:
            case BEDROCK:
            case GRANITE:
            case DIORITE:
            case ANDESITE:
                // Stone variants are the stone block too, which routines would convert.
                return HELD;
            default:
                return TerrainColumn.state(block);
        }
    }

    /** One above the highest solid block of a column (water and air above it excluded). */
    private static int groundTop(NativeColumns.Column column, int index, TerrainBlock[] line) {
        int top = column.surface(index);
        for (int base = Math.floorDiv(top - 1, 16) * 16; base > top - 64; base -= 16) {
            column.cube(index, base, line);
            for (int y = 15; y >= 0; y--) {
                int at = base + y;
                if (at >= top) continue;
                if (line[y] != TerrainBlock.AIR && line[y] != TerrainBlock.WATER) return at + 1;
            }
        }
        return top;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
