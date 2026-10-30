package cc.orthrus.riven.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single-flight construction of pure per-key values: concurrent misses on one key wait for the
 * first thread's result instead of building it again (worker threads on neighbouring rows all
 * miss the same site cell at once). The builder publishes its value where later lookups find it
 * before it lets go of the key, and each build first looks there again, so a miss that raced a
 * build ending finds the value instead of building it again. Values must be pure functions of the
 * key, so this changes timing only. Builds
 * of one key never need that key again, and stage order keeps builds acyclic; a thread that does
 * re-enter its own key builds directly instead of waiting on itself.
 */
final class SharedBuild<T> {
    private static final class Flight<T> {
        final Thread owner = Thread.currentThread();
        final CompletableFuture<T> result = new CompletableFuture<>();
    }

    private final ConcurrentHashMap<Long, Flight<T>> flights = new ConcurrentHashMap<>();

    T get(long key, Supplier<T> build, Consumer<? super T> publish) {
        Flight<T> mine = new Flight<>(), other = flights.putIfAbsent(key, mine);
        if (other != null)
            return other.owner == Thread.currentThread() ? build.get() : other.result.join();
        try {
            T value = build.get();
            publish.accept(value);
            mine.result.complete(value);
            return value;
        } catch (RuntimeException | Error failure) {
            mine.result.completeExceptionally(failure);
            throw failure;
        } finally {
            flights.remove(key, mine);
        }
    }
}
