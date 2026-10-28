import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.Lens;
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

/** Selected Hydro dependency parity. No timing, native blocks, global topology or cold-cache claims. */
public final class HydroBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int OWNER_CAP = 128, BODY_PROBE_CAP = 129;
    private static final String MANIFEST = "seed,ownerX,ownerZ,x,z,role,bodyId,lensId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,planFacts,sourceHeadFacts,rawHydroFacts,heightBits,groundwaterHeadBits,finalWaterFacts,"
                    + "potentialBeforeBits,potentialAfterBits,atBeforeBits,atAfterBits,clearanceBits,clearance64Bits,"
                    + "clearanceCellBits,invalidLimitFacts,aquiferCubeFacts,constrainFacts,marine,scaffoldBits,coverReason,bareStone";

    private static final class Point {
        final int cx, cz, x, z;
        final String role;
        final long body, lens;

        Point(int cx, int cz, int x, int z, String role, long body, long lens) {
            this.cx = cx;
            this.cz = cz;
            this.x = x;
            this.z = z;
            this.role = role;
            this.body = body;
            this.lens = lens;
        }

        String row() {
            return join(",", SEED, cx, cz, x, z, role, hex(body), hex(lens));
        }
    }

    private static final class Discovery {
        final GenerationMaps maps = new GenerationMaps(SEED);
        final Set<Long> owners = new HashSet<>();
        int requests, lensCandidates;

        Hydro.Plan owner(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            if (!owners.contains(key)) {
                if (requests == OWNER_CAP) return null;
                owners.add(key);
                requests++;
                if (requests % 8 == 0) report();
            }
            return maps.terrain.hydro.plan(x, z);
        }

        void report() {
            System.out.println(
                    "Hydro bounded discovery: explicitOwnerRequests="
                            + requests
                            + "/"
                            + OWNER_CAP
                            + " independentLensCandidates="
                            + lensCandidates
                            + " bodyProbeCap="
                            + BODY_PROBE_CAP);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String hex(long value) {
        return Long.toHexString(value);
    }

    private static String bits(double value) {
        return hex(Double.doubleToRawLongBits(value));
    }

    private static String join(String separator, Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static boolean groundwater(Hydro.Plan p) {
        for (int i = 0; i < p.basins(); i++)
            if (p.basin(i).supply == Basin.Supply.GROUNDWATER) return true;
        return false;
    }

    private static boolean interval(Hydro.Sample w, long id) {
        return w.aquiferId == id && w.aquiferMin <= w.aquiferMax;
    }

    private static Point wetBody(Discovery d, int cx, int cz, Hydro.Kind kind) {
        Hydro.Plan p = d.owner(cx, cz);
        require(p != null && p.lens != null, "Known body lost its paired lens");
        for (int i = 0; i < p.basins(); i++) {
            Basin b = p.basin(i);
            if (b.kind != kind || b.supply != Basin.Supply.GROUNDWATER) continue;
            int probes = 0;
            for (int ring = 0; ring <= 8; ring++)
                for (int n = 0; n < (ring == 0 ? 1 : 16); n++) {
                    require(probes++ < BODY_PROBE_CAP, "Known-body stencil cap");
                    double angle = n * 2 * Math.PI / 16;
                    int x = (int) Math.floor(b.x + Math.cos(angle) * b.radius * ring / 8),
                            z = (int) Math.floor(b.z + Math.sin(angle) * b.radius * ring / 8);
                    Hydro.Sample w = new Hydro.Sample();
                    d.maps.terrain.hydro.sample(x, z, w);
                    if (!w.wet || w.kind != kind || w.id != b.id || !interval(w, p.lens.id))
                        continue;
                    TerrainMap.Sample finalSample = new TerrainMap.Sample();
                    d.maps.terrain.sample(x, z, finalSample);
                    if (!interval(finalSample.water, p.lens.id)) continue;
                    Point q = new Point(cx, cz, x, z, kind + "_WET", b.id, p.lens.id);
                    System.out.println(
                            "Current groundwater body witness: " + q.row() + " probes=" + probes);
                    return q;
                }
        }
        throw new AssertionError(
                "Known " + kind + " has no actual wet paired-lens witness in bounded stencil");
    }

    private static Point independent(Discovery d, int cx, int cz) {
        Hydro.Plan p = d.owner(cx, cz);
        if (p == null || p.lens == null || groundwater(p)) return null;
        d.lensCandidates++;
        Lens lens = p.lens;
        for (int[] v : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}}) {
            int x = (int) Math.floor(lens.x + v[0] * lens.radius * .25),
                    z = (int) Math.floor(lens.z + v[1] * lens.radius * .25);
            Hydro.Sample raw = new Hydro.Sample();
            d.maps.terrain.hydro.sample(x, z, raw);
            if (!interval(raw, lens.id)) continue;
            TerrainMap.Sample finalSample = new TerrainMap.Sample();
            d.maps.terrain.sample(x, z, finalSample);
            if (!interval(finalSample.water, lens.id)) continue;
            Point q = new Point(cx, cz, x, z, "INDEPENDENT_LENS", 0, lens.id);
            System.out.println("Actual independent lens witness: " + q.row());
            return q;
        }
        System.out.println(
                "Independent lens candidate lacks surviving final interval: " + cx + "," + cz);
        return null;
    }

    private static void discover(Path output) throws Exception {
        Discovery d = new Discovery();
        List<Point> points = new ArrayList<>();
        // Owner -7,-4 holds a groundwater wetland (formerly a pond) since the v34-v36 changes.
        points.add(wetBody(d, -7, -4, Hydro.Kind.WETLAND));
        points.add(wetBody(d, 2, -1, Hydro.Kind.OASIS));
        Point lens = null;
        // Existing nearby owner hints only; each requires a genuine independent final interval.
        for (int[] owner :
                new int[][] {{-5, -8}, {1, -2}, {2, 1}, {2, 0}, {-3, 2}, {3, -4}, {-1, -2}}) {
            lens = independent(d, owner[0], owner[1]);
            if (lens != null) break;
        }
        for (int z = -4; z <= 4 && lens == null; z++)
            for (int x = -4; x <= 4 && lens == null; x++) lens = independent(d, x, z);
        for (int z = -8; z <= 8 && lens == null; z++)
            for (int x = -8; x <= 8 && lens == null; x++)
                if (Math.abs(x) > 4 || Math.abs(z) > 4) lens = independent(d, x, z);
        d.report();
        require(
                lens != null,
                "No independent lens with actual raw and final interval within owner cap");
        points.add(lens);
        int cell = d.maps.terrain.hydro.cellBlocks;
        for (int[] v : new int[][] {{-17, -16}, {0, 0}})
            points.add(
                    new Point(
                            Math.floorDiv(v[0], cell),
                            Math.floorDiv(v[1], cell),
                            v[0],
                            v[1],
                            "SCAFFOLD_RESET",
                            0,
                            0));
        for (int[] v :
                new int[][] {
                    {-cell - 1, -cell / 2},
                    {-cell, -cell / 2},
                    {-cell + 1, -cell / 2},
                    {cell - 1, cell / 2},
                    {cell, cell / 2},
                    {cell + 1, cell / 2},
                    {-cell, -cell},
                    {cell, cell}
                })
            points.add(
                    new Point(
                            Math.floorDiv(v[0], cell),
                            Math.floorDiv(v[1], cell),
                            v[0],
                            v[1],
                            Math.abs(v[0]) == cell && Math.abs(v[1]) == cell
                                    ? "OWNER_CORNER"
                                    : "OWNER_SEAM",
                            0,
                            0));
        List<String> rows = new ArrayList<>();
        for (Point q : points) rows.add(q.row());
        write(output, MANIFEST, rows);
    }

    private static String water(Hydro.Sample w) {
        String river =
                w.riverShape == null
                        ? "NONE"
                        : join(
                                "/",
                                w.riverShape.edge,
                                w.riverShape.family,
                                w.riverShape.reason,
                                w.riverShape.probes,
                                w.riverShape.shape != null);
        String coast =
                w.coastShape == null
                        ? "NONE"
                        : join(
                                "/",
                                w.coastShape.family,
                                w.coastShape.reason,
                                w.coastShape.probes,
                                w.coastShape.receiverColumns,
                                w.coastShape.shape != null);
        return join(
                "|",
                w.kind,
                w.wet,
                w.waterY,
                hex(w.id),
                bits(w.floor),
                bits(w.bank),
                bits(w.weight),
                bits(w.head),
                w.bed,
                w.aquiferMin,
                w.aquiferMax,
                w.aquiferRoof,
                hex(w.aquiferId),
                river,
                coast);
    }

    private static String plan(Hydro.Plan p) {
        StringBuilder out =
                new StringBuilder(
                        join(
                                "|",
                                hex(p.id),
                                p.present(),
                                p.probes,
                                p.nodes(),
                                bits(p.lakeX),
                                bits(p.lakeZ),
                                bits(p.radius),
                                bits(p.depth),
                                bits(p.lakeHead),
                                bits(p.supply)));
        for (int i = 0; i < p.nodes(); i++)
            out.append(";NODE=")
                    .append(
                            join(
                                    "|",
                                    i,
                                    bits(p.x(i)),
                                    bits(p.z(i)),
                                    bits(p.head(i)),
                                    bits(p.width(i))));
        for (int i = 0; i < p.basins(); i++) {
            Basin b = p.basin(i);
            out.append(";BASIN=")
                    .append(
                            join(
                                    "|",
                                    i,
                                    b.kind,
                                    b.supply,
                                    hex(b.id),
                                    bits(b.x),
                                    bits(b.z),
                                    b.waterY,
                                    bits(b.radius),
                                    bits(b.depth),
                                    bits(b.skirt),
                                    bits(b.aspect),
                                    bits(b.cos),
                                    bits(b.sin),
                                    bits(b.support())));
        }
        Lens l = p.lens;
        out.append(";LENS=")
                .append(
                        l == null
                                ? "NONE"
                                : join(
                                        "|",
                                        hex(l.id),
                                        bits(l.x),
                                        bits(l.z),
                                        bits(l.y),
                                        bits(l.radius),
                                        bits(l.vertical),
                                        l.head,
                                        l.roof));
        return out.toString();
    }

    private static void poison(Hydro.Sample w, Hydro.Plan known) {
        w.kind = Hydro.Kind.WETLAND;
        w.wet = true;
        w.waterY = 12345;
        w.id = 0x504f49534f4eL;
        w.floor = -17.25;
        w.bank = 42.5;
        w.weight = .375;
        w.head = 12345.75;
        w.bed = Hydro.Bed.DIRT;
        w.aquiferMin = -42;
        w.aquiferMax = -24;
        w.aquiferRoof = 19;
        w.aquiferId = 9876543;
        w.riverShape = known.rivers == null ? null : known.rivers.decision(0);
        for (int i = 0; i < known.basins(); i++)
            if (known.basin(i).coastShape != null) w.coastShape = known.basin(i).coastShape;
    }

    private static String invalidLimits(Hydro hydro, int x, int z) {
        StringBuilder out = new StringBuilder();
        for (double limit :
                new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, hydro.cellBlocks + 1.}) {
            try {
                hydro.surfaceClearance(x, z, limit);
                throw new AssertionError("Invalid limit accepted");
            } catch (IllegalArgumentException expected) {
                if (out.length() > 0) out.append('|');
                out.append(bits(limit))
                        .append('=')
                        .append(expected.getClass().getName())
                        .append(':')
                        .append(expected.getMessage());
            }
        }
        return out.toString();
    }

    private static String cubes(Hydro hydro, Lens l) {
        if (l == null) return "NONE";
        int cx = Math.floorDiv((int) Math.floor(l.x), 16),
                cy = Math.floorDiv((int) Math.floor(l.y), 16),
                cz = Math.floorDiv((int) Math.floor(l.z), 16);
        StringBuilder out = new StringBuilder();
        for (int[] p :
                new int[][] {
                    {cx, cy, cz},
                    {cx, Math.floorDiv((int) Math.ceil(l.y - l.vertical), 16), cz},
                    {
                        cx,
                        Math.floorDiv((int) Math.floor(Math.min(l.head, l.y + l.vertical)), 16),
                        cz
                    },
                    {Math.floorDiv((int) Math.ceil(l.x + l.radius + 64), 16), cy, cz},
                    {cx, Math.floorDiv((int) Math.ceil(l.y + l.vertical + 64), 16), cz}
                }) {
            if (out.length() > 0) out.append('|');
            out.append(join("/", p[0], p[1], p[2], hydro.aquiferIntersects(p[0], p[1], p[2])));
        }
        require(
                hydro.aquiferIntersects(cx, cy, cz),
                "Lens center cube lost conservative intersection");
        return out.toString();
    }

    private static String source(GenerationMaps maps, Hydro.Plan p, Point q) {
        if (q.lens == 0) return "NONE";
        require(p.lens != null && p.lens.id == q.lens, "Frozen lens disappeared");
        double x = p.lens.x, z = p.lens.z;
        int head = p.lens.head;
        if (!q.role.equals("INDEPENDENT_LENS")) {
            Basin found = null;
            for (int i = 0; i < p.basins(); i++) if (p.basin(i).id == q.body) found = p.basin(i);
            require(
                    found != null
                            && found.supply == Basin.Supply.GROUNDWATER
                            && found.waterY == head,
                    "Groundwater body lost its shared lens head");
            x = found.x;
            z = found.z;
        } else require(!groundwater(p), "Independent lens acquired a groundwater body source");
        TerrainMap.BaseSample earlier = new TerrainMap.BaseSample();
        maps.terrain.intent(x, z, earlier);
        double potential = maps.terrain.groundwater.potential(x, z, earlier);
        require(Math.floor(potential) == head, "Source potential no longer supplies declared head");
        return join(
                "|", bits(x), bits(z), bits(potential), head, q.role.equals("INDEPENDENT_LENS"));
    }

    private static String snapshot(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        Hydro hydro = maps.terrain.hydro;
        Hydro.Plan p = hydro.plan(q.cx, q.cz);
        Hydro.Sample raw = new Hydro.Sample();
        hydro.sample(q.x, q.z, raw);
        Hydro.Sample poisoned = new Hydro.Sample();
        poison(poisoned, hydro.plan(2, -1));
        hydro.sample(q.x, q.z, poisoned);
        require(water(raw).equals(water(poisoned)), "Poisoned Hydro scratch differs from fresh");
        if (q.role.endsWith("_WET")) {
            Hydro.Kind kind = q.role.equals("POND_WET") ? Hydro.Kind.POND : Hydro.Kind.OASIS;
            require(
                    raw.wet
                            && raw.kind == kind
                            && raw.id == q.body
                            && s.water.wet
                            && s.water.kind == kind
                            && s.water.id == q.body,
                    "Actual groundwater wet ownership disappeared");
        }
        if (q.lens != 0)
            require(
                    interval(raw, q.lens) && interval(s.water, q.lens),
                    "Actual lens interval disappeared");
        double scaffold = maps.continents.scaffoldClearance(q.x, q.z);
        if (q.role.equals("SCAFFOLD_RESET")) {
            require(
                    scaffold <= 0
                            && raw.kind == Hydro.Kind.NONE
                            && !raw.wet
                            && raw.id == 0
                            && raw.waterY == Integer.MIN_VALUE
                            && raw.floor == 0
                            && raw.bank == 0
                            && raw.weight == 0
                            && raw.head == 0
                            && raw.bed == Hydro.Bed.GRAVEL
                            && raw.aquiferMin == Integer.MAX_VALUE
                            && raw.aquiferMax == Integer.MIN_VALUE
                            && raw.aquiferRoof == 0
                            && raw.aquiferId == 0
                            && raw.riverShape == null
                            && raw.coastShape == null,
                    "Scaffold early return did not clear all public water observations");
        }
        TerrainMap.BaseSample earlier = new TerrainMap.BaseSample();
        maps.terrain.intent(q.x, q.z, earlier);
        double potential = maps.terrain.groundwater.potential(q.x, q.z, earlier),
                at = maps.terrain.groundwater.at(q.x, q.z, earlier);
        poison(earlier.water, hydro.plan(2, -1));
        earlier.water.head = potential + 123.25;
        double after = maps.terrain.groundwater.potential(q.x, q.z, earlier),
                atAfter = maps.terrain.groundwater.at(q.x, q.z, earlier);
        require(bits(potential).equals(bits(after)), "Earlier potential consumed later water");
        if (q.lens != 0)
            require(
                    !earlier.ocean.marine && atAfter != after,
                    "Inland potential/at distinguishing control absent");
        return q.row()
                + ","
                + join(
                        ",",
                        stage,
                        plan(p),
                        source(maps, p, q),
                        water(raw),
                        bits(s.height),
                        bits(s.groundwaterHead),
                        water(s.water),
                        bits(potential),
                        bits(after),
                        bits(at),
                        bits(atAfter),
                        bits(hydro.surfaceClearance(q.x, q.z)),
                        bits(hydro.surfaceClearance(q.x, q.z, 64)),
                        bits(hydro.surfaceClearance(q.x, q.z, hydro.cellBlocks)),
                        invalidLimits(hydro, q.x, q.z),
                        cubes(hydro, p.lens),
                        join(
                                "|",
                                bits(hydro.constrain(-32.25, raw)),
                                bits(hydro.constrain(128.75, raw))),
                        s.ocean.marine,
                        bits(scaffold),
                        maps.surfaces.reason(s),
                        maps.surfaces.bareStone(s));
    }

    private static void evaluate(GenerationMaps maps, Point q, int stage, TerrainMap.Sample out) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(q.x, q.z, out);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(q.x, q.z, out);
        else maps.terrain.sample(q.x, q.z, out);
        require(Double.isFinite(out.height), "Nonfinite terrain observation");
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
            require(v.length == 8 && Long.parseLong(v[0]) == SEED, "Invalid Hydro witness row");
            points.add(
                    new Point(
                            Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]),
                            Integer.parseInt(v[3]),
                            Integer.parseInt(v[4]),
                            v[5],
                            Long.parseUnsignedLong(v[6], 16),
                            Long.parseUnsignedLong(v[7], 16)));
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
                roles.contains("POND_WET")
                        && roles.contains("OASIS_WET")
                        && roles.contains("INDEPENDENT_LENS")
                        && roles.contains("SCAFFOLD_RESET")
                        && roles.contains("OWNER_SEAM")
                        && roles.contains("OWNER_CORNER"),
                "Missing actual Hydro branch/control coverage");
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
                "PASS focused Hydro numerical capture rows="
                        + rows.size()
                        + " roles="
                        + roles
                        + "; groundwater bodies and independent lens, potential isolation, both clearances, bounded-limit errors and scaffold reset; fresh/reused forward/reverse agree.");
        System.out.println(
                "Maps reused; owner request caps apply to discovery, not a measurement of total planner work. No cold-cache, eviction, concurrency, timing, native blocks, topology or DH qualification.");
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
