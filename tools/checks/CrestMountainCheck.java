package cc.orthrus.riven.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Crest mountains (mountains-v4): ranges exist, the surface has no seams (slope per block agrees
 * at 1 and 0.25 block spacing), crest nodes are the same from every column that sees them, and
 * concurrent sampling equals serial sampling.
 */
public final class CrestMountainCheck {
    private CrestMountainCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static double worstSlope(GenerationMaps maps, List<double[]> columns, double step) {
        TerrainMap.Sample a = new TerrainMap.Sample(), b = new TerrainMap.Sample();
        double worst = 0;
        for (double[] c : columns) {
            maps.terrain.sample(c[0], c[1], a);
            maps.terrain.sample(c[0] + step, c[1], b);
            if (b.ocean.marine) continue;
            worst = Math.max(worst, Math.abs(a.height - b.height) / step);
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        GenerationMaps maps = new GenerationMaps(8675309, TerrainHeightReference.DEFAULT);
        TerrainMap.Sample s = new TerrainMap.Sample();
        List<double[]> mountains = new ArrayList<>();
        for (int i = 0; i < 128; i++)
            for (int j = 0; j < 1024; j++) {
                double x = -4096 + j * 8 + .5, z = -4096 + i * 64 + .5;
                maps.terrain.sample(x, z, s);
                if (!s.ocean.marine && s.mountains.delta > 0) mountains.add(new double[] {x, z});
            }
        require(mountains.size() > 100, "Too few mountain columns: " + mountains.size());

        double coarse = worstSlope(maps, mountains, 1), fine = worstSlope(maps, mountains, .25);
        require(fine < 1.5 * coarse + .5, "Mountain surface step: " + coarse + " vs " + fine);

        ContinentalOutlineMap.CrestScratch near = new ContinentalOutlineMap.CrestScratch(),
                far = new ContinentalOutlineMap.CrestScratch();
        int shared = 0;
        for (double[] c : mountains.subList(0, Math.min(400, mountains.size()))) {
            int n = maps.outline.crestNodes(c[0], c[1], 700, 900, near);
            int m = maps.outline.crestNodes(c[0] + 300, c[1] - 200, 700, 900, far);
            for (int i = 0; i < n; i++)
                for (int k = 0; k < m; k++)
                    if (near.nodes[6 * i] == far.nodes[6 * k]
                            && near.nodes[6 * i + 1] == far.nodes[6 * k + 1]
                            && near.nodes[6 * i + 2] == far.nodes[6 * k + 2]) {
                        shared++;
                        for (int f = 3; f < 6; f++)
                            require(
                                    Double.doubleToRawLongBits(near.nodes[6 * i + f])
                                            == Double.doubleToRawLongBits(far.nodes[6 * k + f]),
                                    "Crest node differs between columns");
                    }
        }
        require(shared > 0, "No crest node seen from two columns");

        double[] serial = new double[mountains.size()];
        for (int i = 0; i < serial.length; i++) {
            maps.terrain.sample(mountains.get(i)[0], mountains.get(i)[1], s);
            serial[i] = s.height;
        }
        GenerationMaps fresh = new GenerationMaps(8675309, TerrainHeightReference.DEFAULT);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int lane = 0; lane < 4; lane++) {
                final int id = lane;
                tasks.add(
                        () -> {
                            TerrainMap.Sample local = new TerrainMap.Sample();
                            for (int i = serial.length - 1 - id; i >= 0; i -= 4) {
                                fresh.terrain.sample(
                                        mountains.get(i)[0], mountains.get(i)[1], local);
                                require(
                                        Double.doubleToRawLongBits(local.height)
                                                == Double.doubleToRawLongBits(serial[i]),
                                        "Concurrent mountain height differs");
                            }
                            return null;
                        });
            }
            for (Future<Void> task : pool.invokeAll(tasks)) task.get();
        } finally {
            pool.shutdownNow();
        }
        System.out.printf(
                "PASS mountainColumns=%d worstSlope=%.2f/%.2f sharedNodes=%d; seams/nodes/concurrency%n",
                mountains.size(), coarse, fine, shared);
    }
}
