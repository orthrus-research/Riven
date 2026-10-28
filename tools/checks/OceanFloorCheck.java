import cc.orthrus.riven.contrib.ocean.OceanFloors;
import cc.orthrus.riven.worldgen.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Integration guards for ownership seams, reservations, determinism and deep fill bounds. */
public final class OceanFloorCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        long samples = 0, seams = 0, coasts = 0, reservations = 0;
        long[] applied = new long[4];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, maxSeam = 0;
        try (PrintWriter witness = new PrintWriter(new File(args[0], "witnesses.csv"))) {
            witness.println("seed,family,x,z,height,weight");
            for (long seed : new long[] {8675309L, 42L, -7391L}) {
                GenerationMaps maps = new GenerationMaps(seed);
                TerrainMap.Sample p = new TerrainMap.Sample();
                OceanBed.Result b = new OceanBed.Result(), other = new OceanBed.Result();
                ContinentalMap.Sample ocean = new ContinentalMap.Sample(),
                        right = new ContinentalMap.Sample();
                boolean[] found = new boolean[4];
                for (int z = -8192; z <= 8192; z += 64)
                    for (int x = -8192; x <= 8192; x += 64) {
                        maps.terrain.sample(x, z, p);
                        samples++;
                        require(Double.isFinite(p.height), "Nonfinite terrain");
                        if (!p.ocean.marine) continue;
                        require(
                                p.blockY() >= maps.terrain.minimumSurfaceY,
                                "Native stone shortcut hides bed");
                        if (!p.water.wet && p.water.weight == 0)
                            require(p.height <= 62, "Unreserved marine surface emerged");
                        if (p.water.wet) {
                            require(p.height == p.water.floor, "Reserved channel changed");
                            reservations++;
                        }
                        maps.terrain.oceanBed.apply(x, z, p.ocean, false, b);
                        require(
                                b.height >= maps.terrain.oceanBed.minimumSurfaceY && b.height <= 62,
                                "Ocean envelope");
                        min = Math.min(min, b.height);
                        max = Math.max(max, b.height);
                        OceanFloors.Output[] parts = {b.shelf, b.ridge, b.trench, b.bank};
                        for (int i = 0; i < 4; i++)
                            if (parts[i].weight > .05) {
                                applied[i]++;
                                if (seed == 8675309L
                                        && !found[i]
                                        && !p.water.wet
                                        && p.water.weight == 0
                                        && (i != 2 || p.height < -70)) {
                                    witness.println(
                                            seed
                                                    + ","
                                                    + i
                                                    + ","
                                                    + x
                                                    + ","
                                                    + z
                                                    + ","
                                                    + p.height
                                                    + ","
                                                    + parts[i].weight);
                                    found[i] = true;
                                }
                            }
                        maps.terrain.oceanBed.apply(x, z, p.ocean, true, other);
                        require(
                                other.height == p.ocean.bedHeight,
                                "Reserved adapter changed parent");
                    }
                // Locate discontinuous continental owner changes, then narrow to sub-block
                // precision.
                for (int z = -8192; z <= 8192; z += 256)
                    for (int x = -8192; x < 8192; x += 8) {
                        maps.continents.sample(x, z, ocean);
                        maps.continents.sample(x + 8, z, right);
                        boolean coast = ocean.marine != right.marine;
                        boolean jump =
                                ocean.marine
                                        && right.marine
                                        && Math.abs(ocean.coastCoordinate - right.coastCoordinate)
                                                > 100;
                        if (!coast && !jump) continue;
                        double lo = x, hi = x + 8;
                        for (int k = 0; k < 32; k++) {
                            double mid = (lo + hi) / 2;
                            maps.continents.sample(mid, z, right);
                            if (coast
                                    ? right.marine == ocean.marine
                                    : Math.abs(right.coastCoordinate - ocean.coastCoordinate) < 50)
                                lo = mid;
                            else hi = mid;
                        }
                        maps.continents.sample(lo, z, ocean);
                        maps.continents.sample(hi, z, right);
                        maps.terrain.oceanBed.apply(lo, z, ocean, false, b);
                        maps.terrain.oceanBed.apply(hi, z, right, false, other);
                        double difference = Math.abs(b.height - other.height);
                        require(
                                difference < 1e-4,
                                "Coast/owner seam "
                                        + seed
                                        + " "
                                        + lo
                                        + " "
                                        + z
                                        + " delta="
                                        + difference);
                        maxSeam = Math.max(maxSeam, difference);
                        if (coast) coasts++;
                        else seams++;
                    }
                // Earlier broad facts are independent of query order and caller scratch.
                Random random = new Random(seed);
                int n = 2048;
                int[] xs = new int[n], zs = new int[n];
                long[] bits = new long[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = random.nextInt(59999969) - 29999984;
                    zs[i] = random.nextInt(59999969) - 29999984;
                    maps.continents.sample(xs[i], zs[i], ocean);
                    maps.terrain.oceanBed.apply(xs[i], zs[i], ocean, false, b);
                    bits[i] = Double.doubleToLongBits(b.height);
                }
                ExecutorService pool = Executors.newFixedThreadPool(4);
                try {
                    ArrayList<Future<?>> futures = new ArrayList<>();
                    for (int lane = 0; lane < 4; lane++) {
                        final int offset = lane;
                        futures.add(
                                pool.submit(
                                        () -> {
                                            ContinentalMap.Sample c = new ContinentalMap.Sample();
                                            OceanBed.Result r = new OceanBed.Result();
                                            for (int i = n - 1 - offset; i >= 0; i -= 4) {
                                                maps.continents.sample(xs[i], zs[i], c);
                                                maps.terrain.oceanBed.apply(
                                                        xs[i], zs[i], c, false, r);
                                                require(
                                                        bits[i]
                                                                == Double.doubleToLongBits(
                                                                        r.height),
                                                        "Order/concurrency mismatch");
                                            }
                                        }));
                    }
                    for (Future<?> f : futures) f.get();
                } finally {
                    pool.shutdown();
                }
            }
        }
        require(seams > 0 && coasts > 0 && reservations > 0, "Missing seam/reservation coverage");
        for (long count : applied) require(count > 0, "Missing family");
        try (PrintWriter out = new PrintWriter(new File(args[0], "checks.json"))) {
            out.println(
                    "{\"samples\":"
                            + samples
                            + ",\"ownerSeams\":"
                            + seams
                            + ",\"coastCrossings\":"
                            + coasts
                            + ",\"reservedColumns\":"
                            + reservations
                            + ",\"applied\":"
                            + Arrays.toString(applied)
                            + ",\"minimumBed\":"
                            + min
                            + ",\"maximumBed\":"
                            + max
                            + ",\"maxSeamDelta\":"
                            + maxSeam
                            + ",\"concurrentFarCoordinateReplays\":6144}");
        }
    }
}
