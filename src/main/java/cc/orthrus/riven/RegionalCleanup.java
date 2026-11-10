package cc.orthrus.riven;

import cc.orthrus.riven.worldgen.RegionalStore;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Executes detached cleanup decisions after the ownership registry has released its lock. */
final class RegionalCleanup {
    private RegionalCleanup() {}

    /** Keeps the construction failure primary while attempting every selected cleanup action. */
    static void failed(
            Throwable primary,
            RegionalStore unowned,
            RegionalOwnership.FailureCleanup cleanup,
            Consumer<RegionalStore> close,
            Runnable abandon) {
        Objects.requireNonNull(primary);
        Objects.requireNonNull(cleanup);
        Objects.requireNonNull(close);
        Objects.requireNonNull(abandon);
        close(unowned, close, primary);
        if (cleanup.store() != unowned) close(cleanup.store(), close, primary);
        if (cleanup.abandonPool()) {
            try {
                abandon.run();
            } catch (RuntimeException | Error failure) {
                suppress(primary, failure);
            }
        }
    }

    /** Attempts the whole detached batch, then rethrows the first cleanup failure unchanged. */
    static void closeAll(List<RegionalStore> stores, Consumer<RegionalStore> close) {
        Objects.requireNonNull(stores);
        Objects.requireNonNull(close);
        Throwable first = null;
        for (RegionalStore store : stores) {
            if (store == null) continue;
            try {
                close.accept(store);
            } catch (RuntimeException | Error failure) {
                if (first == null) first = failure;
                else suppress(first, failure);
            }
        }
        if (first instanceof RuntimeException failure) throw failure;
        if (first instanceof Error failure) throw failure;
    }

    private static void close(
            RegionalStore store, Consumer<RegionalStore> close, Throwable primary) {
        if (store == null) return;
        try {
            close.accept(store);
        } catch (RuntimeException | Error failure) {
            suppress(primary, failure);
        }
    }

    private static void suppress(Throwable primary, Throwable failure) {
        if (failure == primary) return;
        for (Throwable existing : primary.getSuppressed()) if (existing == failure) return;
        primary.addSuppressed(failure);
    }
}
