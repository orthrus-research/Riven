package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Direct earlier-provider contracts; also runs with every later production class absent. */
public final class BaseElevationCheck {
    private BaseElevationCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String snapshot(BaseElevationMap.Sample sample) {
        StringBuilder result = new StringBuilder();
        for (double value :
                new double[] {
                    sample.meanHeight(),
                    sample.dx(),
                    sample.dz(),
                    sample.minimumY(),
                    sample.maximumY(),
                    sample.envelope(),
                    sample.envelopeDx(),
                    sample.envelopeDz(),
                    sample.uplift(),
                    sample.blend(),
                    sample.intentDx(),
                    sample.intentDz(),
                    sample.legacyPosition()
                }) result.append(Long.toHexString(Double.doubleToRawLongBits(value))).append('|');
        return result.append(sample.firstLandform())
                .append('|')
                .append(sample.secondLandform())
                .toString();
    }

    private static void close(double actual, double expected, String label) {
        require(
                Double.isFinite(actual)
                        && Double.isFinite(expected)
                        && Math.abs(actual - expected) <= 2e-6 + Math.abs(expected) * 2e-4,
                label + ": " + actual + " != " + expected);
    }

    private static void gradients(BaseElevationMap map, double x, double z) {
        BaseElevationMap.Sample center = new BaseElevationMap.Sample(),
                minus = new BaseElevationMap.Sample(),
                plus = new BaseElevationMap.Sample();
        map.sample(x, z, center);
        double h = .015625;
        for (int axis = 0; axis < 2; axis++) {
            map.sample(x - (axis == 0 ? h : 0), z - (axis == 1 ? h : 0), minus);
            map.sample(x + (axis == 0 ? h : 0), z + (axis == 1 ? h : 0), plus);
            close(
                    axis == 0 ? center.dx() : center.dz(),
                    (plus.meanHeight() - minus.meanHeight()) / (2 * h),
                    "Mean gradient");
            close(
                    axis == 0 ? center.envelopeDx() : center.envelopeDz(),
                    (plus.envelope() - minus.envelope()) / (2 * h),
                    "Allocation-envelope gradient");
            close(
                    axis == 0 ? center.intentDx() : center.intentDz(),
                    (plus.legacyPosition() - minus.legacyPosition()) / (2 * h),
                    "Position gradient");
        }
    }

