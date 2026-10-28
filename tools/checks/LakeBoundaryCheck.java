import cc.orthrus.riven.contrib.lakes.Lakes;
import cc.orthrus.riven.worldgen.Basin;
import cc.orthrus.riven.worldgen.GenerationMaps;
import cc.orthrus.riven.worldgen.Hydro;
import cc.orthrus.riven.worldgen.LakeShape;
import cc.orthrus.riven.worldgen.Lens;
import cc.orthrus.riven.worldgen.TerrainHeightReference;
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

/** Selected lake/pond numeric boundary parity, not fluid topology, native blocks or performance. */
public final class LakeBoundaryCheck {
    private static final long SEED = 8675309L;
    private static final int MAXIMUM_OWNER_REQUESTS = 512;
    private static final int MAXIMUM_STENCIL_PROBES = 161;
    private static final String MANIFEST = "seed,ownerX,ownerZ,basinIndex,x,z,role,bodyId";
    private static final String HEADER =
            MANIFEST
                    + ",stage,decisionReason,decisionProbes,shapeAccepted,shapeProfile,bodyKind,"
                    + "planFacts,bodyFacts,shapeFacts,rawHydroFacts,heightBits,originalHeightBits,"
                    + "groundwaterHeadBits,rockyParentHeightBits,waterKind,wet,waterId,waterY,"
                    + "floorBits,bankBits,weightBits,headBits,bed,aquiferMin,aquiferMax,aquiferRoof,"
                    + "aquiferId,marine,supplied,protectedWater,coastBits,shoreHeightBits,minBits,"
                    + "maxBits,allowsRiver,coverReason,bareStone";

    private static final class Point {
        final int ownerX, ownerZ, basinIndex, x, z;
        final String role;
        final long bodyId;

        Point(int ownerX, int ownerZ, int basinIndex, int x, int z, String role, long bodyId) {
            this.ownerX = ownerX;
            this.ownerZ = ownerZ;
            this.basinIndex = basinIndex;
            this.x = x;
            this.z = z;
            this.role = role;
            this.bodyId = bodyId;
        }

        String row() {
            return SEED
                    + ","
                    + ownerX
                    + ","
                    + ownerZ
                    + ","
                    + basinIndex
                    + ","
                    + x
                    + ","
                    + z
                    + ","
                    + role
                    + ","
                    + Long.toHexString(bodyId);
        }
    }

    private static final class Body {
        final int ownerX, ownerZ, basinIndex;
        final Hydro.Plan plan;
        final Basin basin;
        final LakeShape.Decision decision;
        final Hydro.Kind kind;
        final long id;
        final double x, z, radius;

        Body(int ownerX, int ownerZ, Hydro.Plan plan, int basinIndex) {
            this.ownerX = ownerX;
            this.ownerZ = ownerZ;
            this.plan = plan;
            this.basinIndex = basinIndex;
            basin = basinIndex < 0 ? null : plan.basin(basinIndex);
            decision = basin == null ? plan.lakeShape : basin.lakeShape;
            kind = basin == null ? Hydro.Kind.LAKE : basin.kind;
            id = basin == null ? plan.id : basin.id;
            x = basin == null ? plan.lakeX : basin.x;
            z = basin == null ? plan.lakeZ : basin.z;
            radius =
                    decision != null && decision.shape != null
                            ? decision.shape.outerRadius
                            : basin == null ? plan.radius : basin.support();
        }

        boolean accepted() {
            return decision != null && decision.shape != null;
        }

        Point point(int px, int pz, String role) {
            return new Point(ownerX, ownerZ, basinIndex, px, pz, role, id);
        }
    }

    private static final class Probe {
        Point wet, bank;
        int probes;
    }

    private static final class Discovery {
        final GenerationMaps maps = new GenerationMaps(SEED);
        final Set<Long> visited = new HashSet<>();
        final List<Point> points = new ArrayList<>();
        int ownerRequests, stencilProbes, acceptedLakes, acceptedPonds, rejectedShapes;
        int networkOwners, supplementalBasins, emptyOwners;
        boolean lake, pond, fallback;

        boolean covered() {
            return lake && pond && fallback;
        }

