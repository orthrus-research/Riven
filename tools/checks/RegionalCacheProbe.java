package cc.orthrus.riven.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Regional cache probe (tools only). Drives the real {@link TerrainMap} with simulated players'
 * demand at one layout scale and reports, for every memo reachable from the maps: the keys it
 * built, rebuilds of keys it had built before (capacity misses), single-flight waits by thread
 * kind, whether every key it built could be held at once, slots replaced in each pass, and the heap
 * its entries retain. Each {@link SharedBuild}'s flight map is swapped by reflection for a counting
 * one; generator code and content are unchanged. Memos without a SharedBuild report slot
 * replacements only.
 *
 * <p>Demand per player: columns every farStep blocks out to radius (the Distant Horizons reach)
 * and every nearStep blocks out to near (the native view), nearest first, in tasks of task columns
 * dealt round-robin across players to a pool of threads. Passes: cold (first demand), repeat (the
 * same demand again, so any build is a capacity miss) and moved (each player displaced by move
 * blocks). The world is the square of side 8000 x scale blocks centred on the origin, and demand
 * stops at its edge, as a world border would stop it.
 *
 * <pre>
 * .local/atlas-venv/Scripts/python tools/run_check.py RegionalCacheProbe out=DIR scale=4 players=12
 * </pre>
 */
public final class RegionalCacheProbe {
    // The demand passes, then the optional preparation run before them (prepareReach=).
    private static final String[] PASSES = {"cold", "repeat", "moved", "prepare"};
    private static final int DEMAND = 3, PREPARE = 3;
    private static final String[] KINDS = {"worker", "common", "build", "other"};
    private static final int WORKER = 0, COMMON = 1, BUILD = 2, OTHER = 3;
    // Per-pass counters of a memo.
    private static final int BUILDS = 0, REBUILDS = 1, BUILD_NANOS = 2, REENTRIES = 3, RACED = 4;
    private static final int WAITS = 5, WAIT_NANOS = WAITS + KINDS.length;
    private static final int COUNTERS = WAIT_NANOS + KINDS.length;
    private static final ThreadLocal<TerrainMap.Sample> SAMPLE =
            ThreadLocal.withInitial(TerrainMap.Sample::new);

    private static volatile int pass;
    private static volatile Field flightOwner;
    private static volatile ForkJoinPool buildPool;

    private RegionalCacheProbe() {}

    /** One memo: its slots, its set-associative shape if it has one, and what it built. */
    static final class Cache {
        final String name, kind;
        final AtomicReferenceArray<?> slots;
        final int sets, ways;
        final ConcurrentHashMap<Long, AtomicInteger> built = new ConcurrentHashMap<>();
        final LongAdder[][] counts = new LongAdder[PASSES.length][COUNTERS];
        final AtomicLong[] longest = new AtomicLong[PASSES.length];
        final int[] filled = new int[PASSES.length], replaced = new int[PASSES.length];
        final ConcurrentHashMap<Object, Long> starts = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Object, ConcurrentLinkedQueue<long[]>> waiters =
                new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<long[]> timeline = new ConcurrentLinkedQueue<>();
        volatile boolean timed;
        boolean instrumented;
        long retained = -1;

        Cache(String name, String kind, AtomicReferenceArray<?> slots, int sets, int ways) {
            this.name = name;
            this.kind = kind;
            this.slots = slots;
            this.sets = sets;
            this.ways = ways;
            for (int p = 0; p < PASSES.length; p++) {
                for (int i = 0; i < COUNTERS; i++) counts[p][i] = new LongAdder();
                longest[p] = new AtomicLong();
            }
        }

        long count(int p, int counter) {
            return counts[p][counter].sum();
        }

        long total(int counter) {
            long t = 0;
            for (int p = 0; p < PASSES.length; p++) t += count(p, counter);
            return t;
        }

        int capacity() {
            return slots == null ? 0 : slots.length();
        }

        int resident() {
            int n = 0;
            for (int i = 0; i < capacity(); i++) if (slots.get(i) != null) n++;
            return n;
        }

        Object[] snapshot() {
            Object[] s = new Object[capacity()];
            for (int i = 0; i < s.length; i++) s[i] = slots.get(i);
            return s;
        }

        void compare(int p, Object[] before) {
            for (int i = 0; i < before.length; i++) {
                Object now = slots.get(i);
                if (now == before[i]) continue;
                if (before[i] == null) filled[p]++;
                else replaced[p]++;
            }
        }

        /** Whether the memo holds this key now: a flight that starts so finds it on a second look. */
        boolean holds(long key) {
            if (slots == null) return false;
            if (sets > 0) {
                int first = ((int) SeededNoise.mix(key) & (sets - 1)) * ways;
                for (int i = first; i < first + ways; i++) if (is(slots.get(i), key)) return true;
                return false;
            }
            if (!kind.equals("cell-memo")) return false;
            long h = SeededNoise.mix(key);
            int mask = capacity() - 1;
            return is(slots.get((int) h & mask), key)
                    || is(slots.get((int) (h >>> 32) & mask), key);
        }

