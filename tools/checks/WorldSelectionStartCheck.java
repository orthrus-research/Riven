package cc.orthrus.riven.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** One fixed fresh-process construction and atlas sample; its parent must bound process time. */
public final class WorldSelectionStartCheck {
    private static final long SEED = 8675309;
    private static final Map<String, String> RASTER =
            Map.of(
                    "cx",
                    "3800",
                    "cz",
                    "-1288",
                    "width",
                    "4",
                    "height",
                    "4",
                    "step",
                    "16",
                    "footprint",
                    "1",
                    "threads",
                    "1",
                    "order",
                    "forward");

    private WorldSelectionStartCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JsonObject statistics(RegionalStore.Statistics value) {
        JsonObject out = new JsonObject();
        out.addProperty("hits", value.hits());
        out.addProperty("misses", value.misses());
        out.addProperty("writes", value.writes());
        out.addProperty("failedWrites", value.failedWrites());
        return out;
    }

    private static RegionalStore.Statistics delta(
            RegionalStore.Statistics before, RegionalStore.Statistics after) {
        return new RegionalStore.Statistics(
                after.hits() - before.hits(), after.misses() - before.misses(),
                after.writes() - before.writes(), after.failedWrites() - before.failedWrites());
    }

    private static String hash(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void write(Path file, JsonObject value) throws Exception {
        Files.writeString(
                file,
                new GsonBuilder().setPrettyPrinting().create().toJson(value),
                StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static int drainageSurvivors(GenerationMaps maps) throws ReflectiveOperationException {
        Object memo = field(maps.terrain.hydro.drainage, "memo");
        AtomicReferenceArray<?> slots = (AtomicReferenceArray<?>) field(memo, "slots");
        int count = 0;
        for (int i = 0; i < slots.length(); i++) if (slots.get(i) != null) count++;
        return count;
    }

    private static void raster(GenerationMaps maps, Path out, double seconds) throws Exception {
        Method method =
                TerrainAtlas.class.getDeclaredMethod(
                        "raster",
                        GenerationMaps.class,
                        long.class,
                        Path.class,
                        Map.class,
                        double.class);
        method.setAccessible(true);
        try {
            method.invoke(null, maps, SEED, out, RASTER, seconds);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private static void close(ForkJoinPool pool) throws InterruptedException {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                throw new IllegalStateException(
                        "Build pool did not terminate; process needs its bound");
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void run(
            WorldScale world, boolean stored, Path cache, Path out, JsonObject result)
            throws Exception {
        RegionalIdentity identity = RegionalIdentity.capture(SEED, world);
        result.addProperty("regionalNamespace", identity.namespace);
        ForkJoinPool pool = BuildPool.bounded(4, "world selection startup proof");
        Throwable primary = null;
        try (RegionalStore store = new RegionalStore(cache, identity)) {
            Path selection = store.path(WorldSelection.KIND, 0, 0, WorldSelection.CODEC);
            require(
                    stored ? Files.isRegularFile(selection) : !Files.exists(selection),
                    "World selection presence disagrees with the requested mode");
            String prior = stored ? hash(selection) : null;
            result.addProperty("phase", "constructing");
            write(out.resolve("result.json"), result);
            long started = System.nanoTime();
            GenerationMaps maps =
                    new GenerationMaps(SEED, TerrainHeightReference.DEFAULT, world, pool, store);
            double seconds = (System.nanoTime() - started) / 1e9;
            RegionalStore.Statistics before = store.statistics();
            JsonObject constructor = new JsonObject();
            constructor.addProperty("seconds", seconds);
            constructor.addProperty("seed", SEED);
            constructor.addProperty(
                    "layoutBits",
                    Long.toUnsignedString(Double.doubleToRawLongBits(world.layout), 16));
            constructor.addProperty("terrainVersion", TerrainMap.VERSION);
            constructor.addProperty("regionalNamespace", identity.namespace);
            constructor.addProperty("mode", stored ? "stored" : "cold");
            constructor.addProperty("landAttempt", maps.land.outline.attempt);
            constructor.addProperty(
                    "landShareBits",
                    Long.toUnsignedString(Double.doubleToRawLongBits(maps.land.share), 16));
            constructor.addProperty("climateAttempt", maps.climateAttempt);
            constructor.addProperty("climateDraws", maps.climateDraws);
            constructor.addProperty("climateFewest", maps.climateFewest);
            constructor.add("store", statistics(before));
            result.add("constructor", constructor);
            require(
                    before.hits() == (stored ? 1 : 0)
                            && before.misses() == (stored ? 0 : 1)
                            && before.writes() == (stored ? 0 : 1)
                            && before.failedWrites() == 0,
                    "Construction did not perform exactly the expected world selection access");
            int survivors = drainageSurvivors(maps);
            constructor.addProperty("drainageSurvivingEntries", survivors);
            constructor.addProperty(
                    "historicalPreBindDrainageBuilds",
                    "zero by reviewed constructor callchain, not a measured runtime counter");
            require(survivors == 0, "Construction retained pre-bind Drainage entries");
            String selected = hash(selection);
            constructor.addProperty("worldSelectionSha256", selected);
            require(
                    !stored || selected.equals(prior),
                    "Stored construction changed its selection file");
            constructor.addProperty("complete", true);
            write(out.resolve("constructor.json"), constructor);
            result.addProperty("phase", "sampling");
            write(out.resolve("result.json"), result);
            Path atlas = Files.createDirectory(out.resolve("atlas"));
            raster(maps, atlas, seconds);
            RegionalStore.Statistics after = store.statistics();
            JsonObject metadata;
            try (var reader = Files.newBufferedReader(atlas.resolve("atlas.json"))) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject();
            }
            String digest = metadata.get("digest").getAsString();
            require(digest.matches("[0-9a-f]{64}"), "Atlas did not report a SHA-256 digest");
            require(selected.equals(hash(selection)), "Raster changed the world selection file");
            result.addProperty("digest", digest);
            result.add("atlas", metadata);
            result.add("rasterStore", statistics(delta(before, after)));
            result.add("finalStore", statistics(after));
            result.addProperty("storeCounterScope", "all bound kinds; not per-kind build counts");
            result.addProperty("rasterSamples", 16);
            result.addProperty(
                    "noMeasurementEvidence",
                    "reviewed stored branch call absence plus verified hit; no runtime call counter");
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                close(pool);
            } catch (Exception | Error failure) {
                if (primary == null) throw failure;
                primary.addSuppressed(failure);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4
                || !(args[0].equals("1") || args[0].equals("4"))
                || !(args[1].equals("cold") || args[1].equals("stored")))
            throw new IllegalArgumentException(
                    "Usage: WorldSelectionStartCheck <1|4> <cold|stored> <cache root> <fresh output>");
        WorldScale world = new WorldScale(Integer.parseInt(args[0]));
        boolean stored = args[1].equals("stored");
        Path cache = Path.of(args[2]).toAbsolutePath().normalize();
        Path out = Path.of(args[3]).toAbsolutePath().normalize();
        require(!cache.equals(out), "Cache and output roots must differ");
        Files.createDirectories(out.getParent());
        Files.createDirectory(out);
        JsonObject result = new JsonObject();
        result.addProperty("complete", false);
        result.addProperty("seed", SEED);
        result.addProperty("scale", world.layout);
        result.addProperty("mode", args[1]);
        result.addProperty("buildWorkers", 4);
        result.addProperty("phase", "preparing");
        try {
            run(world, stored, cache, out, result);
            result.addProperty("phase", "complete");
            result.addProperty("complete", true);
            write(out.resolve("result.json"), result);
        } catch (Exception | Error failure) {
            result.addProperty("complete", false);
            result.addProperty("failureClass", failure.getClass().getName());
            result.addProperty("failureMessage", failure.getMessage());
            try {
                write(out.resolve("result.json"), result);
            } catch (Exception cannotWrite) {
                failure.addSuppressed(cannotWrite);
            }
            throw failure;
        }
    }
}
