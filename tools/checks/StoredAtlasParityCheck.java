package cc.orthrus.riven.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A fixed production-atlas content check across fresh and populated drainage stores. Usage:
 * {@code StoredAtlasParityCheck <output parent>}. Retains its fresh cache and both atlas outputs.
 * The second construction reads the kept world selection and reuses the owned build workers.
 * Both constructions share one process, so this proves cache content parity, not restart speed.
 * The launcher must bound the process: the unchanged atlas executor can survive a failed task.
 */
public final class StoredAtlasParityCheck {
    private static final long SEED = 8675309;
    private static final WorldScale WORLD = new WorldScale(1);
    private static final Map<String, String> RASTER =
            Map.of(
                    "cx", "3800",
                    "cz", "-1288",
                    "width", "4",
                    "height", "4",
                    "step", "16",
                    "footprint", "1",
                    "threads", "1",
                    "order", "forward");

    private StoredAtlasParityCheck() {}

    private record Key(int x, int z) {}

    private record Memo(List<Key> keys, int nonempty) {}

    private record Pass(String digest, JsonObject report) {}

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    /** Quiescent inspection only: preserve every original entry key, including empty networks. */
    private static Memo memo(GenerationMaps maps) throws ReflectiveOperationException {
        Object memo = field(maps.terrain.hydro.drainage, "memo");
        AtomicReferenceArray<?> slots = (AtomicReferenceArray<?>) field(memo, "slots");
        List<Key> keys = new ArrayList<>();
        int nonempty = 0;
        for (int i = 0; i < slots.length(); i++) {
            Object entry = slots.get(i);
            if (entry == null) continue;
            keys.add(new Key((Integer) field(entry, "x"), (Integer) field(entry, "z")));
            if (Array.getLength(field(field(entry, "value"), "channels")) > 0) nonempty++;
        }
        keys.sort(Comparator.comparingInt(Key::x).thenComparingInt(Key::z));
        return new Memo(List.copyOf(keys), nonempty);
    }

    private static JsonObject json(Memo memo) {
        JsonObject out = new JsonObject();
        JsonArray keys = new JsonArray();
        for (Key key : memo.keys()) {
            JsonArray pair = new JsonArray();
            pair.add(key.x());
            pair.add(key.z());
            keys.add(pair);
        }
        out.add("survivingKeysIncludingEmpty", keys);
        out.addProperty("nonemptyNetworkCount", memo.nonempty());
        return out;
    }

    private static JsonObject json(RegionalStore.Statistics stats) {
        JsonObject out = new JsonObject();
        out.addProperty("hits", stats.hits());
        out.addProperty("misses", stats.misses());
        out.addProperty("writes", stats.writes());
        out.addProperty("failedWrites", stats.failedWrites());
        return out;
    }

    private static RegionalStore.Statistics delta(
            RegionalStore.Statistics before, RegionalStore.Statistics after) {
        return new RegionalStore.Statistics(
                after.hits() - before.hits(),
                after.misses() - before.misses(),
                after.writes() - before.writes(),
                after.failedWrites() - before.failedWrites());
    }

