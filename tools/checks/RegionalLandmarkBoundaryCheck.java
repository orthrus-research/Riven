import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.RegionalLandmarks;
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

/** Selected signed-relief and downstream numeric parity; no native block or DH qualification. */
public final class RegionalLandmarkBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int MAXIMUM_DISCOVERY_SAMPLES = 128;
    private static final int[][] STENCIL = {
        {0, 0},
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1},
        {2, 0},
        {-2, 0},
        {0, 2},
        {0, -2},
        {1, 1},
        {-1, 1},
        {1, -1},
        {-1, -1}
    };

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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean zero(double value) {
        return Double.doubleToRawLongBits(value) == 0;
    }

    /** A missing or earlier-stage landmark must clear all public identity and relief observations. */
    private static boolean cleared(RegionalLandmarks.Result result) {
        if (!zero(result.rawDisplacement)
                || !zero(result.appliedDisplacement)
                || !zero(result.dominantContribution)
                || !zero(result.anchorX)
                || !zero(result.anchorZ)
                || result.dominant != null
                || result.anchorRegion != null
                || result.variant != -1
                || result.featureId != 0
                || result.overlaps != 0) return false;
        for (double contribution : result.contributions) if (!zero(contribution)) return false;
        return true;
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        Sites sites = maps.terrain.types.sites;
        TerrainMap.Sample sample = new TerrainMap.Sample();
        int fullSamples = 0;
        Point marine = new Point(SEED, 7124, 3179, "MARINE");
        require(fullSamples++ < MAXIMUM_DISCOVERY_SAMPLES, "Discovery sample cap");
        maps.terrain.sample(marine.x, marine.z, sample);
        require(
                sample.ocean.marine && cleared(sample.landmarks),
                "Marine landmark reset control no longer qualifies");
        Point dry = null;
        for (int[] position :
                new int[][] {{8102, 4552}, {8200, 4600}, {8500, 4800}, {1500, 1500}}) {
            require(fullSamples++ < MAXIMUM_DISCOVERY_SAMPLES, "Discovery sample cap");
            maps.terrain.sample(position[0], position[1], sample);
            if (!sample.ocean.marine && cleared(sample.landmarks)) {
                dry = new Point(SEED, position[0], position[1], "DRY_ZERO");
                break;
            }
        }
        require(dry != null, "No actual dry no-landmark control found");
        int controlSamples = fullSamples;
        List<Point> witnesses = new ArrayList<>();
        Set<Long> identities = new HashSet<>();
        Set<RegionalLandmarks.Family> families = new HashSet<>();
        Set<Long> raisedOwners = new HashSet<>(), loweredOwners = new HashSet<>();
        TerrainMap.BaseSample anchor = new TerrainMap.BaseSample();
        int admitted = 0, landAnchors = 0;
        // Only independently admitted LANDMARK owners receive bounded earlier-intent queries.
        // Full terrain probes are a finite stencil; discovery cannot become a world raster scan.
        search:
        for (int cz = 0; cz <= 512; cz++) {
            for (int cx = 0; cx <= 512; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.LANDMARK)) continue;
                admitted++;
                double ax = sites.x(cx, key), az = sites.z(cz, key);
                maps.terrain.intent(ax, az, anchor);
                if (anchor.ocean.marine) continue;
                landAnchors++;
                for (int[] offset : STENCIL) {
                    require(fullSamples++ < MAXIMUM_DISCOVERY_SAMPLES, "Discovery sample cap");
                    int x = (int) Math.floor(ax + offset[0] * sites.maximumRadius * .25),
                            z = (int) Math.floor(az + offset[1] * sites.maximumRadius * .25);
                    maps.terrain.sample(x, z, sample);
                    RegionalLandmarks.Result result = sample.landmarks;
                    require(
                            Double.isFinite(result.appliedDisplacement),
                            "Nonfinite landmark displacement");
                    boolean raised = result.appliedDisplacement > 1e-6,
                            lowered = result.appliedDisplacement < -1e-6;
                    if (!raised && !lowered) continue;
                    require(result.dominant != null, "Applied relief has no landmark family");
                    // Identity alone must not discard the opposite sign on a basin's rim or bowl.
                    boolean freshSign =
                            (raised ? raisedOwners : loweredOwners).add(result.featureId);
                    if (freshSign) {
                        identities.add(result.featureId);
                        families.add(result.dominant);
                        String role = raised ? "RAISE" : "LOWER";
                        witnesses.add(new Point(SEED, x, z, role));
                        witnesses.add(marine);
                        witnesses.add(dry);
                        System.out.println(
                                role
                                        + " witness "
                                        + x
                                        + ","
                                        + z
                                        + " "
                                        + result.dominant
                                        + " feature="
                                        + Long.toHexString(result.featureId)
                                        + " applied="
                                        + result.appliedDisplacement);
                    }
                    if (identities.size() >= 3
                            && families.size() >= 2
                            && !raisedOwners.isEmpty()
                            && !loweredOwners.isEmpty()) break search;
                    // Basin rims can have the opposite sign to their center. Continue their stencil
                    // until both signs are seen; other selected bodies need only one relief
                    // witness.
                    if (result.dominant != RegionalLandmarks.Family.RIMMED_BASIN
                            || (raisedOwners.contains(result.featureId)
                                    && loweredOwners.contains(result.featureId))) break;
                }
            }
        }
        System.out.println(
                "Bounded landmark discovery: cellRange=0..512 owners="
                        + identities.size()
                        + " families="
                        + families.size()
                        + " raisedOwners="
                        + raisedOwners.size()
                        + " loweredOwners="
                        + loweredOwners.size()
                        + " admittedSites="
                        + admitted
                        + " landAnchors="
                        + landAnchors
                        + " candidateSamples="
                        + (fullSamples - controlSamples)
                        + " controlSamples="
                        + controlSamples
                        + " totalFullSamples="
                        + fullSamples);
        require(
                identities.size() >= 3
                        && families.size() >= 2
                        && !raisedOwners.isEmpty()
                        && !loweredOwners.isEmpty(),
                "Insufficient actual signed landmark owner/family coverage");
        witnesses.add(new Point(SEED, -1, -1, "BOUNDARY"));
        witnesses.add(new Point(SEED, -16, -16, "BOUNDARY"));
        witnesses.add(new Point(SEED, -17, -16, "BOUNDARY"));
        witnesses.add(new Point(SEED, 0, 0, "BOUNDARY"));
        try (BufferedWriter writer = newFile(output)) {
            writer.write("seed,x,z,role\n");
            for (Point point : witnesses) {
                writer.write(point.row());
                writer.newLine();
            }
        }
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

    private static String snapshot(
            GenerationMaps maps, Point point, int stage, TerrainMap.Sample sample) {
        RegionalLandmarks.Result landmark = sample.landmarks;
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
                        .append(bits(landmark.height))
                        .append(',')
                        .append(bits(landmark.rawDisplacement))
                        .append(',')
                        .append(bits(landmark.appliedDisplacement))
                        .append(',')
                        .append(bits(landmark.dominantContribution))
                        .append(',')
                        .append(bits(landmark.anchorX))
                        .append(',')
                        .append(bits(landmark.anchorZ))
                        .append(',')
                        .append(landmark.dominant)
                        .append(',')
                        .append(landmark.variant)
                        .append(',')
                        .append(landmark.anchorRegion)
                        .append(',')
                        .append(Long.toHexString(landmark.featureId))
                        .append(',')
                        .append(landmark.overlaps);
        for (double contribution : landmark.contributions)
            row.append(',').append(bits(contribution));
        row.append(',')
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
        row.append(',')
                .append(bits(sample.drylands.fit))
                .append(',')
                .append(sample.drylands.reason)
                .append(',')
                .append(bits(sample.drylands.delta))
                .append(',')
                .append(maps.surfaces.reason(sample))
                .append(',')
                .append(maps.surfaces.bareStone(sample));
        return row.toString();
    }

    private static void evaluate(
            GenerationMaps maps, Point point, int stage, TerrainMap.Sample out) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(point.x, point.z, out);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(point.x, point.z, out);
        else maps.terrain.sample(point.x, point.z, out);
        require(
                Double.isFinite(out.height)
                        && Double.isFinite(out.landmarks.height)
                        && Double.isFinite(out.carvers.height),
                "Invalid sampled height");
        if (stage == 0)
            require(cleared(out.landmarks), "Earlier prefix exposed a later landmark result");
        if (stage == 2 && point.role.equals("RAISE"))
            require(out.landmarks.appliedDisplacement > 1e-6, "Frozen raised witness lost relief");
        if (stage == 2 && point.role.equals("LOWER"))
            require(
                    out.landmarks.appliedDisplacement < -1e-6,
                    "Frozen lowered witness lost relief");
        if (stage == 2 && point.role.equals("MARINE"))
            require(
                    out.ocean.marine && cleared(out.landmarks),
                    "Frozen marine reset control no longer qualifies");
        if (stage == 2 && point.role.equals("DRY_ZERO"))
            require(
                    !out.ocean.marine && cleared(out.landmarks),
                    "Frozen dry reset control no longer qualifies");
    }

    private static void capture(Path input, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.equals("seed,x,z,role")) continue;
            String[] values = row.split(",");
            require(values.length == 4, "Invalid frozen witness row");
            String role = values[3];
            require(
                    role.equals("RAISE")
                            || role.equals("LOWER")
                            || role.equals("MARINE")
                            || role.equals("DRY_ZERO")
                            || role.equals("BOUNDARY"),
                    "Unknown witness role");
            points.add(
                    new Point(
                            Long.parseLong(values[0]),
                            Integer.parseInt(values[1]),
                            Integer.parseInt(values[2]),
                            role));
        }
        require(!points.isEmpty(), "Empty witness manifest");
        Map<Long, GenerationMaps> maps = new HashMap<>();
        List<String> reference = new ArrayList<>();
        Set<Long> owners = new HashSet<>();
        Set<RegionalLandmarks.Family> families = new HashSet<>();
        boolean raised = false, lowered = false, marine = false, dry = false;
        for (Point point : points) {
            if (!maps.containsKey(point.seed)) maps.put(point.seed, new GenerationMaps(point.seed));
            GenerationMaps current = maps.get(point.seed);
            for (int stage = 0; stage < 3; stage++) {
                TerrainMap.Sample fresh = new TerrainMap.Sample();
                evaluate(current, point, stage, fresh);
                reference.add(snapshot(current, point, stage, fresh));
                if (stage != 2) continue;
                if (point.role.equals("RAISE") || point.role.equals("LOWER")) {
                    require(fresh.landmarks.dominant != null, "Relief has no selected family");
                    owners.add(fresh.landmarks.featureId);
                    families.add(fresh.landmarks.dominant);
                    raised |= point.role.equals("RAISE");
                    lowered |= point.role.equals("LOWER");
                }
                marine |= point.role.equals("MARINE");
                dry |= point.role.equals("DRY_ZERO");
            }
        }
        require(
                owners.size() >= 3 && families.size() >= 2 && raised && lowered && marine && dry,
                "Missing actual signed owner/family or reset-control coverage");
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++) {
            for (int offset = 0; offset < reference.size(); offset++) {
                int index = pass == 0 ? offset : reference.size() - offset - 1;
                Point point = points.get(index / 3);
                int stage = index % 3;
                GenerationMaps current = maps.get(point.seed);
                evaluate(current, point, stage, reused);
                require(
                        reference.get(index).equals(snapshot(current, point, stage, reused)),
                        "Scratch/order mismatch at " + point.row() + " stage=" + stage);
            }
        }
        try (BufferedWriter writer = newFile(output)) {
            writer.write(
                    "seed,x,z,role,stage,heightBits,waterY,aquiferMin,aquiferMax,marine,supplied,coastBits,"
                            + "baseHeightBits,baseDxBits,baseDzBits,minBits,maxBits,climate,landmarkHeightBits,"
                            + "rawDisplacementBits,appliedDisplacementBits,dominantContributionBits,anchorXBits,"
                            + "anchorZBits,family,variant,anchorClimate,featureId,overlaps");
            for (RegionalLandmarks.Family family : RegionalLandmarks.Family.values())
                writer.write(",contributionBits_" + family);
            writer.write(
                    ",carverHeightBits,carverRawCutBits,carverAppliedCutBits,carverDominantCutBits,"
                            + "carverAnchorXBits,carverAnchorZBits,carverKind,carverAnchorClimate,"
                            + "carverFeatureId,carverOverlaps");
            for (SurfaceCarvers.Kind kind : SurfaceCarvers.Kind.values())
                writer.write(",cutBits" + kind.id);
            writer.write(",drylandFitBits,drylandReason,drylandDeltaBits,coverReason,bareStone\n");
            for (String row : reference) {
                writer.write(row);
                writer.newLine();
            }
        }
        System.out.println(
                "PASS signed landmark numeric/prefix capture: rows="
                        + reference.size()
                        + " owners="
                        + owners.size()
                        + " families="
                        + families
                        + "; raise/lower and marine/dry controls, fresh/forward/reverse agree. "
                        + "Maps are reused; no cold-cache, eviction or concurrency claim.");
        System.out.println(
                "Reference="
                        + TerrainHeightReference.DEFAULT.fingerprint
                        + "; selected numerical/cover-policy evidence only; no native block, "
                        + "biome-registry, fluid-topology, DH or performance claim.");
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
