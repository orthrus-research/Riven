package cc.orthrus.riven.worldgen;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Codec and selection-domain fixtures without constructing or sampling terrain. */
public final class WorldSelectionCheck {
    private static int assertions;

    private WorldSelectionCheck() {}

    private static void require(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static byte[] body(WorldSelection value) {
        RegionalData.Writer out = new RegionalData.Writer();
        WorldSelection.CODEC.write(out, value);
        return out.finish();
    }

    private static byte[] body(int landAttempt, double share, int attempt, int draws, int fewest) {
        return ByteBuffer.allocate(24)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(landAttempt)
                .putDouble(share)
                .putInt(attempt)
                .putInt(draws)
                .putInt(fewest)
                .array();
    }

    private static WorldSelection decode(byte[] bytes) throws RegionalData.Malformed {
        RegionalData.Reader in = new RegionalData.Reader(MemorySegment.ofArray(bytes));
        WorldSelection value = WorldSelection.CODEC.read(in);
        in.requireEnd();
        return value;
    }

    private static void same(WorldSelection expected, WorldSelection actual) {
        require(expected.equals(actual), "Selection fields changed");
        require(
                Double.doubleToRawLongBits(expected.landShare())
                        == Double.doubleToRawLongBits(actual.landShare()),
                "Land share bits changed");
        require(Arrays.equals(body(expected), body(actual)), "Canonical body changed");
    }

    private static void malformed(byte[] bytes) throws Exception {
        boolean rejected = false;
        try {
            decode(bytes);
        } catch (RegionalData.Malformed expected) {
            rejected = true;
        }
        require(rejected, "Malformed selection was accepted");
    }

    private static int maximumFewest() {
        int grid =
                (int)
                        Rules.number(
                                Rules.load("riven-climate.json", 1)
                                        .json
                                        .getAsJsonObject("coverage"),
                                "gridCells",
                                16,
                                1024);
        return grid * grid / (TemperatureBand.values().length * MoistureBand.values().length);
    }

    /** Writes checksum-valid fixture bodies, so a miss must come from the real codec. */
    private static final RegionalStore.Codec<byte[]> RAW =
            new RegionalStore.Codec<>() {
                @Override
                public int version() {
                    return WorldSelection.CODEC.version();
                }

                @Override
                public void write(RegionalData.Writer out, byte[] value) {
                    out.raw(value);
                }

                @Override
                public byte[] read(RegionalData.Reader in) throws RegionalData.Malformed {
                    return in.raw((int) in.remaining());
                }
            };

    private static List<byte[]> invalid() {
        int draws = ClimateCoverage.ATTEMPTS;
        List<byte[]> bodies = new ArrayList<>();
        bodies.add(body(-1, .5, 0, draws, 0));
        bodies.add(body(LandShare.ATTEMPTS, .5, 0, draws, 0));
        for (double share :
                new double[] {
                    -0.0,
                    -Double.MIN_VALUE,
                    Math.nextUp(1.0),
                    Double.NaN,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                }) bodies.add(body(0, share, 0, draws, 0));
        bodies.add(body(0, .5, -1, draws, 0));
        bodies.add(body(0, .5, draws, draws, 0));
        bodies.add(body(0, .5, 0, 0, 0));
        bodies.add(body(0, .5, 0, draws + 1, 0));
        bodies.add(body(0, .5, 0, draws, -1));
        bodies.add(body(0, .5, 0, draws, Integer.MAX_VALUE));
        int beyond = maximumFewest() + 1;
        bodies.add(body(0, .5, 0, beyond >= ClimateCoverage.MIN_PATCHES ? 1 : draws, beyond));
        if (draws > 1) {
            bodies.add(body(0, .5, 0, draws - 1, 0));
            bodies.add(body(0, .5, 0, 2, ClimateCoverage.MIN_PATCHES));
        }
        return bodies;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: WorldSelectionCheck <output parent>");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "world-selection-");
        require(root.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped its parent");
        List<WorldSelection> values =
                new ArrayList<>(
                        List.of(
                                new WorldSelection(0, 0, 0, ClimateCoverage.ATTEMPTS, 0),
                                new WorldSelection(
                                        LandShare.ATTEMPTS - 1,
                                        1,
                                        ClimateCoverage.ATTEMPTS - 1,
                                        ClimateCoverage.ATTEMPTS,
                                        0),
                                new WorldSelection(0, .482421875, 0, ClimateCoverage.ATTEMPTS, 0)));
        if (ClimateCoverage.validSelection(0, 1, ClimateCoverage.MIN_PATCHES))
            values.add(new WorldSelection(0, .5, 0, 1, ClimateCoverage.MIN_PATCHES));
        int limit = maximumFewest();
        values.add(
                new WorldSelection(
                        0,
                        .5,
                        0,
                        limit >= ClimateCoverage.MIN_PATCHES ? 1 : ClimateCoverage.ATTEMPTS,
                        limit));
        if (ClimateCoverage.validSelection(
                ClimateCoverage.ATTEMPTS - 1,
                ClimateCoverage.ATTEMPTS,
                ClimateCoverage.MIN_PATCHES))
            values.add(
                    new WorldSelection(
                            0,
                            .5,
                            ClimateCoverage.ATTEMPTS - 1,
                            ClimateCoverage.ATTEMPTS,
                            ClimateCoverage.MIN_PATCHES));
        List<byte[]> invalid = invalid();
        byte[] valid = body(values.get(2));
        require(valid.length == 24, "World selection is not a 24-byte body");
        require(
                Arrays.equals(valid, body(0, .482421875, 0, ClimateCoverage.ATTEMPTS, 0)),
                "Primitive order or byte order changed");
        for (WorldSelection value : values) same(value, decode(body(value)));
        for (byte[] bytes : invalid) malformed(bytes);
        for (int length = 0; length < valid.length; length++)
            malformed(Arrays.copyOf(valid, length));
        malformed(Arrays.copyOf(valid, valid.length + 1));

        RegionalIdentity identity = RegionalIdentity.capture(8675309, WorldScale.DEFAULT);
        try (RegionalStore store = new RegionalStore(root, identity)) {
            for (WorldSelection value : values) {
                require(
                        store.put(WorldSelection.KIND, 0, 0, value, WorldSelection.CODEC),
                        "Selection fixture write failed");
                same(value, store.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC));
            }
            for (byte[] bytes : invalid) {
                require(
                        store.put(WorldSelection.KIND, 0, 0, bytes, RAW),
                        "Malformed fixture write failed");
                require(
                        store.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC) == null,
                        "Invalid selection did not become a cache miss");
            }
            require(
                    store.put(WorldSelection.KIND, 0, 0, values.get(2), WorldSelection.CODEC),
                    "Restoring valid fixture failed");
        }
        try (RegionalStore reopened = new RegionalStore(root, identity)) {
            same(values.get(2), reopened.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC));
            RegionalStore.Statistics stats = reopened.statistics();
            require(
                    stats.hits() == 1 && stats.misses() == 0 && stats.writes() == 0,
                    "Reopening the selection was not a read-only hit");
        }
        RegionalStore closed = new RegionalStore(root, identity);
        closed.close();
        require(
                closed.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC) == null
                        && !closed.put(
                                WorldSelection.KIND, 0, 0, values.get(2), WorldSelection.CODEC),
                "A closed store accepted selection work");
        Path blocked = root.resolve("blocked");
        Files.createFile(blocked);
        try (RegionalStore unavailable = new RegionalStore(blocked, identity)) {
            require(
                    unavailable.get(WorldSelection.KIND, 0, 0, WorldSelection.CODEC) == null
                            && !unavailable.put(
                                    WorldSelection.KIND, 0, 0, values.get(2), WorldSelection.CODEC),
                    "An unavailable store did not report cache-only failure");
        }
        System.out.printf(
                "World selection: %d assertions, %d valid and %d invalid fixtures; retained at %s%n",
                assertions, values.size(), invalid.size(), root);
    }
}
