package cc.orthrus.riven;

import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.WorldScale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Preview rasters, digested: what the terrain preview emits over windows of run tiles, so two
 * trees, or two ways of building the preview, can be compared exactly. No server and no client:
 * Minecraft's registries are bootstrapped as CubePrimerBootstrapCheck does.
 *
 * <p>Usage: PreviewParityCheck OUT SEED SCALE THREADS SOURCES WINDOW..., where SOURCES is a
 * comma-separated list of {@code generator} (the server's cube generator, built on a fixture world
 * whose every chunk, block and biome access throws) and WINDOW is {@code NAME:CX:CZ:HIGH-LOW[:N]}:
 * for each level from HIGH down to LOW (cells of 2^level blocks), a tile-aligned square of N x N
 * tiles of 64 x 64 columns around (CX, CZ); N defaults to the size survey's rule, at least 2,048
 * blocks and at least 4 x 4 tiles. Each column is sampled at its cell's integer centre with the
 * cell's width as its footprint.
 *
 * <p>A level's digest is the SHA-256 of its tiles' digests in order; a tile's covers, for every
 * column in order, the biome's registry name and each run's bounds and block state. Writes
 * OUT/NAME.json per window. With more than one source, every level's digests must be equal.
 */
public final class PreviewParityCheck {
    private static final int CELLS = 64;

    private PreviewParityCheck() {}

    static void bootstrap() {
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
    }

    /** The packaged definitions as a world records them, as CubePrimerBootstrapCheck writes it. */
    static String recordedDefaults() {
        JsonObject defaults = Rules.load("riven-mappings.json", 1).json;
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", 1);
        JsonArray layers = new JsonArray();
        layers.add(defaults.get("layer"));
        snapshot.add("layers", layers);
        snapshot.add("roles", defaults.get("roles"));
        snapshot.add("biomes", defaults.has("biomes") ? defaults.get("biomes") : new JsonObject());
        return snapshot.toString();
    }

    /** One tile's digest; a sampler and its scratch belong to one thread. */
    private static final class Digester implements ICubicTerrainPreview.ColumnConsumer {
        private final IdentityHashMap<Object, byte[]> names = new IdentityHashMap<>();
        private final ByteBuffer bounds = ByteBuffer.allocate(8);
        private MessageDigest digest;
        long runs;

        private byte[] name(Object key, String text) {
            return names.computeIfAbsent(key, k -> text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void begin(Biome biome) {
            digest.update((byte) 'B');
            digest.update(name(biome, String.valueOf(biome.getRegistryName())));
        }

        @Override
        public void addRun(int minimumY, int maximumYExclusive, IBlockState state) {
            bounds.clear();
            bounds.putInt(minimumY).putInt(maximumYExclusive);
            digest.update(bounds.array());
            digest.update(name(state, state.toString()));
            runs++;
        }

        byte[] tile(ICubicTerrainPreview.Sampler sampler, int level, int tileX, int tileZ)
                throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
            int cell = 1 << level, x0 = tileX * CELLS * cell, z0 = tileZ * CELLS * cell;
            for (int z = 0; z < CELLS; z++)
                for (int x = 0; x < CELLS; x++)
                    sampler.sampleColumn(
                            x0 + x * cell + cell / 2, z0 + z * cell + cell / 2, cell, this);
            return digest.digest();
        }
    }

    private record Level(String digest, int tiles, long runs, double seconds) {}

    private static Level level(
            ICubicTerrainPreview preview,
            ExecutorService pool,
            int level,
            int centreX,
            int centreZ,
            int count)
            throws Exception {
        int side = CELLS << level, n = count > 0 ? count : Math.max(4, 2048 / side);
        int x0 = Math.floorDiv(centreX, side) - n / 2, z0 = Math.floorDiv(centreZ, side) - n / 2;
        long started = System.nanoTime();
        List<Future<Object[]>> work = new ArrayList<>();
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                int tileX = x0 + x, tileZ = z0 + z;
                work.add(
                        pool.submit(
                                () -> {
                                    Digester digester = new Digester();
                                    byte[] tile =
                                            digester.tile(
                                                    preview.createTerrainPreviewSampler(),
                                                    level,
                                                    tileX,
                                                    tileZ);
                                    return new Object[] {tile, digester.runs};
                                }));
            }
        MessageDigest all = MessageDigest.getInstance("SHA-256");
        long runs = 0;
        for (Future<Object[]> tile : work) {
            Object[] done = tile.get();
            all.update((byte[]) done[0]);
            runs += (Long) done[1];
        }
        return new Level(
                HexFormat.of().formatHex(all.digest()),
                n * n,
                runs,
                (System.nanoTime() - started) / 1e9);
    }

