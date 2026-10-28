import cc.orthrus.riven.contrib.river.Rivers;
import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.RiverShape;
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

/** Selected river numeric equivalence; no native blocks, fluid topology or performance claim. */
public final class RiverBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int MAXIMUM_OWNERS = 128, MAXIMUM_PROBES = 161;
    private static final String MANIFEST = "seed,ownerX,ownerZ,edge,x,z,role,ownerId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,decisionFamily,decisionReason,decisionProbes,shapeAccepted,allDecisionFacts,"
                    + "networkFacts,reachFacts,riverApplyOwned,riverApplyFacts,geometrySampleFacts,rawHydroFacts,heightBits,originalHeightBits,"
                    + "groundwaterHeadBits,waterFacts,marine,supplied,coverReason,bareStone";

    private static final class Point {
        final int cx, cz, edge, x, z;
        final String role;
        final long owner;

        Point(int cx, int cz, int edge, int x, int z, String role, long owner) {
            this.cx = cx;
            this.cz = cz;
            this.edge = edge;
            this.x = x;
            this.z = z;
            this.role = role;
            this.owner = owner;
        }

        String row() {
            return join(",", SEED, cx, cz, edge, x, z, role, Long.toHexString(owner));
        }
    }

    private static final class Probe {
        Point wet, bank, dry;
        int calls;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
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

    private static Point point(
            int cx, int cz, RiverShape.Decision d, double x, double z, String role, Hydro.Plan p) {
        return new Point(cx, cz, d.edge, (int) Math.floor(x), (int) Math.floor(z), role, p.id);
    }

    private static Probe probe(
            GenerationMaps maps, int cx, int cz, Hydro.Plan p, RiverShape.Decision d) {
        Probe result = new Probe();
        RiverShape.Reach r = d.shape;
        double ax = r == null ? p.x(d.edge) : r.startX,
                az = r == null ? p.z(d.edge) : r.startZ,
                bx = r == null ? p.x(d.edge + 1) : r.endX,
                bz = r == null ? p.z(d.edge + 1) : r.endZ;
        double length = Math.hypot(bx - ax, bz - az),
                nx = -(bz - az) / length,
                nz = (bx - ax) / length,
                radius = r == null ? Math.min(p.width(d.edge), p.width(d.edge + 1)) : r.allowance;
        Hydro.Sample water = new Hydro.Sample();
        Rivers.Sample geometry = new Rivers.Sample();
        for (int along = 1; along <= 9; along++)
            for (int offset = 0; offset < 17; offset++) {
                require(result.calls++ < MAXIMUM_PROBES, "River stencil cap");
                int cross = offset == 0 ? 0 : (offset + 1) / 2 * (offset % 2 == 1 ? 1 : -1);
                int x = (int) Math.floor(ax + (bx - ax) * along / 10 + nx * radius * cross / 8),
                        z = (int) Math.floor(az + (bz - az) * along / 10 + nz * radius * cross / 8);
                require(
                        Math.floorDiv(x, maps.terrain.hydro.cellBlocks) == cx
                                && Math.floorDiv(z, maps.terrain.hydro.cellBlocks) == cz,
                        "River stencil escaped explicit owner");
                maps.terrain.hydro.sample(x, z, water);
                boolean same = water.kind == Hydro.Kind.RIVER && water.id == p.id;
                if (r != null) {
                    r.geometry.sample(x, z, r.foundation, true, geometry);
                    boolean owned = water.riverShape != null && water.riverShape.edge == d.edge;
                    if (!geometry.wet && !water.wet && owned && result.dry == null)
                        result.dry = point(cx, cz, d, x, z, "ACCEPT_DRY_OWNED", p);
                    if (geometry.wet && water.wet && same && owned && result.wet == null)
                        result.wet = point(cx, cz, d, x, z, "ACCEPT_WET", p);
                    if (!geometry.wet
                            && !water.wet
                            && water.weight > 0
                            && owned
                            && water.id == p.id
                            && result.bank == null)
                        result.bank = point(cx, cz, d, x, z, "ACCEPT_BANK", p);
                    if (result.wet != null && result.bank != null && result.dry != null)
                        return result;
                } else if (water.wet && same && water.riverShape == null) {
                    result.wet = point(cx, cz, d, x, z, "FALLBACK_WET", p);
                    return result;
                }
            }
        return result;
    }

    private static void discover(Path output) throws Exception {
        GenerationMaps maps = new GenerationMaps(SEED);
        List<Point> points = new ArrayList<>();
        Set<Long> visited = new HashSet<>(), acceptedOwners = new HashSet<>();
        List<int[]> owners = new ArrayList<>();
        owners.add(new int[] {2, -1});
        owners.add(new int[] {1, -2});
        for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) owners.add(new int[] {x, z});
        // Only needed if the inner finite window has not supplied a second actual wet owner.
        for (int z = -8; z <= 8; z++)
            for (int x = -8; x <= 8; x++)
                if (Math.abs(x) > 4 || Math.abs(z) > 4) owners.add(new int[] {x, z});
        int requests = 0, networks = 0, acceptedDecisions = 0, intentRejects = 0, probes = 0;
        boolean rejection = false, fallback = false;
        for (int[] owner : owners) {
            int cx = owner[0], cz = owner[1];
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (!visited.add(key)) continue;
            if (requests == MAXIMUM_OWNERS) break;
            requests++;
            Hydro.Plan p = maps.terrain.hydro.plan(cx, cz);
            if (p.present()) networks++;
            if (p.rivers != null)
                for (int i = 0; i < p.rivers.candidates(); i++) {
                    RiverShape.Decision d = p.rivers.decision(i);
                    if (d.shape != null) {
                        acceptedDecisions++;
                        if (acceptedOwners.contains(p.id)) continue;
                        Probe found = probe(maps, cx, cz, p, d);
                        probes += found.calls;
                        if (found.wet == null) continue;
                        acceptedOwners.add(p.id);
                        points.add(found.wet);
                        if (found.dry != null) points.add(found.dry);
                        RiverShape.Reach r = d.shape;
                        points.add(
                                point(
                                        cx,
                                        cz,
                                        d,
                                        (r.startX + r.endX) * .5,
                                        (r.startZ + r.endZ) * .5,
                                        "ACCEPT_CENTER",
                                        p));
                        points.add(
                                found.bank != null
                                        ? found.bank
                                        : point(
                                                cx,
                                                cz,
                                                d,
                                                r.startX,
                                                r.startZ,
                                                "ACCEPT_BANK_PROBE",
                                                p));
                        points.add(point(cx, cz, d, r.startX, r.startZ, "ACCEPT_START", p));
                        points.add(point(cx, cz, d, r.endX, r.endZ, "ACCEPT_END", p));
                        System.out.println(
                                "Actual river wet witness: "
                                        + found.wet.row()
                                        + " family="
                                        + d.family
                                        + " certificateProbes="
                                        + d.probes);
                    } else if (d.probes > 0) {
                        intentRejects++;
                        if (!rejection) {
                            points.add(
                                    point(
                                            cx,
                                            cz,
                                            d,
                                            (p.x(d.edge) + p.x(d.edge + 1)) * .5,
                                            (p.z(d.edge) + p.z(d.edge + 1)) * .5,
                                            "REJECT_PROBE",
                                            p));
                            rejection = true;
                        }
                        if (!fallback) {
                            Probe found = probe(maps, cx, cz, p, d);
                            probes += found.calls;
                            if (found.wet != null) {
                                points.add(found.wet);
                                fallback = true;
                            }
                        }
                    }
                }
            if (requests % 8 == 0 || acceptedOwners.size() >= 2 && rejection)
                System.out.println(
                        join(
                                " ",
                                "River discovery:",
                                "ownerRequests=" + requests,
                                "networks=" + networks,
                                "acceptedDecisions=" + acceptedDecisions,
                                "acceptedWetOwners=" + acceptedOwners.size(),
                                "intentReadRejects=" + intentRejects,
                                "legacyFallbackWet=" + fallback,
                                "stencilProbes=" + probes));
            if (acceptedOwners.size() >= 2 && rejection) break;
        }
        System.out.println(
                join(
                        " ",
                        "Final bounded river coverage:",
                        "ownerRequests=" + requests,
                        "ownerCap=" + MAXIMUM_OWNERS,
                        "finiteRange=[-8,8]",
                        "networks=" + networks,
                        "acceptedDecisions=" + acceptedDecisions,
                        "actualWetOwners=" + acceptedOwners.size(),
                        "intentReadRejects=" + intentRejects,
                        "fallbackWet=" + fallback,
                        "stencilProbes=" + probes,
                        "perReachCap=" + MAXIMUM_PROBES));
        require(
                !acceptedOwners.isEmpty() && rejection,
                "Missing actual accepted river water or a rejected decision that executed intent sampling");
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
        points.add(new Point(-1, -1, -1, -1, -1, "NEGATIVE_CONTROL", 0));
        points.add(new Point(-1, -1, -1, -17, -16, "NEGATIVE_CONTROL", 0));
        points.add(new Point(0, 0, -1, 0, 0, "ORIGIN_CONTROL", 0));
        write(output, MANIFEST, pointRows(points));
    }

    private static List<String> pointRows(List<Point> points) {
        List<String> rows = new ArrayList<>();
        for (Point point : points) rows.add(point.row());
        return rows;
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

    private static String reach(RiverShape.Reach r) {
        if (r == null) return "NONE";
        Rivers.ColumnPlan g = r.geometry;
        return join(
                "|",
                bits(r.startX),
                bits(r.startZ),
                bits(r.endX),
                bits(r.endZ),
                bits(r.length),
                bits(r.allowance),
                bits(r.foundation),
                bits(r.width),
                bits(r.depth),
                bits(r.branchLength),
                bits(r.startWater),
                bits(r.endWater),
                bits(r.bridgeRadius),
                g.family,
                Long.toHexString(g.routeId),
                g.segments,
                bits(g.minX),
                bits(g.maxX),
                bits(g.minZ),
                bits(g.maxZ),
                bits(g.minParent),
                bits(g.maxParent));
    }

    private static String allDecisions(Hydro.Plan p) {
        if (p.rivers == null) return "NONE";
        StringBuilder out =
                new StringBuilder(join("|", p.rivers.accepted, p.rivers.certificateColumns));
        for (int i = 0; i < p.rivers.candidates(); i++) {
            RiverShape.Decision d = p.rivers.decision(i);
            out.append(';').append(join("|", d.edge, d.family, d.reason, d.probes, reach(d.shape)));
        }
        return out.toString();
    }

    private static String network(Hydro.Plan p) {
        StringBuilder out =
                new StringBuilder(
                        join(
                                "|",
                                Long.toHexString(p.id),
                                p.probes,
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
                                    Long.toHexString(b.id),
                                    bits(b.x),
                                    bits(b.z),
                                    b.waterY,
                                    bits(b.radius),
                                    bits(b.depth),
                                    bits(b.skirt),
                                    bits(b.support())));
        }
        return out.toString();
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

    private static String snapshot(GenerationMaps maps, Point q, int stage, TerrainMap.Sample s) {
        Hydro.Plan p = maps.terrain.hydro.plan(q.cx, q.cz);
        RiverShape.Decision d = null;
        if (q.edge >= 0) {
            require(p.id == q.owner && p.rivers != null, "Frozen river owner disappeared");
            for (int i = 0; i < p.rivers.candidates(); i++)
                if (p.rivers.decision(i).edge == q.edge) d = p.rivers.decision(i);
            require(d != null, "Frozen decision disappeared");
        }
        RiverShape.Reach r = d == null ? null : d.shape;
        Hydro.Sample applied = new Hydro.Sample();
        boolean appliedOwner = p.rivers != null && p.rivers.apply(q.x, q.z, p.id, applied);
        Hydro.Sample raw = new Hydro.Sample();
        maps.terrain.hydro.sample(q.x, q.z, raw);
        String geometry = "NONE";
        boolean geometricWet = false;
        if (r != null) {
            Rivers.Sample g = new Rivers.Sample();
            r.geometry.sample(q.x, q.z, r.foundation, true, g);
            geometricWet = g.wet;
            geometry =
                    join(
                            "|",
                            bits(g.surface),
                            bits(g.waterY),
                            bits(g.weight),
                            bits(g.depth),
                            bits(g.foundationWeight),
                            g.wet,
                            g.bar);
        }
        if (q.role.equals("ACCEPT_WET"))
            require(
                    r != null
                            && geometricWet
                            && raw.wet
                            && raw.kind == Hydro.Kind.RIVER
                            && raw.id == q.owner
                            && raw.riverShape != null
                            && raw.riverShape.edge == q.edge
                            && s.water.wet
                            && s.water.kind == Hydro.Kind.RIVER
                            && s.water.id == q.owner,
                    "Actual shaped river wet witness disappeared");
        if (q.role.equals("REJECT_PROBE") || q.role.equals("FALLBACK_WET"))
            require(d != null && r == null && d.probes > 0, "Intent-read rejection disappeared");
        if (q.role.equals("FALLBACK_WET"))
            require(
                    raw.wet
                            && raw.kind == Hydro.Kind.RIVER
                            && raw.id == q.owner
                            && raw.riverShape == null
                            && s.water.wet
                            && s.water.kind == Hydro.Kind.RIVER
                            && s.water.id == q.owner,
                    "Actual legacy fallback witness disappeared");
        if (q.role.equals("ACCEPT_BANK"))
            require(
                    r != null
                            && !raw.wet
                            && raw.weight > 0
                            && raw.id == q.owner
                            && raw.riverShape != null
                            && raw.riverShape.edge == q.edge,
                    "Actual shaped bank witness disappeared");
        if (q.role.equals("ACCEPT_DRY_OWNED"))
            require(
                    r != null
                            && !geometricWet
                            && !raw.wet
                            && raw.riverShape != null
                            && raw.riverShape.edge == q.edge
                            && appliedOwner
                            && !applied.wet
                            && applied.riverShape != null
                            && applied.riverShape.edge == q.edge,
                    "Dry owned river point lost its legacy-suppression ownership");
        if (q.role.equals("MARINE_CONTROL")) require(s.ocean.marine, "Marine control changed");
        return q.row()
                + ","
                + join(
                        ",",
                        stage,
                        d == null ? "NONE" : d.family,
                        d == null ? "NONE" : d.reason,
                        d == null ? 0 : d.probes,
                        r != null,
                        allDecisions(p),
                        network(p),
                        reach(r),
                        appliedOwner,
                        water(applied),
                        geometry,
                        water(raw),
                        bits(s.height),
                        bits(s.originalHeight),
                        bits(s.groundwaterHead),
                        water(s.water),
                        s.ocean.marine,
                        s.ocean.supplied,
                        maps.surfaces.reason(s),
                        maps.surfaces.bareStone(s));
    }

    private static void evaluate(GenerationMaps maps, Point p, int stage, TerrainMap.Sample out) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(p.x, p.z, out);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(p.x, p.z, out);
        else maps.terrain.sample(p.x, p.z, out);
        require(Double.isFinite(out.height), "Nonfinite terrain height");
    }

    private static void capture(Path input, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.equals(MANIFEST)) continue;
            String[] v = row.split(",", -1);
            require(v.length == 8 && Long.parseLong(v[0]) == SEED, "Invalid witness row");
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
        Set<Long> accepted = new HashSet<>();
        boolean rejected = false, fallback = false, marine = false, negative = false;
        for (Point p : points) {
            for (int stage = 0; stage < 3; stage++) {
                TerrainMap.Sample fresh = new TerrainMap.Sample();
                evaluate(maps, p, stage, fresh);
                rows.add(snapshot(maps, p, stage, fresh));
            }
            if (p.role.equals("ACCEPT_WET")) accepted.add(p.owner);
            rejected |= p.role.equals("REJECT_PROBE");
            fallback |= p.role.equals("FALLBACK_WET");
            marine |= p.role.equals("MARINE_CONTROL");
            negative |= p.role.equals("NEGATIVE_CONTROL") && p.x < 0 && p.z < 0;
        }
        require(
                !accepted.isEmpty() && rejected && marine && negative,
                "Missing actual river/rejection/control coverage");
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++)
            for (int offset = 0; offset < rows.size(); offset++) {
                int index = pass == 0 ? offset : rows.size() - offset - 1;
                Point p = points.get(index / 3);
                int stage = index % 3;
                evaluate(maps, p, stage, reused);
                require(
                        rows.get(index).equals(snapshot(maps, p, stage, reused)),
                        "Fresh/reused forward/reverse mismatch: " + p.row() + " stage=" + stage);
            }
        write(output, HEADER, rows);
        System.out.println(
                "PASS selected river numeric capture: columns="
                        + points.size()
                        + " rows="
                        + rows.size()
                        + " acceptedWetOwners="
                        + accepted.size()
                        + " fallbackWet="
                        + fallback
                        + "; actual intent-read rejection and controls; fresh/reused forward/reverse agree.");
        System.out.println(
                "Maps reused: no cold-cache, eviction, concurrency, all-family, topology, native block, DH or performance qualification.");
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
