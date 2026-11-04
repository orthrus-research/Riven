package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.BuildPool;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /** The ground cover of a Riven cubic server world, or null. */
    static GroundCover cover(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.cover;
    }

    /** The first animals of a Riven cubic server world, or null. */
    static FirstAnimals animals(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.animals;
    }

    /** The realms below the crust floor of a Riven cubic server world, or null. */
    static RealmMap realms(World world) {
        Generator generator = generator(world);
        return generator == null ? null : generator.realms;
    }

    private static final class Generator implements ICubeGenerator, ICubicTerrainPreview {
        private final ClimateBiomeProvider biomes;
        private final World world;

        Generator(ClimateBiomeProvider biomes, World world) {
            this.biomes = biomes;
            this.world = world;
            // The world's recorded definitions: resolved once, at creation, then loaded for life.
            snapshot = Mappings.load(world);
            trees = new TreeSites(world.getSeed(), biomes, HEIGHTS, snapshot);
            cover = new GroundCover(world.getSeed(), biomes, trees);
            animals = new FirstAnimals(world.getSeed(), biomes);
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

        /** Riven's tree sites. */
        private final TreeSites trees;

        /** Riven's ground cover. */
        private final GroundCover cover;

        /** Riven's population: each chunk column's first animals. */
        private final FirstAnimals animals;

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
                    terrain.minimumSurfaceY - biomes.maps.columns.assembler().materialDepth() + 1;
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
            // Every cube of a chunk column reuses one assembled column (AssembledColumnCheck proves
            // the stored runs equal the engine's).
            NativeColumns.Column column = biomes.maps.columns.column(cubeX, cubeZ);
            TerrainBlock[] line = new TerrainBlock[16];
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int index = (z << 4) | x;
                    column.cube(index, (int) bottom, line);
                    for (int y = 0; y < 16; y++)
                        primer.setBlockState(
                                x,
                                y,
                                z,
                                rock(
                                        column,
                                        index,
                                        (int) west + x,
                                        (int) bottom + y,
                                        (int) north + z,
                                        line[y],
                                        TerrainColumn.state(line[y])));
                }
            // Boulders and trees are part of the cube: each cube writes their blocks inside itself,
            // boulders first, so trees (whose sites see boulders as ground) grow around them.
            TerrainColumn.placeBoulders(biomes.maps.boulders, primer, cubeX, cubeY, cubeZ);
            trees.place(primer, cubeX, cubeY, cubeZ);
            // Ground cover last, into the air boulders and trees leave on each column's surface.
            TerrainBlock[] at = new TerrainBlock[16];
            cover.place(
                    primer,
                    cubeX,
                    cubeY,
                    cubeZ,
                    column,
                    (index, x, y, z) -> {
                        column.cube(index, Math.floorDiv(y, 16) * 16, at);
                        TerrainBlock block = at[Math.floorMod(y, 16)];
                        return rock(column, index, x, y, z, block, TerrainColumn.state(block));
                    });
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
         * Population (riven-population.json): a chunk column's first animals, each spawned by the
         * cube holding its feet. It writes no block and reads only the cube and the cubes above and
         * below it, so it does not depend on the order cubes populate in.
         */
        @Override
        public void populate(ICube cube) {
            animals.populate(world, cube);
        }

        /** Population writes into its own cube alone. */
        @Override
        public Box getFullPopulationRequirements(ICube cube) {
            return NO_REQUIREMENT;
        }

        /** An animal's ground may lie in the cube below and its head in the cube above. */
        @Override
        public Box getPopulationPregenerationRequirements(ICube cube) {
            return new Box(0, -1, 0, 0, 1, 0);
        }

        @Override
        public void recreateStructures(ICube cube) {}

        @Override
        public void recreateStructures(Chunk column) {}

        @Override
        public List<Biome.SpawnListEntry> getPossibleCreatures(
                EnumCreatureType type, BlockPos pos) {
            // Creatures follow the biome the position reports.
            return world.getBiome(pos).getSpawnableList(type);
        }

        @Override
        public BlockPos getClosestStructure(String name, BlockPos pos, boolean unexplored) {
            return null;
        }
    }
}
