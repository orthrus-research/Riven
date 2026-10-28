package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contribution.continents.ContinentalKernels.Family;

import com.google.gson.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

/** Analytic stencil controls and deterministic early-map composition, runnable without water/terrain. */
public final class ClimateForcingCheck {
    private ClimateForcingCheck() {}

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) <= 1e-10, message + ": " + actual + " != " + expected);
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static JsonObject settings() {
        return Rules.load("riven-climate-forcing.json", 1).json;
    }

    private static final class Context {
        final ClimateStructureMap structure;
        final ClimateForcingMap derived, legacy, disabled;
        final ClimateMap climate, background, zero;

        Context(long seed, Family family) {
            JsonObject outline = Rules.load("riven-continents.json", 1).json;
            outline.addProperty("family", family.name());
            TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
            structure =
                    new ClimateStructureMap(
                            new ContinentalOutlineMap(seed, heights, outline),
                            new BaseElevationMap(seed, new PlateMap(seed), heights),
                            heights);
            JsonObject json = settings();
            json.addProperty("policy", "DERIVED");
            derived = new ClimateForcingMap(structure, heights, json);
            json = settings();
            json.addProperty("policy", "LEGACY");
            legacy = new ClimateForcingMap(structure, heights, json);
            json = settings();
            json.addProperty("policy", "DERIVED");
            json.addProperty("maximumWindwardFactor", 0);
            json.addProperty("maximumLeeFactor", 0);
            disabled = new ClimateForcingMap(structure, heights, json);
            TemperatureMap temperature = new TemperatureMap(seed);
            climate = new ClimateMap(seed, temperature, derived);
            background = new ClimateMap(seed, temperature, legacy);
            zero = new ClimateMap(seed, temperature, disabled);
        }
    }

    private static String forcing(ClimateForcingMap.Sample s) {
        return s.status()
                + "|"
                + bits(s.landWeight())
                + "|"
                + bits(s.obstructionAboveDatum())
                + "|"
                + bits(s.meanContrastBlocks())
                + "|"
                + bits(s.windwardPotential())
                + "|"
                + bits(s.leePotential())
                + "|"
                + bits(s.moistureFactor())
                + "|"
                + s.structureQueries()
                + "|"
                + s.elevationQueries();
    }

    private static String snapshot(ClimateMap.Sample s) {
        return bits(s.celsius)
                + "|"
                + bits(s.moisture)
                + "|"
                + bits(s.warpedX)
                + "|"
                + bits(s.warpedZ)
                + "|"
                + s.temperatureBand
                + "|"
                + s.moistureBand
                + "|"
                + s.region;
    }

    private static String complete(ClimateMap.Sample s) {
        return snapshot(s)
                + "|"
                + bits(s.backgroundMoisture)
                + "|"
                + bits(s.moistureCorrection)
                + "|"
                + forcing(s.forcing);
    }

    private static void set(ClimateForcingMap.Sample s, String name, Object value)
            throws Exception {
        Field field = ClimateForcingMap.Sample.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(s, value);
    }

    private static void analytic(Context context) throws Exception {
        ClimateForcingMap map = context.derived;
        close(map.transportX, .8, "Transport X");
        close(map.transportZ, .6, "Transport Z");
        close(map.stepBlocks, 768, "Reference-scaled spacing");
        close(map.maximumReach, 6144, "Finite reach");
        close(map.responseHeight, 64, "Reference-scaled response");
        Method resolve =
                ClimateForcingMap.class.getDeclaredMethod(
                        "resolve", ClimateForcingMap.Sample.class);
        resolve.setAccessible(true);
        ClimateForcingMap.Sample sample = new ClimateForcingMap.Sample();
        // Known profiles of obstruction, not claims about the realized terrain distribution.
        for (double weight : new double[] {0, .5, 1}) {
            set(sample, "landWeight", weight);
            set(sample, "obstructionAboveDatum", 100.0);
            Field buffer = ClimateForcingMap.Sample.class.getDeclaredField("upwindObstruction");
            buffer.setAccessible(true);
            double[] heights = (double[]) buffer.get(sample);
            java.util.Arrays.fill(heights, 100);
            resolve.invoke(map, sample);
            close(sample.meanContrastBlocks(), 0, "Flat profile");
            require(bits(sample.moistureFactor()) == bits(0), "Flat exact neutrality");
            for (int sign : new int[] {-1, 1}) {
                // O_i = O_0 - sign*6*i: weighted mean distance index is (N+2)/3 = 10/3.
                for (int i = 0; i < heights.length; i++) heights[i] = 100 - sign * 6 * (i + 1);
                resolve.invoke(map, sample);
                close(sample.meanContrastBlocks(), sign * 20, "Linear plane");
                double potential = 0.1800060272216796875; // quintic(20/64), exact dyadic value.
                close(sample.windwardPotential(), sign > 0 ? potential : 0, "Windward plane");
                close(sample.leePotential(), sign < 0 ? potential : 0, "Lee plane");
                close(
                        sample.moistureFactor(),
                        weight * potential * (sign > 0 ? .2 : -.25),
                        "Land applicability");
            }
            java.util.Arrays.fill(heights, 0);
            resolve.invoke(map, sample);
            close(sample.moistureFactor(), weight * .2, "Bounded windward saturation");
            java.util.Arrays.fill(heights, 200);
            resolve.invoke(map, sample);
            close(sample.moistureFactor(), -weight * .25, "Bounded lee saturation");
            // A single near upwind ridge has more effect than an equal far ridge; marine zero
            // elsewhere is retained in the weighted profile, not interpreted as fresh supply.
            java.util.Arrays.fill(heights, 100);
            heights[0] = 136;
            resolve.invoke(map, sample);
            close(sample.meanContrastBlocks(), -8, "Near ridge weight");
            heights[0] = 100;
            heights[7] = 136;
            resolve.invoke(map, sample);
            close(sample.meanContrastBlocks(), -1, "Far ridge weight");
        }
    }

    private static void configurations(Context context) {
        String[] keys = {
            "transportX",
            "transportZ",
            "upwindSamples",
            "stepInMountainWidths",
            "responseInMountainRelief",
            "maximumWindwardFactor",
            "maximumLeeFactor"
        };
        for (String key : keys)
            for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -9, 9}) {
                JsonObject json = settings();
                json.addProperty(key, invalid);
                try {
                    new ClimateForcingMap(context.structure, TerrainHeightReference.DEFAULT, json);
                    throw new AssertionError("Invalid setting accepted: " + key);
                } catch (IllegalStateException expected) {
                    // Invalid packaged definitions must fail explicitly.
                }
            }
        for (double count : new double[] {0, 1.5, 8.5}) {
            JsonObject json = settings();
            json.addProperty("upwindSamples", count);
            try {
                new ClimateForcingMap(context.structure, TerrainHeightReference.DEFAULT, json);
                throw new AssertionError("Nonintegral/out-of-bound stencil");
            } catch (IllegalStateException expected) {
                // Expected.
            }
        }
        JsonObject json = settings();
        json.addProperty("transportX", 0);
        json.addProperty("transportZ", 0);
        try {
            new ClimateForcingMap(context.structure, TerrainHeightReference.DEFAULT, json);
            throw new AssertionError("Zero transport accepted");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        double landX = Double.NaN, landZ = 0;
        ClimateStructureMap.Sample endpoint = new ClimateStructureMap.Sample();
        for (int i = 0; i < 4096; i++) {
            double x = (i % 64 - 32) * 307.25 + .25, z = (i / 64 - 32) * 311.5 - .5;
            context.structure.sample(x, z, endpoint);
            if (endpoint.status() == ClimateStructureMap.Status.LAND) {
                landX = x;
                landZ = z;
                break;
            }
        }
        require(Double.isFinite(landX), "Missing directional land control");
        for (double tx : new double[] {-1, 0, .8})
            for (int count : new int[] {1, 8}) {
                json = settings();
                json.addProperty("transportX", tx);
                json.addProperty("transportZ", .6);
                json.addProperty("upwindSamples", count);
                ClimateForcingMap map =
                        new ClimateForcingMap(
                                context.structure, TerrainHeightReference.DEFAULT, json);
                close(StrictMath.hypot(map.transportX, map.transportZ), 1, "Normalized direction");
                explicitStencil(map, context.structure, landX, landZ);
            }
    }

    private static void explicitStencil(
            ClimateForcingMap map, ClimateStructureMap structure, double x, double z) {
        ClimateForcingMap.Sample actual = new ClimateForcingMap.Sample();
        ClimateStructureMap.Sample point = new ClimateStructureMap.Sample();
        structure.sample(x, z, point);
        map.sample(x, z, actual);
        close(actual.landWeight(), point.landWeight(), "Endpoint weight");
        close(
                actual.obstructionAboveDatum(),
                point.obstructionAboveDatum(),
                "Endpoint obstruction");
        int elevation = point.elevationSampled() ? 1 : 0;
        if (point.status() == ClimateStructureMap.Status.MARINE) {
            require(
                    actual.structureQueries() == 1 && actual.elevationQueries() == 0,
                    "Marine skip");
            return;
        }
        double end = point.obstructionAboveDatum(), contrast = 0;
        for (int i = 1; i <= map.upwindSamples; i++) {
            structure.sample(
                    x - map.transportX * (map.stepBlocks * i),
                    z - map.transportZ * (map.stepBlocks * i),
                    point);
            contrast += (map.upwindSamples + 1 - i) * (end - point.obstructionAboveDatum());
            if (point.elevationSampled()) elevation++;
        }
        close(
                actual.meanContrastBlocks(),
                contrast / (map.upwindSamples * (map.upwindSamples + 1) / 2.0),
                "Absolute stencil");
        require(
                actual.structureQueries() == map.upwindSamples + 1
                        && actual.elevationQueries() == elevation,
                "Exact work counts");
    }

    private static void domains(Context c) {
        ClimateMap.Sample sample = new ClimateMap.Sample();
        c.climate.sample(0, 0, sample);
        String before = complete(sample);
        for (double bad :
                new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            for (int axis = 0; axis < 2; axis++) {
                try {
                    c.climate.sample(axis == 0 ? bad : 0, axis == 1 ? bad : 0, sample);
                    throw new AssertionError("Nonfinite climate accepted");
                } catch (IllegalArgumentException expected) {
                    require(
                            complete(sample).equals(before),
                            "Invalid query changed public observations");
                }
            }
        for (double outside : new double[] {Math.nextDown(-30000000.0), Math.nextUp(30000000.0)}) {
            c.climate.sample(outside, 0, sample);
            require(
                    sample.forcing.status() == ClimateForcingMap.Status.OUTSIDE_DOMAIN,
                    "Outside endpoint");
            require(sample.forcing.structureQueries() == 0, "Outside endpoint read");
            require(Double.isNaN(sample.forcing.landWeight()), "Unavailable endpoint fabricated");
            require(
                    bits(sample.moisture) == bits(sample.backgroundMoisture),
                    "Outside changed background");
        }
        boolean edge = false;
        for (int z = -200000; z <= 200000; z += 157) {
            c.climate.sample(-30000000, z, sample);
            if (sample.forcing.status() != ClimateForcingMap.Status.OUTSIDE_DOMAIN) continue;
            require(sample.forcing.structureQueries() == 1, "Partial upwind query at edge");
            require(
                    Double.isFinite(sample.forcing.obstructionAboveDatum())
                            && Double.isNaN(sample.forcing.meanContrastBlocks()),
                    "Unknown support became flat");
            require(
                    bits(sample.moisture) == bits(sample.backgroundMoisture),
                    "Partial forcing applied");
            edge = true;
            break;
        }
        require(edge, "Missing land edge control");
        // Only the farthest upwind point is outside. Even the nearer seven must remain unread.
        boolean farEdge = false;
        double x = -30000000 + c.derived.transportX * c.derived.maximumReach - 1;
        for (int z = -200000; z <= 200000; z += 157) {
            c.climate.sample(x, z, sample);
            if (sample.forcing.status() == ClimateForcingMap.Status.MARINE) continue;
            require(
                    sample.forcing.status() == ClimateForcingMap.Status.OUTSIDE_DOMAIN
                            && sample.forcing.structureQueries() == 1,
                    "Farthest missing point allowed partial work");
            require(
                    bits(sample.moisture) == bits(sample.backgroundMoisture),
                    "Partial far-edge forcing");
            farEdge = true;
            break;
        }
        require(farEdge, "Missing far-edge land control");
        ClimateForcingMap.Sample direct = new ClimateForcingMap.Sample();
        for (ClimateForcingMap map : new ClimateForcingMap[] {c.derived, c.legacy, c.disabled}) {
            map.sample(0, 0, direct);
            String unchanged = forcing(direct);
            for (double bad :
                    new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
                for (int axis = 0; axis < 2; axis++) {
                    try {
                        map.sample(axis == 0 ? bad : 0, axis == 1 ? bad : 0, direct);
                        throw new AssertionError("Nonfinite direct forcing accepted");
                    } catch (IllegalArgumentException expected) {
                        require(
                                forcing(direct).equals(unchanged),
                                "Invalid direct query changed observations");
                    }
                }
            try {
                map.sample(0, 0, null);
                throw new AssertionError("Null forcing scratch accepted");
            } catch (NullPointerException expected) {
                // Expected for every policy.
            }
        }
        // Reuse across policies must not retain formerly derived values or work counters.
        c.background.sample(100, 200, sample);
        require(
                sample.forcing.status() == ClimateForcingMap.Status.LEGACY
                        && sample.forcing.structureQueries() == 0,
                "Legacy reset");
        require(Double.isNaN(sample.forcing.landWeight()), "Stale legacy observation");
        c.zero.sample(100, 200, sample);
        require(
                sample.forcing.status() == ClimateForcingMap.Status.DISABLED
                        && sample.forcing.structureQueries() == 0,
                "Disabled reset");
        require(Double.isNaN(sample.forcing.meanContrastBlocks()), "Stale disabled observation");
    }

    public static void main(String[] args) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add(
                "seed,family,x,z,celsius,background,moisture,correction,status,landWeight,obstruction,contrast,windward,lee,factor,structureReads,elevationReads,oldBand,newBand");
        long marine = 0, land = 0, wet = 0, dry = 0, changes = 0, transition = 0, mixed = 0;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (long seed : new long[] {8675309, 7, -918273645})
                for (Family family : Family.values()) {
                    final Context c = new Context(seed, family);
                    analytic(c);
                    configurations(c);
                    domains(c);
                    final String[] expected = new String[4096];
                    ClimateMap.Sample a = new ClimateMap.Sample(),
                            b = new ClimateMap.Sample(),
                            zero = new ClimateMap.Sample();
                    for (int i = 0; i < expected.length; i++) {
                        double x = (i % 64 - 32) * 307.25 + .25, z = (i / 64 - 32) * 311.5 - .5;
                        c.climate.sample(x, z, a);
                        c.background.sample(x, z, b);
                        c.zero.sample(x, z, zero);
                        require(
                                snapshot(b).equals(snapshot(zero)),
                                "Zero strength changed climate");
                        require(
                                bits(a.backgroundMoisture) == bits(b.moisture),
                                "Changed background field");
                        require(
                                bits(a.celsius) == bits(b.celsius)
                                        && bits(a.warpedX) == bits(b.warpedX)
                                        && bits(a.warpedZ) == bits(b.warpedZ),
                                "Changed thermal/warp field");
                        require(a.moisture >= 0 && a.moisture <= 1, "Moisture range");
                        require(
                                a.moisture >= b.moisture * .75 - 1e-15
                                        && a.moisture <= Math.min(1, b.moisture * 1.2) + 1e-15,
                                "Relative correction bound");
                        require(
                                a.forcing.moistureFactor() >= -.25
                                        && a.forcing.moistureFactor() <= .2,
                                "Forcing range");
                        require(
                                a.moistureBand == MoistureBand.at(a.moisture)
                                        && a.region
                                                == ClimateRegion.at(
                                                        a.temperatureBand, a.moistureBand),
                                "Classification precedes forcing");
                        require(
                                a.forcing.structureQueries() <= 9
                                        && a.forcing.elevationQueries()
                                                <= a.forcing.structureQueries(),
                                "Work bound");
                        if (a.forcing.status() == ClimateForcingMap.Status.MARINE) {
                            marine++;
                            require(snapshot(a).equals(snapshot(b)), "Marine humidity bonus");
                            require(
                                    a.forcing.structureQueries() == 1
                                            && a.forcing.elevationQueries() == 0,
                                    "Marine work");
                        } else {
                            land++;
                            require(
                                    a.forcing.status() == ClimateForcingMap.Status.COMPLETE
                                            && a.forcing.structureQueries() == 9,
                                    "Incomplete interior land");
                            if (a.forcing.elevationQueries() < 9) mixed++;
                            if (a.forcing.landWeight() < 1) transition++;
                        }
                        if (a.moistureCorrection > 0) wet++;
                        if (a.moistureCorrection < 0) dry++;
                        if (a.moistureBand != b.moistureBand) changes++;
                        if (i % 31 == 0) explicitStencil(c.derived, c.structure, x, z);
                        expected[i] = complete(a);
                        rows.add(
                                seed
                                        + ","
                                        + family
                                        + ","
                                        + x
                                        + ","
                                        + z
                                        + ","
                                        + a.celsius
                                        + ","
                                        + a.backgroundMoisture
                                        + ","
                                        + a.moisture
                                        + ","
                                        + a.moistureCorrection
                                        + ","
                                        + a.forcing.status()
                                        + ","
                                        + a.forcing.landWeight()
                                        + ","
                                        + a.forcing.obstructionAboveDatum()
                                        + ","
                                        + a.forcing.meanContrastBlocks()
                                        + ","
                                        + a.forcing.windwardPotential()
                                        + ","
                                        + a.forcing.leePotential()
                                        + ","
                                        + a.forcing.moistureFactor()
                                        + ","
                                        + a.forcing.structureQueries()
                                        + ","
                                        + a.forcing.elevationQueries()
                                        + ","
                                        + b.moistureBand
                                        + ","
                                        + a.moistureBand);
                    }
                    for (int i = expected.length - 1; i >= 0; i--) {
                        c.climate.sample(
                                (i % 64 - 32) * 307.25 + .25, (i / 64 - 32) * 311.5 - .5, a);
                        require(expected[i].equals(complete(a)), "Reverse order changed climate");
                    }
                    List<Future<Boolean>> checks = new ArrayList<>();
                    for (int worker = 0; worker < 4; worker++) {
                        final int index = worker;
                        checks.add(
                                pool.submit(
                                        new Callable<Boolean>() {
                                            @Override
                                            public Boolean call() {
                                                ClimateMap.Sample local = new ClimateMap.Sample();
                                                for (int i = index; i < expected.length; i += 4) {
                                                    c.climate.sample(
                                                            (i % 64 - 32) * 307.25 + .25,
                                                            (i / 64 - 32) * 311.5 - .5,
                                                            local);
                                                    if (!expected[i].equals(complete(local)))
                                                        return false;
                                                }
                                                return true;
                                            }
                                        }));
                    }
                    for (Future<Boolean> check : checks)
                        require(check.get(), "Concurrent order changed climate");
                }
        } finally {
            pool.shutdownNow();
        }
        require(
                land > 0
                        && marine > 0
                        && wet > 0
                        && dry > 0
                        && transition > 0
                        && mixed > 0
                        && changes > 0,
                "Missing realized coverage");
        if (args.length > 0)
            Files.write(
                    Paths.get(args[0]),
                    rows,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        System.out.println(
                "Climate forcing PASS: points="
                        + (rows.size() - 1)
                        + " land="
                        + land
                        + " marine="
                        + marine
                        + " wet="
                        + wet
                        + " dry="
                        + dry
                        + " classChanges="
                        + changes
                        + " transition="
                        + transition
                        + " mixedStencil="
                        + mixed);
    }
}