        void started(Long key, Object flight) {
            int p = pass;
            if (built.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() > 1)
                counts[p][REBUILDS].increment();
            counts[p][BUILDS].increment();
            starts.put(flight, System.nanoTime());
        }

        void waited(Object flight) {
            int k = kind(Thread.currentThread());
            counts[pass][WAITS + k].increment();
            waiters.computeIfAbsent(flight, f -> new ConcurrentLinkedQueue<>())
                    .add(new long[] {k, System.nanoTime()});
        }

        void ended(Long key, Object flight) {
            long end = System.nanoTime();
            int p = pass;
            Long start = starts.remove(flight);
            if (start != null) {
                long d = end - start;
                counts[p][BUILD_NANOS].add(d);
                longest[p].accumulateAndGet(d, Math::max);
                if (timed) timeline.add(new long[] {key, start, end});
            }
            ConcurrentLinkedQueue<long[]> q = waiters.remove(flight);
            if (q != null) for (long[] w : q) counts[p][WAIT_NANOS + (int) w[0]].add(end - w[1]);
        }
    }

    /** A SharedBuild flight map that reports each build's start and end and each wait. */
    static final class Flights extends ConcurrentHashMap<Long, Object> {
        private static final long serialVersionUID = 1;
        final transient Cache cache;

        Flights(Cache cache) {
            this.cache = cache;
        }

        @Override
        public Object putIfAbsent(Long key, Object flight) {
            Object other = super.putIfAbsent(key, flight);
            if (other == null) {
                if (cache.holds(key)) cache.counts[pass][RACED].increment();
                else cache.started(key, flight);
            } else if (owner(other) == Thread.currentThread())
                cache.counts[pass][REENTRIES].increment();
            else cache.waited(other);
            return other;
        }

        @Override
        public boolean remove(Object key, Object flight) {
            boolean removed = super.remove(key, flight);
            if (removed) cache.ended((Long) key, flight);
            return removed;
        }
    }

