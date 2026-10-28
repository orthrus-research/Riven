package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Axis/cancellation/replay controls; runs with every later production class absent. */
public final class RegionalFabricCheck {
    private RegionalFabricCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String snapshot(RegionalFabricMap.Sample sample, double heading) {
        return Long.toHexString(Double.doubleToRawLongBits(sample.doubledX()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.doubledZ()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.confidence()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(heading));
    }

    private static double field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(value);
    }

    private static RegionalFabricMap.Sample vector(double x, double z) throws Exception {
        RegionalFabricMap.Sample sample = new RegionalFabricMap.Sample();
        for (String name : new String[] {"doubledX", "doubledZ", "confidence"}) {
            Field f = RegionalFabricMap.Sample.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setDouble(
                    sample,
                    name.equals("doubledX")
                            ? x
                            : name.equals("doubledZ") ? z : Math.min(1, StrictMath.hypot(x, z)));
        }
        return sample;
    }

    private static void controls(RegionalFabricMap map) throws Exception {
        for (double neutral : new double[] {0, .33, 2.9, 5.2}) {
            for (double[] q : new double[][] {{0, 0}, {1e-300, -1e-300}, {.1, 0}, {0, -.1}}) {
                require(
                        Double.doubleToRawLongBits(map.heading(vector(q[0], q[1]), neutral))
                                == Double.doubleToRawLongBits(neutral),
                        "Cancellation/weak signal changed neutral");
            }
            for (double angle :
                    new double[] {
                        0, .3, Math.PI / 2 - 1e-10, Math.PI / 2 + 1e-10, Math.PI - 1e-10
                    }) {
                RegionalFabricMap.Sample sample =
                        vector(StrictMath.cos(2 * angle), StrictMath.sin(2 * angle));
                double heading = map.heading(sample, neutral);
                require(Math.abs(StrictMath.sin(heading - angle)) < 1e-12, "Strong axis alignment");
                RegionalFabricMap.Sample equivalent =
                        vector(
                                StrictMath.cos(2 * (angle + Math.PI)),
                                StrictMath.sin(2 * (angle + Math.PI)));
                require(
                        Math.abs(StrictMath.sin(map.heading(equivalent, neutral) - heading))
                                < 1e-12,
                        "Axis pi-equivalence");
                require(
                        Math.abs(map.heading(sample, neutral + Math.PI) - heading - Math.PI)
                                < 1e-12,
                        "Owner polarity lift");
            }
            double lower =
                    map.heading(
                            vector(
                                    StrictMath.cos(Math.PI - 2e-10),
                                    StrictMath.sin(Math.PI - 2e-10)),
                            neutral);
            double upper =
                    map.heading(
                            vector(
                                    StrictMath.cos(-Math.PI + 2e-10),
                                    StrictMath.sin(-Math.PI + 2e-10)),
                            neutral);
            require(Math.abs(StrictMath.sin(lower - upper)) < 3e-10, "Angle wrap discontinuity");
            double previous = 0;
            for (int i = 0; i <= 100; i++) {
                double strength = i / 100.0;
                double target = neutral + .4;
                double heading =
                        map.heading(
                                vector(
                                        strength * StrictMath.cos(2 * target),
                                        strength * StrictMath.sin(2 * target)),
                                neutral);
                double displacement = heading - neutral;
                require(
                        displacement >= previous - 1e-12 && displacement <= .4 + 1e-12,
                        "Confidence fade overshot or reversed");
                previous = displacement;
            }
        }
    }