        void owner(int cx, int cz) {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (covered() || visited.contains(key) || ownerRequests == MAXIMUM_OWNER_REQUESTS)
                return;
            visited.add(key);
            ownerRequests++;
            System.out.println("Discovery owner request " + ownerRequests + ": " + cx + "," + cz);
            Hydro.Plan plan = maps.terrain.hydro.plan(cx, cz);
            if (plan.present()) networkOwners++;
            supplementalBasins += plan.basins();
            if (!plan.present() && plan.basins() == 0) emptyOwners++;
            if (plan.present()) consider(new Body(cx, cz, plan, -1));
            for (int i = 0; i < plan.basins(); i++)
                if (plan.basin(i).kind == Hydro.Kind.POND) consider(new Body(cx, cz, plan, i));
            if (ownerRequests % 8 == 0) report("progress");
        }

        void consider(Body body) {
            if (body.decision == null) return;
            String category;
            if (body.accepted()) {
                if (body.kind == Hydro.Kind.LAKE) {
                    acceptedLakes++;
                    if (lake) return;
                    category = "LAKE";
                } else {
                    acceptedPonds++;
                    if (pond) return;
                    category = "POND";
                }
            } else {
                rejectedShapes++;
                if (fallback) return;
                category = "FALLBACK";
            }
            Probe probe = probe(maps.terrain.hydro, body, category);
            stencilProbes += probe.probes;
            if (probe.wet == null) {
                System.out.println(
                        "No wet witness in bounded body stencil: "
                                + category
                                + " owner="
                                + body.ownerX
                                + ","
                                + body.ownerZ
                                + " body="
                                + Long.toHexString(body.id)
                                + " probes="
                                + probe.probes
                                + " reason="
                                + body.decision.reason);
                return;
            }
            points.add(
                    body.point(
                            (int) Math.floor(body.x),
                            (int) Math.floor(body.z),
                            category + "_CENTER"));
            points.add(probe.wet);
            if (probe.bank != null) points.add(probe.bank);
            else
                points.add(
                        body.point(
                                (int) Math.floor(body.x + body.radius),
                                (int) Math.floor(body.z),
                                category + "_BANK_PROBE"));
            if (body.kind == Hydro.Kind.LAKE && body.plan.nodes() > 1)
                points.add(
                        body.point(
                                (int) Math.floor((body.plan.x(0) + body.plan.x(1)) * .5),
                                (int) Math.floor((body.plan.z(0) + body.plan.z(1)) * .5),
                                category + "_OUTLET"));
            points.add(
                    body.point(
                            (int) Math.floor(body.x + body.radius + 4),
                            (int) Math.floor(body.z),
                            category + "_OUTSIDE"));
            lake |= category.equals("LAKE");
            pond |= category.equals("POND");
            fallback |= category.equals("FALLBACK");
            System.out.println(
                    "Current wet witness: "
                            + probe.wet.row()
                            + " kind="
                            + body.kind
                            + " reason="
                            + body.decision.reason
                            + " probes="
                            + probe.probes
                            + " actualBank="
                            + (probe.bank != null));
        }