    private static void raster(GenerationMaps maps, Path out, double mapSeconds) throws Exception {
        Method raster =
                TerrainAtlas.class.getDeclaredMethod(
                        "raster",
                        GenerationMaps.class,
                        long.class,
                        Path.class,
                        Map.class,
                        double.class);
        raster.setAccessible(true);
        try {
            raster.invoke(null, maps, SEED, out, RASTER, mapSeconds);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private static Pass pass(
            String name,
            Path cache,
            Path out,
            RegionalIdentity identity,
            ForkJoinPool pool,
            boolean full)
            throws Exception {
        try (RegionalStore store = new RegionalStore(cache, identity)) {
            long started = System.nanoTime();
            GenerationMaps maps =
                    new GenerationMaps(SEED, TerrainHeightReference.DEFAULT, WORLD, pool, store);
            double mapSeconds = (System.nanoTime() - started) / 1e9;
            Memo initial = memo(maps);
            RegionalStore.Statistics before = store.statistics();
            System.out.printf(
                    "%s constructor: historical pre-bind Drainage builds=0 (source audit); "
                            + "observed surviving keys=%s; nonempty=%d; store=%s%n",
                    name, initial.keys(), initial.nonempty(), before);
            require(
                    initial.keys().isEmpty(),
                    "Source-audited constructor zero-history premise changed");
            require(
                    before.hits() == (full ? 1 : 0)
                            && before.misses() == (full ? 0 : 1)
                            && before.writes() == (full ? 0 : 1)
                            && before.failedWrites() == 0,
                    "Construction did not perform only the expected world selection access: "
                            + before);

            // Do not add a sites key: the atlas tests presence, even when its value is false.
            raster(maps, out, mapSeconds);
            RegionalStore.Statistics after = store.statistics();
            RegionalStore.Statistics rasterStore = delta(before, after);
            Memo retained = memo(maps);
            System.out.printf(
                    "%s completed: aggregate raster store=%s; nonempty retained networks=%d%n",
                    name, rasterStore, retained.nonempty());
            require(retained.nonempty() > 0, name + " retained no nonempty drainage network");
            if (full) {
                require(
                        rasterStore.hits() > 0
                                && rasterStore.misses() == 0
                                && rasterStore.writes() == 0
                                && rasterStore.failedWrites() == 0,
                        "Populated store missed or rewrote a regional value: " + rasterStore);
            } else {
                require(
                        rasterStore.misses() > 0
                                && rasterStore.writes() == rasterStore.misses()
                                && rasterStore.failedWrites() == 0,
                        "Fresh store did not retain every regional miss: " + rasterStore);
            }
            String digest;
            try (Reader in = Files.newBufferedReader(out.resolve("atlas.json"))) {
                digest = JsonParser.parseReader(in).getAsJsonObject().get("digest").getAsString();
            }
            require(
                    digest.matches("[0-9a-f]{64}"),
                    "Atlas did not supply a SHA-256 content digest");
            JsonObject report = new JsonObject();
            report.addProperty("atlas", out.resolve("atlas.json").toString());
            report.addProperty("digest", digest);
            report.addProperty("historicalPreBindBuilds", "0, established by source audit");
            report.add("constructorMemo", json(initial));
            report.add("constructorStore", json(before));
            report.addProperty("constructorStoreScope", "world selection only");
            report.add("finalMemo", json(retained));
            report.add("finalStore", json(after));
            report.add("rasterStore", json(rasterStore));
            report.addProperty(
                    "storeCounterScope", "all bound regional kinds; not per-kind builds");
            return new Pass(digest, report);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: StoredAtlasParityCheck <output parent>");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "stored-atlas-");
        require(run.toRealPath().startsWith(parent.toRealPath()), "Output escaped its parent");
        Path cache = run.resolve("cache");
        Path cold = Files.createDirectory(run.resolve("cold"));
        Path full = Files.createDirectory(run.resolve("full"));
        System.out.println("Retained atlas proof root=" + run);
        RegionalIdentity identity = RegionalIdentity.capture(SEED, WORLD);
        ForkJoinPool pool = BuildPool.bounded(4, "stored atlas proof");
        try {
            Pass first = pass("cold", cache, cold, identity, pool, false);
            // The first handle is closed. The second owns fresh maps and reads the same files.
            Pass second = pass("full", cache, full, identity, pool, true);
            require(
                    first.digest().equals(second.digest()),
                    "Stored drainage changed the atlas digest");
            JsonObject report = new JsonObject();
            report.addProperty("seed", SEED);
            report.addProperty("scale", WORLD.layout);
            report.addProperty("cache", cache.toString());
            report.addProperty("regionalNamespace", identity.namespace);
            JsonObject raster = new JsonObject();
            RASTER.forEach(raster::addProperty);
            report.add("raster", raster);
            report.addProperty("sites", "not requested");
            report.addProperty("buildWorkers", 4);
            report.addProperty("freshJvmPerConstruction", false);
            report.addProperty("buildPoolReused", true);
            report.addProperty("timingsArePerformanceEvidence", false);
            report.addProperty(
                    "scope", "World selection, regional persistence and atlas content parity");
            report.add("cold", first.report());
            report.add("full", second.report());
            report.addProperty("digestEqual", true);
            try (Writer out = Files.newBufferedWriter(run.resolve("store-parity.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, out);
            }
            System.out.println("Stored atlas content parity passed; digest=" + first.digest());
        } finally {
            pool.close();
        }
    }
}