    private static void definitions() {
        for (String key :
                new String[] {
                    "spanInPlainWidths", "transitionFraction", "minimumConfidence", "fullConfidence"
                }) {
            JsonObject json = Rules.load("riven-regional-fabric.json", 1).json;
            json.addProperty(key, Double.NaN);
            try {
                new RegionalFabricMap(0, TerrainHeightReference.DEFAULT, json);
                throw new AssertionError("Invalid fabric definition");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains(key), "Invalid-definition diagnostic");
            }
        }
        JsonObject json = Rules.load("riven-regional-fabric.json", 1).json;
        json.addProperty("minimumConfidence", .8);
        json.addProperty("fullConfidence", .6);
        try {
            new RegionalFabricMap(0, TerrainHeightReference.DEFAULT, json);
            throw new AssertionError("Inverted confidence interval");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("confidence"), "Confidence diagnostic");
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("seed,x,z,doubledX,doubledZ,confidence,neutral,heading");
        int points = 0;
        for (long seed : new long[] {8675309, 7, -918273645}) {
            RegionalFabricMap map = new RegionalFabricMap(seed, TerrainHeightReference.DEFAULT);
            RegionalFabricMap.Sample sample = new RegionalFabricMap.Sample();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < 16384; i++) {
                double x = (i % 128 - 64) * 256 + .25, z = (i / 128 - 64) * 256 - .5;
                double neutral = .7;
                map.sample(x, z, sample);
                double heading = map.heading(sample, neutral);
                require(
                        Double.isFinite(heading)
                                && sample.confidence() >= 0
                                && sample.confidence() <= 1,
                        "Invalid heading or confidence");
                require(
                        StrictMath.hypot(sample.doubledX(), sample.doubledZ()) <= 1 + 1e-14,
                        "Axis blend exceeded unit disk");
                require(
                        Math.abs(heading - neutral) <= Math.PI / 2 + 1e-12,
                        "Not the shortest axis arc");
                if (sample.confidence() >= .6)
                    require(
                            Math.abs(
                                            sample.doubledX() * StrictMath.sin(2 * heading)
                                                    - sample.doubledZ()
                                                            * StrictMath.cos(2 * heading))
                                    < 1e-12,
                            "Strong field alignment");
                expected.add(snapshot(sample, heading));
                if ((i & 63) == 0) {
                    RegionalFabricMap.Sample fresh = new RegionalFabricMap.Sample();
                    map.sample(x, z, fresh);
                    require(
                            snapshot(fresh, map.heading(fresh, neutral)).equals(expected.get(i)),
                            "Fresh scratch replay");
                }
                rows.add(
                        seed
                                + ","
                                + x
                                + ","
                                + z
                                + ","
                                + sample.doubledX()
                                + ","
                                + sample.doubledZ()
                                + ","
                                + sample.confidence()
                                + ","
                                + neutral
                                + ","
                                + heading);
                points++;
            }
            for (int i = 16383; i >= 0; i--) {
                map.sample((i % 128 - 64) * 256 + .25, (i / 128 - 64) * 256 - .5, sample);
                require(
                        expected.get(i).equals(snapshot(sample, map.heading(sample, .7))),
                        "Reverse replay");
            }
            double c = field(map, "c"), s = field(map, "s"), span = field(map, "span");
            double ox = field(map, "offsetX"), oz = field(map, "offsetZ");
            for (double u : new double[] {-2, -1.75, -.25, 0, .25, .75, 1})
                for (double v : new double[] {-.75, 0, .25}) {
                    double a = (u - ox) * span, b = (v - oz) * span;
                    double x = a * c - b * s, z = a * s + b * c;
                    RegionalFabricMap.Sample minus = new RegionalFabricMap.Sample(),
                            plus = new RegionalFabricMap.Sample();
                    map.sample(x - .001 * c, z - .001 * s, minus);
                    map.sample(x + .001 * c, z + .001 * s, plus);
                    require(
                            Math.hypot(
                                            minus.doubledX() - plus.doubledX(),
                                            minus.doubledZ() - plus.doubledZ())
                                    < 1e-8,
                            "Rotated lattice/core seam");
                }
            for (double x : new double[] {-30000000, -1, 0, 30000000}) {
                map.sample(x, -x, sample);
                require(Double.isFinite(map.heading(sample, .7)), "Far-coordinate heading");
            }
            String retained = snapshot(sample, map.heading(sample, .7));
            for (double bad :
                    new double[] {Double.NaN, Double.POSITIVE_INFINITY, -30000001, 30000001})
                for (int axis = 0; axis < 2; axis++) {
                    try {
                        map.sample(axis == 0 ? bad : 0, axis == 1 ? bad : 0, sample);
                        throw new AssertionError("Invalid coordinate");
                    } catch (IllegalArgumentException wanted) {
                        require(
                                retained.equals(snapshot(sample, map.heading(sample, .7))),
                                "Invalid query mutated sample");
                    }
                }
            controls(map);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int lane = 0; lane < 4; lane++) {
                    final int id = lane;
                    tasks.add(
                            () -> {
                                RegionalFabricMap.Sample local = new RegionalFabricMap.Sample();
                                for (int n = 0; n < 1024; n++) {
                                    int i = (n * 73 + id * 17) & 16383;
                                    map.sample(
                                            (i % 128 - 64) * 256 + .25,
                                            (i / 128 - 64) * 256 - .5,
                                            local);
                                    require(
                                            expected.get(i)
                                                    .equals(
                                                            snapshot(
                                                                    local, map.heading(local, .7))),
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
        definitions();
        if (args.length == 1)
            Files.write(
                    Paths.get(args[0]),
                    rows,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        else if (args.length != 0) throw new IllegalArgumentException("[new-field.csv]");
        System.out.println(
                "Fabric unit-disk/cancellation/pi-wrap/polarity/confidence/seams/coordinates/replay/concurrency=PASS; points="
                        + points);
    }
}
