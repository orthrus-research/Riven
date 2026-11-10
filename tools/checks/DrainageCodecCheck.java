package cc.orthrus.riven.worldgen;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Exact closed drainage graph round trips. The fixture-only mode constructs no terrain; the
 * default also builds and reloads a stored network for each of six seeds, retaining fresh cache
 * directories for inspection. The verify mode reads an existing current-identity namespace and
 * compares selected entries with fresh, cache-free builds; it creates or repairs no files.
 * Usage: {@code DrainageCodecCheck <fixture parent> [fixtures | scale=N [seed ...]]}, or
 * {@code DrainageCodecCheck verify <cache root> seed=N scale=N entries=N random=N}.
 */
public final class DrainageCodecCheck {
    private static final Class<?> CHANNEL = nested("Channel"), NETWORK = nested("Network");
    private static final Class<?> BINS = nested("Bins"), LAKES = nested("Lakes");
    private static final Class<?> CHANNELS = Array.newInstance(CHANNEL, 0).getClass();
    private static final long[] SEEDS = {8675309, 2485331, 1, 5, 101, 12345};
    private static final RegionalStore.Codec<Object> CODEC = codec();

    private DrainageCodecCheck() {}

    private static Class<?> nested(String name) {
        try {
            return Class.forName(Drainage.class.getName() + "$" + name);
        } catch (ClassNotFoundException missing) {
            throw new ExceptionInInitializerError(missing);
        }
    }

    @SuppressWarnings("unchecked")
    private static RegionalStore.Codec<Object> codec() {
        try {
            return (RegionalStore.Codec<Object>) get(Drainage.class, null, "NETWORK_CODEC");
        } catch (ReflectiveOperationException missing) {
            throw new ExceptionInInitializerError(missing);
        }
    }

