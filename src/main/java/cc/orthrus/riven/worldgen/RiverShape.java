package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.river.Rivers;

import com.google.gson.JsonObject;

/** Finite channel replacements on earlier supplied native river segments. */
public final class RiverShape {
    private static final Rules RULES = Rules.load("riven-rivers.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final long VARIATION = Rules.seed(RULES.json, "variationSeed");
    private static final Rivers.Family[] FAMILIES = {
        Rivers.Family.BEND, Rivers.Family.SPLIT, Rivers.Family.POCKET, Rivers.Family.JUNCTION
    };
    private static final double[] WEIGHTS = weights();
    private static final Rivers.Tuning TUNING =
            new Rivers.Tuning(
                    Rules.number(RULES.json, "bendWidths", 0, 2),
                            Rules.number(RULES.json, "widthVariation", 0, .25),
                    Rules.number(RULES.json, "poolFraction", 0, .35),
                            Rules.number(RULES.json, "barFraction", 0, .8),
                    Rules.number(RULES.json, "splitWidths", .5, 1.5),
                            Rules.number(RULES.json, "bankSkirtBlocks", 1, 12));
    private static final double BRANCH =
            Rules.number(RULES.json, "branchSeparationWidths", .5, 1.5);
    private static final double SPLIT_LOW = Rules.number(RULES.json, "minimumSplitLowland", 0, 1);
    private static final double SPLIT_RUGGED =
            Rules.number(RULES.json, "maximumSplitRuggedness", 0, 1);
    private static final double POCKET_RUGGED =
            Rules.number(RULES.json, "minimumPocketRuggedness", 0, 1);
    private static final int MAX_COLUMNS =
            (int) Rules.number(RULES.json, "maximumOwnerCertificateColumns", 1024, 65536);

    static {
        if (RULES.json.get("maximumOwnerCertificateColumns").getAsDouble() != MAX_COLUMNS)
            throw new IllegalStateException("Integer river certificate bound required");
    }

    public static final class Decision {
        public final int edge, probes;
        public final Rivers.Family family;
        public final String reason;
        public final Reach shape;

        private Decision(int edge, Rivers.Family family, String reason, int probes, Reach shape) {
            this.edge = edge;
            this.family = family;
            this.reason = reason;
            this.probes = probes;
            this.shape = shape;
        }
    }

    public static final class Reach {
        public final Rivers.ColumnPlan geometry;
        public final double startX,
                startZ,
                endX,
                endZ,
                length,
                allowance,
                foundation,
                width,
                depth,
                branchLength,
                startWater,
                endWater,
                bridgeRadius;
        private final double cos, sin;

        private Reach(
                Rivers.ColumnPlan geometry,
                double ax,
                double az,
                double bx,
                double bz,
                double allowance,
                double foundation,
                double width,
                double depth,
                double branchLength,
                double startWater,
                double endWater,
                double bridgeRadius) {
            this.geometry = geometry;
            startX = ax;
            startZ = az;
            endX = bx;
            endZ = bz;
            length = Math.hypot(bx - ax, bz - az);
            cos = (bx - ax) / length;
            sin = (bz - az) / length;
            this.allowance = allowance;
            this.foundation = foundation;
            this.width = width;
            this.depth = depth;
            this.branchLength = branchLength;
            this.startWater = startWater;
            this.endWater = endWater;
            this.bridgeRadius = bridgeRadius;
        }

        private boolean owns(double x, double z) {
            double dx = x - startX, dz = z - startZ, along = dx * cos + dz * sin;
            return along >= 0 && along <= length && Math.abs(-dx * sin + dz * cos) <= allowance;
        }
    }

    private final Decision[] decisions;
    public final int accepted, certificateColumns;

    private RiverShape(Decision[] decisions, int accepted, int columns) {
        this.decisions = decisions;
        this.accepted = accepted;
        certificateColumns = columns;
    }

    public int candidates() {
        return decisions.length;
    }

    public Decision decision(int index) {
        return decisions[index];
    }

    private static double[] weights() {
        JsonObject json = RULES.json.getAsJsonObject("familyWeights");
        double[] weights = new double[FAMILIES.length];
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Rules.number(json, FAMILIES[i].name(), 0, 100);
            total += weights[i];
        }
        if (total <= 0)
            throw new IllegalStateException("River family weights require a positive total");
        return weights;
    }

