package cc.orthrus.riven;

import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.Definitions;
import cc.orthrus.riven.worldgen.NativeColumns;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.WorldScale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.opencubicchunks.cubicchunks.api.worldgen.CubePrimer;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubeGenerator;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.ForkJoinPool;

/**
 * Offline bootstrap for the real Riven generator and CubePrimer. Usage:
 * CubePrimerBootstrapCheck <fixture parent> [registries|generate].
 */
public final class CubePrimerBootstrapCheck {
    private CubePrimerBootstrapCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("deprecation") // Minecraft exposes the registry used by CubePrimer here.
    private static int registered(IBlockState state) {
        int id = Block.BLOCK_STATE_IDS.get(state);
        IBlockState decoded = id < 0 ? null : Block.BLOCK_STATE_IDS.getByValue(id);
        // Forge allows multiple actual states to share a metadata id. Block identity must survive.
        require(
                id >= 0 && decoded != null && decoded.getBlock() == state.getBlock(),
                "Unregistered or aliased primer state: " + state + " id=" + id);
        return id;
    }

    private static int registered(Biome biome) {
        require(
                biome != null
                        && biome.getRegistryName() != null
                        && ForgeRegistries.BIOMES.getValue(biome.getRegistryName()) == biome,
                "Unregistered primer biome: " + biome);
        int id = Biome.getIdForBiome(biome);
        require(id >= 0 && Biome.getBiome(id) == biome, "Aliased primer biome: " + biome);
        return id;
    }

    private static final class CheckedPrimer extends CubePrimer {
        private int stateWrites;
        private int biomeWrites;

        @Override
        public void setBlockState(int x, int y, int z, IBlockState state) {
            registered(state);
            super.setBlockState(x, y, z, state);
            require(
                    getBlockState(x, y, z).getBlock() == state.getBlock(),
                    "Primer changed a block on write: " + state);
            stateWrites++;
        }

        @Override
        public void setBiome(int x, int y, int z, Biome biome) {
            registered(biome);
            super.setBiome(x, y, z, biome);
            biomeWrites++;
        }
    }

