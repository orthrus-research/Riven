package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.BuildPool;
import cc.orthrus.riven.worldgen.RegionalIdentity;
import cc.orthrus.riven.worldgen.RegionalStore;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.WorldScale;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

/**
 * Riven's terrain preview: every column as vertical runs of block states, the one sampler native
 * cubes' distant stand-in and any distant renderer share. It holds only what a column reads (the
 * seed maps, tree sites and ground cover) and is a pure function of seed, layout scale and the
 * world's recorded definitions, so it is built the same way with a world (the server's generator
 * hands over its own maps) and without one ({@link #create}: no world, no save directory, no
 * live biome provider).
 */
final class TerrainPreview implements ICubicTerrainPreview {
    private static final TerrainHeightReference HEIGHTS = TerrainHeightReference.DEFAULT;

    private final ClimateBiomeProvider biomes;
    private final TreeSites trees;
    private final GroundCover cover;

    /**
     * What the preview is a function of, hashed: Riven's regional identity (terrain version, seed,
     * exact layout scale bits, every packaged definition's fingerprint), the recorded definitions'
     * fingerprint, and the block each recorded role and each catalogue entry resolved to here. Two
     * sides with equal identities emit equal columns; a side missing a block another has resolves
     * a stand-in and differs.
     */
    final String identity;

    /** The server's preview, on the maps, tree sites and ground cover its generator built. */
    TerrainPreview(
            long seed,
            WorldScale scale,
            Mappings.Snapshot snapshot,
            ClimateBiomeProvider biomes,
            TreeSites trees,
            GroundCover cover) {
        this.biomes = biomes;
        this.trees = trees;
        this.cover = cover;
        identity = identity(seed, scale, snapshot);
    }

    /**
     * A preview from a seed, a layout scale and the recorded definitions as {@link
     * Mappings.Snapshot#bytes} wrote them. Regional builds run on the pool, or on the sampling
     * thread when it is null; a null store keeps derived data in memory only.
     */
    static TerrainPreview create(
            long seed, WorldScale scale, byte[] recorded, ForkJoinPool pool, RegionalStore store) {
        Mappings.Snapshot snapshot = Mappings.read(recorded);
        ClimateBiomeProvider biomes = new ClimateBiomeProvider(seed, scale, pool, store);
        TreeSites trees = new TreeSites(seed, biomes, HEIGHTS, snapshot);
        return new TerrainPreview(
                seed, scale, snapshot, biomes, trees, new GroundCover(seed, biomes, trees));
    }

    static String identity(long seed, WorldScale scale, Mappings.Snapshot snapshot) {
        StringBuilder text = new StringBuilder("riven-preview-1\n");
        text.append(RegionalIdentity.capture(seed, scale).hash()).append('\n');
        text.append(snapshot.fingerprint).append('\n');
        for (Map.Entry<String, IBlockState> role : snapshot.roles.entrySet())
            text.append(role.getKey()).append('=').append(role.getValue()).append('\n');
        text.append(TerrainColumn.resolution());
        return Rules.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Sampler createTerrainPreviewSampler() {
        // The maps are immutable. Only this sampler's scratch sample and buffers are mutable.
        TerrainMap.Sample sample = new TerrainMap.Sample();
        TerrainColumn.Scratch scratch = new TerrainColumn.Scratch();
        return new Sampler() {
            @Override
            public void sampleColumn(int x, int z, ColumnConsumer consumer) {
                sampleColumn(x, z, 1, consumer);
            }

            @Override
            public void sampleColumn(int x, int z, int footprint, ColumnConsumer consumer) {
                if (!ClimateBiomeProvider.inWorld(x, z)) {
                    consumer.begin(Biomes.PLAINS);
                    consumer.addRun(
                            HEIGHTS.worldMinimumY,
                            HEIGHTS.maximumYExclusive,
                            Blocks.AIR.getDefaultState());
                    return;
                }
                // Preview columns build regional plans on this thread alone (BuildPool.alone).
                boolean was = BuildPool.alone(true);
                try {
                    biomes.maps.terrain.sample(x, z, sample);
                    TerrainColumn.preview(
                            sample,
                            biomes.biome(sample, x, z),
                            biomes.maps.columns.assembler(),
                            HEIGHTS,
                            biomes.maps.boulders,
                            trees,
                            cover,
                            footprint,
                            x,
                            z,
                            scratch,
                            consumer);
                } finally {
                    BuildPool.alone(was);
                }
            }
        };
    }
}