    private static double unit(long key, long salt) {
        return SeededNoise.unit(SeededNoise.mix(key ^ salt));
    }

    private static Decision reject(int edge, Rivers.Family family, String reason, int probes) {
        return new Decision(edge, family, reason, probes, null);
    }

    public static RiverShape prepare(
            long seed,
            Hydro.Plan network,
            IntentSampler intent,
            TerrainHeightReference heights,
            double bankScale,
            double freeboard,
            double maxCut,
            double maxFill,
            int cellX,
            int cellZ,
            int cellBlocks) {
        Decision[] decisions = new Decision[Math.max(0, network.nodes() - 2)];
        int accepted = 0, columns = 0;
        for (int edge = 1; edge < network.nodes() - 1; edge++) {
            long key =
                    SeededNoise.mix(
                            seed
                                    ^ VARIATION
                                    ^ network.id
                                    ^ SeededNoise.mix(edge + 0x5245414348303031L));
            int selected = Rules.choose(WEIGHTS, unit(key, 0x46414d494c593031L));
            Rivers.Family family = FAMILIES[Math.max(0, selected)];
            Decision decision =
                    selected < 0
                            ? reject(edge, family, "FAMILY", 0)
                            : prepareReach(
                                    seed,
                                    key,
                                    network,
                                    edge,
                                    family,
                                    intent,
                                    heights,
                                    bankScale,
                                    freeboard,
                                    maxCut,
                                    maxFill,
                                    cellX,
                                    cellZ,
                                    cellBlocks,
                                    MAX_COLUMNS - columns,
                                    decisions);
            decisions[edge - 1] = decision;
            columns += decision.probes;
            if (decision.shape != null) accepted++;
        }
        return new RiverShape(decisions, accepted, columns);
    }

