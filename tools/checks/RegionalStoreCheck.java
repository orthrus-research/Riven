package cc.orthrus.riven.worldgen;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Filesystem and flat-format invariants, without constructing terrain or touching a save. */
public final class RegionalStoreCheck {
    private static final String KIND = "fixture";
    private static final long X = Long.MIN_VALUE, Z = Long.MAX_VALUE;
    private static final AtomicInteger DECODES = new AtomicInteger();

    private record Value(
            boolean flag,
            int integer,
            long scalar,
            double number,
            float fraction,
            byte[] bytes,
            int[] integers,
            long[] longs,
            double[] doubles,
            float[] floats,
            double[] absent,
            double[] empty) {}

    private static final Value VALUE =
            new Value(
                    true,
                    Integer.MIN_VALUE,
                    Long.MAX_VALUE,
                    Double.longBitsToDouble(0x7ff8000000000042L),
                    Float.intBitsToFloat(0x80000000),
                    new byte[] {0, -1, 127},
                    new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE},
                    new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE},
                    new double[] {-0.0, 0.0, Double.longBitsToDouble(0x7ff800000000002aL)},
                    new float[] {-0.0f, 0.0f, Float.intBitsToFloat(0x7fc0002a)},
                    null,
                    new double[0]);

    private static final RegionalStore.Codec<Value> CODEC = codec(1);

    private static RegionalStore.Codec<Value> codec(int version) {
        return new RegionalStore.Codec<>() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public void write(RegionalData.Writer out, Value value) {
                out.bool(value.flag);
                out.integer(value.integer);
                out.longValue(value.scalar);
                out.doubleValue(value.number);
                out.floatValue(value.fraction);
                out.bytes(value.bytes);
                out.integers(value.integers);
                out.longs(value.longs);
                out.doubles(value.doubles);
                out.floats(value.floats);
                out.doubles(value.absent);
                out.doubles(value.empty);
            }

            @Override
            public Value read(RegionalData.Reader in) throws RegionalData.Malformed {
                DECODES.incrementAndGet();
                return new Value(
                        in.bool(),
                        in.integer(),
                        in.longValue(),
                        in.doubleValue(),
                        in.floatValue(),
                        in.bytes(),
                        in.integers(),
                        in.longs(),
                        in.doubles(),
                        in.floats(),
                        in.doubles(),
                        in.doubles());
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void same(Value actual) {
        require(
                actual != null
                        && actual.flag == VALUE.flag
                        && actual.integer == VALUE.integer
                        && actual.scalar == VALUE.scalar,
                "Scalar mismatch");
        require(
                Double.doubleToRawLongBits(actual.number)
                                == Double.doubleToRawLongBits(VALUE.number)
                        && Float.floatToRawIntBits(actual.fraction)
                                == Float.floatToRawIntBits(VALUE.fraction),
                "Scalar floating bits changed");
        require(
                Arrays.equals(actual.bytes, VALUE.bytes)
                        && Arrays.equals(actual.integers, VALUE.integers)
                        && Arrays.equals(actual.longs, VALUE.longs),
                "Primitive arrays changed");
        require(
                actual.doubles.length == VALUE.doubles.length
                        && actual.floats.length == VALUE.floats.length,
                "Floating array lengths changed");
        for (int i = 0; i < actual.doubles.length; i++)
            require(
                    Double.doubleToRawLongBits(actual.doubles[i])
                            == Double.doubleToRawLongBits(VALUE.doubles[i]),
                    "Double array bits changed");
        for (int i = 0; i < actual.floats.length; i++)
            require(
                    Float.floatToRawIntBits(actual.floats[i])
                            == Float.floatToRawIntBits(VALUE.floats[i]),
                    "Float array bits changed");
        require(
                actual.absent == null && actual.empty != null && actual.empty.length == 0,
                "Null and empty arrays were conflated");
    }

    private static int identityStart(byte[] file) {
        ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int identityLengthPosition = 36 + in.getInt(32);
        return identityLengthPosition + Integer.BYTES;
    }

    private static int bodyLengthPosition(byte[] file) {
        int start = identityStart(file);
        return start + ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getInt(start - 4);
    }

    private static byte[] replaceBody(byte[] original, byte[] body) throws Exception {
        int length = bodyLengthPosition(original), start = length + Integer.BYTES + 32;
        byte[] changed = Arrays.copyOf(original, start + body.length);
        ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(length, body.length);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
        System.arraycopy(hash, 0, changed, length + 4, hash.length);
        System.arraycopy(body, 0, changed, start, body.length);
        return changed;
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void corruption(RegionalStore store, Path file, byte[] valid) throws Exception {
        byte[] changed = valid.clone();
        changed[changed.length - 1] ^= 1;
        int before = DECODES.get();
        write(file, changed);
        require(
                store.get(KIND, X, Z, CODEC) == null && DECODES.get() == before,
                "Checksum failure reached codec");
        for (int length : new int[] {0, 17, valid.length - 1}) {
            write(file, Arrays.copyOf(valid, length));
            require(store.get(KIND, X, Z, CODEC) == null, "Truncated entry accepted");
        }
        write(file, Arrays.copyOf(valid, valid.length + 1));
        require(store.get(KIND, X, Z, CODEC) == null, "Trailing file byte accepted");
        int bodyStart = bodyLengthPosition(valid) + Integer.BYTES + 32;
        byte[] body = Arrays.copyOfRange(valid, bodyStart, valid.length);
        write(file, replaceBody(valid, Arrays.copyOf(body, body.length + 1)));
        require(store.get(KIND, X, Z, CODEC) == null, "Trailing decoded byte accepted");
        for (int offset :
                new int[] {0, 8, 12, 16, 24, identityStart(valid), identityStart(valid) + 12}) {
            changed = valid.clone();
            changed[offset] ^= 1;
            write(file, changed);
            require(store.get(KIND, X, Z, CODEC) == null, "Changed header accepted");
        }
        // The manifest carries the actual sorted catalogue, not only an aggregate digest.
        int identity = identityStart(valid);
        ByteBuffer in = ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN);
        require(in.getInt(identity + 20) == Definitions.resources().size(), "Incomplete catalogue");
        int cursor = identity + 24;
        List<Rules> rules = Definitions.loadAll();
        for (Rules rule : rules) {
            int count = in.getInt(cursor);
            cursor += 4;
            require(
                    new String(valid, cursor, count, StandardCharsets.UTF_8).equals(rule.resource),
                    "Catalogue name/order mismatch");
            cursor += count;
            require(
                    java.util.HexFormat.of()
                            .formatHex(valid, cursor, cursor + 32)
                            .equals(rule.fingerprint),
                    "Catalogue fingerprint mismatch");
            cursor += 32;
        }
        changed = valid.clone();
        int firstNameBytes = in.getInt(identity + 24);
        changed[identity + 28 + firstNameBytes] ^= 1;
        write(file, changed);
        require(store.get(KIND, X, Z, CODEC) == null, "Changed definition fingerprint accepted");
        write(file, valid);
    }

    private static void oversized(Path root, RegionalIdentity identity) throws Exception {
        RegionalStore.Codec<int[]> integers =
                new RegionalStore.Codec<>() {
                    @Override
                    public int version() {
                        return 1;
                    }

                    @Override
                    public void write(RegionalData.Writer out, int[] value) {
                        out.integers(value);
                    }

                    @Override
                    public int[] read(RegionalData.Reader in) throws RegionalData.Malformed {
                        return in.integers();
                    }
                };
        try (RegionalStore store = new RegionalStore(root, identity)) {
            require(
                    store.put("counts", 0, 0, new int[] {1}, integers),
                    "Cannot write count fixture");
            Path file = store.path("counts", 0, 0, integers);
            byte[] valid = Files.readAllBytes(file);
            for (int count : new int[] {Integer.MAX_VALUE, -2}) {
                byte[] body =
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array();
                write(file, replaceBody(valid, body));
                require(
                        store.get("counts", 0, 0, integers) == null,
                        "Unbounded array count accepted");
            }
            Files.delete(file);
        }
    }

    private static void concurrent(RegionalStore store, Path file, byte[] valid) throws Exception {
        Files.delete(file);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        AtomicInteger successfulWrites = new AtomicInteger();
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first =
                    pool.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 5; i++)
                                    if (store.put(KIND, X, Z, VALUE, CODEC))
                                        successfulWrites.incrementAndGet();
                                return null;
                            });
            Future<?> second =
                    pool.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 5; i++)
                                    if (store.put(KIND, X, Z, VALUE, CODEC))
                                        successfulWrites.incrementAndGet();
                                return null;
                            });
            Future<?> reads =
                    pool.submit(
                            () -> {
                                start.await();
                                for (int i = 0; i < 25; i++) {
                                    Value value = store.get(KIND, X, Z, CODEC);
                                    if (value != null) same(value);
                                }
                                return null;
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            reads.get(10, TimeUnit.SECONDS);
            require(successfulWrites.get() > 0, "No concurrent writer published an entry");
            same(store.get(KIND, X, Z, CODEC));
            require(
                    Arrays.equals(valid, Files.readAllBytes(file)),
                    "Same-key writers produced different bytes");
            Value detached = store.get(KIND, X, Z, CODEC);
            Future<?> crossThread = pool.submit(() -> same(detached));
            crossThread.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            require(
                    pool.awaitTermination(5, TimeUnit.SECONDS),
                    "Store-check threads did not terminate");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException(
                    "Give a parent directory for temporary cache fixtures");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "regional-store-");
        try {
            RegionalIdentity identity = RegionalIdentity.capture(8675309, WorldScale.DEFAULT);
            Path host = root.resolve("host-cache");
            try (RegionalStore store = new RegionalStore(host, identity)) {
                require(!Files.exists(host), "Store construction created a directory");
                require(store.get(KIND, X, Z, CODEC) == null, "Absent entry was a hit");
                require(!Files.exists(host), "Cache miss created a directory");
                require(store.put(KIND, X, Z, VALUE, CODEC), "Initial cache write failed");
                Path file = store.path(KIND, X, Z, CODEC);
                byte[] valid = Files.readAllBytes(file);
                same(store.get(KIND, X, Z, CODEC));
                corruption(store, file, valid);
                try (RegionalStore otherSeed =
                                new RegionalStore(
                                        host,
                                        RegionalIdentity.capture(8675310, WorldScale.DEFAULT));
                        RegionalStore otherLayout =
                                new RegionalStore(
                                        host,
                                        RegionalIdentity.capture(8675309, new WorldScale(4)))) {
                    require(
                            !file.equals(otherSeed.path(KIND, X, Z, CODEC)),
                            "Seed namespace collided");
                    write(otherSeed.path(KIND, X, Z, CODEC), valid);
                    require(otherSeed.get(KIND, X, Z, CODEC) == null, "Different seed accepted");
                    write(otherLayout.path(KIND, X, Z, CODEC), valid);
                    require(
                            otherLayout.get(KIND, X, Z, CODEC) == null,
                            "Different layout accepted");
                }
                write(store.path("another", X, Z, CODEC), valid);
                require(store.get("another", X, Z, CODEC) == null, "Different kind accepted");
                write(store.path(KIND, X + 1, Z, CODEC), valid);
                require(store.get(KIND, X + 1, Z, CODEC) == null, "Different key accepted");
                write(store.path(KIND, X, Z, codec(2)), valid);
                require(store.get(KIND, X, Z, codec(2)) == null, "Different codec accepted");
                boolean rejected = false;
                try {
                    store.get("../escape", X, Z, CODEC);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                require(rejected, "Kind escaped the host root");
                concurrent(store, file, valid);
                Files.delete(file);
                require(store.put(KIND, X, Z, VALUE, CODEC), "Rewrite after read/deletion failed");
                RegionalStore.Codec<Value> bug =
                        new RegionalStore.Codec<>() {
                            @Override
                            public int version() {
                                return 1;
                            }

                            @Override
                            public void write(RegionalData.Writer out, Value value) {
                                CODEC.write(out, value);
                            }

                            @Override
                            public Value read(RegionalData.Reader in) {
                                throw new IllegalStateException("codec bug");
                            }
                        };
                rejected = false;
                try {
                    store.get(KIND, X, Z, bug);
                } catch (IllegalStateException expected) {
                    rejected = expected.getMessage().equals("codec bug");
                }
                require(rejected, "Codec programming error was hidden as a cache miss");
                Files.delete(file);
                RegionalStore.Statistics stats = store.statistics();
                require(
                        stats.hits() > 0 && stats.misses() > 0 && stats.writes() > 0,
                        "Missing store counters");
            }
            oversized(root.resolve("lengths"), identity);
            RegionalStore closed = new RegionalStore(root.resolve("closed"), identity);
            closed.close();
            closed.close();
            require(
                    closed.get(KIND, X, Z, CODEC) == null && !closed.put(KIND, X, Z, VALUE, CODEC),
                    "Closed store accepted work");
            Path blocked = root.resolve("not-a-directory");
            Files.createFile(blocked);
            try (RegionalStore store = new RegionalStore(blocked, identity)) {
                require(
                        store.get(KIND, X, Z, CODEC) == null
                                && !store.put(KIND, X, Z, VALUE, CODEC),
                        "Filesystem failure was not cache-only");
            }
            System.out.println(
                    "Regional store identity, corruption, primitive-bit, concurrency and mapping-lifetime checks passed");
        } finally {
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                    require(
                            path.toAbsolutePath().normalize().startsWith(root),
                            "Fixture cleanup escaped its root");
                    Files.delete(path);
                }
            }
        }
    }
}