    private static void definitions(TerrainHeightReference heights) {
        for (String key :
                new String[] {
                    "stateOffset", "upliftWeight", "regionalWeight", "regionalInMountainWidths"
                }) {
            JsonObject json = Rules.load("riven-base-elevation.json", 1).json;
            json.addProperty(key, Double.NaN);
            try {
                new BaseElevationMap(0, new PlateMap(0), heights, json);
                throw new AssertionError("Invalid elevation definition");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains(key), "Invalid-definition diagnostic");
            }
        }
        for (double state : new double[] {-1, 0, 1.0 / 3, 2.0 / 3, 1, 2}) {
            JsonObject json = Rules.load("riven-base-elevation.json", 1).json;
            json.addProperty("stateOffset", state);
            json.addProperty("upliftWeight", 0);
            json.addProperty("regionalWeight", 0);
            BaseElevationMap map = new BaseElevationMap(0, new PlateMap(0), heights, json);
            BaseElevationMap.Sample sample = new BaseElevationMap.Sample();
            map.sample(-1234.25, 5678.5, sample);
            require(
                    sample.legacyPosition() == Math.max(0, Math.min(3, state * 3)),
                    "Clamped class control");
            require(
                    sample.dx() == 0
                            && sample.dz() == 0
                            && sample.intentDx() == 0
                            && sample.intentDz() == 0
                            && sample.envelopeDx() == 0
                            && sample.envelopeDz() == 0,
                    "Constant class has a gradient");
            gradients(map, -1234.25, 5678.5);
        }
    }

    public static void main(String[] args) throws Exception {
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        int points = 0;
        for (long seed : new long[] {8675309, 7, -918273645}) {
            BaseElevationMap map = new BaseElevationMap(seed, new PlateMap(seed), heights);
            BaseElevationMap.Sample reused = new BaseElevationMap.Sample();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                double x = (i % 32 - 16) * 941.25 + .25, z = (i / 32 - 16) * 1021.75 - .5;
                BaseElevationMap.Sample fresh = new BaseElevationMap.Sample();
                map.sample(x, z, fresh);
                map.sample(x, z, reused);
                require(snapshot(fresh).equals(snapshot(reused)), "Fresh/reused scratch mismatch");
                require(
                        fresh.minimumY() <= fresh.meanHeight()
                                && fresh.meanHeight() <= fresh.maximumY(),
                        "Mean outside allocation");
                require(
                        fresh.envelope() >= 0
                                && fresh.envelope()
                                        <= Math.min(
                                                        fresh.meanHeight() - fresh.minimumY(),
                                                        fresh.maximumY() - fresh.meanHeight())
                                                + 1e-10,
                        "Invalid envelope");
                expected.add(snapshot(fresh));
                if ((i & 15) == 0) gradients(map, x, z);
                points++;
            }
            for (int i = 1023; i >= 0; i--) {
                map.sample((i % 32 - 16) * 941.25 + .25, (i / 32 - 16) * 1021.75 - .5, reused);
                require(expected.get(i).equals(snapshot(reused)), "Reverse replay");
            }
            double noiseScale =
                    heights.feature("mountains").width.targetAverage * 2 * TerrainScale.HORIZONTAL;
            for (int i = -3; i <= 3; i++) {
                gradients(map, (i - .37) * noiseScale, (i - .61) * noiseScale);
                gradients(map, i * PlateMap.CELL_BLOCKS, -i * PlateMap.CELL_BLOCKS);
            }
            for (double x : new double[] {-30000000, -1, 0, 1, 30000000}) {
                map.sample(x, -x, reused);
                require(Double.isFinite(reused.meanHeight()), "Far coordinate");
            }
            String retained = snapshot(reused);
            for (double bad :
                    new double[] {
                        Double.NaN,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        -30000001,
                        30000001
                    })
                for (int axis = 0; axis < 2; axis++) {
                    try {
                        map.sample(axis == 0 ? bad : 0, axis == 1 ? bad : 0, reused);
                        throw new AssertionError("Invalid coordinate");
                    } catch (IllegalArgumentException wanted) {
                        require(
                                retained.equals(snapshot(reused)),
                                "Invalid query mutated observations");
                    }
                }
            map.clear(reused);
            require(
                    reused.meanHeight() == 0
                            && reused.dx() == 0
                            && reused.dz() == 0
                            && reused.envelope() == 0
                            && reused.envelopeDx() == 0
                            && reused.envelopeDz() == 0
                            && reused.minimumY() == 0
                            && reused.maximumY() == 0
                            && reused.uplift() == 0
                            && reused.blend() == 0
                            && reused.intentDx() == 0
                            && reused.intentDz() == 0
                            && reused.legacyPosition() == -1
                            && reused.firstLandform() == null
                            && reused.secondLandform() == null,
                    "Marine clear leaked observations");
            map.sample(-15059.75, -16348.5, reused);
            require(expected.get(0).equals(snapshot(reused)), "Replay after clear");
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int lane = 0; lane < 4; lane++) {
                    final int id = lane;
                    tasks.add(
                            () -> {
                                BaseElevationMap.Sample local = new BaseElevationMap.Sample();
                                for (int n = 0; n < 256; n++) {
                                    int i = (n * 73 + id * 17) & 1023;
                                    map.sample(
                                            (i % 32 - 16) * 941.25 + .25,
                                            (i / 32 - 16) * 1021.75 - .5,
                                            local);
                                    require(
                                            expected.get(i).equals(snapshot(local)),
                                            "Concurrent replay");
                                }
                                return null;
                            });
                }
                for (Future<Void> task : pool.invokeAll(tasks)) task.get();
            } finally {
                pool.shutdownNow();
            }
        }
        definitions(heights);
        System.out.println(
                "Elevation bounds/gradients/lattice/class controls/scratch/coordinates/concurrency=PASS; points="
                        + points);
    }
}
