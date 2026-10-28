import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Shore;
import cc.orthrus.riven.worldgen.Sites;
import cc.orthrus.riven.worldgen.Terrain;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
import cc.orthrus.riven.worldgen.TerrainMap;
import cc.orthrus.riven.worldgen.VolcanicTerrain;

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
import java.util.TreeMap;

/** Selected volcanic signed-relief and parent parity; no native blocks or performance qualification. */
public final class VolcanicBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int SAMPLE_CAP = 128, LAST_CELL = 512;
    private static final String MANIFEST = "seed,cellX,cellZ,x,z,role,featureId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,heightBits,parentHeightBits,volcanicHeightBits,deltaBits,weightBits,anchorXBits,anchorZBits,"
                    + "minPotentialBits,maxColumnSlopeBits,candidates,contributors,certificateColumns,stencilColumns,"
                    + "volcanicId,dominant,reason,baseHeightBits,minBits,maxBits,marine,supplied,shoreKind,shoreHeightBits,"
                    + "waterFacts,coldHeightBits,plateauHeightBits,mountainHeightBits,hillHeightBits,drylandHeightBits,"
                    + "drylandDeltaBits,drylandWeightBits,drylandFitBits,drylandReason,coverReason,bareStone";

    private static final class Point {
        final int cx, cz;
        final double x, z;
        final String role;
        final long id;

        Point(int cx, int cz, double x, double z, String role, long id) {
            this.cx = cx;
            this.cz = cz;
            this.x = x;
            this.z = z;
            this.role = role;
            this.id = id;
        }

        String row() {
            return join(",", SEED, cx, cz, x, z, role, Long.toHexString(id));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    private static String join(String separator, Object... fields) {
        StringBuilder out = new StringBuilder();
        for (Object field : fields) {
            if (out.length() > 0) out.append(separator);
            out.append(field);
        }
        return out.toString();
    }

    private static boolean cleared(VolcanicTerrain.Result r) {
        return cleared(r, "NO_SITE");
    }

    private static boolean cleared(VolcanicTerrain.Result r, String reason) {
        return r.delta == 0
                && r.weight == 0
                && r.anchorX == 0
                && r.anchorZ == 0
                && r.minPotential == 0
                && r.maxColumnSlope == 0
                && r.candidates == 0
                && r.contributors == 0
                && r.certificateColumns == 0
                && r.stencilColumns == 0
                && r.id == 0
                && r.dominant == null
                && r.reason.equals(reason);
    }

    private static final class Discovery {
        final GenerationMaps maps = new GenerationMaps(SEED);
        final List<Point> points = new ArrayList<>();
        final TreeMap<String, Integer> reasons = new TreeMap<>();
        int samples, admitted, landAnchors;
        Point raise, lower, rejection, dry, dryProtected;

        TerrainMap.Sample sample(double x, double z) {
            require(samples < SAMPLE_CAP, "Full-terrain discovery sample cap");
            samples++;
            TerrainMap.Sample s = new TerrainMap.Sample();
            maps.terrain.sampleBeforeRocky(x, z, s);
            reasons.put(s.volcanic.reason, reasons.getOrDefault(s.volcanic.reason, 0) + 1);
            return s;
        }

        void consider(int cx, int cz, int x, int z, TerrainMap.Sample s) {
            VolcanicTerrain.Result r = s.volcanic;
            if (r.delta > 1e-6 && raise == null) raise = new Point(cx, cz, x, z, "RAISE", r.id);
            if (r.delta < -1e-6 && lower == null) lower = new Point(cx, cz, x, z, "LOWER", r.id);
            if (r.certificateColumns > 0
                    && r.contributors == 0
                    && r.delta == 0
                    && !r.reason.equals("ACCEPTED")
                    && rejection == null) rejection = new Point(cx, cz, x, z, "INTERNAL_REJECT", 0);
            // The fixed dry hints went stale with the outline changes; take one from discovery.
            if (dry == null && !s.ocean.marine && cleared(r))
                dry = new Point(cx, cz, x, z, "DRY_NO_SITE", 0);
            if (dryProtected == null && !s.ocean.marine && cleared(r, "COLUMN_PROTECTED"))
                dryProtected = new Point(cx, cz, x, z, "DRY_PROTECTED", 0);
            System.out.println(
                    join(
                            " ",
                            "Volcanic probe",
                            samples,
                            "cell=" + cx + "/" + cz,
                            "native=" + x + "/" + z,
                            r.reason,
                            "delta=" + r.delta,
                            "columns=" + r.certificateColumns,
                            "stencils=" + r.stencilColumns));
        }

        void report() {
            System.out.println(
                    join(
                            " ",
                            "Bounded volcanic discovery:",
                            "finiteCells=0.." + LAST_CELL,
                            "admittedOwners=" + admitted,
                            "landAnchors=" + landAnchors,
                            "fullSamples=" + samples,
                            "sampleCap=" + SAMPLE_CAP,
                            "raise=" + (raise != null),
                            "lower=" + (lower != null),
                            "internalReject=" + (rejection != null),
                            "reasons=" + reasons));
        }
    }

    private static void discover(Path output) throws Exception {
        Discovery d = new Discovery();
        Sites sites = d.maps.terrain.types.sites;
        TerrainMap.Sample marine = d.sample(7124, 3179);
        require(marine.ocean.marine && cleared(marine.volcanic), "Marine reset control changed");
        for (int[] v : new int[][] {{8102, 4552}, {8200, 4600}, {8500, 4800}, {1500, 1500}}) {
            TerrainMap.Sample s = d.sample(v[0], v[1]);
            if (!s.ocean.marine && cleared(s.volcanic)) {
                d.dry = new Point(0, 0, v[0], v[1], "DRY_NO_SITE", 0);
                break;
            }
        }
        TerrainMap.BaseSample anchor = new TerrainMap.BaseSample();
        search:
        for (int cz = 0; cz <= LAST_CELL; cz++)
            for (int cx = 0; cx <= LAST_CELL; cx++) {
                if (d.samples == SAMPLE_CAP) break search;
                long key = sites.key(cx, cz);
                if (!sites.admits(cx, cz, key, Sites.Lane.VOLCANIC)) continue;
                d.admitted++;
                double ax = sites.x(cx, key), az = sites.z(cz, key);
                d.maps.terrain.intent(ax, az, anchor);
                if (anchor.ocean.marine) continue;
                d.landAnchors++;
                int x = (int) Math.floor(ax), z = (int) Math.floor(az);
                TerrainMap.Sample center = d.sample(x, z);
                d.consider(cx, cz, x, z, center);
                if (center.volcanic.reason.equals("ACCEPTED") || center.volcanic.contributors > 0) {
                    // At most 24 additional native samples, bounded relative to this admitted
                    // support.
                    double radius = sites.maximumRadius(Sites.Lane.VOLCANIC);
                    for (double fraction : new double[] {.125, .25, .5})
                        for (int n = 0; n < 8; n++) {
                            if (d.samples == SAMPLE_CAP) break search;
                            double angle = n * Math.PI / 4;
                            int px = (int) Math.floor(ax + Math.cos(angle) * radius * fraction),
                                    pz = (int) Math.floor(az + Math.sin(angle) * radius * fraction);
                            TerrainMap.Sample s = d.sample(px, pz);
                            d.consider(cx, cz, px, pz, s);
                            if (d.raise != null && d.lower != null && d.rejection != null)
                                break search;
                        }
                }
                if (d.raise != null && d.rejection != null) break search;
            }
        // Most land is shore-protected, which short-circuits before the site query. Filter inland
        // candidates through cheap earlier intent, then spend at most eight full samples.
        TerrainMap.BaseSample probe = new TerrainMap.BaseSample();
        double reservation = Terrain.shoreReservation(TerrainHeightReference.DEFAULT);
        int dryProbes = 0;
        dry:
        for (int z = 0; d.dry == null && z <= LAST_CELL * 288; z += 1024)
            for (int x = 0; x <= LAST_CELL * 288; x += 1024) {
                d.maps.terrain.intent(x, z, probe);
                if (probe.ocean.marine
                        || probe.shore.kind != Shore.Kind.INLAND
                        || probe.shore.distance <= reservation) continue;
                if (dryProbes++ == 8 || d.samples == SAMPLE_CAP) break dry;
                TerrainMap.Sample s = d.sample(x, z);
                if (!s.ocean.marine && cleared(s.volcanic)) {
                    d.dry = new Point(0, 0, x, z, "DRY_NO_SITE", 0);
                    break dry;
                }
            }
        d.report();
        require(d.rejection != null, "Missing internal earlier-reading volcanic rejection");
        // As in the cold, plateau and mountain checks, absent production relief is a reported
        // coverage gap (docs/FEATURES.md); the manifest then fits capture-rejections.
        if (d.raise == null) {
            require(d.dryProtected != null, "Missing protected dry control");
            System.out.println(
                    "Coverage gap: no applied production volcanic formation qualified; use capture-rejections.");
        } else {
            require(d.dry != null, "Missing dry no-site control");
            d.points.add(d.raise);
        }
        if (d.lower != null) d.points.add(d.lower);
        d.points.add(d.rejection);
        Point fractional = d.raise != null ? d.raise : d.rejection;
        d.points.add(
                new Point(
                        fractional.cx,
                        fractional.cz,
                        fractional.x + .25,
                        fractional.z + .5,
                        "FRACTIONAL",
                        0));
        d.points.add(new Point(0, 0, 7124, 3179, "MARINE", 0));
        d.points.add(d.raise == null ? d.dryProtected : d.dry);
        for (int[] v : new int[][] {{-1, -1}, {-16, -16}, {-17, -16}, {0, 0}})
            d.points.add(new Point(0, 0, v[0], v[1], "BOUNDARY", 0));
        List<String> rows = new ArrayList<>();
        for (Point q : d.points) rows.add(q.row());
        write(output, MANIFEST, rows);
    }

    private static void evaluate(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(q.x, q.z, s);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(q.x, q.z, s);
        else maps.terrain.sample(q.x, q.z, s);
        require(
                Double.isFinite(s.height) && Double.isFinite(s.volcanic.height),
                "Nonfinite height");
        VolcanicTerrain.Result r = s.volcanic;
        if (stage == 0)
            require(
                    cleared(r) && bits(r.height).equals(bits(s.height)),
                    "Pre-major volcanic state not reset");
        if (stage == 0) return;
        if (q.role.equals("RAISE"))
            require(
                    r.delta > 1e-6
                            && r.id == q.id
                            && r.dominant != null
                            && r.certificateColumns > 0,
                    "Frozen positive volcanic relief disappeared");
        if (q.role.equals("LOWER"))
            require(
                    r.delta < -1e-6
                            && r.id == q.id
                            && r.dominant != null
                            && r.certificateColumns > 0,
                    "Frozen negative volcanic relief disappeared");
        if (q.role.equals("INTERNAL_REJECT"))
            require(
                    r.certificateColumns > 0
                            && r.contributors == 0
                            && r.delta == 0
                            && !r.reason.equals("ACCEPTED"),
                    "Frozen internal certificate rejection disappeared");
        if (q.role.equals("FRACTIONAL"))
            require(
                    !s.ocean.marine && r.delta == 0 && r.reason.equals("COLUMN_DOMAIN"),
                    "Fractional native-domain control changed");
        if (q.role.equals("MARINE")) require(s.ocean.marine && cleared(r), "Marine reset changed");
        if (q.role.equals("DRY_NO_SITE"))
            require(!s.ocean.marine && cleared(r), "Dry no-site control changed");
        if (q.role.equals("DRY_PROTECTED"))
            require(
                    !s.ocean.marine && cleared(r, "COLUMN_PROTECTED"),
                    "Nonmarine protected-column control changed");
    }

    private static String snapshot(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        TerrainMap.Sample parent = new TerrainMap.Sample();
        maps.terrain.sampleBeforeMajorFeatures(q.x, q.z, parent);
        VolcanicTerrain.Result r = s.volcanic;
        if (stage > 0)
            require(
                    bits(r.height).equals(bits(parent.height + r.delta)),
                    "Volcanic parent/application relation changed");
        return q.row()
                + ","
                + join(
                        ",",
                        stage,
                        bits(s.height),
                        bits(parent.height),
                        bits(r.height),
                        bits(r.delta),
                        bits(r.weight),
                        bits(r.anchorX),
                        bits(r.anchorZ),
                        bits(r.minPotential),
                        bits(r.maxColumnSlope),
                        r.candidates,
                        r.contributors,
                        r.certificateColumns,
                        r.stencilColumns,
                        Long.toHexString(r.id),
                        r.dominant,
                        r.reason,
                        bits(s.baseHeight),
                        bits(s.minimumY),
                        bits(s.maximumY),
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
                        bits(s.cold.height),
                        bits(s.plateaus.height),
                        bits(s.mountains.height),
                        bits(s.hills.height),
                        bits(s.drylands.height),
                        bits(s.drylands.delta),
                        bits(s.drylands.weight),
                        bits(s.drylands.fit),
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

    private static void capture(Path input, Path output, boolean requirePositive) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.equals(MANIFEST)) continue;
            String[] v = row.split(",", -1);
            require(v.length == 7 && Long.parseLong(v[0]) == SEED, "Invalid frozen witness row");
            points.add(
                    new Point(
                            Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]),
                            Double.parseDouble(v[3]),
                            Double.parseDouble(v[4]),
                            v[5],
                            Long.parseUnsignedLong(v[6], 16)));
        }
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
        require(
                roles.contains("INTERNAL_REJECT")
                        && roles.contains("FRACTIONAL")
                        && roles.contains("MARINE")
                        && roles.contains("BOUNDARY"),
                "Missing actual volcanic/control coverage");
        if (requirePositive)
            require(
                    roles.contains("RAISE") && roles.contains("DRY_NO_SITE"),
                    "Missing actual positive volcanic or dry no-site coverage");
        else
            require(
                    roles.contains("DRY_PROTECTED")
                            && !roles.contains("RAISE")
                            && !roles.contains("LOWER")
                            && !roles.contains("DRY_NO_SITE"),
                    "Rejection-only capture requires protected dry control and forbids relief/no-site roles");
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
                (requirePositive
                                ? "PASS selected volcanic capture rows="
                                : "PASS volcanic rejection-only capture rows=")
                        + rows.size()
                        + " roles="
                        + roles
                        + "; all public volcanic observations and selected downstream facts; fresh/reused forward/reverse agree.");
        if (!requirePositive)
            System.out.println(
                    "Scope: earlier-parent rejection/control parity only. No accepted volcanic relief, family occurrence or successful certificate is qualified.");
        System.out.println(
                "Fresh means fresh mutable Sample; maps/caches reused. No all-family occurrence, cold-cache, eviction, concurrency, topology, native blocks, DH or performance qualification.");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("discover")) discover(Paths.get(args[1]));
        else if (args.length == 3 && args[0].equals("capture"))
            capture(Paths.get(args[1]), Paths.get(args[2]), true);
        else if (args.length == 3 && args[0].equals("capture-rejections"))
            capture(Paths.get(args[1]), Paths.get(args[2]), false);
        else
            throw new IllegalArgumentException(
                    "discover <new-witnesses.csv> | capture <witnesses.csv> <new-snapshot.csv> | capture-rejections <rejection-witnesses.csv> <new-snapshot.csv>");
    }
}
