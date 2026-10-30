package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.ColumnBlocks;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.RealmMap;
import cc.orthrus.riven.worldgen.RivenBiomeTable;
import cc.orthrus.riven.worldgen.TerrainBlock;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.WorldScale;

import io.github.opencubicchunks.cubicchunks.api.util.Box;
import io.github.opencubicchunks.cubicchunks.api.util.IntRange;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldType;
import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.chunk.Chunk;

import org.apache.logging.log4j.LogManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;

/** Direct terrain generation. CC owns cube lifetime, lighting and storage. */
final class RivenWorldType extends WorldType implements ICubicWorldType {
    private static final TerrainHeightReference HEIGHTS = TerrainHeightReference.DEFAULT;

    RivenWorldType() {
        super("riven");
    }

    @Override
    public BiomeProvider getBiomeProvider(World world) {
        // Remote clients consume authoritative chunk biome arrays, never a guessed seed.
        return world.isRemote
                ? new BiomeProviderSingle(Biomes.PLAINS)
                : new ClimateBiomeProvider(
                        world.getSeed(),
                        WorldScale.parse(world.getWorldInfo().getGeneratorOptions()));
    }

    /** Vanilla clouds and shader packs (Iris's cloudHeight uniform) both read this. */
    @Override
    public float getCloudHeight() {
        return HEIGHTS.cloudBaseY;
    }

    @Override
    public boolean hasCubicGeneratorForWorld(World world) {
        return world.provider.getDimension() == 0;
    }

    @Override
    public IntRange calculateGenerationHeightRange(WorldServer world) {
        return new IntRange(HEIGHTS.worldMinimumY, HEIGHTS.maximumYExclusive);
    }

    @Override
    public ICubeGenerator createCubeGenerator(World world) {
        world.setSeaLevel(HEIGHTS.seaLevel);
        return new Generator((ClimateBiomeProvider) world.getBiomeProvider(), world);
    }

    /** Decoration failures already logged, one line per biome and message. */
    private static final java.util.Set<String> DECORATION_FAILURES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The recorded definitions of a Riven cubic server world, or null. */
    static Mappings.Snapshot snapshot(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.snapshot;
    }

    private static Generator generator(World world) {
        if (!(world instanceof io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer))
            return null;
        ICubeGenerator generator =
                ((io.github.opencubicchunks.cubicchunks.api.world.ICubicWorldServer) world)
                        .getCubeGenerator();
        return generator instanceof Generator ? (Generator) generator : null;
    }

    /** The tree sites of a Riven cubic server world, or null. */
    static TreeSites trees(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.trees;
    }

    /** The surface transcription of a Riven cubic server world, or null (diagnostics). */
    static SurfaceTranscription surfaces(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.surfaces;
    }

    /** The ground cover of a Riven cubic server world, or null. */
    static GroundCover cover(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.cover;
    }

    /** The realms below the crust floor of a Riven cubic server world, or null. */
    static RealmMap realms(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.realms;
    }

    /** Cubes above and below the anchor that one column's decoration may reach. */
    private static final int DECORATION_BELOW = 2, DECORATION_ABOVE = 3;

    private static final class Generator implements ICubeGenerator, ICubicTerrainPreview {
        private final ClimateBiomeProvider biomes;
        private final World world;

        /** The provider's decoration, or null without the provider (population stays empty). */
        private final BopDecoration decoration;

        /** Biome surface routines on Riven's soil, or null without the provider. */
        private final SurfaceTranscription surfaces;

        Generator(ClimateBiomeProvider biomes, World world) {
            this.biomes = biomes;
            this.world = world;
            decoration =
                    BiomeProvision.active()
                            ? new BopDecoration(
                                    biomes.provision.allowedGenerators(), world.getWorldType())
                            : null;
            surfaces = decoration != null ? new SurfaceTranscription(biomes, world) : null;
            // The world's recorded definitions: resolved once, at creation, then loaded for life.
            snapshot = Mappings.load(world);
            trees =
                    decoration != null
                            ? new TreeSites(world.getSeed(), biomes, surfaces, HEIGHTS, snapshot)
                            : null;
            cover = trees != null ? new GroundCover(world.getSeed(), biomes, trees) : null;
            realms = new RealmMap(world.getSeed(), HEIGHTS);
            List<Biome> realmBiomes = new ArrayList<>();
            for (RealmMap.Realm realm : RealmMap.realms())
                realmBiomes.add(RivenBiomes.of(RivenBiomeTable.byId(realm.biome)));
            this.realmBiomes = realmBiomes.toArray(new Biome[0]);
            List<Biome> depthBiomes = new ArrayList<>();
            for (RealmMap.Depth depth : RealmMap.depths())
                depthBiomes.add(RivenBiomes.of(RivenBiomeTable.byId(depth.biome)));
            this.depthBiomes = depthBiomes.toArray(new Biome[0]);
        }