    private static ICubicTerrainPreview source(
            String kind, Path out, long seed, WorldScale scale, String recorded) throws Exception {
        if (kind.equals("generator")) {
            Path fixture = out.resolve("fixture");
            Path file = fixture.resolve("data/riven/definitions.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, recorded, StandardCharsets.UTF_8);
            ClimateBiomeProvider biomes = new ClimateBiomeProvider(seed, scale, null, null);
            World world = CubePrimerFixture.create(fixture, seed, biomes);
            return (ICubicTerrainPreview) new RivenWorldType().createCubeGenerator(world);
        }
        throw new IllegalArgumentException("Unknown source " + kind);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6)
            throw new IllegalArgumentException(
                    "PreviewParityCheck OUT SEED SCALE THREADS SOURCES WINDOW...");
        Path out = Path.of(args[0]).toAbsolutePath().normalize();
        long seed = Long.parseLong(args[1]);
        WorldScale scale = new WorldScale(Double.parseDouble(args[2]));
        int threads = Integer.parseInt(args[3]);
        String[] kinds = args[4].split(",");
        Files.createDirectories(out);
        bootstrap();
        String recorded = recordedDefaults();
        Map<String, ICubicTerrainPreview> sources = new LinkedHashMap<>();
        for (String kind : kinds) sources.put(kind, source(kind, out, seed, scale, recorded));
        AtomicInteger made = new AtomicInteger();
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        threads,
                        task -> {
                            Thread thread =
                                    new Thread(task, "Preview parity #" + made.incrementAndGet());
                            thread.setDaemon(true);
                            return thread;
                        });
        boolean equal = true;
        try {
            for (int w = 5; w < args.length; w++) {
                String[] window = args[w].split(":");
                String[] levels = window[3].split("-");
                int centreX = Integer.parseInt(window[1]), centreZ = Integer.parseInt(window[2]);
                int count = window.length > 4 ? Integer.parseInt(window[4]) : 0;
                JsonObject report = new JsonObject();
                report.addProperty("window", args[w]);
                report.addProperty("seed", seed);
                report.addProperty("layoutScale", scale.layout);
                JsonArray rows = new JsonArray();
                for (int level = Integer.parseInt(levels[0]);
                        level >= Integer.parseInt(levels[1]);
                        level--) {
                    JsonObject row = new JsonObject();
                    row.addProperty("level", level);
                    String first = null;
                    for (Map.Entry<String, ICubicTerrainPreview> source : sources.entrySet()) {
                        Level done = level(source.getValue(), pool, level, centreX, centreZ, count);
                        row.addProperty("tiles", done.tiles());
                        row.addProperty(source.getKey(), done.digest());
                        row.addProperty(source.getKey() + "Seconds", done.seconds());
                        row.addProperty("runs", done.runs());
                        if (first == null) first = done.digest();
                        else if (!first.equals(done.digest())) equal = false;
                        System.out.printf(
                                Locale.ROOT,
                                "%s level %d %s: %d tiles, %d runs, %s (%.1f s)%n",
                                window[0],
                                level,
                                source.getKey(),
                                done.tiles(),
                                done.runs(),
                                done.digest(),
                                done.seconds());
                    }
                    rows.add(row);
                }
                report.add("levels", rows);
                Files.writeString(
                        out.resolve(window[0] + ".json"),
                        report.toString(),
                        StandardCharsets.UTF_8);
            }
        } finally {
            pool.shutdownNow();
        }
        if (!equal) {
            System.out.println("PreviewParityCheck FAILED: sources differ");
            System.exit(1);
        }
        System.out.println(
                "PreviewParityCheck passed"
                        + (kinds.length > 1 ? ": every level equal across " + args[4] : ""));
        // Minecraft's bootstrap leaves non-daemon threads behind.
        System.exit(0);
    }
}
