package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plates own the mountain belts (P1). Usage: PlateCheck [seeds [scale ...]] (default 32 seeds at
 * layouts 1 and 4). Over a 128 x 128 grid of each world square, reports plates per world and how
 * much their sizes vary, the land in mountain belts, how much high ground (base elevation 100 or
 * more above the sea) lies in belts, and the signed uplift's spread. Requires: high ground in belts
 * at least 70%, uplift nonzero (|u| > 0.02) on at least 85% of land, a fresh plate map equal to the
 * first, and each belt gradient within 2% (of the steepest) of a finite difference where one belt
 * holds. Range provinces follow the continents' spines (SpineMap, ProvinceCheck), not belts.
 */
public final class PlateCheck {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 32;
        double[] scales =
                args.length > 1
                        ? Arrays.stream(args, 1, args.length)
                                .mapToDouble(Double::parseDouble)
                                .toArray()
                        : new double[] {1, 4};
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors(),
                        r -> {
                            Thread t = new Thread(r);
                            t.setDaemon(true);
                            return t;
                        });
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        for (double layout : scales) {
            WorldScale world = layout == 1 ? WorldScale.DEFAULT : new WorldScale(layout);
            List<Future<double[]>> rows = new ArrayList<>();
            for (int s = 1; s <= seeds; s++) {
                long seed = s;
                rows.add(pool.submit(() -> world(seed, world, heights)));
            }
            double[][] r = new double[seeds][];
            for (int s = 0; s < seeds; s++) r[s] = rows.get(s).get();
            String[] names = {
                "plates owning >= 1% of the world",
                "plate size spread (std / mean)",
                "land in a mountain belt %",
                "land in a strong belt (>= 0.35) %",
                "high ground (base >= +100) in a belt %",
                "land with nonzero uplift %",
                "land raised (u > 0.02) %",
                "land lowered (u < -0.02) %",
                "high ground share of land %"
            };
            System.out.printf(
                    Locale.ROOT,
                    "layout %.1f (%.0f km), %d seeds:%n",
                    layout,
                    world.side() / 1024,
                    seeds);
            for (int k = 0; k < names.length; k++) {
                double mean = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (double[] row : r) {
                    mean += row[k] / seeds;
                    min = Math.min(min, row[k]);
                    max = Math.max(max, row[k]);
                }
                System.out.printf(
                        Locale.ROOT,
                        "  %-42s mean %6.1f  min %6.1f  max %6.1f%n",
                        names[k],
                        mean,
                        min,
                        max);
                if (k == 4) require(mean >= 70, "High ground outside belts: " + mean + "%");
                if (k == 5) require(mean >= 85, "Uplift zero on too much land: " + mean + "%");
            }
        }
        // Determinism and the belt gradient on one world.
        PlateMap a = new PlateMap(8675309), b = new PlateMap(8675309);
        double worst = 0, steepest = 1e-9;
        java.util.Random random = new java.util.Random(1);
        for (int k = 0; k < 20000; k++) {
            int x = random.nextInt(8192) - 4096, z = random.nextInt(8192) - 4096;
            PlateMap.Sample p = a.sample(x, z), q = b.sample(x, z);
            require(
                    p.plateId == q.plateId
                            && p.belt == q.belt
                            && p.boundaryDistance == q.boundaryDistance,
                    "Plates differ between fresh maps at " + x + "," + z);
            if (p.belt <= 0.02 || p.belt >= 0.98) continue;
            PlateMap.Sample px = a.sample(x + 1, z), mx = a.sample(x - 1, z);
            PlateMap.Sample pz = a.sample(x, z + 1), mz = a.sample(x, z - 1);
            // Only where the same belt, on the same plate, holds on every side (a max of belts has
            // corners where its source changes).
            boolean same = true;
            for (PlateMap.Sample n : new PlateMap.Sample[] {px, mx, pz, mz})
                same &= n.plateId == p.plateId && n.beltSource == p.beltSource && n.belt > 0;
            if (!same) continue;
            double fx = (px.belt - mx.belt) / 2, fz = (pz.belt - mz.belt) / 2;
            steepest = Math.max(steepest, Math.hypot(p.beltDx, p.beltDz));
            worst = Math.max(worst, Math.hypot(fx - p.beltDx, fz - p.beltDz));
        }
        double relative = worst / steepest;
        System.out.printf(
                Locale.ROOT,
                "belt gradient against finite differences: worst %.5f per block, %.1f%% of the steepest %.5f%n",
                worst,
                100 * relative,
                steepest);
        require(relative <= 0.02, "Belt gradient off by " + 100 * relative + "% of the steepest");
        // A plate sample's cost, one thread.
        long t0 = System.nanoTime();
        double sink = 0;
        for (int k = 0; k < 200000; k++)
            sink += a.sample(k * 37 % 8192 - 4096, k * 53 % 8192 - 4096).belt;
        System.out.printf(
                Locale.ROOT,
                "plate sample: %.2f us each (sum %.1f)%n",
                (System.nanoTime() - t0) / 1e3 / 200000,
                sink);
        pool.shutdown();
        System.out.println("PlateCheck passed");
    }

    private static double[] world(long seed, WorldScale world, TerrainHeightReference heights) {
        PlateMap plates = new PlateMap(seed, world);
        ContinentalOutlineMap outline = LandShare.choose(seed, heights, world).outline;
        BaseElevationMap elevation = new BaseElevationMap(seed, plates, heights);
        ContinentalMap continents = new ContinentalMap(seed, heights, outline);
        int side = 128;
        double half = world.side() / 2, step = world.side() / side;
        ContinentalMap.Sample c = new ContinentalMap.Sample();
        BaseElevationMap.Sample e = new BaseElevationMap.Sample();
        HeightSample u = new HeightSample();
        Map<Long, int[]> owned = new HashMap<>();
        int land = 0, belt = 0, strong = 0, high = 0, highBelt = 0;
        int nonzero = 0, raised = 0, lowered = 0;
        for (int i = 0; i < side; i++)
            for (int j = 0; j < side; j++) {
                int x = (int) (-half + (i + 0.5) * step), z = (int) (-half + (j + 0.5) * step);
                PlateMap.Sample p = plates.sample(x, z);
                owned.computeIfAbsent(p.plateId, k -> new int[1])[0]++;
                continents.sample(x, z, c);
                if (c.marine) continue;
                land++;
                if (p.belt > 0) belt++;
                if (p.belt >= 0.35) strong++;
                elevation.sample(x, z, e);
                if (e.meanHeight() >= heights.seaLevel + 100) {
                    high++;
                    if (p.belt > 0) highBelt++;
                }
                plates.uplift(x, z, u);
                if (Math.abs(u.height) > 0.02) nonzero++;
                if (u.height > 0.02) raised++;
                if (u.height < -0.02) lowered++;
            }
        List<Double> sizes = new ArrayList<>();
        int big = 0;
        for (int[] o : owned.values())
            if (o[0] >= side * side / 100) {
                big++;
                sizes.add((double) o[0]);
            }
        double mean = 0, sq = 0;
        for (double v : sizes) mean += v / sizes.size();
        for (double v : sizes) sq += (v - mean) * (v - mean) / sizes.size();
        return new double[] {
            big,
            Math.sqrt(sq) / mean,
            100.0 * belt / land,
            100.0 * strong / land,
            high == 0 ? 100 : 100.0 * highBelt / high,
            100.0 * nonzero / land,
            100.0 * raised / land,
            100.0 * lowered / land,
            100.0 * high / land
        };
    }
}
