import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Sites;
import cc.orthrus.riven.worldgen.SurfaceCarvers;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen/candidate numeric equivalence only; no block registry, native or DH qualification. */
public final class SurfaceCarverBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int MAXIMUM_DISCOVERY_SAMPLES = 128;

    private static final class Point {
        final long seed;
        final int x, z;
        final String role;

        Point(long seed, int x, int z, String role) {
            this.seed = seed;
            this.x = x;
            this.z = z;
            this.role = role;
        }

        String row() {
            return seed + "," + x + "," + z + "," + role;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static boolean zero(TerrainMap.Sample sample) {
        return sample.carvers.rawCut == 0
                && sample.carvers.appliedCut == 0
                && sample.carvers.dominant == null;
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        Sites sites = maps.terrain.types.sites;
        TerrainMap.Sample sample = new TerrainMap.Sample();
        Point marine = new Point(SEED, 7124, 3179, "MARINE");
        maps.terrain.sample(marine.x, marine.z, sample);
        require(sample.ocean.marine && zero(sample), "Marine zero-cut control no longer qualifies");
        Point dry = null;
        for (int[] position :
                new int[][] {{8102, 4552}, {8200, 4600}, {8500, 4800}, {1500, 1500}}) {
            maps.terrain.sample(position[0], position[1], sample);
            if (!sample.ocean.marine && zero(sample)) {
                dry = new Point(SEED, position[0], position[1], "DRY_ZERO");
                break;
            }
        }
        require(dry != null, "No actual dry zero-cut control found");
        List<Point> witnesses = new ArrayList<>();
        Set<Long> identities = new HashSet<>();
        Set<SurfaceCarvers.Kind> kinds = new HashSet<>();
        TerrainMap.BaseSample anchor = new TerrainMap.BaseSample();
        int samples = 0, admitted = 0;
        // Finite owner search, rather than an expensive full-terrain grid. Public site admission
        // filters first; only admitted land anchors receive a small exact native stencil.
        int lastCell = 96;
        search:
        for (int cz = 0; cz <= lastCell; cz++) {
            for (int cx = 0; cx <= lastCell; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.CARVER)) continue;
                admitted++;
                double ax = sites.x(cx, key), az = sites.z(cz, key);
                maps.terrain.intent(ax, az, anchor);
                if (anchor.ocean.marine) continue;
                for (int[] offset : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    require(samples++ < MAXIMUM_DISCOVERY_SAMPLES, "Carver discovery sample cap");
                    int x = (int) Math.floor(ax + offset[0] * sites.maximumRadius * .2),
                            z = (int) Math.floor(az + offset[1] * sites.maximumRadius * .2);
                    maps.terrain.sample(x, z, sample);
                    SurfaceCarvers.Result carver = sample.carvers;
                    if (carver.appliedCut <= 1e-6 || !identities.add(carver.featureId)) continue;
                    require(carver.dominant != null, "Positive cut has no selected profile");
                    kinds.add(carver.dominant);
                    witnesses.add(new Point(SEED, x, z, "POSITIVE"));
                    witnesses.add(marine);
                    witnesses.add(dry);
                    System.out.println(
                            "Positive witness "
                                    + x
                                    + ","
                                    + z
                                    + " "
                                    + carver.dominant
                                    + " feature="
                                    + Long.toHexString(carver.featureId));
                    if (identities.size() >= 3 && kinds.size() >= 2) break search;
                    break;
                }
            }
        }
        require(
                identities.size() >= 3 && kinds.size() >= 2,
                "Insufficient positive carver coverage");
        witnesses.add(new Point(SEED, -1, -1, "BOUNDARY"));
        witnesses.add(new Point(SEED, -16, -16, "BOUNDARY"));
        witnesses.add(new Point(SEED, 0, 0, "BOUNDARY"));
        try (BufferedWriter writer = newFile(output)) {
            writer.write("seed,x,z,role\n");
            for (Point point : witnesses) {
                writer.write(point.row());
                writer.newLine();
            }
        }
        System.out.println(
                "Discovered "
                        + identities.size()
                        + " positive owners, "
                        + kinds.size()
                        + " profiles; admittedSites="
                        + admitted
                        + " fullSamples="
                        + samples);
    }

    private static BufferedWriter newFile(Path path) throws Exception {
        return Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String snapshot(Point point, int stage, TerrainMap.Sample sample) {
        SurfaceCarvers.Result carver = sample.carvers;
        StringBuilder row =
                new StringBuilder(point.row())
                        .append(',')
                        .append(stage)
                        .append(',')
                        .append(bits(sample.height))
                        .append(',')
                        .append(sample.waterY())
                        .append(',')
                        .append(sample.water.aquiferMin)
                        .append(',')
                        .append(sample.water.aquiferMax)
                        .append(',')
                        .append(sample.ocean.marine)
                        .append(',')
                        .append(sample.ocean.supplied)
                        .append(',')
                        .append(bits(sample.ocean.coastCoordinate))
                        .append(',')
                        .append(bits(sample.baseHeight))
                        .append(',')
                        .append(bits(sample.baseDx))
                        .append(',')
                        .append(bits(sample.baseDz))
                        .append(',')
                        .append(bits(sample.minimumY))
                        .append(',')
                        .append(bits(sample.maximumY))
                        .append(',')
                        .append(sample.climate.region)
                        .append(',')
                        .append(bits(carver.height))
                        .append(',')
                        .append(bits(carver.rawCut))
                        .append(',')
                        .append(bits(carver.appliedCut))
                        .append(',')
                        .append(bits(carver.dominantCut))
                        .append(',')
                        .append(bits(carver.anchorX))
                        .append(',')
                        .append(bits(carver.anchorZ))
                        .append(',')
                        .append(carver.dominant == null ? 0 : carver.dominant.id)
                        .append(',')
                        .append(carver.anchorRegion)
                        .append(',')
                        .append(Long.toHexString(carver.featureId))
                        .append(',')
                        .append(carver.overlaps);
        for (double cut : carver.cuts) row.append(',').append(bits(cut));
        return row.toString();
    }

    private static void evaluate(
            GenerationMaps maps, Point point, int stage, TerrainMap.Sample out) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(point.x, point.z, out);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(point.x, point.z, out);
        else maps.terrain.sample(point.x, point.z, out);
        require(
                Double.isFinite(out.height) && Double.isFinite(out.carvers.height),
                "Invalid height");
        if (stage == 0) require(zero(out), "Earlier prefix exposed a later carver result");
        if (stage == 2 && point.role.equals("POSITIVE"))
            require(out.carvers.appliedCut > 1e-6, "Frozen positive witness lost its cut");
        if (stage == 2 && point.role.equals("MARINE"))
            require(out.ocean.marine && zero(out), "Frozen marine control no longer qualifies");
        if (stage == 2 && point.role.equals("DRY_ZERO"))
            require(!out.ocean.marine && zero(out), "Frozen dry control no longer qualifies");
    }

    private static void capture(Path input, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.startsWith("seed,")) continue;
            String[] values = row.split(",");
            require(values.length == 4, "Invalid frozen witness row");
            points.add(
                    new Point(
                            Long.parseLong(values[0]),
                            Integer.parseInt(values[1]),
                            Integer.parseInt(values[2]),
                            values[3]));
        }
        require(!points.isEmpty(), "Empty witness manifest");
        Map<Long, GenerationMaps> maps = new HashMap<>();
        List<String> reference = new ArrayList<>();
        Set<Long> owners = new HashSet<>();
        Set<SurfaceCarvers.Kind> kinds = new HashSet<>();
        for (Point point : points) {
            if (!maps.containsKey(point.seed)) maps.put(point.seed, new GenerationMaps(point.seed));
            for (int stage = 0; stage < 3; stage++) {
                TerrainMap.Sample fresh = new TerrainMap.Sample();
                evaluate(maps.get(point.seed), point, stage, fresh);
                reference.add(snapshot(point, stage, fresh));
                if (stage == 2 && point.role.equals("POSITIVE")) {
                    owners.add(fresh.carvers.featureId);
                    kinds.add(fresh.carvers.dominant);
                }
            }
        }
        require(
                owners.size() >= 3 && kinds.size() >= 2,
                "Missing actual positive owner/profile coverage");
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++) {
            for (int offset = 0; offset < reference.size(); offset++) {
                int index = pass == 0 ? offset : reference.size() - offset - 1;
                Point point = points.get(index / 3);
                int stage = index % 3;
                evaluate(maps.get(point.seed), point, stage, reused);
                require(
                        reference.get(index).equals(snapshot(point, stage, reused)),
                        "Scratch/order mismatch at " + point.row() + " stage=" + stage);
            }
        }
        try (BufferedWriter writer = newFile(output)) {
            writer.write(
                    "seed,x,z,role,stage,heightBits,waterY,aquiferMin,aquiferMax,marine,supplied,coastBits,"
                            + "baseHeightBits,baseDxBits,baseDzBits,minBits,maxBits,climate,carverHeightBits,"
                            + "rawCutBits,appliedCutBits,dominantCutBits,anchorXBits,anchorZBits,kind,"
                            + "anchorClimate,featureId,overlaps");
            for (SurfaceCarvers.Kind kind : SurfaceCarvers.Kind.values())
                writer.write(",cutBits" + kind.id);
            writer.newLine();
            for (String row : reference) {
                writer.write(row);
                writer.newLine();
            }
        }
        System.out.println(
                "PASS numeric/prefix capture: rows="
                        + reference.size()
                        + " positiveOwners="
                        + owners.size()
                        + " kinds="
                        + kinds
                        + "; fresh/forward/reverse agree. "
                        + "No native block, biome-registry, fluid-topology, DH or performance claim.");
        System.out.println(
                "Terrain="
                        + TerrainMap.VERSION
                        + " reference="
                        + TerrainHeightReference.DEFAULT.fingerprint);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("discover")) discover(Paths.get(args[1]));
        else if (args.length == 3 && args[0].equals("capture"))
            capture(Paths.get(args[1]), Paths.get(args[2]));
        else
            throw new IllegalArgumentException(
                    "discover <new-witnesses.csv> | capture <witnesses.csv> <new-snapshot.csv>");
    }
}
