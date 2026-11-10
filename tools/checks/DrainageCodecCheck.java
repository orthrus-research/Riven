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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exact closed drainage graph round trips. The fixture-only mode constructs no terrain; the
 * default also builds one nonempty network for each of six seeds. No game or store wiring.
 * Usage: {@code DrainageCodecCheck <fixture parent> [fixtures | scale=N [seed ...]]}.
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
        byte[] encoded = encode(original);
        Object loaded = decode(encoded);
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
        return loaded;
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
        cacheFailureBoundary(parent, structuralFailures(body), original);
    }

    private static void generated(long seed, double scale) throws Exception {
        GenerationMaps maps = new GenerationMaps(seed, new WorldScale(scale));
        Drainage drainage = maps.terrain.hydro.drainage;
        Set<Long> cells = new LinkedHashSet<>();
        for (int ring = 0; ring <= 2; ring++)
            for (int z = -ring; z <= ring; z++)
                for (int x = -ring; x <= ring; x++) {
                    long[] pairs = drainage.continents(1000 * scale * x, 1000 * scale * z);
                    for (int i = 0; i < pairs.length; i += 2)
                        cells.add((pairs[i] << 32) ^ (pairs[i + 1] & 0xffffffffL));
                }
        Method network = Drainage.class.getDeclaredMethod("network", long.class, long.class);
        for (long key : cells) {
            Object value = invoke(network, drainage, key >> 32, (long) (int) key);
            if (Array.getLength(get(value, "channels")) == 0) continue;
            roundTrip(
                    value,
                    "seed=" + seed + " scale=" + scale + " key=" + (key >> 32) + "," + (int) key);
            return;
        }
        throw new AssertionError("No nonempty drainage fixture for seed " + seed);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0)
            throw new IllegalArgumentException(
                    "Give a parent directory for temporary cache fixtures");
        fixtures(Path.of(args[0]).toAbsolutePath().normalize());
        if (args.length == 2 && args[1].equals("fixtures")) return;
        double scale = 1;
        int first = 1;
        if (args.length > 1 && args[1].startsWith("scale=")) {
            scale = Double.parseDouble(args[1].substring(6));
            first++;
        }
        if (first == args.length) for (long seed : SEEDS) generated(seed, scale);
        else for (int i = first; i < args.length; i++) generated(Long.parseLong(args[i]), scale);
        System.out.println("Drainage complete-field raw-bit round trips passed");
    }
}