    private static Object owner(Object flight) {
        try {
            Field f = flightOwner;
            if (f == null) {
                f = flight.getClass().getDeclaredField("owner");
                f.setAccessible(true);
                flightOwner = f;
            }
            return f.get(flight);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ConcurrentHashMap<Class<?>, Field[]> ENTRY_FIELDS =
            new ConcurrentHashMap<>();

    /** Whether a memo entry holds this key: a {@code key} field, or a CellMemo's x and z. */
    private static boolean is(Object entry, long key) {
        if (entry == null) return false;
        Field[] f =
                ENTRY_FIELDS.computeIfAbsent(
                        entry.getClass(),
                        c -> {
                            try {
                                for (Field k : c.getDeclaredFields())
                                    if (k.getName().equals("key")) {
                                        k.setAccessible(true);
                                        return new Field[] {k};
                                    }
                                Field x = c.getDeclaredField("x"), z = c.getDeclaredField("z");
                                x.setAccessible(true);
                                z.setAccessible(true);
                                return new Field[] {x, z};
                            } catch (NoSuchFieldException e) {
                                return new Field[0];
                            }
                        });
        try {
            if (f.length == 1) return f[0].getLong(entry) == key;
            if (f.length == 2)
                return (((long) f[0].getInt(entry) << 32) ^ (f[1].getInt(entry) & 0xffffffffL))
                        == key;
            return false;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int kind(Thread t) {
        if (t instanceof ForkJoinWorkerThread w) {
            if (w.getPool() == ForkJoinPool.commonPool()) return COMMON;
            if (w.getPool() == buildPool) return BUILD;
        }
        return t.getName().startsWith("probe-worker") ? WORKER : OTHER;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("Expected key=value: " + arg);
            a.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        Path out = Paths.get(required(a, "out"));
        long seed = Long.parseLong(value(a, "seed", "8675309"));
        double scale = Double.parseDouble(value(a, "scale", "1"));
        int players = Integer.parseInt(value(a, "players", "1"));
        int threads = Integer.parseInt(value(a, "threads", "12"));
        // In-build parallelism on a pool shaped as Cubature's build pool (a quarter of the
        // processors, 1 to 4), as in the game.
        int processors = Runtime.getRuntime().availableProcessors();
        int buildThreads =
                Integer.parseInt(
                        value(a, "buildThreads", "" + Math.max(1, Math.min(4, processors / 4))));
        double radius = Double.parseDouble(value(a, "radius", "4096"));
        double farStep = Double.parseDouble(value(a, "farStep", "32"));
        double near = Double.parseDouble(value(a, "near", "192"));
        double nearStep = Double.parseDouble(value(a, "nearStep", "4"));
        int task = Integer.parseInt(value(a, "task", "64"));
        double move = Double.parseDouble(value(a, "move", "960"));
        String spread = value(a, "spread", "dispersed");
        boolean sizes = Boolean.parseBoolean(value(a, "sizes", "true"));
        double prepareReach = Double.parseDouble(value(a, "prepareReach", "0"));
        double half = 4000 * scale;
        Files.createDirectories(out);

        long t0 = System.nanoTime();
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        buildPool = buildThreads == 0 ? null : BuildPool.bounded(buildThreads, "probe-build");
        maps.pool.use(buildPool);
        double mapSeconds = (System.nanoTime() - t0) / 1e9;
        List<Cache> caches = discover(maps);
        Hydro hydro = maps.terrain.hydro;
        Object planSlots = read(hydro, "memo");
        Object networkSlots = read(read(hydro.drainage, "memo"), "slots");
        Cache plans = null, networks = null;
        for (Cache c : caches) {
            if (c.slots != null && c.slots == planSlots) plans = c;
            if (c.slots != null && c.slots == networkSlots) networks = c;
        }
        if (plans == null || networks == null)
            throw new IllegalStateException("Hydro plan or drainage memo not found");
        plans.timed = networks.timed = true;

        double[][] at = players(maps, seed, players, half, spread);
        double[][] moved = new double[players][];
        Random r = new Random(seed * 31 + 17);
        for (int i = 0; i < players; i++) {
            double angle = r.nextDouble() * 2 * Math.PI;
            moved[i] =
                    new double[] {
                        clamp(at[i][0] + move * Math.cos(angle), half),
                        clamp(at[i][1] + move * Math.sin(angle), half)
                    };
        }
        List<double[]> first = tasks(at, half, radius, farStep, near, nearStep, task);
        List<double[]> second = tasks(moved, half, radius, farStep, near, nearStep, task);

        AtomicInteger made = new AtomicInteger();
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        threads,
                        job -> {
                            Thread t = new Thread(job, "probe-worker-" + made.getAndIncrement());
                            t.setDaemon(true);
                            return t;
                        });
        PoolWatch watch = new PoolWatch();
        Thread watcher = new Thread(watch, "probe-pool-watch");
        watcher.setDaemon(true);
        watcher.start();
        JsonObject preparation = prepare(maps, at, half, prepareReach);
        JsonArray passes = new JsonArray();
        for (int p = 0; p < DEMAND; p++) {
            pass = p;
            List<double[]> work = p == 2 ? second : first;
            Object[][] before = new Object[caches.size()][];
            for (int i = 0; i < caches.size(); i++)
                if (caches.get(i).slots != null) before[i] = caches.get(i).snapshot();
            long started = System.nanoTime();
            long[] taskNanos = run(pool, work, maps.terrain);
            double seconds = (System.nanoTime() - started) / 1e9;
            for (int i = 0; i < caches.size(); i++)
                if (before[i] != null) caches.get(i).compare(p, before[i]);
            // Snapshots hold entries; drop them so retained() sees only the memos' own.
            Arrays.fill(before, null);
            passes.add(passJson(p, seconds, work, taskNanos, watch, caches));
        }
        watch.stop = true;
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        watcher.join();

        int[] overflow = new int[caches.size()];
        for (int i = 0; i < caches.size(); i++) overflow[i] = overflow(caches.get(i));
        int[] resident = new int[caches.size()];
        for (int i = 0; i < caches.size(); i++) resident[i] = caches.get(i).resident();
        if (sizes) retained(caches, resident);

        JsonObject report = new JsonObject();
        report.addProperty("seed", seed);
        report.addProperty("scale", scale);
        report.addProperty("worldBlocks", 2 * half);
        report.addProperty("players", players);
        report.addProperty("spread", spread);
        report.addProperty("threads", threads);
        report.addProperty("buildThreads", buildThreads);
        report.addProperty("commonParallelism", ForkJoinPool.getCommonPoolParallelism());
        report.addProperty("radius", radius);
        report.addProperty("farStep", farStep);
        report.addProperty("near", near);
        report.addProperty("nearStep", nearStep);
        report.addProperty("task", task);
        report.addProperty("move", move);
        report.addProperty("mapSeconds", mapSeconds);
        report.add("positions", points(at));
        report.add("movedPositions", points(moved));
        report.add("passes", passes);
        report.add("preparation", preparation);
        JsonArray cacheJson = new JsonArray();
        for (int i = 0; i < caches.size(); i++)
            cacheJson.add(cacheJson(caches.get(i), resident[i], overflow[i]));
        report.add("caches", cacheJson);
        report.add("continents", continents(maps, plans, networks));
        Files.write(
                out.resolve("probe.json"),
                new GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(report)
                        .getBytes(StandardCharsets.UTF_8));
        print(report);
    }

    private static String required(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null) throw new IllegalArgumentException("Missing " + key + "=");
        return v;
    }

    private static String value(Map<String, String> a, String key, String fallback) {
        return a.containsKey(key) ? a.get(key) : fallback;
    }

    private static double clamp(double v, double half) {
        return Math.max(-half, Math.min(half, v));
    }

    // ---- Discovery

    /**
     * Every memo reachable from the maps through Riven objects and arrays of them, named by the
     * shortest field path: CellMemos, and objects holding a SharedBuild or a memo array.
     */
    static List<Cache> discover(Object root) throws ReflectiveOperationException {
        List<Cache> caches = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        seen.put(root, true);
        queue.add(new Object[] {root, "maps"});
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Object o = item[0];
            String path = (String) item[1];
            Class<?> c = o.getClass();
            if (c.isArray()) {
                for (int i = 0; i < Array.getLength(o); i++)
                    offer(Array.get(o, i), path + "[" + i + "]", seen, queue);
                continue;
            }
            if (o instanceof CellMemo) {
                Cache cache =
                        new Cache(
                                path,
                                "cell-memo",
                                (AtomicReferenceArray<?>) read(o, "slots"),
                                0,
                                0);
                wrap(read(o, "builds"), cache);
                caches.add(cache);
                continue;
            }
            Object builds = null;
            AtomicReferenceArray<?> memo = null;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (RuntimeException | IllegalAccessException e) {
                        continue;
                    }
                    if (v instanceof SharedBuild) builds = v;
                    else if (v instanceof AtomicReferenceArray) {
                        if (memo == null || f.getName().equals("memo"))
                            memo = (AtomicReferenceArray<?>) v;
                    } else offer(v, path + "." + f.getName(), seen, queue);
                }
            if (builds == null && memo == null) continue;
            int sets = integer(o, "sets"), ways = integer(o, "ways");
            Cache cache =
                    new Cache(
                            path,
                            builds != null ? "set-assoc" : sets > 0 ? "set-assoc-unshared" : "memo",
                            memo,
                            sets,
                            ways);
            if (builds != null) wrap(builds, cache);
            caches.add(cache);
        }
        return caches;
    }

