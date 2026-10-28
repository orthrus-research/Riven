package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contribution.continents.ContinentalKernels.Family;

import com.google.gson.JsonObject;

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

/** Earlier geographic contracts. Also runs with all water, climate and terrain classes absent. */
public final class ContinentalOutlineCheck {
    private ContinentalOutlineCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String snapshot(ContinentalOutlineMap.Sample sample) {
        return Long.toHexString(Double.doubleToRawLongBits(sample.coastCoordinate()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.landWeight()))
                + "|"
                + sample.marine()
                + "|"
                + sample.owner()
                + "|"
                + sample.archipelago()
                + "|"
                + sample.reason();
    }

    /** Every single-family outline plus the packaged per-continent mode. */
    private static final String[] MODES = {
        "HARMONIC", "SPINE", "CELLULAR", "POTENTIAL", "PER_CONTINENT"
    };

    private static JsonObject definition(String family) {
        JsonObject json = Rules.load("riven-continents.json", 1).json;
        json.addProperty("family", family);
        // These downstream policies are neither dependencies nor required inputs of the outline.
        json.remove("nativeWater");
        json.remove("shelfFraction");
        json.remove("normalBandInCoastalPlainWidths");
        return json;
    }

    private static void check(ContinentalOutlineMap.Sample sample) {
        require(Double.isFinite(sample.coastCoordinate()), "Nonfinite outline score");
        require(sample.landWeight() >= 0 && sample.landWeight() <= 1, "Coastal blend bound");
        require(sample.marine() == (sample.coastCoordinate() <= 0), "Marine ownership rule");
        require(
                !sample.marine() || sample.landWeight() == 0,
                "Marine point inherited inland weight");
        require(sample.reason() != null, "Unwritten reason");
    }

    private static void coordinates(ContinentalOutlineMap map) {
        ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
        for (double value :
                new double[] {
                    Double.NaN,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    -30000000.25,
                    30000000.25
                }) {
            for (int axis = 0; axis < 2; axis++) {
                try {
                    map.sample(axis == 0 ? value : 0, axis == 1 ? value : 0, sample);
                    throw new AssertionError("Invalid coordinate accepted");
                } catch (IllegalArgumentException expected) {
                    require(
                            expected.getMessage().contains("absolute coordinates"),
                            "Coordinate rejection reason");
                }
            }
        }
        for (double x : new double[] {-30000000, -0.0, 0, 30000000})
            for (double z : new double[] {-30000000, -0.0, 0, 30000000}) {
                map.sample(x, z, sample);
                check(sample);
            }
        try {
            map.sample(0, 0, (ContinentalOutlineMap.Sample) null);
            throw new AssertionError("Null scratch accepted");
        } catch (NullPointerException expected) {
            require("out".equals(expected.getMessage()), "Null scratch reason");
        }
    }

    private static void exactZero(String family) {
        JsonObject json = definition(family);
        JsonObject tuning = json.getAsJsonObject("tuning");
        tuning.addProperty("warpAmplitude", 0);
        tuning.addProperty("occurrence", 1);
        tuning.addProperty("eligibilityFloor", 0);
        // Put the straight scaffold edge through mainland cores for this ownership-boundary
        // control.
        tuning.addProperty("corridorHalfWidth", .45 * tuning.get("spacing").getAsDouble());
        // The packaged corridors are shifted away from the origin; this control needs one at x = 0.
        tuning.addProperty("corridorOffsetInSpacings", 0);
        ContinentalOutlineMap map =
                new ContinentalOutlineMap(8675309, TerrainHeightReference.DEFAULT, json);
        ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
        double edge = tuning.get("corridorHalfWidth").getAsDouble();
        int zeros = 0;
        for (int z = -12000; z <= 12000; z += 16) {
            map.sample(edge, z, sample);
            if (sample.coastCoordinate() != 0) continue;
            require(sample.marine() && sample.landWeight() == 0, "Exact zero is not marine");
            map.sample(edge - 1e-6, z, sample);
            require(sample.marine() && sample.landWeight() == 0, "Marine side of zero");
            map.sample(edge + 1e-6, z, sample);
            require(!sample.marine() && sample.landWeight() > 0, "Land side of zero");
            zeros++;
        }
        require(zeros > 0, "No exact-zero positive control for " + family);
        System.out.println("family=" + family + " exactZeroControls=" + zeros);
    }

    public static void main(String[] args) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("seed,family,x,z,observations");
        int points = 0, land = 0, marine = 0, transition = 0;
        for (String family : MODES) {
            exactZero(family);
            for (long seed : new long[] {8675309, 7, -918273645}) {
                ContinentalOutlineMap map =
                        new ContinentalOutlineMap(
                                seed, TerrainHeightReference.DEFAULT, definition(family));
                require(
                        "PER_CONTINENT".equals(family)
                                ? map.family == null
                                : map.family == Family.valueOf(family),
                        "Explicit family selection");
                coordinates(map);
                ContinentalOutlineMap.Sample sample = new ContinentalOutlineMap.Sample();
                List<String> expected = new ArrayList<>();
                for (int i = 0; i < 4096; i++) {
                    double x = (i % 64 - 32) * 307.25 + .25, z = (i / 64 - 32) * 311.5 - .5;
                    map.sample(x, z, sample);
                    check(sample);
                    expected.add(snapshot(sample));
                    rows.add(seed + "," + family + "," + x + "," + z + "," + expected.get(i));
                    points++;
                    if (sample.marine()) marine++;
                    else land++;
                    if (sample.landWeight() > 0 && sample.landWeight() < 1) transition++;
                }
                // Scratch reuse must overwrite prior land, marine, family-shape and rejection data.
                for (int i = 4095; i >= 0; i--) {
                    map.sample((i % 64 - 32) * 307.25 + .25, (i / 64 - 32) * 311.5 - .5, sample);
                    require(expected.get(i).equals(snapshot(sample)), "Reverse scratch replay");
                }
                ExecutorService pool = Executors.newFixedThreadPool(4);
                try {
                    List<Callable<Void>> tasks = new ArrayList<>();
                    for (int lane = 0; lane < 4; lane++) {
                        final int id = lane;
                        tasks.add(
                                () -> {
                                    ContinentalOutlineMap.Sample local =
                                            new ContinentalOutlineMap.Sample();
                                    for (int n = 0; n < 1024; n++) {
                                        int i = (n * 73 + id * 17) & 4095;
                                        map.sample(
                                                (i % 64 - 32) * 307.25 + .25,
                                                (i / 64 - 32) * 311.5 - .5,
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
        }
        require(land > 0 && marine > 0 && transition > 0, "Missing geographic coverage");
        if (args.length == 1)
            Files.write(
                    Paths.get(args[0]),
                    rows,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        else if (args.length != 0) throw new IllegalArgumentException("[new-observations.csv]");
        System.out.println(
                "PASS points="
                        + points
                        + " land="
                        + land
                        + " marine="
                        + marine
                        + " coastalTransition="
                        + transition
                        + "; coordinate/zero/reverse/concurrent contracts");
    }
}
