package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.BuildPool;
import cc.orthrus.riven.worldgen.RegionalIdentity;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.WorldScale;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ClientPreviewInputs;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewFactories;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Riven's terrain preview: every column as vertical runs of block states, the one sampler native
 * cubes' distant stand-in and any distant renderer share. It holds only what a column reads (the
 * seed maps, tree sites and ground cover) and is a pure function of seed, layout scale and the
 * world's recorded definitions, so it is built the same way with a world (the server's generator
 * hands over its own maps) and without one ({@link #fromServer}: on a client, from what the
 * server sent at join, with no world, no save directory and no live biome provider).
 */
final class TerrainPreview implements ICubicTerrainPreview {
    private static final TerrainHeightReference HEIGHTS = TerrainHeightReference.DEFAULT;
    private static final Logger LOG = LogManager.getLogger("riven");

    /** The name Riven's client factory is registered under, and its server's offers carry. */
    static final String FACTORY = "riven";

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

    /** Registers the factory that builds a client's preview from what its server sent at join. */
    static void registerFactory() {
        TerrainPreviewFactories.register(FACTORY, TerrainPreview::fromServer);
    }

    /**
     * A client's preview from its server's offer, or null to decline: the definitions cannot be
     * resolved here, or the identity they give with this build's packaged definitions and this
     * client's blocks is not the server's. The identity is compared before any map is built.
     */
    static TerrainPreview fromServer(ClientPreviewInputs inputs) {
        long seed = inputs.seed();
        WorldScale scale;
        Mappings.Snapshot snapshot;
        try {
            scale = WorldScale.parse(inputs.generatorOptions());
            snapshot = Mappings.read(inputs.recordedDefinitions());
        } catch (RuntimeException unreadable) {
            LOG.warn("Riven declines the server's terrain preview: {}", unreadable.toString());
            return null;
        }
        String identity = identity(seed, scale, snapshot);
        if (!identity.equals(inputs.identity())) {
            LOG.warn(
                    "Riven declines the server's terrain preview: its identity {} is not this"
                            + " client's {} (another Riven build, or a block resolved differently)",
                    inputs.identity(),
                    identity);
            return null;
        }
        // The host offers no derived-data directory yet, so regional data stays in memory.
        ClimateBiomeProvider biomes =
                new ClimateBiomeProvider(seed, scale, inputs.buildPool(), null);
        TreeSites trees = new TreeSites(seed, biomes, HEIGHTS, snapshot);
        LOG.info("Riven terrain preview {} built from the server's definitions", identity);
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
