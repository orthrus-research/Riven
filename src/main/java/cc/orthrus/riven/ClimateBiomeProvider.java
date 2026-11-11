package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Habitats;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.RegionalStore;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.Variants;
import cc.orthrus.riven.worldgen.WorldGenerationContext;
import cc.orthrus.riven.worldgen.WorldScale;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/** Owns biome selection at world construction, before columns and cubes are generated. */
final class ClimateBiomeProvider extends BiomeProvider {
    private static final Logger LOG = LogManager.getLogger("riven");

    final GenerationMaps maps;
    final WorldGenerationContext context;
    private final List<Biome> spawn;

    ClimateBiomeProvider(long seed, WorldScale scale, ForkJoinPool pool, RegionalStore store) {
        context = new WorldGenerationContext(seed, scale, pool, store);
        maps = context.maps;
        LOG.info("Riven land: {}", maps.land);
        LOG.info("Riven provinces: {}", maps.provinces.summary());
        // Spawn search stays on land: the biomes inland habitat keys report.
        spawn = RivenBiomes.inland();
    }

    /** The riven: biome a complete sample reports. */
    Biome biome(TerrainMap.Sample sample, int x, int z) {
        return RivenBiomes.select(Habitats.key(sample));
    }

    /** The riven: biome one column of a resolved chunk column reports. */
    Biome biome(NativeColumns.Column column, int index, int x, int z) {
        return RivenBiomes.select(column.key(index));
    }

    /** The variant (Variants) column (x, z) has inside the riven: biome it reports, or NONE. */
    int variant(Biome biome, int x, int z) {
        if (!(biome instanceof RivenBiomeTypes.RivenBiome)) return Variants.NONE;
        return maps.variants.at(x, z, ((RivenBiomeTypes.RivenBiome) biome).definition().index);
    }

    static boolean inWorld(long x, long z) {
        return x >= -30000000 && x < 30000000 && z >= -30000000 && z < 30000000;
    }

    /** The same resolved chunk column as native biome arrays, so queries never disagree with them. */
    Biome at(int x, int z) {
        if (!inWorld(x, z)) return RivenBiomes.select("OCEAN");
        return biome(maps.columns.column(x >> 4, z >> 4), ((z & 15) << 4) | (x & 15), x, z);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return at(pos.getX(), pos.getZ());
    }

    @Override
    public Biome getBiome(BlockPos pos, Biome fallback) {
        return getBiome(pos);
    }

    @Override
    public Biome[] getBiomesForGeneration(Biome[] out, int x, int z, int width, int height) {
        return fill(out, x, z, width, height, 4);
    }

    @Override
    public Biome[] getBiomes(Biome[] out, int x, int z, int width, int height) {
        return fill(out, x, z, width, height, 1);
    }

    @Override
    public Biome[] getBiomes(Biome[] out, int x, int z, int width, int height, boolean cache) {
        return fill(out, x, z, width, height, 1);
    }

    private Biome[] fill(Biome[] out, int x, int z, int width, int height, int scale) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative biome query");
        int size = Math.multiplyExact(width, height);
        if (out == null || out.length < size) out = new Biome[size];
        for (int dz = 0; dz < height; dz++)
            for (int dx = 0; dx < width; dx++) {
                out[dz * width + dx] =
                        at(Math.multiplyExact(x + dx, scale), Math.multiplyExact(z + dz, scale));
            }
        return out;
    }

    @Override
    public List<Biome> getBiomesToSpawnIn() {
        return spawn;
    }

    @Override
    public boolean isFixedBiome() {
        return false;
    }

    @Override
    public Biome getFixedBiome() {
        return null;
    }

    @Override
    public void cleanupCache() {}

    @Override
    public boolean areBiomesViable(int x, int z, int radius, List<Biome> allowed) {
        for (int qz = (z - radius) >> 2; qz <= (z + radius) >> 2; qz++)
            for (int qx = (x - radius) >> 2; qx <= (x + radius) >> 2; qx++)
                if (!allowed.contains(at(qx * 4, qz * 4))) return false;
        return true;
    }

    @Override
    public BlockPos findBiomePosition(
            int x, int z, int radius, List<Biome> allowed, Random random) {
        int count = 0;
        BlockPos found = null;
        for (int qz = (z - radius) >> 2; qz <= (z + radius) >> 2; qz++)
            for (int qx = (x - radius) >> 2; qx <= (x + radius) >> 2; qx++)
                if (allowed.contains(at(qx * 4, qz * 4)) && random.nextInt(++count) == 0)
                    found = new BlockPos(qx * 4, 0, qz * 4);
        return found;
    }
}
