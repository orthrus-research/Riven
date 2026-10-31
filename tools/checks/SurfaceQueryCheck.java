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
 * on the land route for 8675309.
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
                                    if (queried.resolvedSurface(x, z, false)
                                            != built.surface(index))
                                        uncarvedDiffers.incrementAndGet();
                                    columns.incrementAndGet();
                                }
                                // Held: the chunk column in the memo answers the same.
                                queried.column(cx, cz);
                                for (int index = 0; index < 256; index++)
                                    compare(
                                            queried,
                                            built,
                                            index,
                                            cx * 16 + (index & 15),
                                            cz * 16 + (index >> 4),
                                            "Held",
                                            seed);
                            });
            total += columns.get();
            System.out.printf(
                    "seed %d: %d columns match, surface and water (cold, cached, held); uncarved runs"
                            + " differ at %d; %.1f s%n",
                    seed,
                    columns.get(),
                    uncarvedDiffers.get(),
                    (System.nanoTime() - started) / 1e9);
        }
        System.out.println("SurfaceQueryCheck: " + total + " columns, every query matches");
    }
}
