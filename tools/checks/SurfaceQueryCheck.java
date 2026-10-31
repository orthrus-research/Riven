package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * NativeColumns.surface and NativeColumns.water, one-column queries, equal the surface and the
 * water of the chunk column NativeColumns builds, at every column: cold (resolved alone), warm
 * (this thread's cache) and held (the chunk column in the memo). Water is asked around the surface,
 * where reeds ask it, and at two depths below. It also counts where the column's runs before
 * carving would give another surface, so whether one column's carving is needed. Usage:
 * SurfaceQueryCheck [seed ...]. Chunks along diagonals crossing land and coast, plus a dense block
 * on the land route for 8675309. It also proves carving never raises a column's top, the bound
 * NativeColumns.surfaceAbove relies on, and that surfaceAbove agrees with the exact surface.
 */
public final class SurfaceQueryCheck {
    private static void require(boolean value, String what, int x, int y, int z, long seed) {
        if (!value)
            throw new AssertionError(
                    what + " differs at " + x + "," + y + "," + z + " seed " + seed);
    }

    private static List<int[]> chunks(long seed) {
        List<int[]> chunks = new ArrayList<>();
        for (int i = 0; i < 240; i++) chunks.add(new int[] {i * 3 - 360, i * 2 - 240});
        for (int i = 0; i < 160; i++) chunks.add(new int[] {i * 2 - 160, 200 - i * 3});
        // The land route (around 4144, -25008), where full-detail previews meet forests.
        if (seed == 8675309L)
            for (int cz = -1580; cz < -1548; cz++)
                for (int cx = 244; cx < 276; cx++) chunks.add(new int[] {cx, cz});
        return chunks;
    }

    /** Heights to ask water at: around the surface, and two depths below it. */
    private static int[] heights(int surface) {
        if (surface == Integer.MIN_VALUE) return new int[0];
        return new int[] {
            surface - 60, surface - 20, surface - 3, surface - 2, surface - 1, surface, surface + 1
        };
    }

    /** Surface and water queries against the built chunk column, at one column. */
    private static void compare(
            NativeColumns queried,
            NativeColumns.Column built,
            int index,
            int x,
            int z,
            String how,
            long seed) {
        int surface = built.surface(index);
        require(queried.surface(x, z) == surface, how + " surface", x, 0, z, seed);
        for (int y : heights(surface))
            require(
                    queried.water(x, y, z) == (built.block(index, y) == TerrainBlock.WATER),
                    how + " water",
                    x,
                    y,
                    z,
                    seed);
    }

    /**
     * surfaceAbove against the exact surface at heights that walk a slot through each state: the
     * uncarved top (settled by the bound), one below it (the bound must be carved), and around the
     * exact surface (answered from the carved top).
     */
    private static void bounded(
            NativeColumns queried, int surface, int uncarved, int x, int z, String how, long seed) {
        if (surface == Integer.MIN_VALUE) return;
        for (int h : new int[] {uncarved, uncarved - 1, surface + 1, surface, surface - 1})
            require(
                    queried.surfaceAbove(x, z, h) == surface > h,
                    how + " surfaceAbove",
                    x,
                    h,
                    z,
                    seed);
    }

    public static void main(String[] args) {
        long[] seeds =
                args.length == 0
                        ? new long[] {8675309L, 101L, -991L, 7L, 42L, 1L}
                        : java.util.Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        long total = 0;
        for (long seed : seeds) {
            List<int[]> chunks = chunks(seed);
            NativeColumns reference = new GenerationMaps(seed).columns;
            NativeColumns queried = new GenerationMaps(seed).columns;
            NativeColumns bounds = new GenerationMaps(seed).columns;
            AtomicLong columns = new AtomicLong(), uncarvedDiffers = new AtomicLong();
            long started = System.nanoTime();
            IntStream.range(0, chunks.size())
                    .parallel()
                    .forEach(
                            c -> {
                                int cx = chunks.get(c)[0], cz = chunks.get(c)[1];
                                NativeColumns.Column built = reference.build(cx, cz);
                                for (int index = 0; index < 256; index++) {
                                    int x = cx * 16 + (index & 15), z = cz * 16 + (index >> 4);
                                    compare(queried, built, index, x, z, "Cold", seed);
                                    compare(queried, built, index, x, z, "Cached", seed);
                                    int surface = built.surface(index);
                                    int uncarved = queried.resolvedSurface(x, z, false);
                                    // Carving never raises a column's top: the bound surfaceAbove
                                    // uses.
                                    require(
                                            surface <= uncarved,
                                            "Carved above uncarved",
                                            x,
                                            0,
                                            z,
                                            seed);
                                    if (uncarved != surface) uncarvedDiffers.incrementAndGet();
                                    // A fresh cache, so the bound is met cold; then the exact
                                    // answers after it.
                                    bounded(bounds, surface, uncarved, x, z, "Cold", seed);
                                    compare(bounds, built, index, x, z, "After bound", seed);
                                    bounded(bounds, surface, uncarved, x, z, "Exact", seed);
                                    columns.incrementAndGet();
                                }
                                // Held: the chunk column in the memo answers the same.
                                queried.column(cx, cz);
                                for (int index = 0; index < 256; index++) {
                                    int x = cx * 16 + (index & 15), z = cz * 16 + (index >> 4);
                                    compare(queried, built, index, x, z, "Held", seed);
                                    bounded(
                                            queried,
                                            built.surface(index),
                                            built.surface(index),
                                            x,
                                            z,
                                            "Held",
                                            seed);
                                }
                            });
            total += columns.get();
            System.out.printf(
                    "seed %d: %d columns match, surface, water and surfaceAbove (cold, cached, held);"
                            + " carved never above uncarved, lower at %d; %.1f s%n",
                    seed,
                    columns.get(),
                    uncarvedDiffers.get(),
                    (System.nanoTime() - started) / 1e9);
        }
        System.out.println("SurfaceQueryCheck: " + total + " columns, every query matches");
    }
}