        void report(String phase) {
            System.out.println(
                    "Bounded lake discovery "
                            + phase
                            + ": explicitOwnerRequests="
                            + ownerRequests
                            + "/"
                            + MAXIMUM_OWNER_REQUESTS
                            + " stencilProbes="
                            + stencilProbes
                            + " perBodyCap="
                            + MAXIMUM_STENCIL_PROBES
                            + " networkOwners="
                            + networkOwners
                            + " supplementalBasins="
                            + supplementalBasins
                            + " emptyOwners="
                            + emptyOwners
                            + " acceptedLakeDecisions="
                            + acceptedLakes
                            + " acceptedPondDecisions="
                            + acceptedPonds
                            + " rejectedDecisions="
                            + rejectedShapes
                            + " lakeWet="
                            + lake
                            + " pondWet="
                            + pond
                            + " fallbackWet="
                            + fallback);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Probe probe(Hydro hydro, Body body, String category) {
        Probe result = new Probe();
        Hydro.Sample water = new Hydro.Sample();
        // A finite native stencil, not a raster. The center may be a deliberately dry island.
        for (int ring = 0; ring <= 10; ring++) {
            int directions = ring == 0 ? 1 : 16;
            for (int direction = 0; direction < directions; direction++) {
                require(result.probes++ < MAXIMUM_STENCIL_PROBES, "Body stencil cap");
                double angle = direction * 2 * Math.PI / directions;
                int x = (int) Math.floor(body.x + Math.cos(angle) * body.radius * ring / 10),
                        z = (int) Math.floor(body.z + Math.sin(angle) * body.radius * ring / 10);
                require(
                        Math.floorDiv(x, hydro.cellBlocks) == body.ownerX
                                && Math.floorDiv(z, hydro.cellBlocks) == body.ownerZ,
                        "Body stencil escaped its explicit owner");
                hydro.sample(x, z, water);
                if (water.wet
                        && water.id == body.id
                        && water.kind == body.kind
                        && result.wet == null) result.wet = body.point(x, z, category + "_WET");
                if (!water.wet && water.weight > 0 && water.id == body.id && result.bank == null)
                    result.bank = body.point(x, z, category + "_BANK");
                if (result.wet != null && result.bank != null) return result;
            }
        }
        return result;
    }

    private static void discover(Path output) throws Exception {
        Discovery discovery = new Discovery();
        // Historical candidate owners only: every decision and wet witness is requalified now.
        for (int[] owner : new int[][] {{3, -4}, {-1, -2}, {-3, 1}, {1, 2}, {4, 3}})
            discovery.owner(owner[0], owner[1]);
        for (int z = -4; z <= 4 && !discovery.covered(); z++)
            for (int x = -4; x <= 4 && !discovery.covered(); x++) discovery.owner(x, z);
        discovery.report("inner[-4,4]");
        if (!discovery.covered()) {
            for (int z = -8; z <= 8 && !discovery.covered(); z++)
                for (int x = -8; x <= 8 && !discovery.covered(); x++)
                    if (Math.abs(x) > 4 || Math.abs(z) > 4) discovery.owner(x, z);
            discovery.report("expanded[-8,8]");
        }
        if (!discovery.covered()) {
            for (int z = -16; z <= 16 && !discovery.covered(); z++)
                for (int x = -16; x <= 16 && !discovery.covered(); x++)
                    if (Math.abs(x) > 8 || Math.abs(z) > 8) discovery.owner(x, z);
            discovery.report("expanded[-16,16]");
        }
        // Report bounded work before failing coverage; absence is never represented as a pass.
        require(
                discovery.covered(),
                "Missing actual accepted connected lake, accepted closed pond, or rejected-shape/plain wet fallback");
        int cell = discovery.maps.terrain.hydro.cellBlocks;
        discovery.points.add(
                new Point(
                        Math.floorDiv(7124, cell),
                        Math.floorDiv(3179, cell),
                        -2,
                        7124,
                        3179,
                        "MARINE_CONTROL",
                        0));
        discovery.points.add(new Point(-1, -1, -2, -1, -1, "NEGATIVE_CONTROL", 0));
        discovery.points.add(new Point(-1, -1, -2, -17, -16, "NEGATIVE_CONTROL", 0));
        discovery.points.add(new Point(0, 0, -2, 0, 0, "ORIGIN_CONTROL", 0));
        try (BufferedWriter writer = newFile(output)) {
            writer.write(MANIFEST);
            writer.newLine();
            for (Point point : discovery.points) {
                writer.write(point.row());
                writer.newLine();
            }
        }
        System.out.println(
                "Discovery complete: "
                        + discovery.points.size()
                        + " selected columns; bank-probe/outlet/outside labels are observations, not topology proofs.");
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

    private static String bodyFacts(Body body) {
        Basin basin = body.basin;
        if (basin == null)
            return "NETWORK_LAKE|"
                    + Long.toHexString(body.id)
                    + "|"
                    + bits(body.x)
                    + "|"
                    + bits(body.z)
                    + "|"
                    + bits(body.plan.radius)
                    + "|"
                    + bits(body.plan.depth)
                    + "|"
                    + bits(body.plan.lakeHead)
                    + "|"
                    + bits(body.plan.supply);
        return basin.kind
                + "|"
                + basin.supply
                + "|"
                + Long.toHexString(basin.id)
                + "|"
                + bits(basin.x)
                + "|"
                + bits(basin.z)
                + "|"
                + bits(basin.radius)
                + "|"
                + bits(basin.depth)
                + "|"
                + bits(basin.skirt)
                + "|"
                + bits(basin.aspect)
                + "|"
                + bits(basin.cos)
                + "|"
                + bits(basin.sin)
                + "|"
                + basin.waterY
                + "|"
                + bits(basin.support());
    }

    private static String planFacts(Hydro.Plan plan) {
        StringBuilder out = new StringBuilder(Long.toHexString(plan.id));
        out.append('|')
                .append(plan.present())
                .append('|')
                .append(plan.nodes())
                .append('|')
                .append(bits(plan.lakeX))
                .append('|')
                .append(bits(plan.lakeZ))
                .append('|')
                .append(bits(plan.radius))
                .append('|')
                .append(bits(plan.depth))
                .append('|')
                .append(bits(plan.lakeHead))
                .append('|')
                .append(bits(plan.supply))
                .append('|')
                .append(plan.probes);
        for (int i = 0; i < plan.nodes(); i++)
            out.append("|NODE")
                    .append(i)
                    .append('=')
                    .append(bits(plan.x(i)))
                    .append('/')
                    .append(bits(plan.z(i)))
                    .append('/')
                    .append(bits(plan.head(i)))
                    .append('/')
                    .append(bits(plan.width(i)));
        out.append("|BASINS=").append(plan.basins());
        for (int i = 0; i < plan.basins(); i++) {
            Basin basin = plan.basin(i);
            out.append('|')
                    .append(i)
                    .append('=')
                    .append(basin.kind)
                    .append('/')
                    .append(basin.supply)
                    .append('/')
                    .append(Long.toHexString(basin.id))
                    .append('/')
                    .append(bits(basin.x))
                    .append('/')
                    .append(bits(basin.z))
                    .append('/')
                    .append(basin.waterY)
                    .append('/')
                    .append(bits(basin.support()));
        }
        Lens lens = plan.lens;
        out.append("|LENS=");
        if (lens == null) out.append("NONE");
        else
            out.append(Long.toHexString(lens.id))
                    .append('/')
                    .append(bits(lens.x))
                    .append('/')
                    .append(bits(lens.z))
                    .append('/')
                    .append(bits(lens.y))
                    .append('/')
                    .append(bits(lens.radius))
                    .append('/')
                    .append(bits(lens.vertical))
                    .append('/')
                    .append(lens.head)
                    .append('/')
                    .append(lens.roof);
        out.append("|RIVERS=");
        if (plan.rivers == null) out.append("NONE");
        else {
            out.append(plan.rivers.candidates())
                    .append('/')
                    .append(plan.rivers.accepted)
                    .append('/')
                    .append(plan.rivers.certificateColumns);
            for (int i = 0; i < plan.rivers.candidates(); i++) {
                cc.orthrus.riven.worldgen.RiverShape.Decision decision = plan.rivers.decision(i);
                out.append('|')
                        .append(decision.edge)
                        .append('=')
                        .append(decision.family)
                        .append('/')
                        .append(decision.reason)
                        .append('/')
                        .append(decision.probes)
                        .append('/')
                        .append(decision.shape != null);
            }
        }
        return out.toString();
    }

    private static String shapeFacts(LakeShape shape) {
        if (shape == null) return "NONE";
        Lakes.Plan g = shape.geometry;
        Lakes.Tuning t = g.t;
        Lakes.Earlier e = g.earlier;
        return bits(shape.radius)
                + "|"
                + bits(shape.outerRadius)
                + "|"
                + bits(shape.foundation)
                + "|"
                + shape.certificateColumns
                + "|"
                + g.family
                + "|"
                + bits(g.anchorX)
                + "|"
                + bits(g.anchorZ)
                + "|"
                + bits(g.supportRadius)
                + "|"
                + Long.toHexString(g.key)
                + "|T="
                + bits(t.width())
                + "/"
                + bits(t.depth())
                + "/"
                + bits(t.aspect())
                + "/"
                + bits(t.detail())
                + "/"
                + bits(t.shelfFraction())
                + "/"
                + bits(t.islandRadius())
                + "/"
                + bits(t.skirtFraction())
                + "/"
                + bits(t.freeboard())
                + "/"
                + bits(t.maxCut())
                + "/"
                + bits(t.outletWidth())
                + "/"
                + bits(t.outletDepth())
                + "|EARLIER="
                + e.waterY()
                + "/"
                + e.supplied()
                + "/"
                + e.marine()
                + "/"
                + e.protectedShore()
                + "/"
                + e.reservedClear()
                + "/"
                + bits(e.certifiedRadius())
                + "/"
                + bits(e.parentMin())
                + "/"
                + bits(e.parentMax())
                + "/"
                + e.sealedFloor()
                + "/"
                + e.receiverCertified()
                + "/"
                + e.receiverWaterY();
    }

    private static String waterFacts(Hydro.Sample water) {
        return water.kind
                + "|"
                + water.wet
                + "|"
                + Long.toHexString(water.id)
                + "|"
                + water.waterY
                + "|"
                + bits(water.floor)
                + "|"
                + bits(water.bank)
                + "|"
                + bits(water.weight)
                + "|"
                + bits(water.head)
                + "|"
                + water.bed
                + "|"
                + water.aquiferMin
                + "|"
                + water.aquiferMax
                + "|"
                + water.aquiferRoof
                + "|"
                + Long.toHexString(water.aquiferId);
    }

    private static Body body(Point point, Hydro.Plan plan) {
        if (point.basinIndex == -2) return null;
        require(point.basinIndex >= -1 && point.basinIndex < plan.basins(), "Invalid body index");
        if (point.basinIndex == -1) require(plan.present(), "Frozen network disappeared");
        Body body = new Body(point.ownerX, point.ownerZ, plan, point.basinIndex);
        require(body.id == point.bodyId && body.decision != null, "Frozen lake body changed");
        if (point.role.startsWith("LAKE_")) {
            require(body.kind == Hydro.Kind.LAKE && body.accepted(), "Accepted lake disappeared");
            require(
                    plan.nodes() > 1
                            && plan.head(0) == plan.lakeHead
                            && plan.head(1) == plan.lakeHead,
                    "Lake lost its actual same-head first receiver segment");
            require(
                    body.decision.shape.geometry.earlier.receiverCertified()
                            && body.decision.shape.geometry.earlier.receiverWaterY()
                                    == plan.lakeHead,
                    "Lake lost certified receiver");
        } else if (point.role.startsWith("POND_")) {
            require(
                    body.kind == Hydro.Kind.POND
                            && body.basin.supply == Basin.Supply.GROUNDWATER
                            && body.accepted(),
                    "Accepted closed groundwater pond disappeared");
            require(
                    body.decision.shape.geometry.t.outletWidth() == 0,
                    "Closed pond acquired an outlet");
        } else
            require(
                    !body.accepted() && !body.decision.reason.equals("ACCEPT"),
                    "Rejected-shape plain fallback disappeared");
        return body;
    }

    private static String snapshot(
            GenerationMaps maps, Point point, int stage, TerrainMap.Sample sample) {
        Hydro.Plan plan = maps.terrain.hydro.plan(point.ownerX, point.ownerZ);
        Body body = body(point, plan);
        LakeShape.Decision decision = body == null ? null : body.decision;
        LakeShape shape = decision == null ? null : decision.shape;
        Hydro.Sample raw = new Hydro.Sample();
        maps.terrain.hydro.sample(point.x, point.z, raw);
        if (point.role.endsWith("_WET"))
            require(
                    body != null
                            && raw.wet
                            && raw.id == body.id
                            && raw.kind == body.kind
                            && sample.water.wet
                            && sample.water.id == body.id,
                    "Frozen actual wet body witness disappeared: " + point.row());
        if (point.role.endsWith("_BANK"))
            require(
                    !raw.wet && raw.weight > 0 && raw.id == point.bodyId,
                    "Frozen actual bank witness disappeared");
        if (point.role.equals("MARINE_CONTROL"))
            require(sample.ocean.marine, "Marine reset control no longer marine");
        Hydro.Sample water = sample.water;
        return point.row()
                + ","
                + stage
                + ","
                + (decision == null ? "NONE" : decision.reason)
                + ","
                + (decision == null ? 0 : decision.probes)
                + ","
                + (shape != null)
                + ","
                + (shape == null ? "NONE" : shape.profile)
                + ","
                + (body == null ? "NONE" : body.kind)
                + ","
                + planFacts(plan)
                + ","
                + (body == null ? "NONE" : bodyFacts(body))
                + ","
                + shapeFacts(shape)
                + ","
                + waterFacts(raw)
                + ","
                + bits(sample.height)
                + ","
                + bits(sample.originalHeight)
                + ","
                + bits(sample.groundwaterHead)
                + ","
                + bits(sample.rockyParentHeight)
                + ","
                + water.kind
                + ","
                + water.wet
                + ","
                + Long.toHexString(water.id)
                + ","
                + water.waterY
                + ","
                + bits(water.floor)
                + ","
                + bits(water.bank)
                + ","
                + bits(water.weight)
                + ","
                + bits(water.head)
                + ","
                + water.bed
                + ","
                + water.aquiferMin
                + ","
                + water.aquiferMax
                + ","
                + water.aquiferRoof
                + ","
                + Long.toHexString(water.aquiferId)
                + ","
                + sample.ocean.marine
                + ","
                + sample.ocean.supplied
                + ","
                + sample.ocean.protectedWater
                + ","
                + bits(sample.ocean.coastCoordinate)
                + ","
                + bits(sample.shore.height)
                + ","
                + bits(sample.minimumY)
                + ","
                + bits(sample.maximumY)
                + ","
                + (shape == null ? "NONE" : shape.allowsRiver(point.x, point.z))
                + ","
                + maps.surfaces.reason(sample)
                + ","
                + maps.surfaces.bareStone(sample);
    }

    private static void evaluate(
            GenerationMaps maps, Point point, int stage, TerrainMap.Sample sample) {
        if (stage == 0) maps.terrain.sampleBeforeMajorFeatures(point.x, point.z, sample);
        else if (stage == 1) maps.terrain.sampleBeforeRocky(point.x, point.z, sample);
        else maps.terrain.sample(point.x, point.z, sample);
        require(
                Double.isFinite(sample.height) && Double.isFinite(sample.groundwaterHead),
                "Nonfinite terrain observation");
    }

    private static void capture(Path input, Path output) throws Exception {
        List<Point> points = new ArrayList<>();
        for (String row : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (row.equals(MANIFEST)) continue;
            String[] v = row.split(",", -1);
            require(v.length == 8 && Long.parseLong(v[0]) == SEED, "Invalid frozen witness row");
            String role = v[6];
            require(
                    role.startsWith("LAKE_")
                            || role.startsWith("POND_")
                            || role.startsWith("FALLBACK_")
                            || role.equals("MARINE_CONTROL")
                            || role.equals("NEGATIVE_CONTROL")
                            || role.equals("ORIGIN_CONTROL"),
                    "Unknown witness role");
            points.add(
                    new Point(
                            Integer.parseInt(v[1]),
                            Integer.parseInt(v[2]),
                            Integer.parseInt(v[3]),
                            Integer.parseInt(v[4]),
                            Integer.parseInt(v[5]),
                            role,
                            Long.parseUnsignedLong(v[7], 16)));
        }
        require(!points.isEmpty(), "Empty witness manifest");
        GenerationMaps maps = new GenerationMaps(SEED);
        List<String> rows = new ArrayList<>();
        boolean lake = false, pond = false, fallback = false, marine = false, negative = false;
        for (Point point : points) {
            for (int stage = 0; stage < 3; stage++) {
                TerrainMap.Sample fresh = new TerrainMap.Sample();
                evaluate(maps, point, stage, fresh);
                rows.add(snapshot(maps, point, stage, fresh));
            }
            lake |= point.role.equals("LAKE_WET");
            pond |= point.role.equals("POND_WET");
            fallback |= point.role.equals("FALLBACK_WET");
            marine |= point.role.equals("MARINE_CONTROL");
            negative |= point.role.equals("NEGATIVE_CONTROL") && point.x < 0 && point.z < 0;
        }
        require(
                lake && pond && fallback && marine && negative,
                "Missing frozen positive/fallback/reset coverage");
        TerrainMap.Sample reused = new TerrainMap.Sample();
        for (int pass = 0; pass < 2; pass++)
            for (int offset = 0; offset < rows.size(); offset++) {
                int index = pass == 0 ? offset : rows.size() - offset - 1;
                Point point = points.get(index / 3);
                int stage = index % 3;
                evaluate(maps, point, stage, reused);
                require(
                        rows.get(index).equals(snapshot(maps, point, stage, reused)),
                        "Fresh/reused forward/reverse mismatch: "
                                + point.row()
                                + " stage="
                                + stage);
            }
        try (BufferedWriter writer = newFile(output)) {
            writer.write(HEADER);
            writer.newLine();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
        System.out.println(
                "PASS selected lake/pond boundary capture: columns="
                        + points.size()
                        + " rows="
                        + rows.size()
                        + "; accepted connected lake, accepted closed pond, actual"
                        + " rejected-shape/plain wet fallback and reset controls; fresh/reused forward/reverse agree.");
        System.out.println(
                "Reference="
                        + TerrainHeightReference.DEFAULT.fingerprint
                        + "; maps are reused: no cold-cache, eviction, concurrency, complete family occurrence,"
                        + " native block, fluid topology, DH or performance qualification.");
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