    private static Object get(Class<?> type, Object value, String name)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static Object get(Object value, String name) throws ReflectiveOperationException {
        return get(value.getClass(), value, name);
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... values)
            throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            if (wrapped.getCause() instanceof Error failure) throw failure;
            throw wrapped;
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    /** Reflect every field, so an omitted field cannot hide behind a matching re-encode. */
    private static void same(Object expected, Object actual, String path) throws Exception {
        if (expected == null || actual == null) {
            require(expected == actual, path + ": null changed");
            return;
        }
        Class<?> type = expected.getClass();
        require(type == actual.getClass(), path + ": type changed");
        if (type.isArray()) {
            int count = Array.getLength(expected);
            require(count == Array.getLength(actual), path + ": length changed");
            for (int i = 0; i < count; i++)
                same(Array.get(expected, i), Array.get(actual, i), path + "[" + i + "]");
        } else if (expected instanceof Double a) {
            require(
                    Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits((Double) actual),
                    path + ": double bits changed");
        } else if (expected instanceof Float a) {
            require(
                    Float.floatToRawIntBits(a) == Float.floatToRawIntBits((Float) actual),
                    path + ": float bits changed");
        } else if (expected instanceof Long
                || expected instanceof Integer
                || expected instanceof Byte
                || expected instanceof Boolean) {
            require(expected.equals(actual), path + ": primitive changed");
        } else {
            require(
                    type.getEnclosingClass() == Drainage.class,
                    path + ": foreign retained type " + type);
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                same(field.get(expected), field.get(actual), path + "." + field.getName());
            }
        }
    }

    private static byte[] encode(Object network) {
        RegionalData.Writer out = new RegionalData.Writer();
        CODEC.write(out, network);
        return out.finish();
    }

    private static Object decode(byte[] body) throws RegionalData.Malformed {
        RegionalData.Reader in = new RegionalData.Reader(MemorySegment.ofArray(body));
        Object value = CODEC.read(in);
        in.requireEnd();
        return value;
    }

    private static void malformed(byte[] body) throws Exception {
        try {
            decode(body);
            throw new AssertionError("Malformed graph was accepted");
        } catch (RegionalData.Malformed expected) {
            // An unchecked codec failure is deliberately not accepted here.
        }
    }

    private static long aligned(long bytes) {
        return (bytes + 7) & ~7L;
    }

    private static int width(Class<?> type) {
        if (!type.isPrimitive() || type == int.class || type == float.class) return 4;
        if (type == long.class || type == double.class) return 8;
        if (type == short.class || type == char.class) return 2;
        return 1;
    }

    /** Independent complete retained-graph estimate under the codec's stated layout assumptions. */
    private static long heap(Object value, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || seen.put(value, Boolean.TRUE) != null) return 0;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            int length = Array.getLength(value);
            long bytes = aligned(16 + (long) width(component) * length);
            if (!component.isPrimitive())
                for (int i = 0; i < length; i++) bytes += heap(Array.get(value, i), seen);
            return bytes;
        }
        require(type.getEnclosingClass() == Drainage.class, "Unexpected heap type " + type);
        long fields = 16, children = 0;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            fields += width(field.getType());
            if (!field.getType().isPrimitive()) {
                field.setAccessible(true);
                children += heap(field.get(value), seen);
            }
        }
        return aligned(fields) + children;
    }

    private static Object roundTrip(Object original, String label) throws Exception {
        Object loaded = decode(encode(original));
        equivalent(original, loaded, label);
        return loaded;
    }

    private static void equivalent(Object original, Object loaded, String label) throws Exception {
        byte[] encoded = encode(original);
        same(original, loaded, label);
        require(Arrays.equals(encoded, encode(loaded)), label + ": encoded bytes changed");
        Method estimate = CODEC.getClass().getDeclaredMethod("detachedBytes", NETWORK);
        long complete = (Long) invoke(estimate, null, loaded);
        require(
                complete == heap(loaded, new IdentityHashMap<>()),
                label + ": incomplete heap estimate");
        System.out.printf(
                "%s: %d body bytes, %d live estimated bytes, %d detached estimated bytes%n",
                label, encoded.length, (Long) get(loaded, "bytes"), complete);
    }

    private static Object channel(boolean compact) throws Exception {
        double nan = Double.longBitsToDouble(0x7ff8000000000042L);
        double[] x = {-0.0, 2.25, 4.5}, z = {5, -0.0, 7};
        double[] level = {-0.0, nan, 12}, radius = {1, 2, 3}, depth = {4.5, 5, 6};
        double[] valley = compact ? new double[3] : new double[] {-0.0, 1, 2};
        double[] braid = compact ? new double[3] : new double[] {-0.0, 0, 0};
        double[] bar = compact ? new double[3] : new double[] {-0.0, 0, 0};
        double[] weave = {nan, -0.0, 1}, gradient = {nan, -0.0, .25}, riffle = {0, 1, 0};
        return constructChannel(
                compact ? Long.MIN_VALUE : Long.MAX_VALUE,
                x,
                z,
                level,
                radius,
                depth,
                valley,
                weave,
                gradient,
                braid,
                bar,
                riffle,
                compact,
                3.5,
                .25,
                nan,
                Double.longBitsToDouble(0xfff800000000002aL));
    }

    private static Object constructChannel(Object... values) throws ReflectiveOperationException {
        return construct(
                CHANNEL,
                new Class<?>[] {
                    long.class, double[].class, double[].class, double[].class, double[].class,
                    double[].class, double[].class, double[].class, double[].class, double[].class,
                    double[].class, double[].class, boolean.class, double.class, double.class,
                    double.class, double.class
                },
                values);
    }

    private static Object fixture(boolean withLakes) throws Exception {
        Object channels = Array.newInstance(CHANNEL, 2);
        Array.set(channels, 0, channel(true));
        Array.set(channels, 1, channel(false));
        Class<?>[] binTypes = {long[].class, int[].class, int[].class, int[].class, int[].class};
        Object refs =
                construct(
                        BINS,
                        binTypes,
                        new long[] {10},
                        new int[] {0},
                        new int[] {0, 2, 3},
                        new int[] {0, 0x10001, 0x10000},
                        new int[] {2, 1, 1});
        Object rivers =
                construct(
                        BINS,
                        binTypes,
                        new long[] {8},
                        new int[] {0},
                        new int[] {0, 1},
                        new int[] {0},
                        new int[] {2});
        Object lakes = null;
        if (withLakes)
            lakes =
                    construct(
                            LAKES,
                            new Class<?>[] {
                                double.class,
                                double.class,
                                double.class,
                                int.class,
                                int.class,
                                int[].class,
                                float[].class,
                                int[].class,
                                long[].class,
                                double[].class
                            },
                            -0.0,
                            17.25,
                            32.0,
                            2,
                            2,
                            new int[] {-1, 1, 0, 1},
                            new float[] {Float.intBitsToFloat(0x7fc0002a), -0.0f, 0, 12},
                            new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE},
                            new long[] {Long.MIN_VALUE, Long.MAX_VALUE},
                            new double[] {
                                -0.0, 1, 2, 3, 4, 5, 6, Double.longBitsToDouble(0x7ff800000000002aL)
                            });
        return construct(
                NETWORK,
                new Class<?>[] {
                    CHANNELS,
                    double.class,
                    double.class,
                    double.class,
                    int.class,
                    int.class,
                    BINS,
                    BINS,
                    double[].class,
                    LAKES
                },
                channels,
                -0.0,
                123.5,
                64.0,
                2,
                2,
                refs,
                rivers,
                new double[] {-0.0, 0, 3, Double.longBitsToDouble(0x7ff800000000002aL)},
                lakes);
    }

    private static void binReferenceFixtures() throws Exception {
        Method read =
                CODEC.getClass()
                        .getDeclaredMethod("bins", RegionalData.Reader.class, int.class, CHANNELS);
        Method build = BINS.getDeclaredMethod("of", List.class);
        Object channels = Array.newInstance(CHANNEL, 65536);
        // Only four referenced slots are populated; no large graph is encoded.
        double[] points = new double[65537];
        Object wide =
                constructChannel(
                        1L,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        points,
                        false,
                        0.0,
                        0.0,
                        Double.NaN,
                        Double.NaN);
        Object narrow = channel(true);
        Array.set(channels, 0, narrow);
        Array.set(channels, 32767, wide);
        Array.set(channels, 32768, narrow);
        Array.set(channels, 65535, wide);
        List<List<Integer>> cases =
                List.of(
                        List.of(Integer.MAX_VALUE),
                        List.of(Integer.MAX_VALUE, Integer.MIN_VALUE),
                        List.of(Integer.MIN_VALUE),
                        List.of(-1),
                        List.of(-1, 0));
        for (int k = 0; k < cases.size(); k++) {
            Object original = invoke(build, null, List.of(cases.get(k)));
            require(
                    ((int[]) get(original, "start"))[0] == cases.get(k).get(0)
                            && ((int[]) get(original, "count"))[0] == cases.get(k).size(),
                    "Builder did not preserve the expected boundary run");
            binRoundTrip(read, original, channels, true, "Packed boundary " + k);
        }
        Object wrapped = invoke(build, null, List.of(List.of(-1, 0)));
        binRoundTrip(
                read, wrapped, Array.newInstance(CHANNEL, 65535), false, "Missing tail channel");
        Array.set(channels, 0, null);
        binRoundTrip(read, wrapped, channels, false, "Missing head channel");
        Array.set(channels, 0, narrow);
        Array.set(channels, 65535, narrow);
        binRoundTrip(read, wrapped, channels, false, "Short tail points");
    }

    private static void binRoundTrip(
            Method read, Object original, Object channels, boolean valid, String label)
            throws Exception {
        RegionalData.Writer out = new RegionalData.Writer();
        out.longs((long[]) get(original, "occupied"));
        for (String name : new String[] {"before", "first", "start", "count"})
            out.integers((int[]) get(original, name));
        RegionalData.Reader in = new RegionalData.Reader(MemorySegment.ofArray(out.finish()));
        try {
            Object loaded = invoke(read, null, in, 1, channels);
            require(valid, label + ": malformed references were accepted");
            in.requireEnd();
            same(original, loaded, label);
        } catch (RegionalData.Malformed failure) {
            require(!valid, label + ": valid references were rejected");
        }
    }

    /** Well-sized corrupt graph fields must fail structurally, before any consumer sees them. */
    private static byte[] structuralFailures(byte[] body) throws Exception {
        RegionalData.Reader in = new RegionalData.Reader(MemorySegment.ofArray(body));
        in.raw(32);
        int channels = in.integer(), riffle = -1;
        for (int c = 0; c < channels; c++) {
            in.longValue();
            for (int a = 0; a < 10; a++) in.doubles();
            if (c == 0) riffle = (int) in.position() + Integer.BYTES;
            in.bytes();
            in.bool();
            in.raw(6 * Double.BYTES);
        }
        in.longs();
        int rank = (int) in.position() + Integer.BYTES;
        in.integers();
        int extent = (int) in.position() + Integer.BYTES;
        in.integers();
        in.integers();
        in.integers();
        in.longs();
        for (int a = 0; a < 4; a++) in.integers();
        in.doubles();
        require(in.bool(), "Fixture lost its lakes");
        in.raw(3 * Double.BYTES + 2 * Integer.BYTES);
        int lake = (int) in.position() + Integer.BYTES;
        for (int position : new int[] {rank, extent, lake}) {
            byte[] invalid = body.clone();
            ByteBuffer.wrap(invalid)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(position, Integer.MAX_VALUE);
            malformed(invalid);
        }
        byte[] invalid = body.clone();
        invalid[riffle] = 2;
        malformed(invalid);
        return invalid;
    }

    private static RegionalStore.Codec<Object> protocol(byte[] body, boolean bug) {
        return new RegionalStore.Codec<>() {
            @Override
            public int version() {
                return CODEC.version();
            }

            @Override
            public void write(RegionalData.Writer out, Object value) {
                out.raw(body);
            }

            @Override
            public Object read(RegionalData.Reader in) throws RegionalData.Malformed {
                if (bug) throw new IllegalStateException("Codec bug fixture");
                return CODEC.read(in);
            }
        };
    }

    private static void cacheFailureBoundary(Path parent, byte[] invalid, Object original)
            throws Exception {
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "fixture-");
        require(root.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped its parent");
        try (RegionalStore store =
                new RegionalStore(root, RegionalIdentity.capture(8675309, WorldScale.DEFAULT))) {
            RegionalStore.Codec<Object> malformed = protocol(invalid, false);
            require(
                    store.put("drainage", 0, 0, original, malformed),
                    "Cannot write malformed fixture");
            require(
                    store.get("drainage", 0, 0, malformed) == null,
                    "Checksummed malformed graph was not a cache miss");
            try {
                store.get("drainage", 0, 0, protocol(invalid, true));
                throw new AssertionError("Unexpected codec bug became a cache miss");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().equals("Codec bug fixture"), "Wrong codec exception");
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void fixtures(Path parent) throws Exception {
        roundTrip(get(NETWORK, null, "EMPTY"), "empty");
        Object original = fixture(true);
        roundTrip(original, "compact/null arrays and lakes");
        roundTrip(fixture(false), "absent lakes");
        byte[] body = encode(original);
        for (int length = 0; length < body.length; length++) malformed(Arrays.copyOf(body, length));
        malformed(Arrays.copyOf(body, body.length + 1));
        for (int position : new int[] {24, 28, 32, 44}) {
            byte[] invalid = body.clone();
            ByteBuffer.wrap(invalid)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(position, Integer.MAX_VALUE);
            malformed(invalid);
        }
        binReferenceFixtures();
        keyFixtures();
        cacheFailureBoundary(parent, structuralFailures(body), original);
    }

    private static Set<Long> nearby(Drainage drainage, double scale) {
        Set<Long> cells = new LinkedHashSet<>();
        for (int ring = 0; ring <= 2; ring++)
            for (int z = -ring; z <= ring; z++)
                for (int x = -ring; x <= ring; x++) {
                    long[] pairs = drainage.continents(1000 * scale * x, 1000 * scale * z);
                    for (int i = 0; i < pairs.length; i += 2)
                        cells.add((pairs[i] << 32) ^ (pairs[i + 1] & 0xffffffffL));
                }
        return cells;
    }

    /** No sampler or build is active when its synchronous network request returns. */
    private static void evictDrainage(Drainage drainage) throws ReflectiveOperationException {
        Object memo = get(drainage, "memo");
        AtomicReferenceArray<?> slots = (AtomicReferenceArray<?>) get(memo, "slots");
        for (int i = 0; i < slots.length(); i++) slots.set(i, null);
    }

    /** Snapshot the original memo before any request, instrumentation or eviction can change it. */
    private static void constructorBoundary(Drainage drainage, RegionalStore store)
            throws ReflectiveOperationException {
        Object memo = get(drainage, "memo");
        AtomicReferenceArray<?> slots = (AtomicReferenceArray<?>) get(memo, "slots");
        Set<Key> keys = new LinkedHashSet<>(), nonempty = new LinkedHashSet<>();
        for (int i = 0; i < slots.length(); i++) {
            Object entry = slots.get(i);
            if (entry == null) continue;
            Key key = new Key((Integer) get(entry, "x"), (Integer) get(entry, "z"));
            keys.add(key);
            if (Array.getLength(get(get(entry, "value"), "channels")) > 0) nonempty.add(key);
        }
        List<Key> ordered =
                keys.stream()
                        .sorted(Comparator.comparingLong(Key::x).thenComparingLong(Key::z))
                        .toList();
        RegionalStore.Statistics stats = store.statistics();
        System.out.printf(
                "Constructor boundary: historical pre-bind builds=0 (source audit); "
                        + "observed surviving keys=%s; nonempty keys=%d; store=%s%n",
                ordered, nonempty.size(), stats);
        require(keys.isEmpty(), "Source-audited constructor zero-history premise changed");
        require(
                stats.hits() == 0
                        && stats.misses() == 0
                        && stats.writes() == 0
                        && stats.failedWrites() == 0,
                "Construction performed regional store operations before the first request");
    }

    private static void generated(Path parent, long seed, WorldScale world) throws Exception {
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "drainage-store-");
        require(root.toRealPath().startsWith(parent.toRealPath()), "Cache escaped its parent");
        System.out.printf("Retained cache root=%s seed=%d scale=%s%n", root, seed, world.layout);
        try (RegionalStore store = new RegionalStore(root, RegionalIdentity.capture(seed, world))) {
            GenerationMaps maps =
                    new GenerationMaps(
                            seed, TerrainHeightReference.DEFAULT, world, BuildPool.tools(), store);
            Drainage drainage = maps.terrain.hydro.drainage;
            constructorBoundary(drainage, store);
            Method network = Drainage.class.getDeclaredMethod("network", long.class, long.class);
            for (long key : nearby(drainage, world.layout)) {
                long x = key >> 32, z = (int) key;
                Object built = invoke(network, drainage, x, z);
                if (Array.getLength(get(built, "channels")) == 0) continue;
                RegionalStore.Statistics cold = store.statistics();
                require(
                        cold.misses() > 0 && cold.writes() > 0 && cold.failedWrites() == 0,
                        "Fresh store did not retain its generated networks");
                evictDrainage(drainage);
                Object loaded = invoke(network, drainage, x, z);
                RegionalStore.Statistics repeat = store.statistics();
                require(
                        loaded != built && Array.getLength(get(loaded, "channels")) > 0,
                        "Memo eviction did not return a distinct nonempty network");
                require(
                        repeat.hits() == cold.hits() + 1
                                && repeat.misses() == cold.misses()
                                && repeat.writes() == cold.writes()
                                && repeat.failedWrites() == cold.failedWrites(),
                        "Reload rebuilt or rewrote a network instead of reading its stored entry");
                equivalent(
                        built,
                        loaded,
                        "seed=" + seed + " scale=" + world.layout + " key=" + x + "," + z);
                System.out.printf("Store cold=%s reload=%s%n", cold, repeat);
                return;
            }
            throw new AssertionError("No nonempty drainage fixture for seed " + seed);
        }
    }

    private record Key(long x, long z) {
        String filename() {
            return Long.toUnsignedString(x, 16) + "-" + Long.toUnsignedString(z, 16) + ".bin";
        }
    }

    /** Accept only the store's unique lowercase unsigned-hex representation of a key. */
    private static Key canonicalKey(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".bin")) return null;
        String stem = name.substring(0, name.length() - 4);
        int split = stem.indexOf('-');
        if (split < 1 || split == stem.length() - 1) return null;
        try {
            Key key =
                    new Key(
                            Long.parseUnsignedLong(stem.substring(0, split), 16),
                            Long.parseUnsignedLong(stem.substring(split + 1), 16));
            return key.filename().equals(name) ? key : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static void keyFixtures() {
        require(
                new Key(-1, Long.MIN_VALUE)
                        .equals(canonicalKey(Path.of("ffffffffffffffff-8000000000000000.bin"))),
                "Unsigned key bits changed");
        require(
                new Key(0, 1).equals(canonicalKey(Path.of("0-1.bin"))),
                "Canonical key was rejected");
        for (String invalid :
                List.of(
                        "00-0.bin",
                        "0-00.bin",
                        "FF-0.bin",
                        "-1-0.bin",
                        "10000000000000000-0.bin",
                        "0-0.part",
                        "0-.bin"))
            require(canonicalKey(Path.of(invalid)) == null, "Noncanonical key was accepted");
    }

    private static void verify(Path root, long seed, WorldScale world, int count, long randomSeed)
            throws Exception {
        if (count <= 0)
            throw new IllegalArgumentException("Verification entry count must be positive");
        try (RegionalStore store = new RegionalStore(root, RegionalIdentity.capture(seed, world))) {
            Path namespace = store.path("drainage", 0, 0, CODEC).getParent();
            List<Key> keys = new ArrayList<>();
            try (var paths = Files.list(namespace)) {
                for (Path path :
                        paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                                .toList()) {
                    Key key = canonicalKey(path);
                    if (key != null) keys.add(key);
                }
            }
            keys.sort(Comparator.comparing(Key::filename));
            require(
                    !keys.isEmpty(),
                    "No canonical entries in current drainage namespace " + namespace);
            require(
                    count <= keys.size(),
                    "Requested more entries than the current namespace contains");
            Random random = new Random(randomSeed);
            for (int i = 0; i < count; i++)
                Collections.swap(keys, i, i + random.nextInt(keys.size() - i));
            GenerationMaps fresh = new GenerationMaps(seed, world);
            Drainage drainage = fresh.terrain.hydro.drainage;
            Method build = Drainage.class.getDeclaredMethod("build", long.class, long.class);
            for (Key key : keys.subList(0, count)) {
                require(
                        key.x() == (int) key.x() && key.z() == (int) key.z(),
                        "Stored drainage key is outside its signed-int cell domain");
                Object loaded = store.get("drainage", key.x(), key.z(), CODEC);
                require(
                        loaded != null,
                        "Selected entry is stale, corrupt or missing: " + key.filename());
                Object recomputed = invoke(build, drainage, key.x(), key.z());
                equivalent(recomputed, loaded, "Verified " + key.filename());
            }
            RegionalStore.Statistics stats = store.statistics();
            require(
                    stats.hits() == count
                            && stats.misses() == 0
                            && stats.writes() == 0
                            && stats.failedWrites() == 0,
                    "Verification did not remain a read-only hit-only pass");
            System.out.printf(
                    "Verified %d of %d entries seed=%d scale=%s random=%d namespace=%s%n",
                    count, keys.size(), seed, world.layout, randomSeed, namespace);
        }
    }

    private static String option(String value, String prefix) {
        if (!value.startsWith(prefix)) throw new IllegalArgumentException("Expected " + prefix);
        return value.substring(prefix.length());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("verify")) {
            if (args.length != 6)
                throw new IllegalArgumentException(
                        "Use verify <cache root> seed=N scale=N entries=N random=N");
            verify(
                    Path.of(args[1]).toAbsolutePath().normalize(),
                    Long.parseLong(option(args[2], "seed=")),
                    new WorldScale(Double.parseDouble(option(args[3], "scale="))),
                    Integer.parseInt(option(args[4], "entries=")),
                    Long.parseLong(option(args[5], "random=")));
            return;
        }
        if (args.length == 0)
            throw new IllegalArgumentException(
                    "Give a parent directory for temporary cache fixtures");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        fixtures(parent);
        if (args.length == 2 && args[1].equals("fixtures")) return;
        double scale = 1;
        int first = 1;
        if (args.length > 1 && args[1].startsWith("scale=")) {
            scale = Double.parseDouble(args[1].substring(6));
            first++;
        }
        WorldScale world = new WorldScale(scale);
        if (first == args.length) for (long seed : SEEDS) generated(parent, seed, world);
        else
            for (int i = first; i < args.length; i++)
                generated(parent, Long.parseLong(args[i]), world);
        System.out.println("Drainage complete-field raw-bit store reloads passed");
    }
}
