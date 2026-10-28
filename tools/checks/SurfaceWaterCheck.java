package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Surface water holds: a wet column beside a dry one whose top solid block lies below the water's
 * top would spill when the world updates it. Scans the 8 km world on a 16-block grid for surface
 * water, builds 33 x 33 column windows around up to 1500 of those points, and counts spills by the
 * dry column's state: beside a drainage channel's bank or valley, a network lake's or route's
 * bank, or neither. Requires fewer than one spill per 10,000 wet columns (a few remain where
 * network rivers meet unbanked ground or a drainage valley). args: [seed...]
 */
public final class SurfaceWaterCheck {
    private static final int HALF = 4096, SCAN = 16, WINDOWS = 1500, R = 16;
    private static final double TOLERANCE = 1e-4;

    public static void main(String[] args) {
        long[] seeds = args.length == 0 ? new long[] {8675309L, 101L} : new long[args.length];
        for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        for (long seed : seeds) {
            GenerationMaps maps = new GenerationMaps(seed);
            TerrainMap.Sample s = new TerrainMap.Sample();
            List<int[]> points = new ArrayList<>();
            for (int z = -HALF; z < HALF; z += SCAN)
                for (int x = -HALF; x < HALF; x += SCAN) {
                    maps.terrain.sample(x, z, s);
                    if (s.water.wet && !s.ocean.marine && s.water.waterY > s.blockY())
                        points.add(new int[] {x, z});
                }
            List<int[]> chosen = new ArrayList<>();
            int stride = Math.max(1, points.size() / WINDOWS);
            for (int i = 0; i < points.size(); i += stride) chosen.add(points.get(i));
            // 0 wet columns, 1 beside a channel, 2 beside a network bank, 3 beside neither.
            AtomicLongArray count = new AtomicLongArray(4);
            List<String> examples = new ArrayList<>();
            chosen.parallelStream().forEach(p -> window(maps, p[0], p[1], count, examples));
            long spills = count.get(1) + count.get(2) + count.get(3);
            System.out.printf(
                    "seed %d: %d windows, %d wet columns; spills beside a channel %d, a network bank %d, neither %d%n",
                    seed, chosen.size(), count.get(0), count.get(1), count.get(2), count.get(3));
            examples.forEach(e -> System.out.println("  " + e));
            if (spills > TOLERANCE * count.get(0))
                throw new AssertionError("Surface water spills on seed " + seed);
        }
        System.out.println("SurfaceWaterCheck passed");
    }

    private static void window(
            GenerationMaps maps, int x0, int z0, AtomicLongArray count, List<String> examples) {
        int n = 2 * R + 1;
        TerrainMap.Sample t = new TerrainMap.Sample();
        int[] top = new int[n * n], water = new int[n * n], state = new int[n * n];
        boolean[] wet = new boolean[n * n], marine = new boolean[n * n];
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                maps.terrain.sample(x0 - R + i, z0 - R + j, t);
                top[k] = t.blockY();
                water[k] = t.water.waterY;
                marine[k] = t.ocean.marine;
                wet[k] = t.water.wet && t.water.waterY > top[k];
                state[k] = t.water.channel ? 1 : t.water.weight > 0 ? 2 : 3;
            }
        // Interior columns, so every one has its four neighbours (overlapping windows may count a
        // column twice).
        for (int j = 1; j < n - 1; j++)
            for (int i = 1; i < n - 1; i++) {
                int k = j * n + i;
                if (!wet[k] || marine[k]) continue;
                count.incrementAndGet(0);
                for (int m : new int[] {k - 1, k + 1, k - n, k + n}) {
                    if (wet[m] || marine[m] || top[m] >= water[k]) continue;
                    count.incrementAndGet(state[m]);
                    synchronized (examples) {
                        if (examples.size() < 8)
                            examples.add(
                                    String.format(
                                            "spill at %d %d: water top %d, dry neighbour %d %d top %d",
                                            x0 - R + i,
                                            z0 - R + j,
                                            water[k],
                                            x0 - R + m % n,
                                            z0 - R + m / n,
                                            top[m]));
                    }
                    break;
                }
            }
    }
}
