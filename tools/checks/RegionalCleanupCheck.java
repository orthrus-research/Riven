package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RegionalIdentity;
import cc.orthrus.riven.worldgen.RegionalStore;
import cc.orthrus.riven.worldgen.WorldScale;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Offline cleanup ordering and exception identity checks; no terrain or host callbacks. */
public final class RegionalCleanupCheck {
    private static final Object ABANDON = new Object();

    private RegionalCleanupCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void order(List<Object> actual, Object... expected) {
        require(actual.size() == expected.length, "Wrong cleanup call count");
        for (int i = 0; i < expected.length; i++)
            require(actual.get(i) == expected[i], "Wrong cleanup order or resource identity");
    }

    private static void suppressed(Throwable primary, Throwable... expected) {
        Throwable[] actual = primary.getSuppressed();
        require(actual.length == expected.length, "Wrong suppression count");
        for (int i = 0; i < expected.length; i++)
            require(actual[i] == expected[i], "Wrong suppressed exception identity");
    }

    private static void allFailureAttempts(RegionalStore first, RegionalStore second) {
        RuntimeException primary = new IllegalStateException("primary");
        RuntimeException close = new IllegalArgumentException("close");
        Error abandon = new AssertionError("abandon");
        List<Object> calls = new ArrayList<>();
        RegionalCleanup.failed(
                primary,
                first,
                new RegionalOwnership.FailureCleanup(second, true),
                store -> {
                    calls.add(store);
                    throw close;
                },
                () -> {
                    calls.add(ABANDON);
                    throw abandon;
                });
        order(calls, first, second, ABANDON);
        suppressed(primary, close, abandon);
    }

    private static void suppressionIdentity(RegionalStore first, RegionalStore second) {
        RuntimeException primary = new IllegalStateException("same primary");
        Error prior = new AssertionError("already suppressed");
        primary.addSuppressed(prior);
        List<Object> calls = new ArrayList<>();
        RegionalCleanup.failed(
                primary,
                first,
                new RegionalOwnership.FailureCleanup(second, true),
                store -> {
                    calls.add(store);
                    if (store == first) throw primary;
                    throw prior;
                },
                () -> {
                    calls.add(ABANDON);
                    throw primary;
                });
        order(calls, first, second, ABANDON);
        suppressed(primary, prior);
    }

    private static void oneResource(RegionalStore store) {
        Error primary = new AssertionError("primary error");
        List<Object> calls = new ArrayList<>();
        RegionalCleanup.failed(
                primary,
                store,
                new RegionalOwnership.FailureCleanup(store, false),
                resource -> {
                    calls.add(resource);
                    resource.close();
                },
                () -> calls.add(ABANDON));
        order(calls, store);
        suppressed(primary);
    }

    private static void memoryOnly() {
        RuntimeException primary = new IllegalStateException("memory only");
        List<Object> calls = new ArrayList<>();
        RegionalCleanup.failed(
                primary,
                null,
                new RegionalOwnership.FailureCleanup(null, true),
                calls::add,
                () -> calls.add(ABANDON));
        order(calls, ABANDON);
        RegionalCleanup.failed(
                primary,
                null,
                new RegionalOwnership.FailureCleanup(null, false),
                calls::add,
                () -> calls.add(ABANDON));
        order(calls, ABANDON);
        suppressed(primary);
    }

    private static void batchFailures(List<RegionalStore> stores, boolean errorFirst) {
        Error error = new AssertionError("batch error");
        RuntimeException runtime = new IllegalStateException("batch exception");
        Throwable first = errorFirst ? error : runtime, later = errorFirst ? runtime : error;
        List<Object> calls = new ArrayList<>();
        Throwable caught = null;
        try {
            RegionalCleanup.closeAll(
                    stores,
                    store -> {
                        calls.add(store);
                        if (store == stores.get(0) || store == stores.get(3)) {
                            if (errorFirst) throw error;
                            throw runtime;
                        }
                        if (errorFirst) throw runtime;
                        throw error;
                    });
        } catch (RuntimeException | Error failure) {
            caught = failure;
        }
        require(caught == first, "Batch replaced the first exception");
        order(calls, stores.toArray());
        suppressed(first, later);
    }

    private static void successfulBatch(RegionalStore first, RegionalStore second) {
        List<Object> calls = new ArrayList<>();
        RegionalCleanup.closeAll(List.of(), calls::add);
        order(calls);
        RegionalCleanup.closeAll(
                List.of(first, second),
                store -> {
                    calls.add(store);
                    store.close();
                });
        order(calls, first, second);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("Give a parent directory for fixtures");
        Path parent = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "regional-cleanup-");
        require(root.toRealPath().startsWith(parent.toRealPath()), "Fixture escaped parent");
        List<RegionalStore> stores = new ArrayList<>();
        try {
            RegionalIdentity identity = RegionalIdentity.capture(8675309, WorldScale.DEFAULT);
            for (int i = 0; i < 6; i++) stores.add(new RegionalStore(root, identity));
            allFailureAttempts(stores.get(0), stores.get(1));
            suppressionIdentity(stores.get(0), stores.get(1));
            oneResource(stores.get(0));
            memoryOnly();
            batchFailures(stores.subList(0, 4), true);
            batchFailures(stores.subList(0, 4), false);
            successfulBatch(stores.get(4), stores.get(5));
            System.out.println("Regional cleanup ordering and exception identity checks passed");
        } finally {
            for (RegionalStore store : stores) store.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
