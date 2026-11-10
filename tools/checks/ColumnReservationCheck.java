package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.worldgen.engine.Assembler;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Retained native columns survive memo eviction, overlapping owners and an older build in flight.
 * Every compared value comes from the actual column engine. Usage: ColumnReservationCheck [seed ...].
 * The check instruments private cache slots and the assembler's shaper only in this tools process;
 * Java 26 may report the final-field mutation used to hold the older build at a known point.
 */
public final class ColumnReservationCheck {
    private record Position(int x, int z) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Map<?, ?> retained(NativeColumns columns) throws ReflectiveOperationException {
        return (Map<?, ?>) field(columns, "retained");
    }

    private static int references(NativeColumns columns, Position position)
            throws ReflectiveOperationException {
        long key = ((long) position.x() << 32) ^ (position.z() & 0xffffffffL);
        Object entry = retained(columns).get(key);
        return entry == null ? 0 : ((AtomicInteger) field(entry, "references")).get();
    }

    private static void evict(NativeColumns columns) throws ReflectiveOperationException {
        AtomicReferenceArray<?> memo = (AtomicReferenceArray<?>) field(columns, "memo");
        for (int i = 0; i < memo.length(); i++) memo.set(i, null);
    }

    private static void integer(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    /** All native block, role, ore, instability, habitat, depth and surface answers participate. */
    private static String digest(NativeColumns.Column column, Position position) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        integer(digest, column.minimumGround());
        TerrainBlock[] cube = new TerrainBlock[16];
        for (int index = 0; index < 256; index++) {
            byte[] habitat = column.key(index).getBytes(StandardCharsets.UTF_8);
            integer(digest, habitat.length);
            digest.update(habitat);
            integer(digest, column.surface(index));
            int x = position.x() * 16 + (index & 15);
            int z = position.z() * 16 + (index >> 4);
            for (int bottom = heights.minimumY - 16;
                    bottom < heights.maximumYExclusive + 16;
                    bottom += 16) {
                column.cube(index, bottom, cube);
                for (int offset = 0; offset < 16; offset++) {
                    int y = bottom + offset;
                    TerrainBlock block = column.block(index, y);
                    require(cube[offset] == block, "Cube and block queries differ at " + position);
                    byte[] id = block.id.getBytes(StandardCharsets.UTF_8);
                    integer(digest, id.length);
                    digest.update(id);
                    integer(digest, column.role(index, y).ordinal());
                    integer(digest, column.ore(index, x, y, z));
                    digest.update((byte) (column.unstable(index, y) ? 1 : 0));
                }
            }
        }
        for (int cell = 0; cell < 16; cell++)
            for (int y = heights.minimumY - 1; y <= heights.maximumYExclusive; y++)
                integer(digest, column.depth(cell, y));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void same(
            NativeColumns.Column expected,
            NativeColumns.Column actual,
            Position position,
            String context)
            throws Exception {
        require(
                digest(expected, position).equals(digest(actual, position)),
                context + " at " + position);
    }

    private static long undergroundAir(NativeColumns.Column column) {
        long count = 0;
        for (int index = 0; index < 256; index++)
            for (int y = TerrainHeightReference.DEFAULT.minimumY; y < column.minimumGround(); y++)
                if (column.block(index, y) == TerrainBlock.AIR) count++;
        return count;
    }

    /** Pick one continental land column and one marine column without warming regional plans. */
    private static Position[] positions(GenerationMaps maps) {
        ContinentalMap.Sample sample = new ContinentalMap.Sample();
        Position land = null, marine = null;
        for (int z = -4096; z <= 4096 && (land == null || marine == null); z += 512)
            for (int x = -4096; x <= 4096 && (land == null || marine == null); x += 512) {
                maps.continents.sample(x + 8, z + 8, sample);
                if (sample.marine && marine == null) marine = new Position(x >> 4, z >> 4);
                if (!sample.marine && land == null) land = new Position(x >> 4, z >> 4);
            }
        require(land != null && marine != null, "Land and marine coverage not found");
        return new Position[] {land, marine, new Position(506, 284)};
    }

    private static void closed(NativeColumns.Reservation reservation) {
        try {
            reservation.column();
            throw new AssertionError("Closed reservation still exposes a column");
        } catch (IllegalStateException expected) {
            // The immutable value may outlive a lease; the lease itself is no longer usable.
        }
    }

    private static void lifecycle(NativeColumns columns, Position first, Position replacement)
            throws Exception {
        NativeColumns.Reservation a = columns.retain(first.x(), first.z());
        NativeColumns.Reservation b = columns.retain(first.x(), first.z());
        NativeColumns.Column held = a.column();
        try {
            require(held == b.column(), "Overlapping reservations do not share their column");
            require(references(columns, first) == 2, "Expected two owners");
            evict(columns);
            require(
                    columns.column(first.x(), first.z()) == held,
                    "Memo eviction lost retained data");
            a.close();
            a.close();
            closed(a);
            require(
                    references(columns, first) == 1,
                    "Repeated close changed another owner's lease");
            require(
                    columns.column(first.x(), first.z()) == held,
                    "One owner released the other's data");
            try (NativeColumns.Reservation next =
                    columns.retain(replacement.x(), replacement.z())) {
                require(
                        retained(columns).size() == 2,
                        "Replacement did not retain both active keys");
                b.close();
                require(references(columns, first) == 0, "Abandoned destination remains retained");
                evict(columns);
                require(
                        columns.column(replacement.x(), replacement.z()) == next.column(),
                        "Replacement lost its retained value");
            }
            require(retained(columns).isEmpty(), "Replacement cleanup leaked a retained key");
        } finally {
            a.close();
            b.close();
        }
        List<NativeColumns.Reservation> shutdown = new ArrayList<>();
        try {
            shutdown.add(columns.retain(first.x(), first.z()));
            shutdown.add(columns.retain(first.x(), first.z()));
            shutdown.add(columns.retain(replacement.x(), replacement.z()));
        } finally {
            for (NativeColumns.Reservation reservation : shutdown) reservation.close();
        }
        require(retained(columns).isEmpty(), "Host shutdown cleanup leaked retained data");
    }

    private static void acquireAndClose(NativeColumns columns, Position position) throws Exception {
        try (NativeColumns.Reservation anchor = columns.retain(position.x(), position.z());
                ExecutorService workers = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++)
                tasks.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    for (int attempt = 0; attempt < 128; attempt++)
                                        try (NativeColumns.Reservation other =
                                                columns.retain(position.x(), position.z())) {
                                            require(
                                                    other.column() == anchor.column(),
                                                    "Concurrent owners lost the anchor");
                                            require(
                                                    columns.column(position.x(), position.z())
                                                            == anchor.column(),
                                                    "Concurrent memo read rebuilt a retained column");
                                        }
                                    return null;
                                }));
            evict(columns);
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
            require(
                    references(columns, position) == 1,
                    "Concurrent close lost or leaked references");
            NativeColumns.Reservation shared = columns.retain(position.x(), position.z());
            tasks.clear();
            for (int worker = 0; worker < 4; worker++) tasks.add(workers.submit(shared::close));
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
            closed(shared);
            require(references(columns, position) == 1, "Racing close released the anchor");
        }
        require(retained(columns).isEmpty(), "Concurrent reservation cleanup leaked a key");
    }

    /** Align the last owner's close with a new acquisition of its key, without an anchor owner. */
    private static void lastCloseAndAcquire(NativeColumns columns, Position position)
            throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 32; round++) {
                NativeColumns.Reservation last = columns.retain(position.x(), position.z());
                try {
                    CyclicBarrier start = new CyclicBarrier(2);
                    Future<?> closing =
                            workers.submit(
                                    () -> {
                                        start.await(30, TimeUnit.SECONDS);
                                        last.close();
                                        return null;
                                    });
                    Future<NativeColumns.Reservation> acquiring =
                            workers.submit(
                                    () -> {
                                        start.await(30, TimeUnit.SECONDS);
                                        return columns.retain(position.x(), position.z());
                                    });
                    try (NativeColumns.Reservation next = acquiring.get(30, TimeUnit.SECONDS)) {
                        closing.get(30, TimeUnit.SECONDS);
                        require(
                                references(columns, position) == 1,
                                "Last-close race lost or leaked the new owner's reference");
                        evict(columns);
                        require(
                                columns.column(position.x(), position.z()) == next.column(),
                                "Last close removed a new reservation of the same key");
                    }
                } finally {
                    last.close();
                }
                require(retained(columns).isEmpty(), "Last-close race leaked a retired entry");
            }
        }
    }

    /** Actual assembly is paused only for the designated older caller; every other caller delegates. */
    private static final class OlderBuild implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        private final Field shaper;
        private final Assembler<TerrainMap.Sample> assembler;
        private final Assembler.Shaper<TerrainMap.Sample> original;

        @SuppressWarnings("unchecked")
        OlderBuild(Assembler<TerrainMap.Sample> assembler, AtomicReference<Thread> older)
                throws ReflectiveOperationException {
            this.assembler = assembler;
            shaper = Assembler.class.getDeclaredField("shaper");
            shaper.setAccessible(true);
            original = (Assembler.Shaper<TerrainMap.Sample>) shaper.get(assembler);
            AtomicBoolean first = new AtomicBoolean(true);
            Assembler.Shaper<TerrainMap.Sample> controlled =
                    (sample, shape) -> {
                        if (Thread.currentThread() == older.get()
                                && first.compareAndSet(true, false)) {
                            entered.countDown();
                            try {
                                if (!release.await(30, TimeUnit.SECONDS))
                                    throw new AssertionError("Older build was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("Older build interrupted", interrupted);
                            }
                        }
                        original.shape(sample, shape);
                    };
            shaper.set(assembler, controlled);
        }

        @Override
        public void close() throws IllegalAccessException {
            release.countDown();
            shaper.set(assembler, original);
        }
    }

    private static void olderBuild(NativeColumns columns, Position position) throws Exception {
        NativeColumns.Column oracle = columns.build(position.x(), position.z());
        evict(columns);
        AtomicReference<Thread> older = new AtomicReference<>();
        try (OlderBuild gate = new OlderBuild(columns.assembler(), older);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<NativeColumns.Column> ordinary =
                    workers.submit(
                            () -> {
                                older.set(Thread.currentThread());
                                return columns.column(position.x(), position.z());
                            });
            try {
                require(
                        gate.entered.await(30, TimeUnit.SECONDS),
                        "Ordinary build never reached assembly");
                try (NativeColumns.Reservation arrival =
                        columns.retain(position.x(), position.z())) {
                    evict(columns);
                    Future<NativeColumns.Column> install =
                            workers.submit(() -> columns.column(position.x(), position.z()));
                    require(
                            install.get(5, TimeUnit.SECONDS) == arrival.column(),
                            "Server-shaped read ignored the retained result");
                    require(!ordinary.isDone(), "Older build was not still in flight");
                    same(
                            oracle,
                            arrival.column(),
                            position,
                            "Reserved build differs from fresh engine");
                    gate.release.countDown();
                    same(
                            oracle,
                            ordinary.get(30, TimeUnit.SECONDS),
                            position,
                            "Older build changed content after reservation publication");
                    evict(columns);
                    require(
                            columns.column(position.x(), position.z()) == arrival.column(),
                            "An older ordinary publication displaced the reservation");
                }
            } finally {
                gate.release.countDown();
            }
        }
        require(retained(columns).isEmpty(), "In-flight build proof leaked retention");
    }

    public static void main(String[] args) throws Exception {
        long[] seeds =
                args.length == 0
                        ? new long[] {8675309L, 101L}
                        : Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            Position[] positions = positions(maps);
            long caveBlocks = 0;
            for (Position position : positions) {
                NativeColumns.Column oracle = maps.columns.build(position.x(), position.z());
                caveBlocks += undergroundAir(oracle);
                try (NativeColumns.Reservation reservation =
                        maps.columns.retain(position.x(), position.z())) {
                    evict(maps.columns);
                    NativeColumns.Column held = maps.columns.column(position.x(), position.z());
                    require(held == reservation.column(), "Retained lookup rebuilt its key");
                    same(oracle, held, position, "Retained native content differs");
                    System.out.printf(
                            "seed %d column %d,%d: native digest %s%n",
                            seed, position.x(), position.z(), digest(held, position));
                }
            }
            require(
                    caveBlocks > 0,
                    "Selected native columns contain no underground air to compare");
            lifecycle(maps.columns, positions[0], positions[1]);
            acquireAndClose(maps.columns, positions[0]);
            lastCloseAndAcquire(maps.columns, positions[0]);
            olderBuild(maps.columns, positions[0]);
            require(retained(maps.columns).isEmpty(), "Seed completed with retained entries");
            System.out.printf(
                    "seed %d: column parity (%d underground air blocks), eviction, lifecycle, concurrent close and older build passed%n",
                    seed, caveBlocks);
        }
    }
}