    private static Decision prepareReach(
            long seed,
            long key,
            Hydro.Plan network,
            int edge,
            Rivers.Family family,
            IntentSampler intent,
            TerrainHeightReference heights,
            double bankScale,
            double freeboard,
            double maxCut,
            double maxFill,
            int cellX,
            int cellZ,
            int cellBlocks,
            int remainingColumns,
            Decision[] earlier) {
        double ax = network.x(edge),
                az = network.z(edge),
                bx = network.x(edge + 1),
                bz = network.z(edge + 1);
        double dx = bx - ax,
                dz = bz - az,
                fullLength = Math.hypot(dx, dz),
                cos = dx / fullLength,
                sin = dz / fullLength;
        double nativeHalf = Math.min(network.width(edge), network.width(edge + 1));
        double allowance = nativeHalf * (1 + bankScale),
                incidentHalf = Math.max(network.width(edge), network.width(edge + 1));
        if (edge > 0) incidentHalf = Math.max(incidentHalf, network.width(edge - 1));
        if (edge + 2 < network.nodes())
            incidentHalf = Math.max(incidentHalf, network.width(edge + 2));
        if (allowance <= incidentHalf + 1) return reject(edge, family, "HANDOFF_WIDTH", 0);
        // Leave supplied legacy bridges at both ends and avoid adjacent segments' wet capsules.
        double trim = allowance + incidentHalf + 2, length = fullLength - 2 * trim;
        if (length < 16 || length > 512) return reject(edge, family, "REACH_LENGTH", 0);
        ax += cos * trim;
        az += sin * trim;
        bx -= cos * trim;
        bz -= sin * trim;
        if (Math.max(Math.max(Math.abs(ax), Math.abs(az)), Math.max(Math.abs(bx), Math.abs(bz)))
                        + allowance
                        + 1
                > 29999000) return reject(edge, family, "WORLD_BOUNDS", 0);
        double startWater = water(network, edge, ax, az), endWater = water(network, edge, bx, bz);
        double lakeSupport =
                network.radius + heights.feature("river_bed").width.targetAverage * bankScale;
        if (Rivers.pointDistance(network.lakeX, network.lakeZ, ax, az, bx, bz)
                <= lakeSupport + allowance + 1) return reject(edge, family, "LAKE_RESERVATION", 0);
        for (int i = 0; i < network.basins(); i++) {
            Basin basin = network.basin(i);
            double support = basin.support();
            if (basin.kind == Hydro.Kind.LAGOON) support = Math.hypot(support, basin.skirt * .25);
            // Include the existing outside-mouth receiver probes as well as all body/bank support.
            if (Rivers.pointDistance(basin.x, basin.z, ax, az, bx, bz)
                    <= support + 4 + allowance + 1)
                return reject(edge, family, "BASIN_RESERVATION", 0);
        }
        for (int i = 0; i < network.nodes() - 1; i++)
            if (i != edge) {
                double otherHalf = Math.max(network.width(i), network.width(i + 1));
                if (Rivers.segmentDistance(
                                ax,
                                az,
                                bx,
                                bz,
                                network.x(i),
                                network.z(i),
                                network.x(i + 1),
                                network.z(i + 1))
                        <= allowance + otherHalf + .5)
                    return reject(edge, family, "OTHER_WET_ROUTE", 0);
            }
        for (Decision previous : earlier)
            if (previous != null && previous.shape != null) {
                Reach shape = previous.shape;
                if (Rivers.segmentDistance(
                                ax, az, bx, bz, shape.startX, shape.startZ, shape.endX, shape.endZ)
                        <= allowance + shape.allowance + 1)
                    return reject(edge, family, "REACH_RESERVATION", 0);
            }
        TerrainHeightReference.Feature ref =
                heights.feature(family == Rivers.Family.POCKET ? "stream_bed" : "river_bed");
        double variation = TUNING.widthVariation;
        double minimum =
                Math.max(
                        ref.width.min * TerrainScale.HORIZONTAL / (1 - variation),
                        (Math.sqrt(2) + 2e-6) / (1 - variation));
        double requested = Math.max(ref.width.targetAverage * TerrainScale.HORIZONTAL, minimum);
        double move =
                family == Rivers.Family.BEND
                        ? TUNING.bendWidths
                        : family == Rivers.Family.SPLIT
                                ? TUNING.splitWidths
                                : family == Rivers.Family.POCKET ? .25 : BRANCH + .35;
        double width =
                Math.min(
                        requested,
                        Math.min(
                                (allowance - 2 * TUNING.bankSkirt - 1)
                                        / (move + .5 * (1 + variation)),
                                Math.min(
                                        2 * (nativeHalf - .5) / (1 + variation),
                                        ref.width.max
                                                * TerrainScale.HORIZONTAL
                                                / (1 + variation))));
        if (width < minimum) return reject(edge, family, "CORRIDOR_WIDTH", 0);
        double depth = ref.magnitude.targetAverage;
        double mx = (ax + bx) * .5, mz = (az + bz) * .5, midWater = water(network, edge, mx, mz);
        double firstLength = Math.hypot(mx - ax, mz - az),
                lastLength = Math.hypot(bx - mx, bz - mz);
        if (firstLength < 2
                || lastLength < 2
                || firstLength > 256
                || lastLength > 256
                || firstLength + lastLength < 16
                || startWater < midWater
                || midWater < endWater) return reject(edge, family, "ROUTE_INPUT", 0);
        Rivers.Route main =
                new Rivers.Route(
                        key,
                        new double[] {ax, mx, bx},
                        new double[] {az, mz, bz},
                        new double[] {startWater, midWater, endWater},
                        width,
                        depth);
        if (main.grade > family.maxGrade) return reject(edge, family, "GRADE", 0);
        Rivers.Route branch = null;
        if (family == Rivers.Family.JUNCTION) {
            // An earlier anabranch takes real water at the upstream trunk and rejoins its interior
            // node.
            // This plans topology before the geometry kernel; no independent water source is
            // invented.
            double side = unit(key, 0x4252414e43483031L) < .5 ? -1 : 1,
                    offset = side * BRANCH * width;
            double sx = (ax + mx) * .5 - sin * offset, sz = (az + mz) * .5 + cos * offset;
            double first = Math.hypot(sx - ax, sz - az), last = Math.hypot(mx - sx, mz - sz);
            if (first < 2 || last < 2 || first + last < 16 || first > 256 || last > 256)
                return reject(edge, family, "BRANCH_LENGTH", 0);
            branch =
                    new Rivers.Route(
                            SeededNoise.mix(key ^ 0x4252414e43485254L),
                            new double[] {ax, sx, mx},
                            new double[] {az, sz, mz},
                            new double[] {startWater, (startWater + midWater) * .5, midWater},
                            width,
                            depth);
            if (branch.grade > family.maxGrade) return reject(edge, family, "BRANCH_GRADE", 0);
        }
        // The unmodified earlier native river really supplies both handoff points.
        boolean supplied = nativeWet(network, ax, az, startWater);
        boolean receiver =
                nativeWet(network, bx, bz, endWater)
                        && (branch == null || nativeWet(network, mx, mz, midWater));
        if (!supplied || !receiver) return reject(edge, family, "HANDOFF", 0);
        double foundation = Math.floor(startWater) + Math.max(2, freeboard),
                lowest = Math.floor(endWater) - ref.magnitude.max;
        if (foundation > heights.highestSurface
                || lowest < heights.lowestSurface
                || foundation - lowest > maxCut) return reject(edge, family, "ALLOCATION_CUT", 0);
        double pad = allowance + 1;
        int minX = (int) Math.floor(Math.min(ax, bx) - pad),
                maxX = (int) Math.ceil(Math.max(ax, bx) + pad);
        int minZ = (int) Math.floor(Math.min(az, bz) - pad),
                maxZ = (int) Math.ceil(Math.max(az, bz) + pad);
        if (minX <= cellX * (double) cellBlocks
                || minZ <= cellZ * (double) cellBlocks
                || maxX >= (cellX + 1.) * cellBlocks
                || maxZ >= (cellZ + 1.) * cellBlocks) return reject(edge, family, "OWNER", 0);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > remainingColumns)
            return reject(edge, family, "WORK_BOUND", 0);
        TerrainMap.BaseSample scratch = new TerrainMap.BaseSample();
        int probes = 0;
        double minLow = 1, maxRugged = 0;
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) {
                intent.sample(x, z, scratch);
                probes++;
                if (scratch.ocean.marine || scratch.shore.sandy || scratch.shore.rocky())
                    return reject(edge, family, "PROTECTED_SHORE", probes);
                if (foundation - scratch.shore.height > maxFill
                        || scratch.shore.height - lowest > maxCut)
                    return reject(edge, family, "EARTHWORK", probes);
                minLow = Math.min(minLow, scratch.land.low);
                maxRugged = Math.max(maxRugged, scratch.land.rugged);
            }
        if (family == Rivers.Family.SPLIT && (minLow < SPLIT_LOW || maxRugged > SPLIT_RUGGED))
            return reject(edge, family, "SPLIT_TERRAIN", probes);
        if (family == Rivers.Family.POCKET && maxRugged < POCKET_RUGGED)
            return reject(edge, family, "POCKET_TERRAIN", probes);
        // The full envelope fits the old reservation. Its constructed parent is constant and above
        // every head.
        // Native solid fill and final aquifer roof sealing protect its deepest allowed floor.
        Rivers.ColumnAdmission facts =
                new Rivers.ColumnAdmission(
                        family,
                        main,
                        branch,
                        TUNING,
                        TerrainScale.HORIZONTAL,
                        supplied,
                        receiver,
                        true,
                        false,
                        true,
                        allowance,
                        foundation,
                        foundation,
                        probes);
        Rivers.ColumnResult result =
                Rivers.prepareColumns(
                        family,
                        seed ^ VARIATION,
                        main,
                        branch,
                        facts,
                        TUNING,
                        heights,
                        TerrainScale.HORIZONTAL);
        if (result.plan == null) return reject(edge, family, "KERNEL_" + result.reason, probes);
        Reach shape =
                new Reach(
                        result.plan,
                        ax,
                        az,
                        bx,
                        bz,
                        allowance,
                        foundation,
                        width,
                        depth,
                        branch == null ? 0 : branch.length,
                        startWater,
                        endWater,
                        incidentHalf);
        return new Decision(edge, family, "ACCEPT", probes, shape);
    }

    private static double water(Hydro.Plan network, int edge, double x, double z) {
        double dx = network.x(edge + 1) - network.x(edge),
                dz = network.z(edge + 1) - network.z(edge);
        double t =
                Math.max(
                        0,
                        Math.min(
                                1,
                                ((x - network.x(edge)) * dx + (z - network.z(edge)) * dz)
                                        / (dx * dx + dz * dz)));
        return network.head(edge) + t * (network.head(edge + 1) - network.head(edge));
    }

    private static boolean nativeWet(Hydro.Plan network, double x, double z, double expectedHead) {
        double nearest = Double.POSITIVE_INFINITY, head = Double.NaN;
        for (int i = 0; i < network.nodes() - 1; i++) {
            double dx = network.x(i + 1) - network.x(i), dz = network.z(i + 1) - network.z(i);
            double t =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    ((x - network.x(i)) * dx + (z - network.z(i)) * dz)
                                            / (dx * dx + dz * dz)));
            double distance = Math.hypot(x - network.x(i) - t * dx, z - network.z(i) - t * dz);
            double half = network.width(i) + t * (network.width(i + 1) - network.width(i));
            if (distance / half < nearest) {
                nearest = distance / half;
                head = network.head(i) + t * (network.head(i + 1) - network.head(i));
            }
        }
        return nearest <= 1 && Math.floor(head) == Math.floor(expectedHead);
    }

    /** Returns ownership even where a displaced channel leaves the old thalweg dry. */
    public boolean apply(double x, double z, long networkId, Hydro.Sample out) {
        if (x != Math.floor(x) || z != Math.floor(z)) return false;
        for (Decision decision : decisions) {
            Reach shape = decision.shape;
            if (shape == null || !shape.owns(x, z)) continue;
            out.riverShape = decision;
            if (out.wet) return true;
            shape.geometry.sample(x, z, shape.foundation, true, out.riverGeometry);
            Rivers.Sample sample = out.riverGeometry;
            if (sample.wet) {
                out.wet = true;
                out.floor = sample.surface;
                out.waterY = (int) sample.waterY;
                out.head = out.waterY;
                out.weight = 1;
                out.kind = Hydro.Kind.RIVER;
                out.id = networkId;
                out.bed = sample.bar ? Hydro.Bed.SAND : Hydro.Bed.GRAVEL;
            } else {
                double dx = x - shape.startX,
                        dz = z - shape.startZ,
                        along = dx * shape.cos + dz * shape.sin;
                double cross = Math.abs(-dx * shape.sin + dz * shape.cos),
                        endDistance = Math.min(along, shape.length - along);
                double lateralSkirt =
                        Math.min(TUNING.bankSkirt, shape.allowance - shape.bridgeRadius - 1);
                // Seal the wider supplied legacy bridge for one column at each terminal plane.
                double gate =
                        (1 - Rules.smooth((endDistance - 1) / TUNING.bankSkirt))
                                * (1
                                        - Rules.smooth(
                                                (cross - shape.bridgeRadius - 1) / lateralSkirt));
                double weight = Math.max(sample.foundationWeight, gate);
                if (weight > out.weight) {
                    out.bank = sample.surface + (shape.foundation - sample.surface) * gate;
                    out.weight = weight;
                    out.kind = Hydro.Kind.RIVER;
                    out.id = networkId;
                    out.head =
                            shape.startWater
                                    + along / shape.length * (shape.endWater - shape.startWater);
                }
            }
            return true;
        }
        return false;
    }
}