        /** The realms below the crust floor, and the biome each reports. */
        private final RealmMap realms;

        private final Biome[] realmBiomes;

        /** The crust's depth biomes, by RealmMap.Depth index. */
        private final Biome[] depthBiomes;

        /** The world's resolved definitions (data/riven/definitions.json). */
        private final Mappings.Snapshot snapshot;

        /** Riven's tree sites, grown with the provider's generators, or null without it. */
        private final TreeSites trees;

        /** Riven's ground cover, or null without the provider. */
        private final GroundCover cover;

        @Override
        public Sampler createTerrainPreviewSampler() {
            // The maps are immutable. Only this sampler's scratch sample is mutable.
            TerrainMap.Sample sample = new TerrainMap.Sample();
            return new Sampler() {
                @Override
                public void sampleColumn(int x, int z, ColumnConsumer consumer) {
                    sampleColumn(x, z, 1, consumer);
                }

                /** Cubature 2026-10-25 API; without @Override Riven still builds against older pins. */
                public void sampleColumn(int x, int z, int footprint, ColumnConsumer consumer) {
                    if (!ClimateBiomeProvider.inWorld(x, z)) {
                        consumer.begin(Biomes.PLAINS);
                        consumer.addRun(
                                HEIGHTS.worldMinimumY,
                                HEIGHTS.maximumYExclusive,
                                Blocks.AIR.getDefaultState());
                        return;
                    }
                    biomes.maps.terrain.sample(x, z, sample);
                    TerrainColumn.preview(
                            sample,
                            biomes.look(sample, x, z),
                            biomes.biome(sample, x, z),
                            ColumnBlocks.bareStone(sample, biomes.maps.surfaces),
                            biomes.maps.surfaces,
                            HEIGHTS,
                            surfaces,
                            biomes.maps.boulders,
                            trees,
                            cover,
                            footprint,
                            x,
                            z,
                            consumer);
                }
            };
        }

        /** Cubes are pure functions of seed, definitions and coordinates; every map is thread-safe. */
        @Override
        public boolean supportsConcurrentCubeGeneration() {
            return true;
        }

        /**
         * Warms the shared native column for a Cubature generation worker (Cubature 2026-10-23 API;
         * no @Override so Riven still builds against older Cubature, where this is never called).
         */
        public void prepareColumn(int columnX, int columnZ) {
            long west = (long) columnX * 16, north = (long) columnZ * 16;
            if (ClimateBiomeProvider.inWorld(west, north)
                    && ClimateBiomeProvider.inWorld(west + 15, north + 15))
                biomes.maps.columns.column(columnX, columnZ);
        }

        /**
         * Runs Riven's in-build parallelism on the world's Cubature build pool, or on the calling
         * thread once the world withdraws it (null).
         */
        @Override
        public void useBuildPool(ForkJoinPool pool) {
            biomes.maps.pool.use(pool);
        }

        /** Cubature prepares Riven's regions by hydrology owner cell. */
        @Override
        public int preparationRegionBlocks() {
            return biomes.maps.terrain.hydro.cellBlocks;
        }

        /**
         * Builds a region's drainage, hydrology plan and sediment and rock decisions ahead of
         * demand; a region outside the world needs nothing.
         */
        @Override
        public boolean prepareRegion(int regionX, int regionZ) {
            long size = preparationRegionBlocks(), x = regionX * size, z = regionZ * size;
            if (!ClimateBiomeProvider.inWorld(x, z)
                    && !ClimateBiomeProvider.inWorld(x + size - 1, z + size - 1)) return true;
            return biomes.maps.terrain.prepareRegion(regionX, regionZ);
        }

        @SuppressWarnings("deprecation") // Preserve the older CC generator entry point.
        @Override
        public CubePrimer generateCube(int x, int y, int z) {
            return generateCube(x, y, z, new CubePrimer());
        }

