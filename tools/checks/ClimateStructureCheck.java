package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contribution.continents.ContinentalKernels.Family;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Constructor;
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

/** Marine masking, datum and unknown-domain contracts; also runs without climate/water/terrain. */
public final class ClimateStructureCheck {
    private ClimateStructureCheck() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String snapshot(ClimateStructureMap.Sample sample) {
        return sample.status()
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.landWeight()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.meanSolidY()))
                + "|"
                + Long.toHexString(Double.doubleToRawLongBits(sample.obstructionAboveDatum()))
                + "|"
                + sample.elevationSampled();
    }

    private static Object scratch(ClimateStructureMap.Sample sample, String name) throws Exception {
        Field field = ClimateStructureMap.Sample.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(sample);
    }

    private static void unknown(
            ClimateStructureMap.Sample sample, ClimateStructureMap.Status status) {
        require(sample.status() == status && !sample.elevationSampled(), "Unknown status/work");
        require(
                Double.isNaN(sample.landWeight())
                        && Double.isNaN(sample.meanSolidY())
                        && Double.isNaN(sample.obstructionAboveDatum()),
                "Unknown point became flat land or sea");
    }

    private static void domains(ClimateStructureMap map) throws Exception {
        ClimateStructureMap.Sample sample = new ClimateStructureMap.Sample();
        unknown(sample, ClimateStructureMap.Status.UNSAMPLED);
        for (double x : new double[] {-30000000, -0.0, 0, 30000000})
            for (double z : new double[] {-30000000, -0.0, 0, 30000000}) {
                map.sample(x, z, sample);
                require(
                        sample.status() != ClimateStructureMap.Status.OUTSIDE_DOMAIN
                                && Double.isFinite(sample.obstructionAboveDatum()),
                        "Supported domain edge");
            }
        ContinentalOutlineMap.Sample outline =
                (ContinentalOutlineMap.Sample) scratch(sample, "outline");
        BaseElevationMap.Sample elevation = (BaseElevationMap.Sample) scratch(sample, "elevation");
        long coastBits = Double.doubleToRawLongBits(outline.coastCoordinate());
        long elevationBits = Double.doubleToRawLongBits(elevation.meanHeight());
        for (double outside :
                new double[] {Math.nextDown(-30000000.0), Math.nextUp(30000000.0), -1e300, 1e300})
            for (int axis = 0; axis < 2; axis++) {
                map.sample(axis == 0 ? outside : 0, axis == 1 ? outside : 0, sample);
                unknown(sample, ClimateStructureMap.Status.OUTSIDE_DOMAIN);
                require(
                        Double.doubleToRawLongBits(outline.coastCoordinate()) == coastBits
                                && Double.doubleToRawLongBits(elevation.meanHeight())
                                        == elevationBits,
                        "Outside query touched parent scratch");
            }
        String before = snapshot(sample);
        for (double bad :
                new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            for (int axis = 0; axis < 2; axis++) {
                try {
                    map.sample(axis == 0 ? bad : 0, axis == 1 ? bad : 0, sample);
                    throw new AssertionError("Nonfinite coordinates accepted");
                } catch (IllegalArgumentException expected) {
                    require(snapshot(sample).equals(before), "Invalid query mutated observations");
                }
            }
        try {
            map.sample(0, 0, null);
            throw new AssertionError("Null scratch accepted");
        } catch (NullPointerException expected) {
            require("out".equals(expected.getMessage()), "Null scratch diagnostic");
        }
        map.sample(0, 0, sample);
        require(
                sample.status() != ClimateStructureMap.Status.OUTSIDE_DOMAIN,
                "Stale outside status");
    }

    private static TerrainHeightReference constantHeights(int datum, int offset) throws Exception {
        JsonObject json = Rules.load("riven-terrain-height-reference.json", 1).json;
        JsonObject sea = json.getAsJsonObject("datum");
        sea.addProperty("engineSeaLevelY", datum);
        sea.addProperty("highestOceanWaterBlockY", datum - 1);
        sea.addProperty("waterSurfaceBoundaryY", datum);
        for (JsonElement item : json.getAsJsonArray("primaryFeatures")) {
            JsonObject feature = item.getAsJsonObject();
            String id = feature.get("id").getAsString();
            if (!id.equals("inland_plain")
                    && !id.equals("rolling_hills")
                    && !id.equals("foothills")
                    && !id.equals("mountains")) continue;
            JsonObject range = new JsonObject();
            range.addProperty("min", offset - 1);
            range.addProperty("targetAverage", offset);
            range.addProperty("max", offset + 1);
            feature.add("elevationOffset", range);
        }
        Constructor<TerrainHeightReference> constructor =
                TerrainHeightReference.class.getDeclaredConstructor(JsonObject.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(json, "climate-structure-constant-control");
    }

    private static void knownControls() throws Exception {
        int controls = 0;
        for (Family family : Family.values())
            for (int datum : new int[] {63, 111})
                for (int offset : new int[] {-2, -1, 0, 7}) {
                    TerrainHeightReference heights = constantHeights(datum, offset);
                    JsonObject json = Rules.load("riven-continents.json", 1).json;
                    json.addProperty("family", family.name());
                    JsonObject tuning = json.getAsJsonObject("tuning");
                    tuning.addProperty("warpAmplitude", 0);
                    tuning.addProperty("occurrence", 1);
                    tuning.addProperty("eligibilityFloor", 0);
                    tuning.addProperty(
                            "corridorHalfWidth", .45 * tuning.get("spacing").getAsDouble());
                    ContinentalOutlineMap outline =
                            new ContinentalOutlineMap(8675309, heights, json);
                    ContinentalOutlineMap.Sample point = new ContinentalOutlineMap.Sample();
                    double edge = tuning.get("corridorHalfWidth").getAsDouble(), z = Double.NaN;
                    for (int n = -12000; n <= 12000; n += 16) {
                        outline.sample(edge, n, point);
                        if (point.coastCoordinate() != 0) continue;
                        outline.sample(edge + outline.landBlend / 2, n, point);
                        if (point.landWeight() != .5) continue;
                        outline.sample(edge + outline.landBlend, n, point);
                        if (point.landWeight() == 1) {
                            z = n;
                            break;
                        }
                    }
                    require(Double.isFinite(z), "No exact half/full coastal control for " + family);
                    JsonObject elevationJson = Rules.load("riven-base-elevation.json", 1).json;
                    elevationJson.addProperty("stateOffset", 0);
                    elevationJson.addProperty("upliftWeight", 0);
                    elevationJson.addProperty("regionalWeight", 0);
                    BaseElevationMap elevation =
                            new BaseElevationMap(
                                    8675309, new PlateMap(8675309), heights, elevationJson);
                    ClimateStructureMap map = new ClimateStructureMap(outline, elevation, heights);
                    ClimateStructureMap.Sample sample = new ClimateStructureMap.Sample();
                    double expected = offset < 0 ? 0 : offset == 0 ? 1 : 8;
                    map.sample(edge + outline.landBlend, z, sample);
                    require(
                            map.datumY == datum
                                    && sample.status() == ClimateStructureMap.Status.LAND
                                    && sample.landWeight() == 1
                                    && sample.elevationSampled()
                                    && sample.meanSolidY() == datum + offset
                                    && sample.obstructionAboveDatum() == expected,
                            "Full-land solid-top/datum control");
                    map.sample(edge, z, sample);
                    require(
                            sample.status() == ClimateStructureMap.Status.MARINE
                                    && sample.landWeight() == 0
                                    && sample.obstructionAboveDatum() == 0
                                    && !sample.elevationSampled()
                                    && Double.isNaN(sample.meanSolidY()),
                            "Marine failed to clear inland observation");
                    map.sample(30000001, 0, sample);
                    unknown(sample, ClimateStructureMap.Status.OUTSIDE_DOMAIN);
                    map.sample(edge + outline.landBlend / 2, z, sample);
                    require(
                            sample.status() == ClimateStructureMap.Status.LAND
                                    && sample.landWeight() == .5
                                    && sample.obstructionAboveDatum() == expected / 2
                                    && sample.elevationSampled(),
                            "Half-coast control");
                    controls++;
                }
        System.out.println("Known datum/solid-top/half-coast/marine/outside sequences=" + controls);
    }

    public static void main(String[] args) throws Exception {
        knownControls();
        TerrainHeightReference heights = TerrainHeightReference.DEFAULT;
        List<String> rows = new ArrayList<>();
        rows.add(
                "seed,family,x,z,status,landWeight,meanSolidY,obstruction,elevationSampled,unmaskedMeanSolidY,observations");
        int points = 0, marine = 0, land = 0, transition = 0, maskedPositive = 0, skipWitnesses = 0;
        for (Family family : Family.values())
            for (long seed : new long[] {8675309, 7, -918273645}) {
                JsonObject json = Rules.load("riven-continents.json", 1).json;
                json.addProperty("family", family.name());
                json.remove("nativeWater");
                ContinentalOutlineMap outline = new ContinentalOutlineMap(seed, heights, json);
                BaseElevationMap elevation =
                        new BaseElevationMap(seed, new PlateMap(seed), heights);
                ClimateStructureMap map = new ClimateStructureMap(outline, elevation, heights);
                domains(map);
                ClimateStructureMap.Sample sample = new ClimateStructureMap.Sample();
                ContinentalOutlineMap.Sample geographic = new ContinentalOutlineMap.Sample();
                BaseElevationMap.Sample potential = new BaseElevationMap.Sample();
                BaseElevationMap.Sample privateElevation =
                        (BaseElevationMap.Sample) scratch(sample, "elevation");
                List<String> expected = new ArrayList<>();
                boolean witnessedSkip = false, sampledLand = false;
                for (int i = 0; i < 4096; i++) {
                    double x = (i % 64 - 32) * 307.25 + .25, z = (i / 64 - 32) * 311.5 - .5;
                    outline.sample(x, z, geographic);
                    elevation.sample(
                            x, z,
                            potential); // Independent diagnostic/visualization observation only.
                    long retainedMean = Double.doubleToRawLongBits(privateElevation.meanHeight());
                    map.sample(x, z, sample);
                    require(
                            Double.isFinite(sample.obstructionAboveDatum())
                                    && sample.obstructionAboveDatum() >= 0
                                    && sample.obstructionAboveDatum()
                                            <= Math.max(0, potential.meanHeight() + 1 - map.datumY),
                            "Obstruction bounds");
                    require(
                            sample.landWeight() == geographic.landWeight(),
                            "Earlier coastal blend changed");
                    if (geographic.marine()) {
                        require(
                                sample.status() == ClimateStructureMap.Status.MARINE
                                        && sample.obstructionAboveDatum() == 0
                                        && !sample.elevationSampled()
                                        && Double.isNaN(sample.meanSolidY()),
                                "Marine mask/observation");
                        if (sampledLand
                                && Double.doubleToRawLongBits(potential.meanHeight())
                                        != retainedMean) {
                            require(
                                    Double.doubleToRawLongBits(privateElevation.meanHeight())
                                            == retainedMean,
                                    "Marine sampled inland elevation");
                            witnessedSkip = true;
                        }
                        marine++;
                        if (potential.meanHeight() + 1 > map.datumY) maskedPositive++;
                    } else {
                        require(sample.status() == ClimateStructureMap.Status.LAND, "Land status");
                        if (sample.landWeight() > 0) {
                            require(
                                    sample.elevationSampled()
                                            && sample.meanSolidY() == potential.meanHeight(),
                                    "Earlier inland mean changed");
                            sampledLand = true;
                        }
                        land++;
                    }
                    if (sample.landWeight() > 0 && sample.landWeight() < 1) transition++;
                    expected.add(snapshot(sample));
                    rows.add(
                            seed
                                    + ","
                                    + family
                                    + ","
                                    + x
                                    + ","
                                    + z
                                    + ","
                                    + sample.status()
                                    + ","
                                    + sample.landWeight()
                                    + ","
                                    + sample.meanSolidY()
                                    + ","
                                    + sample.obstructionAboveDatum()
                                    + ","
                                    + sample.elevationSampled()
                                    + ","
                                    + potential.meanHeight()
                                    + ","
                                    + expected.get(i));
                    points++;
                }
                require(witnessedSkip, "Missing real marine elevation-skip witness");
                skipWitnesses++;
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
                                    ClimateStructureMap.Sample local =
                                            new ClimateStructureMap.Sample();
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
        require(
                land > 0 && marine > 0 && transition > 0 && maskedPositive > 0,
                "Missing geographic controls");
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
                        + " maskedPositiveInlandPotential="
                        + maskedPositive
                        + " elevationSkipCohorts="
                        + skipWitnesses);
    }
}