    private static void offer(
            Object v, String path, IdentityHashMap<Object, Boolean> seen, ArrayDeque<Object[]> q) {
        if (v == null || seen.containsKey(v)) return;
        Class<?> c = v.getClass();
        if (c.isArray()) {
            Class<?> e = c.getComponentType();
            if (e.isPrimitive()) return;
            if (!(e.isArray() || e == Object.class || e.getName().startsWith("cc.orthrus.riven.")))
                return;
        } else if (!c.getName().startsWith("cc.orthrus.riven.")) return;
        seen.put(v, true);
        q.add(new Object[] {v, path});
    }

    private static void wrap(Object builds, Cache cache) throws ReflectiveOperationException {
        Field f = SharedBuild.class.getDeclaredField("flights");
        f.setAccessible(true);
        f.set(builds, new Flights(cache));
        cache.instrumented = true;
    }

    static Object read(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass())
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // Try the superclass.
            }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    private static int integer(Object o, String name) {
        try {
            Object v = read(o, name);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    // ---- Preparation

    /**
     * Prepares every hydrology owner cell within {@code reach} of a player, nearest first, on the
     * build pool, as Cubature's regional preparation would before the players arrive.
     */
    static JsonObject prepare(GenerationMaps maps, double[][] at, double half, double reach)
            throws Exception {
        JsonObject j = new JsonObject();
        j.addProperty("reach", reach);
        if (reach <= 0) return j;
        pass = PREPARE;
        int cell = maps.terrain.hydro.cellBlocks;
        Map<Long, Double> nearest = new HashMap<>();
        for (double[] p : at)
            for (int cz = (int) Math.floor((p[1] - reach) / cell);
                    cz <= (int) Math.floor((p[1] + reach) / cell);
                    cz++)
                for (int cx = (int) Math.floor((p[0] - reach) / cell);
                        cx <= (int) Math.floor((p[0] + reach) / cell);
                        cx++) {
                    double dx =
                            Math.max(
                                    0,
                                    Math.max(cx * (double) cell - p[0], p[0] - (cx + 1.0) * cell));
                    double dz =
                            Math.max(
                                    0,
                                    Math.max(cz * (double) cell - p[1], p[1] - (cz + 1.0) * cell));
                    double d = Math.hypot(dx, dz);
                    if (d > reach
                            || cx * (double) cell > half
                            || (cx + 1.0) * cell < -half
                            || cz * (double) cell > half
                            || (cz + 1.0) * cell < -half) continue;
                    nearest.merge(((long) cx << 32) ^ (cz & 0xffffffffL), d, Math::min);
                }
        List<Map.Entry<Long, Double>> order = new ArrayList<>(nearest.entrySet());
        order.sort(Map.Entry.comparingByValue());
        AtomicInteger declined = new AtomicInteger();
        long started = System.nanoTime();
        List<java.util.concurrent.ForkJoinTask<?>> jobs = new ArrayList<>();
        for (Map.Entry<Long, Double> e : order) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            Runnable job =
                    () -> {
                        if (!maps.terrain.prepareRegion(cx, cz)) declined.incrementAndGet();
                    };
            if (buildPool == null) job.run();
            else jobs.add(buildPool.submit(job));
        }
        for (java.util.concurrent.ForkJoinTask<?> job : jobs) job.join();
        j.addProperty("regions", order.size());
        j.addProperty("declined", declined.get());
        j.addProperty("seconds", (System.nanoTime() - started) / 1e9);
        return j;
    }

    // ---- Demand

    /** Player positions on land: anywhere in the world, or near the land closest to the origin. */
    static double[][] players(GenerationMaps maps, long seed, int n, double half, String spread) {
        Random r = new Random(seed ^ 0x5DEECE66DL);
        ContinentalMap.Sample s = new ContinentalMap.Sample();
        double cx = 0, cz = 0, reach = half;
        if (spread.equals("clustered")) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 20000; i++) {
                double x = (r.nextDouble() * 2 - 1) * half, z = (r.nextDouble() * 2 - 1) * half;
                maps.continents.sample(x, z, s);
                if (!s.marine && Math.hypot(x, z) < best) {
                    best = Math.hypot(x, z);
                    cx = x;
                    cz = z;
                }
            }
            reach = 1024;
        } else if (!spread.equals("dispersed"))
            throw new IllegalArgumentException("spread=dispersed|clustered");
        double[][] at = new double[n][];
        for (int i = 0; i < n; i++)
            for (int tries = 0; ; tries++) {
                double x = clamp(cx + (r.nextDouble() * 2 - 1) * reach, half);
                double z = clamp(cz + (r.nextDouble() * 2 - 1) * reach, half);
                maps.continents.sample(x, z, s);
                if (!s.marine || tries > 10000) {
                    at[i] = new double[] {x, z};
                    break;
                }
            }
        return at;
    }

    /** Each player's columns nearest first in tasks, dealt round-robin across players. */
    static List<double[]> tasks(
            double[][] at,
            double half,
            double radius,
            double farStep,
            double near,
            double nearStep,
            int task) {
        List<List<double[]>> each = new ArrayList<>();
        for (double[] p : at) {
            List<double[]> columns = new ArrayList<>();
            grid(p, half, radius, farStep, columns);
            grid(p, half, near, nearStep, columns);
            columns.sort(
                    (u, v) ->
                            Double.compare(
                                    Math.hypot(u[0] - p[0], u[1] - p[1]),
                                    Math.hypot(v[0] - p[0], v[1] - p[1])));
            List<double[]> mine = new ArrayList<>();
            for (int i = 0; i < columns.size(); i += task) {
                int n = Math.min(task, columns.size() - i);
                double[] t = new double[2 * n];
                for (int j = 0; j < n; j++) {
                    t[2 * j] = columns.get(i + j)[0];
                    t[2 * j + 1] = columns.get(i + j)[1];
                }
                mine.add(t);
            }
            each.add(mine);
        }
        List<double[]> dealt = new ArrayList<>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (List<double[]> mine : each)
                if (i < mine.size()) {
                    dealt.add(mine.get(i));
                    any = true;
                }
            if (!any) return dealt;
        }
    }

    private static void grid(
            double[] p, double half, double radius, double step, List<double[]> out) {
        long i0 = (long) Math.ceil((p[0] - radius) / step - .5);
        long i1 = (long) Math.floor((p[0] + radius) / step - .5);
        long j0 = (long) Math.ceil((p[1] - radius) / step - .5);
        long j1 = (long) Math.floor((p[1] + radius) / step - .5);
        for (long j = j0; j <= j1; j++)
            for (long i = i0; i <= i1; i++) {
                double x = (i + .5) * step, z = (j + .5) * step;
                if (Math.abs(x) > half || Math.abs(z) > half) continue;
                if (Math.hypot(x - p[0], z - p[1]) <= radius) out.add(new double[] {x, z});
            }
    }

    private static long[] run(ExecutorService pool, List<double[]> work, TerrainMap terrain)
            throws Exception {
        List<Future<Long>> futures = new ArrayList<>(work.size());
        for (double[] t : work) {
            Callable<Long> job =
                    () -> {
                        long start = System.nanoTime();
                        TerrainMap.Sample s = SAMPLE.get();
                        for (int i = 0; i < t.length; i += 2) terrain.sample(t[i], t[i + 1], s);
                        return System.nanoTime() - start;
                    };
            futures.add(pool.submit(job));
        }
        long[] nanos = new long[futures.size()];
        for (int i = 0; i < nanos.length; i++) nanos[i] = futures.get(i).get();
        return nanos;
    }

    /** The common ForkJoin pool's size and activity, and the build pool's size, every 10 ms. */
    static final class PoolWatch implements Runnable {
        final AtomicLong[] maxSize = longs(), maxActive = longs(), ticks = longs(), busy = longs();
        final AtomicLong[] buildMax = longs();
        volatile boolean stop;

        private static AtomicLong[] longs() {
            AtomicLong[] a = new AtomicLong[PASSES.length];
            for (int i = 0; i < a.length; i++) a[i] = new AtomicLong();
            return a;
        }

        @Override
        public void run() {
            ForkJoinPool common = ForkJoinPool.commonPool();
            while (!stop) {
                int p = pass;
                maxSize[p].accumulateAndGet(common.getPoolSize(), Math::max);
                ForkJoinPool build = buildPool;
                if (build != null) buildMax[p].accumulateAndGet(build.getPoolSize(), Math::max);
                int active = common.getActiveThreadCount();
                maxActive[p].accumulateAndGet(active, Math::max);
                ticks[p].incrementAndGet();
                if (active > 0) busy[p].incrementAndGet();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ---- Analysis

    /**
     * Keys built that cannot all be held at once. Set-associative memos pick a set by
     * mix(key) & (sets - 1) and hold ways keys a set. A CellMemo key may take either of its two
     * slots, so each key is an edge between them and a group of slots joined by keys holds at
     * most as many keys as it has slots.
     */
    static int overflow(Cache c) {
        if (c.built.isEmpty() || c.slots == null) return 0;
        if (c.sets > 0) return setOverflow(c.built.keySet(), c.sets, c.ways);
        if (c.kind.equals("cell-memo")) return slotOverflow(c.built.keySet(), c.capacity());
        return 0;
    }

    static int setOverflow(Iterable<Long> keys, int sets, int ways) {
        int[] load = new int[sets];
        for (long k : keys) load[(int) SeededNoise.mix(k) & (sets - 1)]++;
        int over = 0;
        for (int l : load) over += Math.max(0, l - ways);
        return over;
    }

    static int slotOverflow(Iterable<Long> keys, int capacity) {
        int mask = capacity - 1;
        int[] parent = new int[capacity], edges = new int[capacity], vertices = new int[capacity];
        boolean[] touched = new boolean[capacity];
        for (int i = 0; i < capacity; i++) parent[i] = i;
        List<int[]> pairs = new ArrayList<>();
        for (long k : keys) {
            long h = SeededNoise.mix(k);
            int a = (int) h & mask, b = (int) (h >>> 32) & mask;
            touched[a] = touched[b] = true;
            parent[root(parent, a)] = root(parent, b);
            pairs.add(new int[] {a, b});
        }
        for (int[] e : pairs) edges[root(parent, e[0])]++;
        for (int i = 0; i < capacity; i++) if (touched[i]) vertices[root(parent, i)]++;
        int over = 0;
        for (int i = 0; i < capacity; i++) over += Math.max(0, edges[i] - vertices[i]);
        return over;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /** Heap each memo's entries retain alone: used heap before and after clearing its slots. */
    static void retained(List<Cache> caches, int[] resident) {
        Integer[] order = new Integer[caches.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (u, v) -> resident[v] - resident[u]);
        long before = used();
        for (int i : order) {
            Cache c = caches.get(i);
            if (resident[i] == 0) continue;
            for (int s = 0; s < c.capacity(); s++) c.slots.set(s, null);
            long after = used();
            c.retained = Math.max(0, before - after);
            before = after;
        }
    }

    private static long used() {
        for (int i = 0; i < 3; i++) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** Each continent's drainage scan: its support, the plans it covers and the plans it built. */
    static JsonArray continents(GenerationMaps maps, Cache plans, Cache networks) {
        int cell = maps.terrain.hydro.cellBlocks;
        List<long[]> planBuilds = new ArrayList<>(plans.timeline);
        JsonArray out = new JsonArray();
        for (long[] n : networks.timeline) {
            int gx = (int) (n[0] >> 32), gz = (int) n[0];
            double[] box = new double[4];
            long owner =
                    maps.outline.continentBounds(
                            gx, gz, new ContinentalOutlineMap.TrunkScratch(0), box);
            int x0 = (int) Math.floor(box[0] / cell), z0 = (int) Math.floor(box[1] / cell);
            int x1 = (int) Math.floor(box[2] / cell), z1 = (int) Math.floor(box[3] / cell);
            List<Long> covered = new ArrayList<>(), ring = new ArrayList<>();
            for (int z = z0 - 1; z <= z1 + 1; z++)
                for (int x = x0 - 1; x <= x1 + 1; x++) {
                    long key = ((long) x << 32) ^ (z & 0xffffffffL);
                    ring.add(key);
                    if (x >= x0 && x <= x1 && z >= z0 && z <= z1) covered.add(key);
                }
            int during = 0, outside = 0;
            for (long[] b : planBuilds) {
                if (b[1] < n[1] || b[1] > n[2]) continue;
                int px = (int) (b[0] >> 32), pz = (int) b[0];
                if (px >= x0 - 1 && px <= x1 + 1 && pz >= z0 - 1 && pz <= z1 + 1) during++;
                else outside++;
            }
            JsonObject j = new JsonObject();
            JsonArray at = new JsonArray();
            at.add(gx);
            at.add(gz);
            j.add("cell", at);
            j.addProperty("owner", owner);
            j.addProperty("supportWidth", box[2] - box[0]);
            j.addProperty("supportDepth", box[3] - box[1]);
            j.addProperty("planCells", covered.size());
            j.addProperty("planCellsWithRing", ring.size());
            j.addProperty("plansBuiltDuringScan", during);
            j.addProperty("otherPlansBuiltDuringScan", outside);
            j.addProperty("ringSetOverflow", setOverflow(ring, plans.sets, plans.ways));
            j.addProperty("scanSeconds", (n[2] - n[1]) / 1e9);
            out.add(j);
        }
        return out;
    }

    // ---- Report

    private static JsonArray points(double[][] at) {
        JsonArray a = new JsonArray();
        for (double[] p : at) {
            JsonArray q = new JsonArray();
            q.add(Math.round(p[0]));
            q.add(Math.round(p[1]));
            a.add(q);
        }
        return a;
    }

    private static JsonObject passJson(
            int p,
            double seconds,
            List<double[]> work,
            long[] taskNanos,
            PoolWatch watch,
            List<Cache> caches) {
        long columns = 0;
        for (double[] t : work) columns += t.length / 2;
        long[] sorted = taskNanos.clone();
        Arrays.sort(sorted);
        JsonObject j = new JsonObject();
        j.addProperty("pass", PASSES[p]);
        j.addProperty("seconds", seconds);
        j.addProperty("columns", columns);
        j.addProperty("tasks", sorted.length);
        j.addProperty("taskP50Ms", percentile(sorted, .5) / 1e6);
        j.addProperty("taskP90Ms", percentile(sorted, .9) / 1e6);
        j.addProperty("taskP99Ms", percentile(sorted, .99) / 1e6);
        j.addProperty("taskMaxMs", sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6);
        j.addProperty("commonPoolMaxSize", watch.maxSize[p].get());
        j.addProperty("buildPoolMaxSize", watch.buildMax[p].get());
        j.addProperty("commonPoolMaxActive", watch.maxActive[p].get());
        long ticks = Math.max(1, watch.ticks[p].get());
        j.addProperty("commonPoolBusyShare", watch.busy[p].get() / (double) ticks);
        long builds = 0, rebuilds = 0, raced = 0;
        long[] waits = new long[KINDS.length], waitNanos = new long[KINDS.length];
        for (Cache c : caches) {
            builds += c.count(p, BUILDS);
            rebuilds += c.count(p, REBUILDS);
            raced += c.count(p, RACED);
            for (int k = 0; k < KINDS.length; k++) {
                waits[k] += c.count(p, WAITS + k);
                waitNanos[k] += c.count(p, WAIT_NANOS + k);
            }
        }
        j.addProperty("builds", builds);
        j.addProperty("rebuilds", rebuilds);
        j.addProperty("raced", raced);
        for (int k = 0; k < KINDS.length; k++) {
            j.addProperty(KINDS[k] + "Waits", waits[k]);
            j.addProperty(KINDS[k] + "WaitSeconds", waitNanos[k] / 1e9);
        }
        return j;
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(q * sorted.length))];
    }

    private static JsonObject cacheJson(Cache c, int resident, int overflow) {
        JsonObject j = new JsonObject();
        j.addProperty("name", c.name);
        j.addProperty("kind", c.kind);
        j.addProperty("capacity", c.capacity());
        if (c.sets > 0) {
            j.addProperty("sets", c.sets);
            j.addProperty("ways", c.ways);
        }
        j.addProperty("resident", resident);
        j.addProperty("instrumented", c.instrumented);
        j.addProperty("distinctBuilt", c.built.size());
        j.addProperty("overflow", overflow);
        j.addProperty("retainedBytes", c.retained);
        JsonObject passes = new JsonObject();
        for (int p = 0; p < PASSES.length; p++) {
            JsonObject q = new JsonObject();
            q.addProperty("builds", c.count(p, BUILDS));
            q.addProperty("rebuilds", c.count(p, REBUILDS));
            q.addProperty("buildSeconds", c.count(p, BUILD_NANOS) / 1e9);
            q.addProperty("longestBuildMs", c.longest[p].get() / 1e6);
            q.addProperty("reentries", c.count(p, REENTRIES));
            q.addProperty("raced", c.count(p, RACED));
            for (int k = 0; k < KINDS.length; k++) {
                q.addProperty(KINDS[k] + "Waits", c.count(p, WAITS + k));
                q.addProperty(KINDS[k] + "WaitSeconds", c.count(p, WAIT_NANOS + k) / 1e9);
            }
            q.addProperty("slotsFilled", c.filled[p]);
            q.addProperty("slotsReplaced", c.replaced[p]);
            passes.add(PASSES[p], q);
        }
        j.add("passes", passes);
        return j;
    }

    private static void print(JsonObject r) {
        System.out.printf(
                "seed %d, scale %s (%.0f-block world), %d %s players, %d threads, build pool %d,"
                        + " common pool parallelism %d, maps %.1fs%n",
                r.get("seed").getAsLong(),
                r.get("scale").getAsString(),
                r.get("worldBlocks").getAsDouble(),
                r.get("players").getAsInt(),
                r.get("spread").getAsString(),
                r.get("threads").getAsInt(),
                r.get("buildThreads").getAsInt(),
                r.get("commonParallelism").getAsInt(),
                r.get("mapSeconds").getAsDouble());
        JsonObject prep = r.getAsJsonObject("preparation");
        if (prep.has("regions"))
            System.out.printf(
                    "preparation within %.0f blocks: %d regions, %d declined, %.1fs%n",
                    prep.get("reach").getAsDouble(),
                    prep.get("regions").getAsInt(),
                    prep.get("declined").getAsInt(),
                    prep.get("seconds").getAsDouble());
        System.out.printf(
                "%-7s %8s %9s %8s %8s %8s %7s %7s %6s %9s %9s %9s %7s %6s %6s%n",
                "pass",
                "seconds",
                "columns",
                "taskP50",
                "taskP99",
                "taskMax",
                "builds",
                "rebuild",
                "raced",
                "cmnWaitS",
                "bldWaitS",
                "wkrWaitS",
                "cmnMax",
                "busy",
                "bldMax");
        for (int i = 0; i < r.getAsJsonArray("passes").size(); i++) {
            JsonObject p = r.getAsJsonArray("passes").get(i).getAsJsonObject();
            System.out.printf(
                    "%-7s %8.1f %9d %7.1fm %7.1fm %7.0fm %7d %7d %6d %9.1f %9.1f %9.1f %7d %5.0f%% %6d%n",
                    p.get("pass").getAsString(),
                    p.get("seconds").getAsDouble(),
                    p.get("columns").getAsLong(),
                    p.get("taskP50Ms").getAsDouble(),
                    p.get("taskP99Ms").getAsDouble(),
                    p.get("taskMaxMs").getAsDouble(),
                    p.get("builds").getAsLong(),
                    p.get("rebuilds").getAsLong(),
                    p.get("raced").getAsLong(),
                    p.get("commonWaitSeconds").getAsDouble(),
                    p.get("buildWaitSeconds").getAsDouble(),
                    p.get("workerWaitSeconds").getAsDouble(),
                    p.get("commonPoolMaxSize").getAsLong(),
                    100 * p.get("commonPoolBusyShare").getAsDouble(),
                    p.get("buildPoolMaxSize").getAsLong());
        }
        System.out.printf(
                "%-58s %6s %6s %6s %13s %7s %6s %8s %9s %6s %13s %8s %8s%n",
                "memo",
                "cap",
                "held",
                "keys",
                "builds c/r/m",
                "rebuild",
                "raced",
                "buildS",
                "waitS",
                "over",
                "replaced c/r/m",
                "MB",
                "KB/key");
        for (int i = 0; i < r.getAsJsonArray("caches").size(); i++) {
            JsonObject c = r.getAsJsonArray("caches").get(i).getAsJsonObject();
            JsonObject ps = c.getAsJsonObject("passes");
            long[] b = new long[PASSES.length], rep = new long[PASSES.length];
            long rebuilds = 0, raced = 0;
            double buildS = 0, waitS = 0;
            for (int p = 0; p < PASSES.length; p++) {
                JsonObject q = ps.getAsJsonObject(PASSES[p]);
                b[p] = q.get("builds").getAsLong();
                rep[p] = q.get("slotsReplaced").getAsLong();
                rebuilds += q.get("rebuilds").getAsLong();
                raced += q.get("raced").getAsLong();
                buildS += q.get("buildSeconds").getAsDouble();
                for (String k : KINDS) waitS += q.get(k + "WaitSeconds").getAsDouble();
            }
            int held = c.get("resident").getAsInt(), keys = c.get("distinctBuilt").getAsInt();
            if (held == 0 && keys == 0) continue;
            long bytes = c.get("retainedBytes").getAsLong();
            int denominator = keys > 0 ? Math.min(keys, held) : held;
            System.out.printf(
                    "%-58s %6d %6d %6s %13s %7s %6s %8.1f %9.1f %6s %13s %8s %8s%n",
                    c.get("name").getAsString().replaceFirst("^maps\\.", ""),
                    c.get("capacity").getAsInt(),
                    held,
                    c.get("instrumented").getAsBoolean() ? Integer.toString(keys) : "-",
                    b[0] + "/" + b[1] + "/" + b[2],
                    Long.toString(rebuilds),
                    Long.toString(raced),
                    buildS,
                    waitS,
                    Integer.toString(c.get("overflow").getAsInt()),
                    rep[0] + "/" + rep[1] + "/" + rep[2],
                    bytes < 0 ? "-" : String.format("%.1f", bytes / 1048576.0),
                    bytes < 0 || denominator == 0
                            ? "-"
                            : String.format("%.1f", bytes / 1024.0 / denominator));
        }
        System.out.printf(
                "%-10s %7s %7s %6s %6s %7s %7s %6s %7s%n",
                "continent",
                "width",
                "depth",
                "plans",
                "+ring",
                "built",
                "others",
                "over",
                "scanS");
        for (int i = 0; i < r.getAsJsonArray("continents").size(); i++) {
            JsonObject c = r.getAsJsonArray("continents").get(i).getAsJsonObject();
            JsonArray at = c.getAsJsonArray("cell");
            System.out.printf(
                    "%-10s %7.0f %7.0f %6d %6d %7d %7d %6d %7.1f%n",
                    at.get(0).getAsInt() + "," + at.get(1).getAsInt(),
                    c.get("supportWidth").getAsDouble(),
                    c.get("supportDepth").getAsDouble(),
                    c.get("planCells").getAsInt(),
                    c.get("planCellsWithRing").getAsInt(),
                    c.get("plansBuiltDuringScan").getAsInt(),
                    c.get("otherPlansBuiltDuringScan").getAsInt(),
                    c.get("ringSetOverflow").getAsInt(),
                    c.get("scanSeconds").getAsDouble());
        }
    }
}
