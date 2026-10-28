import cc.orthrus.riven.contrib.estuaries.EstuaryKernels;
import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.CoastShape;
import cc.orthrus.riven.worldgen.ContinentalMap;
import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.OceanBed;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Public-API coastal numerical comparison. Direct wet-policy fixtures are not source-world proof. */
public final class CoastBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int MAXIMUM_OWNERS = 128, MAXIMUM_PROBES = 257;
    private static final String MANIFEST = "seed,ownerX,ownerZ,basinIndex,x,z,role,bodyId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,decisionFamily,decisionReason,decisionProbes,receiverColumns,shapeAccepted,"
                    + "networkFacts,bodyFacts,shapeFacts,marineFacts,parentBits,oceanBedFacts,geometryFacts,"
                    + "rawHydroFacts,directApplyFacts,policyBefore,policyAfter,heightBits,originalHeightBits,"
                    + "groundwaterHeadBits,waterFacts,coverReason,bareStone";

    private static final class Point {
        final int cx, cz, index, x, z;
        final String role;
        final long body;

        Point(int cx, int cz, int index, int x, int z, String role, long body) {
            this.cx = cx;
            this.cz = cz;
            this.index = index;
            this.x = x;
            this.z = z;
            this.role = role;
            this.body = body;
        }

        String row() {
            return join(",", SEED, cx, cz, index, x, z, role, Long.toHexString(body));
        }
    }

    private static final class Column {
        final ContinentalMap.Sample ocean = new ContinentalMap.Sample();
        final OceanBed.Result bed = new OceanBed.Result();
        final EstuaryKernels.Sample geometry = new EstuaryKernels.Sample();
        final Hydro.Sample raw = new Hydro.Sample(), applied = new Hydro.Sample();
        double parent;
    }

    private static final class Probe {
        Point wet, bank, receiver, network, unsupplied;
        int calls;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String bits(double v) {
        return Long.toHexString(Double.doubleToRawLongBits(v));
    }

    private static String join(String separator, Object... values) {
        StringBuilder out = new StringBuilder();
        for (Object value : values) {
            if (out.length() > 0) out.append(separator);
            out.append(value);
        }
        return out.toString();
    }

    private static Point point(
            int cx, int cz, int index, Basin b, double x, double z, String role) {
        return new Point(cx, cz, index, (int) Math.floor(x), (int) Math.floor(z), role, b.id);
    }

    private static Column column(
            GenerationMaps maps, Basin b, CoastShape.Decision d, int x, int z) {
        Column c = new Column();
        maps.continents.sample(x, z, c.ocean);
        c.parent =
                d != null && d.shape != null
                        ? d.shape.foundation
                        : b == null ? 0 : b.waterY + Math.max(2, maps.terrain.hydro.freeboard);
        if (c.ocean.marine) {
            maps.terrain.oceanBed.apply(x, z, c.ocean, false, c.bed);
            c.parent = c.bed.height;
        }
        maps.terrain.hydro.sample(x, z, c.raw);
        if (d != null && d.shape != null) {
            d.shape.apply(x, z, c.applied, d);
            d.shape.geometry.sample(
                    x, z, c.parent, c.ocean.marine, false, Integer.MIN_VALUE, 0, c.geometry);
        } else if (b != null) b.apply(x, z, c.applied, maps.terrain.hydro.freeboard);
        return c;
    }

    private static void inspect(
            GenerationMaps maps,
            int cx,
            int cz,
            int index,
            Hydro.Plan p,
            Basin b,
            CoastShape.Decision d,
            double x,
            double z,
            boolean mouth,
            Probe probe) {
        require(probe.calls++ < MAXIMUM_PROBES, "Coast stencil cap");
        Point q = point(cx, cz, index, b, x, z, "PROBE");
        require(
                Math.floorDiv(q.x, maps.terrain.hydro.cellBlocks) == cx
                        && Math.floorDiv(q.z, maps.terrain.hydro.cellBlocks) == cz,
                "Coast stencil escaped explicit owner");
        Column c = column(maps, b, d, q.x, q.z);
        boolean owned = c.raw.id == b.id && c.raw.kind == Hydro.Kind.LAGOON;
        if (c.raw.wet && owned && c.applied.wet && c.applied.id == b.id && probe.wet == null)
            probe.wet = point(cx, cz, index, b, q.x, q.z, "ACCEPT_WET");
        if (!c.raw.wet
                && owned
                && c.raw.weight > 0
                && !c.applied.wet
                && c.applied.id == b.id
                && probe.bank == null)
            probe.bank = point(cx, cz, index, b, q.x, q.z, "ACCEPT_BANK");
        if (mouth
                && c.ocean.marine
                && c.ocean.supplied
                && c.parent < b.waterY
                && c.geometry.channel
                && c.applied.wet
                && c.applied.id == b.id
                && probe.receiver == null)
            probe.receiver = point(cx, cz, index, b, q.x, q.z, "ACCEPT_RECEIVER");
        if (c.raw.wet
                && c.raw.id == p.id
                && (c.raw.kind == Hydro.Kind.RIVER || c.raw.kind == Hydro.Kind.LAKE)
                && probe.network == null)
            probe.network = point(cx, cz, index, b, q.x, q.z, "CURRENT_NETWORK_WET_POLICY");
        if (c.ocean.marine && !c.ocean.supplied && probe.unsupplied == null)
            probe.unsupplied = point(cx, cz, index, b, q.x, q.z, "UNSUPPLIED_MARINE_CONTROL");
    }

    private static Probe probe(
            GenerationMaps maps, int cx, int cz, int index, Hydro.Plan p, Basin b) {
        CoastShape.Decision d = b.coastShape;
        CoastShape s = d.shape;
        TerrainMap.BaseSample host = new TerrainMap.BaseSample();
        maps.terrain.intent(b.x, b.z, host);
        double norm = Math.hypot(host.shore.normalX, host.shore.normalZ),
                nx = host.shore.normalX / norm,
                nz = host.shore.normalZ / norm;
        Probe result = new Probe();
        inspect(maps, cx, cz, index, p, b, d, s.geometry.x, s.geometry.z, false, result);
        int mouth = Math.min(121, Math.max(3, 2 * (int) Math.ceil(s.receiverHalfWidth) + 1));
        for (int i = 0; i < mouth; i++) {
            double u = s.receiverHalfWidth * (2. * i / (mouth - 1) - 1), v = -.25 * s.length;
            inspect(
                    maps,
                    cx,
                    cz,
                    index,
                    p,
                    b,
                    d,
                    s.geometry.x + nz * u + nx * v,
                    s.geometry.z - nx * u + nz * v,
                    true,
                    result);
        }
        // 135 body columns plus at most 121 mouth columns and center: bounded native stencil.
        for (int j = 0; j < 9; j++)
            for (int i = -7; i <= 7; i++) {
                double u = i * .08 * s.length, v = (.05 + .1 * j) * s.length;
                inspect(
                        maps,
                        cx,
                        cz,
                        index,
                        p,
                        b,
                        d,
                        s.geometry.x + nz * u + nx * v,
                        s.geometry.z - nx * u + nz * v,
                        false,
                        result);
            }
        return result;
    }

    private static Point fallback(GenerationMaps maps, int cx, int cz, int index, Basin b) {
        for (int ring = 0; ring <= 8; ring++)
            for (int i = 0; i < (ring == 0 ? 1 : 20); i++) {
                double a = i * 2 * Math.PI / 20;
                Point q =
                        point(
                                cx,
                                cz,
                                index,
                                b,
                                b.x + Math.cos(a) * b.radius * ring / 8,
                                b.z + Math.sin(a) * b.radius * ring / 8,
                                "FALLBACK_WET");
                Hydro.Sample w = new Hydro.Sample();
                maps.terrain.hydro.sample(q.x, q.z, w);
                if (w.wet && w.kind == Hydro.Kind.LAGOON && w.id == b.id) return q;
            }
        return null;
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        List<Point> points = new ArrayList<>();
        List<int[]> owners = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (int[] owner : new int[][] {{1, -2}, {2, -1}, {2, 0}, {-7, -4}}) owners.add(owner);
        for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) owners.add(new int[] {x, z});
        for (int z = -8; z <= 8; z++)
            for (int x = -8; x <= 8; x++)
                if (Math.abs(x) > 4 || Math.abs(z) > 4) owners.add(new int[] {x, z});
        int requests = 0, lagoons = 0, accepted = 0, intentRejects = 0, probes = 0;
        boolean positive = false, rejected = false, plain = false;
        for (int[] owner : owners) {
            int cx = owner[0], cz = owner[1];
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (!seen.add(key)) continue;
            if (requests == MAXIMUM_OWNERS) break;
            requests++;
            Hydro.Plan p = maps.terrain.hydro.plan(cx, cz);
            for (int index = 0; index < p.basins(); index++) {
                Basin b = p.basin(index);
                CoastShape.Decision d = b.coastShape;
                if (b.kind != Hydro.Kind.LAGOON || d == null) continue;
                lagoons++;
                String reasonKey = d.family + ":" + d.reason;
                reasons.put(reasonKey, reasons.getOrDefault(reasonKey, 0) + 1);
                System.out.println(
                        join(
                                " ",
                                "Coast decision:",
                                "owner=" + cx + "/" + cz,
                                "basin=" + index,
                                d.family,
                                d.reason,
                                "probes=" + d.probes,
                                "receivers=" + d.receiverColumns,
                                "accepted=" + (d.shape != null)));
                if (d.shape != null) {
                    accepted++;
                    require(
                            d.probes > 0 && d.receiverColumns > 0,
                            "Accepted coast lacks native certificates");
                    if (positive) continue;
                    Probe found = probe(maps, cx, cz, index, p, b);
                    probes += found.calls;
                    System.out.println(
                            join(
                                    " ",
                                    "Coast stencil:",
                                    "calls=" + found.calls,
                                    "wet=" + (found.wet != null),
                                    "bank=" + (found.bank != null),
                                    "receiver=" + (found.receiver != null),
                                    "networkPolicy=" + (found.network != null),
                                    "unsupplied=" + (found.unsupplied != null)));
                    if (found.wet == null || found.bank == null || found.receiver == null) continue;
                    points.add(found.wet);
                    points.add(found.bank);
                    points.add(found.receiver);
                    points.add(
                            point(
                                    cx,
                                    cz,
                                    index,
                                    b,
                                    d.shape.geometry.x,
                                    d.shape.geometry.z,
                                    "ACCEPT_CENTER"));
                    points.add(
                            point(
                                    cx,
                                    cz,
                                    index,
                                    b,
                                    d.shape.geometry.x,
                                    d.shape.geometry.z,
                                    "DIRECT_WET_POLICY"));
                    if (found.network != null) points.add(found.network);
                    if (found.unsupplied != null) points.add(found.unsupplied);
                    positive = true;
                } else {
                    if (d.probes > 0) {
                        intentRejects++;
                        if (!rejected) {
                            points.add(point(cx, cz, index, b, b.x, b.z, "REJECT_PROBE"));
                            rejected = true;
                        }
                    }
                    if (!plain) {
                        Point q = fallback(maps, cx, cz, index, b);
                        if (q != null) {
                            points.add(q);
                            plain = true;
                        }
                    }
                }
            }
            if (requests % 8 == 0)
                System.out.println(
                        join(
                                " ",
                                "Coast discovery progress:",
                                "requests=" + requests,
                                "lagoons=" + lagoons,
                                "accepted=" + accepted,
                                "intentReadRejects=" + intentRejects,
                                "completePositive=" + positive,
                                "reasonCounts=" + reasons));
            if (positive && rejected && plain) break;
        }
        System.out.println(
                join(
                        " ",
                        "Final coast discovery:",
                        "ownerRequests=" + requests,
                        "ownerCap=" + MAXIMUM_OWNERS,
                        "finiteRange=[-8,8]",
                        "lagoons=" + lagoons,
                        "accepted=" + accepted,
                        "intentReadRejects=" + intentRejects,
                        "stencilCalls=" + probes,
                        "perAcceptedBodyCap=" + MAXIMUM_PROBES,
                        "perFallbackCap=161",
                        "completePositive=" + positive,
                        "plainFallbackWet=" + plain,
                        "reasonCounts=" + reasons));
        require(
                positive && rejected,
                "Missing actual accepted coast wet/bank/receiver or intent-read rejection");
        int cell = maps.terrain.hydro.cellBlocks;
        points.add(
                new Point(
                        Math.floorDiv(7124, cell),
                        Math.floorDiv(3179, cell),
                        -1,
                        7124,
                        3179,
                        "MARINE_CONTROL",
                        0));
        points.add(new Point(-1, -1, -1, -17, -16, "NEGATIVE_CONTROL", 0));
        points.add(new Point(0, 0, -1, 0, 0, "ORIGIN_CONTROL", 0));
        List<String> rows = new ArrayList<>();
        for (Point q : points) rows.add(q.row());
        write(output, MANIFEST, rows);
    }

    private static String water(Hydro.Sample w) {
        return join(
                "|",
                w.kind,
                w.wet,
                Long.toHexString(w.id),
                w.waterY,
                bits(w.floor),
                bits(w.bank),
                bits(w.weight),
                bits(w.head),
                w.bed,
                w.aquiferMin,
                w.aquiferMax,
                w.aquiferRoof,
                Long.toHexString(w.aquiferId),
                w.riverShape == null ? -1 : w.riverShape.edge);
    }

    private static Hydro.Sample copy(Hydro.Sample w) {
        Hydro.Sample c = new Hydro.Sample();
        c.kind = w.kind;
        c.wet = w.wet;
        c.id = w.id;
        c.waterY = w.waterY;
        c.floor = w.floor;
        c.bank = w.bank;
        c.weight = w.weight;
        c.head = w.head;
        c.bed = w.bed;
        c.aquiferMin = w.aquiferMin;
        c.aquiferMax = w.aquiferMax;
        c.aquiferRoof = w.aquiferRoof;
        c.aquiferId = w.aquiferId;
        c.riverShape = w.riverShape;
        return c;
    }

    private static String shape(CoastShape s) {
        return s == null
                ? "NONE"
                : join(
                        "|",
                        Long.toHexString(s.ownerId),
                        bits(s.length),
                        bits(s.foundation),
                        bits(s.shelter),
                        bits(s.sediment),
                        bits(s.receiverHalfWidth),
                        s.certificateColumns,
                        s.receiverColumns,
                        s.geometry.family,
                        Long.toHexString(s.geometry.id),
                        bits(s.geometry.x),
                        bits(s.geometry.z),
                        bits(s.geometry.supportRadius));
    }

    private static String body(Basin b) {
        return b == null
                ? "NONE"
                : join(
                        "|",
                        b.kind,
                        b.supply,
                        Long.toHexString(b.id),
                        bits(b.x),
                        bits(b.z),
                        bits(b.radius),
                        bits(b.depth),
                        bits(b.skirt),
                        bits(b.aspect),
                        bits(b.cos),
                        bits(b.sin),
                        b.waterY,
                        bits(b.support()));
    }

    private static String network(Hydro.Plan p) {
        StringBuilder out =
                new StringBuilder(
                        join(
                                "|",
                                Long.toHexString(p.id),
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
            CoastShape.Decision d = b.coastShape;
            out.append(";BASIN=").append(body(b));
            if (d != null)
                out.append(";COAST=")
                        .append(
                                join(
                                        "|",
                                        d.family,
                                        d.reason,
                                        d.probes,
                                        d.receiverColumns,
                                        shape(d.shape)));
        }
        if (p.rivers != null)
            for (int i = 0; i < p.rivers.candidates(); i++) {
                cc.orthrus.riven.worldgen.RiverShape.Decision d = p.rivers.decision(i);
                out.append(";RIVER=")
                        .append(join("|", d.edge, d.family, d.reason, d.probes, d.shape != null));
            }
        return out.toString();
    }

    private static String snapshot(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        Hydro.Plan p = maps.terrain.hydro.plan(q.cx, q.cz);
        Basin b = null;
        CoastShape.Decision d = null;
        if (q.index >= 0) {
            require(q.index < p.basins(), "Frozen coastal host disappeared");
            b = p.basin(q.index);
            d = b.coastShape;
            require(
                    b.id == q.body && d != null && b.kind == Hydro.Kind.LAGOON,
                    "Frozen coast decision disappeared");
        }
        CoastShape shape = d == null ? null : d.shape;
        Column c = column(maps, b, d, q.x, q.z);
        if (q.role.startsWith("ACCEPT_") || q.role.endsWith("WET_POLICY"))
            require(
                    shape != null && d.probes > 0 && d.receiverColumns > 0,
                    "Accepted native coast disappeared");
        if (q.role.equals("ACCEPT_WET") || q.role.equals("FALLBACK_WET"))
            require(
                    c.raw.wet
                            && c.raw.kind == Hydro.Kind.LAGOON
                            && c.raw.id == q.body
                            && s.water.wet
                            && s.water.kind == Hydro.Kind.LAGOON
                            && s.water.id == q.body,
                    "Actual coastal wet owner disappeared");
        if (q.role.equals("ACCEPT_WET"))
            require(c.applied.wet && c.applied.id == q.body, "Direct coast no longer wet");
        if (q.role.equals("ACCEPT_BANK"))
            require(
                    !c.raw.wet
                            && c.raw.id == q.body
                            && c.raw.weight > 0
                            && !c.applied.wet
                            && c.applied.id == q.body,
                    "Actual coastal bank disappeared");
        if (q.role.equals("ACCEPT_RECEIVER"))
            require(
                    c.ocean.marine
                            && c.ocean.supplied
                            && c.parent < b.waterY
                            && c.geometry.channel
                            && c.applied.wet
                            && c.applied.id == q.body,
                    "Native supplied channel receiver disappeared");
        if (q.role.equals("REJECT_PROBE"))
            require(shape == null && d.probes > 0, "Intent-read coast rejection disappeared");
        if (q.role.equals("FALLBACK_WET")) require(shape == null, "Plain coast fallback changed");
        if (q.role.equals("MARINE_CONTROL")) require(c.ocean.marine, "Marine control changed");
        if (q.role.equals("UNSUPPLIED_MARINE_CONTROL"))
            require(c.ocean.marine && !c.ocean.supplied, "Unsupplied control changed");
        String before = "NONE", after = "NONE";
        if (q.role.equals("DIRECT_WET_POLICY") || q.role.equals("CURRENT_NETWORK_WET_POLICY")) {
            Hydro.Sample fixture;
            if (q.role.equals("CURRENT_NETWORK_WET_POLICY")) {
                require(
                        c.raw.wet
                                && c.raw.id == p.id
                                && (c.raw.kind == Hydro.Kind.RIVER
                                        || c.raw.kind == Hydro.Kind.LAKE),
                        "Current network wet policy input changed");
                fixture = copy(c.raw);
            } else {
                fixture = new Hydro.Sample();
                fixture.kind = Hydro.Kind.RIVER;
                fixture.wet = true;
                fixture.id = 0x434f415354504f4cL;
                fixture.waterY = b.waterY;
                fixture.head = b.waterY;
                fixture.floor = b.waterY - 3.25;
                fixture.bank = b.waterY + 2;
                fixture.weight = .75;
                fixture.bed = Hydro.Bed.DIRT;
                fixture.aquiferMin = -32;
                fixture.aquiferMax = -24;
                fixture.aquiferRoof = 8;
                fixture.aquiferId = 123;
            }
            before = water(fixture);
            shape.apply(q.x, q.z, fixture, d);
            after = water(fixture);
            require(before.equals(after), "Direct already-wet reservation policy changed");
        }
        ContinentalMap.Sample o = c.ocean;
        EstuaryKernels.Sample g = c.geometry;
        return q.row()
                + ","
                + join(
                        ",",
                        stage,
                        d == null ? "NONE" : d.family,
                        d == null ? "NONE" : d.reason,
                        d == null ? 0 : d.probes,
                        d == null ? 0 : d.receiverColumns,
                        shape != null,
                        network(p),
                        body(b),
                        shape(shape),
                        join(
                                "|",
                                o.marine,
                                o.supplied,
                                o.protectedWater,
                                bits(o.coastCoordinate),
                                bits(o.bedHeight),
                                bits(o.normalX),
                                bits(o.normalZ),
                                bits(o.scaffoldClearance)),
                        bits(c.parent),
                        o.marine
                                ? join(
                                        "|",
                                        bits(c.bed.height),
                                        bits(c.bed.distance),
                                        bits(c.bed.shelf.bedY),
                                        bits(c.bed.ridge.bedY),
                                        bits(c.bed.trench.bedY),
                                        bits(c.bed.bank.bedY))
                                : "NONE",
                        shape == null
                                ? "NONE"
                                : join(
                                        "|",
                                        bits(g.height),
                                        bits(g.weight),
                                        bits(g.depth),
                                        bits(g.foundationWeight),
                                        g.waterY,
                                        g.wet,
                                        g.channel,
                                        g.barrier,
                                        g.earlierReservation,
                                        g.barrierRequired),
                        water(c.raw),
                        water(c.applied),
                        before,
                        after,
                        bits(s.height),
                        bits(s.originalHeight),
                        bits(s.groundwaterHead),
                        water(s.water),
                        maps.surfaces.reason(s),
                        maps.surfaces.bareStone(s));
    }

    private static void evaluate(GenerationMaps maps, Point q, int stage, TerrainMap.Sample out) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(q.x, q.z, out);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(q.x, q.z, out);
        else maps.terrain.sample(q.x, q.z, out);
        require(Double.isFinite(out.height), "Nonfinite final observation");
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
            require(v.length == 8 && Long.parseLong(v[0]) == SEED, "Invalid coast witness row");
            points.add(
                    new Point(
                            Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]),
                            Integer.parseInt(v[3]),
                            Integer.parseInt(v[4]),
                            Integer.parseInt(v[5]),
                            v[6],
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
                roles.contains("ACCEPT_WET")
                        && roles.contains("ACCEPT_BANK")
                        && roles.contains("ACCEPT_RECEIVER")
                        && roles.contains("REJECT_PROBE")
                        && roles.contains("DIRECT_WET_POLICY")
                        && roles.contains("MARINE_CONTROL")
                        && roles.contains("NEGATIVE_CONTROL"),
                "Missing actual coast/rejection/policy/control coverage");
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
                "PASS selected coast capture rows="
                        + rows.size()
                        + " roles="
                        + roles
                        + "; native integer model wet/bank/receiver and intent-read rejection; fresh/reused forward/reverse agree.");
        System.out.println(
                "DIRECT_WET_POLICY is synthetic application policy only; CURRENT_NETWORK_WET_POLICY if present uses the final current network, not raw earlier-network proof. Maps reused: no cold-cache, concurrency, full topology, native blocks, DH or performance qualification.");
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
