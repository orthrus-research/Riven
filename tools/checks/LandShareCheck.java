package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The land band over a seed set at 8 km and 32 km (scale 4): the kept share's mean, minimum and
 * maximum, the first draw's (what the outline's radius and occupancy tune), and how many attempts
 * each seed needed. Fails when a kept share is outside the band or a repeated choice differs.
 *
 * <p>Arguments: {@code [seeds=64] [first=1] [scale ...]}; seeds are first, first + 1, ... and
 * the scales default to 1 and 4.
 */
public final class LandShareCheck {
    private LandShareCheck() {}

    public static void main(String[] args) throws Exception {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        long first = args.length > 1 ? Long.parseLong(args[1]) : 1;
        double[] scales = {1, 4};
        if (args.length > 2) {
            scales = new double[args.length - 2];
            for (int i = 2; i < args.length; i++) scales[i - 2] = Double.parseDouble(args[i]);
        }
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        System.out.printf(
                Locale.ROOT,
                "band %.2f-%.2f, %d attempts, %d x %d samples; %d seeds from %d%n",
                LandShare.LOW,
                LandShare.HIGH,
                LandShare.ATTEMPTS,
                LandShare.SIDE,
                LandShare.SIDE,
                count,
                first);
        ExecutorService pool =
                Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        boolean failed = false;
        try {
            for (double layout : scales) {
                WorldScale world = layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout);
                List<Future<double[]>> rows = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    long seed = first + i;
                    rows.add(
                            pool.submit(
                                    () -> {
                                        long start = System.nanoTime();
                                        LandShare kept = LandShare.choose(seed, heights, world);
                                        double seconds = (System.nanoTime() - start) / 1e9;
                                        LandShare again = LandShare.choose(seed, heights, world);
                                        double draw =
                                                kept.outline.attempt == 0
                                                        ? kept.share
                                                        : LandShare.measure(
                                                                new ContinentalOutlineMap(
                                                                        seed, heights, world),
                                                                world);
                                        boolean same =
                                                again.outline.attempt == kept.outline.attempt
                                                        && again.share == kept.share;
                                        double[] mass = largestMass(kept.outline, world);
                                        return new double[] {
                                            seed,
                                            kept.share,
                                            kept.outline.attempt + 1,
                                            draw,
                                            seconds,
                                            kept.inBand() ? 1 : 0,
                                            same ? 1 : 0,
                                            mass[0],
                                            mass[1]
                                        };
                                    }));
                }
                failed |= report(world, rows);
            }
        } finally {
            pool.shutdown();
        }
        if (failed) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("OK");
    }

    /**
     * The largest 4-connected landmass on the measuring grid, as a share of land, and 1 when it
     * touches three or more edges of the world square (it spans the world).
     */
    private static double[] largestMass(ContinentalOutlineMap outline, WorldScale world) {
        int side = LandShare.SIDE;
        double step = world.side() / side, origin = -world.side() / 2 + step / 2;
        boolean[] land = new boolean[side * side];
        ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
        int total = 0;
        for (int i = 0; i < side; i++)
            for (int k = 0; k < side; k++) {
                outline.sample(origin + i * step, origin + k * step, sample);
                if (land[i * side + k] = sample.landWeight() > 0) total++;
            }
        int[] stack = new int[side * side];
        int best = 0, bestEdges = 0;
        for (int start = 0; start < land.length; start++) {
            if (!land[start]) continue;
            int top = 0, size = 0, edges = 0;
            land[start] = false;
            stack[top++] = start;
            while (top > 0) {
                int c = stack[--top], i = c / side, k = c % side;
                size++;
                if (i == 0) edges |= 1;
                if (i == side - 1) edges |= 2;
                if (k == 0) edges |= 4;
                if (k == side - 1) edges |= 8;
                if (i > 0 && land[c - side]) land[stack[top++] = c - side] = false;
                if (i < side - 1 && land[c + side]) land[stack[top++] = c + side] = false;
                if (k > 0 && land[c - 1]) land[stack[top++] = c - 1] = false;
                if (k < side - 1 && land[c + 1]) land[stack[top++] = c + 1] = false;
            }
            if (size > best) {
                best = size;
                bestEdges = Integer.bitCount(edges);
            }
        }
        return new double[] {total == 0 ? 0 : best / (double) total, bestEdges >= 3 ? 1 : 0};
    }

    /** Prints one scale's summary; true when it fails. */
    private static boolean report(WorldScale world, List<Future<double[]>> rows) throws Exception {
        int n = rows.size(), outside = 0, unstable = 0;
        int[] needed = new int[LandShare.ATTEMPTS + 1];
        double sum = 0, min = 1, max = 0, drawSum = 0, drawMin = 1, drawMax = 0, slowest = 0;
        double attemptSum = 0, massSum = 0;
        int spanning = 0;
        StringBuilder misses = new StringBuilder();
        for (Future<double[]> row : rows) {
            double[] r = row.get();
            sum += r[1];
            min = Math.min(min, r[1]);
            max = Math.max(max, r[1]);
            needed[(int) r[2]]++;
            attemptSum += r[2];
            drawSum += r[3];
            drawMin = Math.min(drawMin, r[3]);
            drawMax = Math.max(drawMax, r[3]);
            slowest = Math.max(slowest, r[4]);
            if (r[5] == 0) {
                outside++;
                misses.append(String.format(Locale.ROOT, " %d (%.1f%%)", (long) r[0], 100 * r[1]));
            }
            if (r[6] == 0) unstable++;
            massSum += r[7];
            if (r[8] == 1) spanning++;
        }
        System.out.printf(
                Locale.ROOT,
                "%.0f km (%s): kept %.1f%% mean, %.1f-%.1f%%; first draw %.1f%% mean, %.1f-%.1f%%;"
                        + " %.2f attempts mean, slowest choice %.2f s%n",
                world.side() / 1024,
                world,
                100 * sum / n,
                100 * min,
                100 * max,
                100 * drawSum / n,
                100 * drawMin,
                100 * drawMax,
                attemptSum / n,
                slowest);
        StringBuilder histogram = new StringBuilder("  attempts needed:");
        for (int a = 1; a < needed.length; a++)
            if (needed[a] > 0) histogram.append(' ').append(a).append(" x").append(needed[a]);
        System.out.println(histogram);
        System.out.printf(
                Locale.ROOT,
                "  largest landmass %.1f%% of land mean; spans the world (3+ edges) in %d of %d%n",
                100 * massSum / n,
                spanning,
                n);
        if (outside > 0) System.out.println("  outside the band:" + misses);
        if (unstable > 0) System.out.println("  repeated choices differ: " + unstable);
        return outside > 0 || unstable > 0;
    }
}
