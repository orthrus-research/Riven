package cc.orthrus.riven.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Where Riven's in-build parallelism runs: a bounded ForkJoinPool the host supplies (Cubature's
 * build pool, through {@code ICubeGenerator.useBuildPool}), or the calling thread alone while none
 * is. In the game Riven starts no threads and never uses the JVM's common pool (docs/DECISIONS.md,
 * 2026-10-30). The work it splits is pure, so the pool changes timing, never results.
 *
 * <p>The calling thread always works its own loop and waits only for items a helper has started,
 * never for queued helpers. A build that holds a single flight therefore finishes even when every
 * pool thread is blocked waiting on that flight, so a bounded pool cannot deadlock on it.
 */
public final class BuildPool {
    private static volatile ForkJoinPool tools;
    private volatile ForkJoinPool pool;

    /** Splits work across this pool from now on, or keeps it on the calling thread for null. */
    public void use(ForkJoinPool pool) {
        this.pool = pool;
    }

    /**
     * A bounded pool as Cubature builds one: {@code threads} running, at most twice that while
     * tasks block, past which a blocked task waits. For tools; the game uses its host's pool.
     */
    public static ForkJoinPool bounded(int threads, String name) {
        AtomicInteger made = new AtomicInteger();
        return new ForkJoinPool(
                threads,
                owner -> {
                    ForkJoinWorkerThread t =
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(owner);
                    t.setName(name + " #" + made.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                null,
                false,
                threads,
                2 * threads,
                1,
                full -> true,
                30,
                TimeUnit.SECONDS);
    }

    /** Tools only: one shared pool of all processors but one, as the common pool was. */
    static ForkJoinPool tools() {
        ForkJoinPool t = tools;
        if (t == null)
            synchronized (BuildPool.class) {
                t = tools;
                if (t == null) {
                    int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                    tools = t = bounded(threads, "Riven tools build");
                }
            }
        return t;
    }

    /** Runs {@code body} for each index below {@code count}, across the pool and the caller. */
    void forEach(int count, IntConsumer body) {
        ForkJoinPool p = pool;
        if (p == null || count < 2) {
            for (int i = 0; i < count; i++) body.accept(i);
            return;
        }
        Loop loop = new Loop(count, body);
        for (int h = Math.min(count - 1, p.getParallelism()); h > 0; h--)
            try {
                p.execute(loop::work);
            } catch (RejectedExecutionException stopped) {
                break; // The host is stopping its pool: the caller finishes alone.
            }
        loop.work();
        loop.finish();
    }

    private static final class Loop {
        private final int count;
        private final IntConsumer body;
        private final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private volatile Throwable failure;

        Loop(int count, IntConsumer body) {
            this.count = count;
            this.body = body;
        }

        /** Claims and runs items until none is left; a late helper finds none and returns. */
        void work() {
            for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) {
                try {
                    if (failure == null) body.accept(i);
                } catch (RuntimeException | Error thrown) {
                    if (failure == null) failure = thrown;
                } finally {
                    if (done.incrementAndGet() == count) finished.complete(null);
                }
            }
        }

        /** Waits for items helpers have started, then rethrows the first failure. */
        void finish() {
            finished.join();
            Throwable f = failure;
            if (f instanceof RuntimeException r) throw r;
            if (f instanceof Error e) throw e;
        }
    }
}