    private static int nonAir(CubePrimer primer) {
        int nonAir = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    IBlockState state = primer.getBlockState(x, y, z);
                    registered(state);
                    if (state.getBlock() != Blocks.AIR) nonAir++;
                }
        return nonAir;
    }

    private static void noBiomes(CubePrimer primer) {
        require(!primer.hasBiomes(), "Unexpected biome array");
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 4; x++)
                    require(primer.getBiome(x, y, z) == null, "Unexpected biome cell");
    }

    private static CheckedPrimer generate(ICubeGenerator generator, int x, int y, int z) {
        CheckedPrimer requested = new CheckedPrimer();
        CubePrimer result = generator.generateCube(x, y, z, requested);
        require(result == requested, "Riven returned a primer outside the emission validator");
        return requested;
    }

    private static JsonObject inspect(String label, int cubeY, CheckedPrimer primer) {
        ByteBuffer cells = ByteBuffer.allocate(4096 * Integer.BYTES + 1 + 64 * Integer.BYTES);
        int nonAir = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    IBlockState state = primer.getBlockState(x, y, z);
                    cells.putInt(registered(state));
                    if (state.getBlock() != Blocks.AIR) nonAir++;
                }
        cells.put((byte) (primer.hasBiomes() ? 1 : 0));
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 4; x++) {
                    Biome biome = primer.getBiome(x, y, z);
                    if (primer.hasBiomes()) cells.putInt(registered(biome));
                    else {
                        require(biome == null, "Biome cell exists without a biome array");
                        cells.putInt(-1);
                    }
                }
        require(!cells.hasRemaining(), "Incomplete primer digest");
        JsonObject result = new JsonObject();
        result.addProperty("label", label);
        result.addProperty("cubeX", 0);
        result.addProperty("cubeY", cubeY);
        result.addProperty("cubeZ", 0);
        result.addProperty("blockCells", 4096);
        result.addProperty("biomeCells", 64);
        result.addProperty("nonAir", nonAir);
        result.addProperty("hasBiomes", primer.hasBiomes());
        result.addProperty("stateWrites", primer.stateWrites);
        result.addProperty("biomeWrites", primer.biomeWrites);
        result.addProperty("sha256", Rules.sha256(cells.array()));
        return result;
    }

    private static void bootstrap() {
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "riven";
        metadata.name = "Riven";
        metadata.version = "0.0.1";
        Bootstrap.register();
        Loader.instance().setupTestHarness(new DummyModContainer(metadata));
        RivenContent.registerBlocks(
                new RegistryEvent.Register<>(
                        new ResourceLocation("minecraft", "blocks"), ForgeRegistries.BLOCKS));
        RivenBiomes.register(
                new RegistryEvent.Register<>(
                        new ResourceLocation("minecraft", "biomes"), ForgeRegistries.BIOMES));
        int states = 0;
        for (Block block : RivenContent.blocks())
            for (IBlockState state : block.getBlockState().getValidStates()) {
                registered(state);
                states++;
            }
        String catalogue = TerrainColumn.catalogueSummary();
        CheckedPrimer primer = new CheckedPrimer();
        require(nonAir(primer) == 0, "Fresh primer is not all air");
        noBiomes(primer);
        primer.setBlockState(3, 5, 7, Blocks.STONE.getDefaultState());
        primer.setBiome(1, 2, 3, Biomes.PLAINS);
        require(
                nonAir(primer) == 1
                        && primer.hasBiomes()
                        && primer.getBiome(1, 2, 3) == Biomes.PLAINS,
                "Non-air/biome positive controls failed");
        primer.reset();
        require(nonAir(primer) == 0, "Reset primer retained blocks");
        noBiomes(primer);
        IBlockState unregistered =
                new Block(Material.ROCK)
                        .setRegistryName("riven", "unregistered_fixture")
                        .getDefaultState();
        boolean rejected = false;
        try {
            primer.setBlockState(0, 0, 0, unregistered);
        } catch (AssertionError expected) {
            require(
                    expected.getMessage().startsWith("Unregistered or aliased primer state:"),
                    "Wrong unregistered-state rejection: " + expected);
            rejected = true;
        }
        require(rejected, "Unregistered state bypassed the emission validator");
        System.out.printf(
                "Registry bootstrap: %d Riven blocks, %d valid states, %d biomes; %s%n",
                RivenContent.blocks().size(), states, RivenBiomes.list().size(), catalogue);
    }

    private static void snapshot(Path fixture) throws Exception {
        JsonObject defaults = Rules.load("riven-mappings.json", 1).json;
        require(
                defaults.getAsJsonArray("integrations").size() == 0,
                "Packaged mappings have integrations; the bootstrap needs their real registry inputs");
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", 1);
        JsonArray layers = new JsonArray();
        layers.add(defaults.get("layer"));
        snapshot.add("layers", layers);
        snapshot.add("roles", defaults.get("roles"));
        snapshot.add("biomes", defaults.has("biomes") ? defaults.get("biomes") : new JsonObject());
        Path file = fixture.resolve("data/riven/definitions.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, snapshot.toString(), StandardCharsets.UTF_8);
    }

    private static void generate(Path fixture, Path receiptFile) throws Exception {
        snapshot(fixture);
        long seed = 8675309;
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schema", 1);
        receipt.addProperty("seed", seed);
        receipt.addProperty("layoutScale", 1);
        receipt.addProperty(
                "digestEncoding",
                "y,z,x; big-endian int state IDs; hasBiomes byte; 64 int biome IDs or -1");
        receipt.addProperty(
                "snapshotSha256",
                Rules.sha256(Files.readAllBytes(fixture.resolve("data/riven/definitions.json"))));
        JsonObject definitions = new JsonObject();
        for (Rules rules : Definitions.loadAll())
            definitions.addProperty(rules.resource, rules.fingerprint);
        receipt.add("definitions", definitions);
        JsonArray cubes = new JsonArray();
        receipt.add("cubes", cubes);
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        try (ForkJoinPool workers = new ForkJoinPool(4)) {
            ClimateBiomeProvider biomes =
                    new ClimateBiomeProvider(seed, new WorldScale(1), workers, null);
            World world = CubePrimerFixture.create(fixture, seed, biomes);
            ICubeGenerator generator = new RivenWorldType().createCubeGenerator(world);
            receipt.addProperty("generatorClass", generator.getClass().getName());
            int sky = Math.floorDiv(heights.maximumYExclusive + 15, 16);
            CheckedPrimer air = generate(generator, 0, sky, 0);
            require(nonAir(air) == 0, "Actual generator's sky cube is not empty");
            noBiomes(air);
            cubes.add(inspect("sky", sky, air));
            NativeColumns.Column column = biomes.maps.columns.column(0, 0);
            int surface = column.surface(0);
            require(surface != Integer.MIN_VALUE, "Positive-control column has no surface");
            int surfaceCubeY = Math.floorDiv(surface - 1, 16);
            CheckedPrimer ground = generate(generator, 0, surfaceCubeY, 0);
            int blocks = nonAir(ground);
            require(blocks > 0, "Actual generator non-air positive control failed");
            cubes.add(inspect("surface", surfaceCubeY, ground));
            int depthCubeY = Math.floorDiv(heights.minimumY - 16, 16);
            CheckedPrimer depth = generate(generator, 0, depthCubeY, 0);
            nonAir(depth);
            require(depth.hasBiomes(), "Actual generator biome positive control failed");
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++)
                    for (int x = 0; x < 4; x++)
                        require(
                                depth.getBiome(x, y, z) != null,
                                "Actual generator left a biome hole");
            cubes.add(inspect("realm", depthCubeY, depth));
            Files.writeString(
                    receiptFile,
                    receipt.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            System.out.printf(
                    "Actual generator: sky=4096 air/no biomes; surface=%d non-air; depth=64 biomes%n",
                    blocks);
            System.out.println("Actual generator baseline: " + receiptFile);
        }
    }

    public static void main(String[] args) throws Exception {
        require(Runtime.version().feature() == 26, "Diagnostics must run on Java 26");
        System.out.printf("Java %s at %s%n", Runtime.version(), System.getProperty("java.home"));
        System.out.println(
                "JVM arguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException(
                    "Give a fixture parent and optional registries|generate mode");
        String mode = args.length == 1 ? "registries" : args[1];
        if (!mode.equals("registries") && !mode.equals("generate"))
            throw new IllegalArgumentException("Unknown bootstrap mode: " + mode);
        bootstrap();
        if (mode.equals("registries")) return;
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path fixture = Files.createTempDirectory(parent, "primer-");
        require(fixture.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped its parent");
        Throwable primary = null;
        try {
            generate(fixture, parent.resolve(fixture.getFileName() + "-baseline.json"));
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                try (var files = Files.walk(fixture)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                        Files.delete(file);
                }
            } catch (Exception | Error cleanup) {
                if (primary == null) throw cleanup;
                if (cleanup != primary) primary.addSuppressed(cleanup);
            }
        }
    }
}
