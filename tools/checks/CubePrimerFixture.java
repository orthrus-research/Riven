package cc.orthrus.riven;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.structure.template.TemplateManager;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Supplies the actual cube generator with its seed, biome provider and disposable snapshot. */
final class CubePrimerFixture {
    private CubePrimerFixture() {}

    static World create(Path root, long seed, ClimateBiomeProvider biomes) {
        Path directory = Objects.requireNonNull(root).toAbsolutePath().normalize();
        Objects.requireNonNull(biomes);
        if (!Files.isDirectory(directory)
                || !Files.isRegularFile(directory.resolve("data/riven/definitions.json")))
            throw new IllegalArgumentException("The primer fixture needs a recorded snapshot");
        WorldInfo info =
                new WorldInfo(
                        new WorldSettings(seed, GameType.CREATIVE, false, false, WorldType.DEFAULT),
                        "primer-fixture");
        WorldProviderSurface provider =
                new WorldProviderSurface() {
                    @Override
                    protected void init() {
                        hasSkyLight = true;
                        biomeProvider = biomes;
                    }
                };
        World world =
                new World(
                        new DirectorySaveHandler(directory.toFile()),
                        info,
                        provider,
                        new Profiler(),
                        false) {
                    @Override
                    protected IChunkProvider createChunkProvider() {
                        throw unexpected("chunk-provider creation");
                    }

                    @Override
                    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
                        throw unexpected("chunk-residency lookup");
                    }

                    @Override
                    public Chunk getChunk(int x, int z) {
                        throw unexpected("chunk lookup");
                    }

                    @Override
                    public IBlockState getBlockState(BlockPos position) {
                        throw unexpected("world block read");
                    }

                    @Override
                    public boolean setBlockState(BlockPos position, IBlockState state, int flags) {
                        throw unexpected("world block write");
                    }

                    @Override
                    public Biome getBiome(BlockPos position) {
                        throw unexpected("world biome lookup");
                    }

                    @Override
                    public int getHeight(int x, int z) {
                        throw unexpected("world surface-height lookup");
                    }

                    @Override
                    public int getHeight() {
                        throw unexpected("world height lookup");
                    }

                    @Override
                    public int getActualHeight() {
                        throw unexpected("world actual-height lookup");
                    }
                };
        provider.setWorld(world);
        if (world.getSeed() != seed
                || provider.getBiomeProvider() != biomes
                || world.getBiomeProvider() != biomes)
            throw new AssertionError(
                    "Primer fixture did not associate its real seed/biome provider");
        return world;
    }

    private static AssertionError unexpected(String action) {
        return new AssertionError("Primer fixture attempted " + action);
    }

    private static final class DirectorySaveHandler implements ISaveHandler {
        private final File directory;

        DirectorySaveHandler(File directory) {
            this.directory = directory;
        }

        @Override
        public File getWorldDirectory() {
            return directory;
        }

        @Override
        public WorldInfo loadWorldInfo() {
            throw unexpected("world-info loading");
        }

        @Override
        public void checkSessionLock() {
            throw unexpected("session-lock access");
        }

        @Override
        public IChunkLoader getChunkLoader(WorldProvider provider) {
            throw unexpected("chunk-loader access");
        }

        @Override
        public void saveWorldInfoWithPlayer(WorldInfo info, NBTTagCompound player) {
            throw unexpected("player/world-info saving");
        }

        @Override
        public void saveWorldInfo(WorldInfo info) {
            throw unexpected("world-info saving");
        }

        @Override
        public IPlayerFileData getPlayerNBTManager() {
            throw unexpected("player-data access");
        }

        @Override
        public void flush() {
            throw unexpected("save flush");
        }

        @Override
        public File getMapFileFromName(String name) {
            throw unexpected("map-file access");
        }

        @Override
        public TemplateManager getStructureTemplateManager() {
            throw unexpected("structure-template access");
        }
    }
}