        @Override
        public CubePrimer generateCube(int cubeX, int cubeY, int cubeZ, CubePrimer primer) {
            long bottom = (long) cubeY * 16, top = bottom + 15;
            long west = (long) cubeX * 16, north = (long) cubeZ * 16;
            TerrainMap terrain = biomes.maps.terrain;
            if (!ClimateBiomeProvider.inWorld(west, north)
                    || !ClimateBiomeProvider.inWorld(west + 15, north + 15)
                    || bottom >= HEIGHTS.maximumYExclusive
                    || bottom > terrain.maximumSurfaceY)
                return fill(primer, Blocks.AIR.getDefaultState());
            if (top < HEIGHTS.minimumY) return realmCube(primer, cubeX, cubeY, cubeZ);
            int minimumSoilY =
                    terrain.minimumSurfaceY - ColumnBlocks.materialDepth(biomes.maps.surfaces) + 1;
            boolean aquifer =
                    top < minimumSoilY && terrain.hydro.aquiferIntersects(cubeX, cubeY, cubeZ);
            if (top < minimumSoilY && !aquifer) {
                // Below the lowest soil only rock varies: read the column's runs (its geology and
                // bedrock) without the biome and surface-routine lookups the surface needs.
                NativeColumns.Column deep = biomes.maps.columns.column(cubeX, cubeZ);
                TerrainBlock[] rock = new TerrainBlock[16];
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int index = (z << 4) | x;
                        deep.cube(index, (int) bottom, rock);
                        for (int y = 0; y < 16; y++)
                            primer.setBlockState(
                                    x,
                                    y,
                                    z,
                                    rock(
                                            deep,
                                            index,
                                            (int) west + x,
                                            (int) bottom + y,
                                            (int) north + z,
                                            rock[y],
                                            TerrainColumn.state(rock[y])));
                    }
                return depthBiomes(primer, deep, cubeX, cubeY, cubeZ);
            }
            // Every cube of a chunk column reuses one resolved column (ColumnRunsCheck proves the
            // runs equal per-block resolution).
            NativeColumns.Column column = biomes.maps.columns.column(cubeX, cubeZ);
            TerrainBlock[] line = new TerrainBlock[16];
            SurfaceTranscription.Surface surface =
                    surfaces == null ? null : surfaces.surface(cubeX, cubeZ);
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int index = (z << 4) | x;
                    Biome look = biomes.look(column, index, (int) west + x, (int) north + z);
                    column.cube(index, (int) bottom, line);
                    for (int y = 0; y < 16; y++) {
                        IBlockState kept =
                                surface == null ? null : surface.at(index, (int) bottom + y);
                        primer.setBlockState(
                                x,
                                y,
                                z,
                                kept != null
                                        ? kept
                                        : rock(
                                                column,
                                                index,
                                                (int) west + x,
                                                (int) bottom + y,
                                                (int) north + z,
                                                line[y],
                                                TerrainColumn.state(line[y], look)));
                    }
                }
            // Boulders and trees are part of the cube: each cube writes their blocks inside itself,
            // boulders first, so trees (whose sites see boulders as ground) grow around them.
            TerrainColumn.placeBoulders(biomes.maps.boulders, primer, cubeX, cubeY, cubeZ);
            if (trees != null) trees.place(primer, cubeX, cubeY, cubeZ);
            // Ground cover last, into the air boulders and trees leave on each column's surface.
            if (cover != null) {
                TerrainBlock[] at = new TerrainBlock[16];
                cover.place(
                        primer,
                        cubeX,
                        cubeY,
                        cubeZ,
                        column,
                        (index, x, y, z) -> {
                            IBlockState kept = surface == null ? null : surface.at(index, y);
                            if (kept != null) return kept;
                            column.cube(index, Math.floorDiv(y, 16) * 16, at);
                            TerrainBlock block = at[Math.floorMod(y, 16)];
                            return rock(
                                    column,
                                    index,
                                    x,
                                    y,
                                    z,
                                    block,
                                    TerrainColumn.state(block, biomes.look(column, index, x, z)));
                        });
            }
            return depthBiomes(primer, column, cubeX, cubeY, cubeZ);
        }

        /**
         * A column block's state: its ore where the rock holds one, else its unstable stone where
         * the floor is unstable, else {@code state}.
         */
        private static IBlockState rock(
                NativeColumns.Column column,
                int index,
                int x,
                int y,
                int z,
                TerrainBlock block,
                IBlockState state) {
            IBlockState ore = OreBlocks.at(column, index, x, y, z, block, state);
            return ore != state ? ore : UnstableBlocks.at(column, index, y, block, state);
        }

        /**
         * A cube wholly below the ground of all its columns reports a biome per 4x4x4 cell
         * (riven-realms.json crust): the cell centre column's depth biome, or that column's own
         * biome within the surface band. A cube that holds any ground keeps the column biomes, so
         * the surface, open entrances and every lookup at the precipitation height are unchanged.
         */
        private CubePrimer depthBiomes(
                CubePrimer primer, NativeColumns.Column column, int cubeX, int cubeY, int cubeZ) {
            int bottom = cubeY * 16;
            if (bottom + 15 >= column.minimumGround()) return primer;
            for (int bz = 0; bz < 4; bz++)
                for (int bx = 0; bx < 4; bx++) {
                    Biome own = null;
                    for (int by = 0; by < 4; by++) {
                        int code = column.depth(bz * 4 + bx, bottom + by * 4 + 2);
                        if (code == 0 && own == null)
                            own =
                                    biomes.biome(
                                            column,
                                            ((bz * 4 + 2) << 4) | (bx * 4 + 2),
                                            cubeX * 16 + bx * 4 + 2,
                                            cubeZ * 16 + bz * 4 + 2);
                        primer.setBiome(bx, by, bz, code == 0 ? own : depthBiomes[code - 1]);
                    }
                }
            return primer;
        }

        /**
         * A cube below the crust floor: realm rock, with the crust's floor rock mixed in just under
         * the floor, and each 4x4x4 cell reporting its realm's biome. The crust floor lies on a cube
         * boundary, so a realm cube never holds crust blocks.
         */
        private CubePrimer realmCube(CubePrimer primer, int cubeX, int cubeY, int cubeZ) {
            int bottom = cubeY * 16;
            if (bottom + 15 < HEIGHTS.worldMinimumY)
                return fill(primer, Blocks.AIR.getDefaultState());
            // Only cubes within the first transition band need the crust's own floor rock.
            NativeColumns.Column crust =
                    bottom + 16 > HEIGHTS.minimumY - realms.band()
                            ? biomes.maps.columns.column(cubeX, cubeZ)
                            : null;
            TerrainBlock[] floor = new TerrainBlock[16];
            double[] tops = new double[RealmMap.realms().size()];
            for (int lz = 0; lz < 16; lz++)
                for (int lx = 0; lx < 16; lx++) {
                    int x = cubeX * 16 + lx, z = cubeZ * 16 + lz;
                    TerrainBlock crustBlock = TerrainBlock.STONE;
                    if (crust != null) {
                        crust.cube((lz << 4) | lx, HEIGHTS.minimumY, floor);
                        crustBlock = floor[0];
                    }
                    realms.tops(x, z, tops);
                    for (int ly = 0; ly < 16; ly++)
                        primer.setBlockState(
                                lx,
                                ly,
                                lz,
                                TerrainColumn.state(
                                        realms.block(x, bottom + ly, z, tops, crustBlock)));
                }
            for (int bz = 0; bz < 4; bz++)
                for (int bx = 0; bx < 4; bx++) {
                    realms.tops(cubeX * 16 + bx * 4 + 2, cubeZ * 16 + bz * 4 + 2, tops);
                    for (int by = 0; by < 4; by++) {
                        int realm = RealmMap.realm(bottom + by * 4 + 2, tops);
                        primer.setBiome(bx, by, bz, realmBiomes[Math.max(0, realm)]);
                    }
                }
            return primer;
        }

        private static CubePrimer fill(CubePrimer primer, IBlockState state) {
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++) primer.setBlockState(x, y, z, state);
            return primer;
        }

        @Override
        public void generateColumn(Chunk column) {
            byte[] ids = column.getBiomeArray();
            if (!ClimateBiomeProvider.inWorld((long) column.x * 16, (long) column.z * 16)) {
                Arrays.fill(ids, (byte) Biome.getIdForBiome(Biomes.PLAINS));
                return;
            }
            // The same resolved column as the cubes; its habitat equals surfaceHabitat's.
            NativeColumns.Column resolved = biomes.maps.columns.column(column.x, column.z);
            for (int i = 0; i < 256; i++)
                ids[i] =
                        (byte)
                                Biome.getIdForBiome(
                                        biomes.biome(
                                                resolved,
                                                i,
                                                column.x * 16 + (i & 15),
                                                column.z * 16 + (i >> 4)));
        }

        /**
         * Provider decoration (riven-bop.json). Each chunk column decorates its vanilla offset area
         * once, in the cube holding the surface at that area's centre, with a vanilla-style seed:
         * first the biome's own decorator (vanilla's, filtered by {@link DecorationFilter}), then
         * the provider's allowed generators.
         * Placements that cross into neighbouring chunks depend on population order: the one
         * recorded exception to order independence (docs/DECISIONS.md).
         */
        @Override
        public void populate(ICube cube) {
            if (decoration == null) return;
            int cubeX = cube.getX(), cubeZ = cube.getZ();
            int ax = cubeX * 16 + 16, az = cubeZ * 16 + 16;
            if (!ClimateBiomeProvider.inWorld(cubeX * 16L, cubeZ * 16L)
                    || !ClimateBiomeProvider.inWorld(ax + 16L, az + 16L)) return;
            NativeColumns.Column anchor = biomes.maps.columns.column(ax >> 4, az >> 4);
            int index = ((az & 15) << 4) | (ax & 15), surface = anchor.surface(index);
            if (Math.floorDiv(surface, 16) != cube.getY()) return;
            Random random = new Random(world.getSeed());
            long a = random.nextLong() / 2 * 2 + 1, b = random.nextLong() / 2 * 2 + 1;
            random.setSeed(cubeX * a + cubeZ * b ^ world.getSeed());
            BlockPos corner = new BlockPos(cubeX * 16, 0, cubeZ * 16);
            // Decoration follows the area centre's legacy look, not the riven: biome it reports.
            Biome biome = biomes.lookAt(corner.getX() + 16, corner.getZ() + 16);
            // Vanilla decorators assume heights of zero and up (a roofed forest over a sea floor
            // below Y0 asks Random for a negative bound). Decoration is optional, so a decorator
            // that fails skips this chunk instead of stopping the server.
            // Tree sites already stand in the cubes; the look's own tree stages stand aside.
            try {
                biome.decorate(world, random, corner);
                decoration.decorate(world, random, corner, biome);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                if (DECORATION_FAILURES.add(BiomeProvision.lookName(biome) + ": " + e.getMessage()))
                    LogManager.getLogger("Riven")
                            .warn(
                                    "Skipped decoration of {} at {} after {}; further failures of this kind are not logged",
                                    BiomeProvision.lookName(biome),
                                    corner,
                                    e.toString());
            }
            // A chunk column's first animals, from the riven: biome its surface reports (not the
            // biome at Y 0, which may be a depth biome) and their own seed, so the decoration
            // sequence above stays as it was.
            net.minecraft.world.WorldEntitySpawner.performWorldGenSpawning(
                    world,
                    biomes.biome(anchor, index, ax, az),
                    corner.getX() + 8,
                    corner.getZ() + 8,
                    16,
                    16,
                    new Random(world.getSeed() ^ (cubeX * 341873128712L + cubeZ * 132897987541L)));
        }

        /** Decorations of the columns at and below/west/north of a cube reach into it. */
        @Override
        public Box getFullPopulationRequirements(ICube cube) {
            return decoration == null
                    ? NO_REQUIREMENT
                    : new Box(-1, -DECORATION_ABOVE, -1, 0, DECORATION_BELOW, 0);
        }

        /** An anchor decorates its offset area, spanning the next column east and south. */
        @Override
        public Box getPopulationPregenerationRequirements(ICube cube) {
            return decoration == null
                    ? NO_REQUIREMENT
                    : new Box(0, -DECORATION_BELOW, 0, 1, DECORATION_ABOVE, 1);
        }

        @Override
        public void recreateStructures(ICube cube) {}

        @Override
        public void recreateStructures(Chunk column) {}

        @Override
        public List<Biome.SpawnListEntry> getPossibleCreatures(
                EnumCreatureType type, BlockPos pos) {
            // With the provider, creatures follow the reported biome; without it there are none.
            return decoration == null
                    ? Collections.emptyList()
                    : world.getBiome(pos).getSpawnableList(type);
        }

        @Override
        public BlockPos getClosestStructure(String name, BlockPos pos, boolean unexplored) {
            return null;
        }
    }
}
