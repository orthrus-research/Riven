import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.MountainTerrain;
import cc.orthrus.riven.worldgen.Sites;
import cc.orthrus.riven.worldgen.TerrainMap;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finite integer-only public-API differential; no positive occurrence is assumed. */
public final class MountainBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int OWNER_CAP = 8, LAST_CELL = 128;
    private static final String MANIFEST = "seed,cellX,cellZ,x,z,role,expectedReason,expectedId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,heightBits,parentHeightBits,mountainHeightBits,deltaBits,weightBits,shapeBits,ridgeBits,passBits,"
                    + "anchorXBits,anchorZBits,candidates,contributors,mountainId,dominant,reason,temperatureBits,moistureBits,"
                    + "baseHeightBits,minBits,maxBits,landHighBits,upliftBits,broadDxBits,broadDzBits,marine,supplied,shoreKind,"
                    + "shoreHeightBits,waterFacts,volcanicHeightBits,volcanicDeltaBits,volcanicReason,coldHeightBits,coldDeltaBits,"
                    + "coldReason,plateauDeltaBits,plateauReason,hillHeightBits,landmarkHeightBits,carverHeightBits,"
                    + "drylandHeightBits,drylandDeltaBits,drylandWeightBits,drylandReason,coverReason,bareStone";

    private static final class Point {
        final int cx, cz, x, z;
        final String role, reason;
        final long id;

        Point(int cx, int cz, int x, int z, String role, String reason, long id) {
            this.cx = cx;
            this.cz = cz;
            this.x = x;
            this.z = z;
            this.role = role;
            this.reason = reason;
            this.id = id;
        }

        String row() {
            return join(",", SEED, cx, cz, x, z, role, reason, Long.toHexString(id));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String join(String separator, Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static boolean reset(MountainTerrain.Result r) {
        return r.delta == 0
                && r.weight == 0
                && r.shape == 0
                && r.ridge == 0
                && r.pass == 0
                && r.anchorX == 0
                && r.anchorZ == 0
                && r.candidates == 0
                && r.contributors == 0
                && r.id == 0
                && r.dominant == null
                && r.reason.equals("NO_SITE");
    }

    private static Point observe(GenerationMaps maps, int cx, int cz, int x, int z, boolean owner) {
        TerrainMap.Sample s = new TerrainMap.Sample();
        maps.terrain.sampleBeforeRocky(x, z, s);
        MountainTerrain.Result r = s.mountains;
        String role =
                !owner
                        ? "OBSERVED_CONTROL"
                        : r.contributors > 0 && r.delta > 0 && r.dominant != null
                                ? "APPLIED_OWNER"
                                : "OWNER_OBSERVATION";
        Point q = new Point(cx, cz, x, z, role, r.reason, r.id);
        System.out.println(
                join(
                        " ",
                        "Mountain observed:",
                        q.row(),
                        "marine=" + s.ocean.marine,
                        "parent=" + s.plateaus.height,
                        "delta=" + r.delta,
                        "candidates=" + r.candidates,
                        "contributors=" + r.contributors));
        return q;
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        List<Point> points = new ArrayList<>();
        // Requalify these existing coordinates before recording any expected reason.
        for (int[] p :
                new int[][] {
                    {7124, 3179}, {8102, 4552}, {75891, 3626}, {-1, -1}, {-17, -16}, {0, 0}
                }) points.add(observe(maps, 0, 0, p[0], p[1], false));
        Sites sites = maps.terrain.types.sites;
        int owners = 0;
        search:
        for (int cz = 0; cz <= LAST_CELL; cz++)
            for (int cx = 0; cx <= LAST_CELL; cx++) {
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.MOUNTAINS)) continue;
                if (owners == OWNER_CAP) break search;
                owners++;
                double ax = sites.x(cx, key), az = sites.z(cz, key);
                points.add(observe(maps, cx, cz, (int) Math.floor(ax), (int) Math.floor(az), true));
            }
        int applied = 0;
        Set<String> observedReasons = new HashSet<>();
        for (Point q : points) {
            if (q.role.equals("APPLIED_OWNER")) applied++;
            observedReasons.add(q.reason);
        }
        // The public Result has no certificate/read count; do not infer internal read coverage.
        System.out.println(
                join(
                        " ",
                        "Finite mountain discovery:",
                        "siteCells=0.." + LAST_CELL,
                        "explicitOwnerProbes=" + owners,
                        "ownerCap=" + OWNER_CAP,
                        "knownControlProbes=6",
                        "totalFullTerrainProbes=" + points.size(),
                        "actualAppliedOwners=" + applied,
                        "observedReasons=" + observedReasons));
        if (applied == 0)
            System.out.println("Coverage gap: no applied production mountain formation qualified.");
        System.out.println(
                "No internal planning-read count is exposed by Mountain.Result; observed reasons do not establish that coverage. No search expansion.");
        List<String> rows = new ArrayList<>();
        for (Point q : points) rows.add(q.row());
        write(output, MANIFEST, rows);
    }

    private static void evaluate(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(q.x, q.z, s);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(q.x, q.z, s);
        else maps.terrain.sample(q.x, q.z, s);
        MountainTerrain.Result r = s.mountains;
        require(Double.isFinite(s.height) && Double.isFinite(r.height), "Nonfinite sampled height");
        if (stage == 0)
            require(
                    reset(r) && bits(r.height).equals(bits(s.height)),
                    "Pre-major mountain reset changed");
        else {
            require(
                    r.reason.equals(q.reason) && r.id == q.id,
                    "Frozen integer mountain decision changed: " + q.row());
            if (q.role.equals("APPLIED_OWNER"))
                require(
                        r.contributors > 0 && r.delta > 0 && r.dominant != null,
                        "Applied owner disappeared");
            require(
                    bits(r.height).equals(bits(s.plateaus.height + r.delta)),
                    "Mountain parent/application relation changed");
        }
    }

    private static String snapshot(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        MountainTerrain.Result r = s.mountains;
        return q.row()
                + ","
                + join(
                        ",",
                        stage,
                        bits(s.height),
                        bits(s.plateaus.height),
                        bits(r.height),
                        bits(r.delta),
                        bits(r.weight),
                        bits(r.shape),
                        bits(r.ridge),
                        bits(r.pass),
                        bits(r.anchorX),
                        bits(r.anchorZ),
                        r.candidates,
                        r.contributors,
                        Long.toHexString(r.id),
                        r.dominant,
                        r.reason,
                        bits(s.climate.celsius),
                        bits(s.climate.moisture),
                        bits(s.baseHeight),
                        bits(s.minimumY),
                        bits(s.maximumY),
                        bits(s.land.high),
                        bits(s.uplift),
                        bits(s.broadDx),
                        bits(s.broadDz),
                        s.ocean.marine,
                        s.ocean.supplied,
                        s.shore.kind,
                        bits(s.shore.height),
                        join(
                                "|",
                                s.water.kind,
                                s.water.wet,
                                Long.toHexString(s.water.id),
                                s.water.waterY,
                                bits(s.water.floor),
                                bits(s.water.bank),
                                bits(s.water.weight),
                                bits(s.water.head),
                                s.water.bed,
                                s.water.aquiferMin,
                                s.water.aquiferMax,
                                s.water.aquiferRoof,
                                Long.toHexString(s.water.aquiferId)),
                        bits(s.volcanic.height),
                        bits(s.volcanic.delta),
                        s.volcanic.reason,
                        bits(s.cold.height),
                        bits(s.cold.delta),
                        s.cold.reason,
                        bits(s.plateaus.delta),
                        s.plateaus.reason,
                        bits(s.hills.height),
                        bits(s.landmarks.height),
                        bits(s.carvers.height),
                        bits(s.drylands.height),
                        bits(s.drylands.delta),
                        bits(s.drylands.weight),
                        s.drylands.reason,
                        maps.surfaces.reason(s),
                        maps.surfaces.bareStone(s));
    }

    private static void write(Path output, String header, List<String> rows) throws Exception {
        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            writer.write(header);
            writer.newLine();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
    }

    private static void capture(Path input, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.equals(MANIFEST)) continue;
            String[] v = row.split(",", -1);
            require(v.length == 8 && Long.parseLong(v[0]) == SEED, "Invalid integer witness row");
            points.add(
                    new Point(
                            Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]),
                            Integer.parseInt(v[3]),
                            Integer.parseInt(v[4]),
                            v[5],
                            v[6],
                            Long.parseUnsignedLong(v[7], 16)));
        }
        require(
                !points.isEmpty() && points.size() <= 6 + OWNER_CAP,
                "Unexpected finite witness size");
        GenerationMaps maps = new GenerationMaps(SEED);
        List<String> rows = new ArrayList<>();
        Set<String> roles = new HashSet<>();
        for (Point q : points) {
            for (int stage = 0; stage < 3; stage++) {
                TerrainMap.Sample fresh = new TerrainMap.Sample();
                evaluate(maps, q, stage, fresh);
                rows.add(snapshot(maps, q, stage, fresh));
            }
            roles.add(q.role);
        }
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++)
            for (int offset = 0; offset < rows.size(); offset++) {
                int index = pass == 0 ? offset : rows.size() - offset - 1;
                Point q = points.get(index / 3);
                int stage = index % 3;
                evaluate(maps, q, stage, reused);
                require(
                        rows.get(index).equals(snapshot(maps, q, stage, reused)),
                        "Fresh/reused order mismatch: " + q.row());
            }
        write(output, HEADER, rows);
        System.out.println(
                "PASS finite integer mountain capture rows="
                        + rows.size()
                        + " roles="
                        + roles
                        + "; all 13 public mountain fields and selected downstream facts; fresh/reused forward/reverse agree.");
        System.out.println(
                "No positive/internal coverage inferred from controls. Maps reused; no cold-cache, eviction, concurrency, topology, native blocks, DH or performance qualification.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("discover")) discover(Paths.get(args[1]));
        else if (args.length == 3 && args[0].equals("capture"))
            capture(Paths.get(args[1]), Paths.get(args[2]));
        else
            throw new IllegalArgumentException(
                    "discover <new-integer-witnesses.csv> | capture <integer-witnesses.csv> <new-snapshot.csv>");
    }
}
