package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.estuaries.EstuaryKernels;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Properties;

/** Supplied coastal geometry with complete expanded lagoon reservations, before final occupancy. */
public final class CoastShape {
    private static final Rules RULES = Rules.load("riven-estuaries.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;
    private static final long VARIATION = Rules.seed(RULES.json, "variationSeed");
    private static final EstuaryKernels.Family[] FAMILIES = {
        EstuaryKernels.Family.LOBED_LAGOON,
        EstuaryKernels.Family.CRESCENT_LAGOON,
        EstuaryKernels.Family.BRANCHING_ESTUARY,
        EstuaryKernels.Family.FUNNEL_ESTUARY
    };
    private static final double[] WEIGHTS = weights();
    private static final double SHELTER_GAIN = Rules.number(RULES.json, "shelterGain", 1, 4);
    private static final int MAX_COLUMNS =
            (int) Rules.number(RULES.json, "maximumCertificateColumns", 1024, 1048576);

    static {
        if (RULES.json.get("maximumCertificateColumns").getAsDouble() != MAX_COLUMNS)
            throw new IllegalStateException("Coast column bound");
    }

    public static final class Decision {
        public final CoastShape shape;
        public final EstuaryKernels.Family family;
        public final String reason;
        public final int probes, receiverColumns;

        private Decision(
                CoastShape shape,
                EstuaryKernels.Family family,
                String reason,
                int probes,
                int receiverColumns) {
            this.shape = shape;
            this.family = family;
            this.reason = reason;
            this.probes = probes;
            this.receiverColumns = receiverColumns;
        }
    }

    /** Per-caller scratch. No terrain/world reference, retained raster or shared mutation. */
    public static final class Column {
        final ContinentalMap.Sample ocean = new ContinentalMap.Sample();
        final OceanBed.Result seabed = new OceanBed.Result();
        final EstuaryKernels.Sample geometry = new EstuaryKernels.Sample();
    }

    public final EstuaryKernels.ColumnPlan geometry;
    public final long ownerId;
    public final double length, foundation, shelter, sediment, receiverHalfWidth;
    public final int certificateColumns, receiverColumns;
    private final int waterY;
    private final ContinentalMap continents;
    private final OceanBed oceanBed;

    private CoastShape(
            EstuaryKernels.ColumnPlan geometry,
            double length,
            double foundation,
            double shelter,
            double sediment,
            double receiverHalfWidth,
            int columns,
            int receivers,
            int waterY,
            long ownerId,
            ContinentalMap continents,
            OceanBed oceanBed) {
        this.geometry = geometry;
        this.length = length;
        this.foundation = foundation;
        this.shelter = shelter;
        this.sediment = sediment;
        this.receiverHalfWidth = receiverHalfWidth;
        certificateColumns = columns;
        receiverColumns = receivers;
        this.waterY = waterY;
        this.ownerId = ownerId;
        this.continents = continents;
        this.oceanBed = oceanBed;
    }

    private static double[] weights() {
        JsonObject values = RULES.json.getAsJsonObject("familyWeights");
        double[] out = new double[FAMILIES.length];
        double total = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = Rules.number(values, FAMILIES[i].name(), 0, 100);
            total += out[i];
        }
        if (total <= 0) throw new IllegalStateException("Positive coastal family weight required");
        return out;
    }

    private static Decision reject(
            EstuaryKernels.Family f, String reason, int columns, int receivers) {
        return new Decision(null, f, reason, columns, receivers);
    }

    private static void put(Properties p, String key, double value) {
        p.setProperty(key, Double.toString(value));
    }

    private static EstuaryKernels.Heights reference(TerrainHeightReference heights) {
        Properties p = new Properties();
        p.setProperty("sea", Integer.toString(heights.seaLevel));
        p.setProperty("water", Integer.toString(heights.waterTopY));
        p.setProperty("minSurface", Integer.toString(heights.lowestSurface));
        p.setProperty("maxSurface", Integer.toString(heights.highestSurface));
        TerrainHeightReference.Feature lake = heights.feature("lake_bed"),
                river = heights.feature("river_bed");
        put(p, "lakeWidth", lake.width.targetAverage * TerrainScale.HORIZONTAL);
        put(p, "lakeWidthMin", lake.width.min * TerrainScale.HORIZONTAL);
        put(p, "lakeWidthMax", lake.width.max * TerrainScale.HORIZONTAL);
        put(p, "lakeDepth", lake.magnitude.targetAverage);
        put(p, "riverWidth", river.width.targetAverage * TerrainScale.HORIZONTAL);
        put(p, "riverDepth", river.magnitude.targetAverage);
        put(p, "beachOffset", heights.feature("beach").elevationOffset.targetAverage);
        p.setProperty("fingerprint", heights.fingerprint);
        return new EstuaryKernels.Heights(p);
    }

    public static Decision prepare(
            long seed,
            Basin host,
            List<Basin> basins,
            Hydro.Plan network,
            EarlierNetworkSampler earlierNetwork,
            IntentSampler intent,
            ContinentalMap continents,
            OceanBed oceanBed,
            TerrainHeightReference heights,
            int cellX,
            int cellZ,
            int cellBlocks,
            double freeboard,
            double maxCut,
            double maxFill,
            double bankScale) {
        long key = SeededNoise.mix(seed ^ VARIATION ^ host.id);
        int chosen =
                Rules.choose(WEIGHTS, SeededNoise.unit(SeededNoise.mix(key ^ 0x46414d494c593031L)));
        EstuaryKernels.Family family = FAMILIES[Math.max(0, chosen)];
        if (chosen < 0) return reject(family, "FAMILY", 0, 0);
        if (host.kind != Hydro.Kind.LAGOON
                || host.supply != Basin.Supply.OCEAN
                || host.waterY != heights.waterTopY) return reject(family, "SUPPLY", 0, 0);
        TerrainMap.BaseSample base = new TerrainMap.BaseSample();
        intent.sample(host.x, host.z, base);
        double nx = base.shore.normalX, nz = base.shore.normalZ, norm = Math.hypot(nx, nz);
        if (norm < .99 || !Double.isFinite(norm)) return reject(family, "SHORE_FRAME", 0, 0);
        nx /= norm;
        nz /= norm;
        // One fixed bracket on the earlier continental boundary, with bounded bisection.
        double bracket = host.support() * .5;
        ContinentalMap.Sample probe = new ContinentalMap.Sample();
        continents.sample(host.x - nx * bracket, host.z - nz * bracket, probe);
        if (!probe.marine) return reject(family, "OCEAN_BRACKET", 0, 0);
        continents.sample(host.x + nx * bracket, host.z + nz * bracket, probe);
        if (probe.marine) return reject(family, "LAND_BRACKET", 0, 0);
        double lo = -bracket, hi = bracket;
        for (int i = 0; i < 32; i++) {
            double at = (lo + hi) * .5;
            continents.sample(host.x + nx * at, host.z + nz * at, probe);
            if (probe.marine) lo = at;
            else hi = at;
        }
        double offset = (lo + hi) * .5, x = host.x + nx * offset, z = host.z + nz * offset;
        intent.sample(x + nx, z + nz, base);
        double shelter = Rules.smooth(base.shore.shelter * SHELTER_GAIN);
        double sediment = Math.max(0, Math.min(1, base.land.low * (1 - base.land.rugged)));
        EstuaryKernels.Reason opportunity = EstuaryKernels.opportunity(family, shelter, sediment);
        if (opportunity != EstuaryKernels.Reason.ACCEPTED)
            return reject(family, "KERNEL_" + opportunity, 0, 0);
        EstuaryKernels.Heights h = reference(heights);
        JsonObject rules = RULES.json;
        double length = h.lakeWidth * Rules.number(rules, "lengthInLakeWidths", .5, 2);
        double inlet =
                Math.max(
                        2,
                        h.riverWidth * Rules.number(rules, "inletHalfWidthInRiverWidths", .1, 1));
        double skirt = Math.max(4, h.riverWidth * Rules.number(rules, "skirtInRiverWidths", .1, 2));
        if (length < 128 * TerrainScale.HORIZONTAL
                || length > 1024 * TerrainScale.HORIZONTAL
                || length < h.lakeWidthMin
                || length > h.lakeWidthMax
                || inlet > .04 * length
                || skirt > .08 * length) return reject(family, "REFERENCE_FIT", 0, 0);
        Properties tuning = new Properties();
        put(tuning, "lengthInLakeWidths", length / h.lakeWidth);
        put(tuning, "depthInLakeDepths", Rules.number(rules, "depthInLakeDepths", .1, 1));
        put(tuning, "inletHalfWidthInRiverWidths", inlet / h.riverWidth);
        put(tuning, "skirtInRiverWidths", skirt / h.riverWidth);
        put(tuning, "lobeVariation", Rules.number(rules, "lobeVariation", 0, .15));
        put(tuning, "branchSpread", Rules.number(rules, "branchSpread", .2, .4));
        put(tuning, "channelBend", Rules.number(rules, "channelBend", 0, .08));
        put(tuning, "barStrength", Rules.number(rules, "barStrength", 0, .8));
        EstuaryKernels.Tuning t =
                EstuaryKernels.Tuning.nativeColumns(h, tuning, TerrainScale.HORIZONTAL);
        double radius = 1.5 * t.length;
        if (continents.scaffoldClearance(x, z) <= radius + 2)
            return reject(family, "OCEAN_SCAFFOLD", 0, 0);
        if (Math.abs(x) + radius + 1 > 29999984
                || Math.abs(z) + radius + 1 > 29999984
                || x - radius - 1 <= cellX * (double) cellBlocks
                || z - radius - 1 <= cellZ * (double) cellBlocks
                || x + radius + 1 >= (cellX + 1.) * cellBlocks
                || z + radius + 1 >= (cellZ + 1.) * cellBlocks)
            return reject(family, "OWNER", 0, 0);
        for (Basin other : basins)
            if (other != host) {
                double support = other.support();
                if (other.kind == Hydro.Kind.LAGOON)
                    support = Math.hypot(support, other.skirt * .25);
                if (Math.hypot(x - other.x, z - other.z) <= radius + support + 2)
                    return reject(family, "BASIN_CONFLICT", 0, 0);
            }
        if (network.present()
                && Math.hypot(x - network.lakeX, z - network.lakeZ)
                        <= radius
                                + network.radius
                                + heights.feature("river_bed").width.targetAverage * bankScale
                                + 2) return reject(family, "LAKE_CONFLICT", 0, 0);
        double[] routeU = new double[0], routeV = new double[0];
        if (!family.lagoon()) {
            if (!network.present()) return reject(family, "NO_INCOMING_ROUTE", 0, 0);
            routeU = new double[5];
            routeV = new double[5];
            for (int n = 0; n < 5; n++) {
                double v = -.25 + 1.1 * n / 4.0;
                routeV[n] = v;
                boolean found = false;
                // Raw routes run inland to sea; walk backward to obtain an increasing inland frame.
                for (int j = network.nodes() - 2; j >= 0; j--) {
                    double av = ((network.x(j) - x) * nx + (network.z(j) - z) * nz) / t.length;
                    double bv =
                            ((network.x(j + 1) - x) * nx + (network.z(j + 1) - z) * nz) / t.length;
                    if (av <= bv || v < bv || v > av) continue;
                    double at = (av - v) / (av - bv),
                            rx = network.x(j) + at * (network.x(j + 1) - network.x(j));
                    double rz = network.z(j) + at * (network.z(j + 1) - network.z(j));
                    double head = network.head(j) + at * (network.head(j + 1) - network.head(j));
                    if (Math.floor(head) != h.water) return reject(family, "INCOMING_HEAD", 0, 0);
                    routeU[n] = ((rx - x) * nz - (rz - z) * nx) / t.length;
                    if (Math.abs(routeU[n]) > .12) return reject(family, "ROUTE_FRAME", 0, 0);
                    found = true;
                    break;
                }
                if (!found) return reject(family, "ROUTE_EXTENT", 0, 0);
            }
        }
        EstuaryKernels.ColumnLayout layout =
                new EstuaryKernels.ColumnLayout(
                        seed ^ VARIATION ^ host.id, cellX, cellZ, x, z, nx, nz, routeU, routeV);
        EstuaryKernels.ColumnProposal proposal =
                EstuaryKernels.proposeColumns(family, h, t, layout);
        if (proposal.reason != EstuaryKernels.Reason.ACCEPTED)
            return reject(family, "LAYOUT_" + proposal.reason, 0, 0);
        double required =
                family.lagoon()
                        ? .16 * t.length + t.inletHalfWidth
                        : t.length * (family == EstuaryKernels.Family.FUNNEL_ESTUARY ? .40 : .23);
        int minX = (int) Math.floor(x - radius - 1), maxX = (int) Math.ceil(x + radius + 1);
        int minZ = (int) Math.floor(z - radius - 1), maxZ = (int) Math.ceil(z + radius + 1);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_COLUMNS)
            return reject(family, "WORK_BOUND", 0, 0);
        double foundation = h.water + Math.max(2, freeboard),
                high = h.water + Math.max(Math.max(2, freeboard), t.crestAboveWater);
        double low = h.water - t.depth,
                minParent = Double.POSITIVE_INFINITY,
                maxParent = Double.NEGATIVE_INFINITY;
        OceanBed.Result bed = new OceanBed.Result();
        Hydro.Sample water = new Hydro.Sample();
        EstuaryKernels.Sample proposed = new EstuaryKernels.Sample();
        int columns = 0, receivers = 0;
        for (int pz = minZ; pz <= maxZ; pz++)
            for (int px = minX; px <= maxX; px++) {
                intent.sample(px, pz, base);
                columns++;
                double parent = foundation;
                if (base.ocean.marine) {
                    oceanBed.apply(px, pz, base.ocean, false, bed);
                    parent = bed.height;
                } else {
                    if (base.shore.rocky())
                        return reject(family, "PROTECTED_ROCK", columns, receivers);
                    if (high - base.shore.height > maxFill || base.shore.height - low > maxCut)
                        return reject(family, "EARTHWORK", columns, receivers);
                }
                minParent = Math.min(minParent, parent);
                maxParent = Math.max(maxParent, parent);
                proposal.inspectColumn(px, pz, parent, base.ocean.marine, proposed);
                earlierNetwork.sample(px, pz, network, water);
                boolean affected =
                        proposed.wet || proposed.weight > 0 || proposed.foundationWeight > 0;
                if (affected
                        && ((water.wet && water.waterY != h.water)
                                || (!water.wet
                                        && water.weight > 0
                                        && Math.floor(water.head) != h.water)))
                    return reject(family, "RESERVED_HEAD", columns, receivers);
                double u = (px - x) * nz - (pz - z) * nx, v = (px - x) * nx + (pz - z) * nz;
                if (family.lagoon()
                        && base.ocean.marine
                        && (proposed.barrierRequired
                                || (v >= 0 && proposed.wet && !proposed.channel)))
                    return reject(family, "BARRIER_MARINE_CONFLICT", columns, receivers);
                // A complete integer strip across every possible seeded ocean mouth, with a voxel
                // halo.
                if (Math.abs(v + .25 * t.length) <= 2 && Math.abs(u) <= required + 2) {
                    receivers++;
                    if (!base.ocean.marine
                            || !base.ocean.supplied
                            || parent >= h.water
                            || (water.weight > 0 && (!water.wet || water.waterY != h.water)))
                        return reject(family, "OCEAN_RECEIVER", columns, receivers);
                }
            }
        if (receivers == 0) return reject(family, "EMPTY_RECEIVER", columns, receivers);
        // The dry foundation is analytically constant. Actual marine parents retain their earlier
        // bed.
        EstuaryKernels.ColumnFacts facts =
                new EstuaryKernels.ColumnFacts(
                        proposal,
                        columns,
                        minParent,
                        maxParent,
                        0,
                        shelter,
                        sediment,
                        required,
                        receivers > 0,
                        columns == (long) (maxX - minX + 1) * (maxZ - minZ + 1),
                        true,
                        false,
                        h.water,
                        h.water);
        EstuaryKernels.ColumnDecision decision = EstuaryKernels.planColumns(proposal, facts);
        if (decision.plan == null)
            return reject(family, "KERNEL_" + decision.reason, columns, receivers);
        CoastShape shape =
                new CoastShape(
                        decision.plan,
                        t.length,
                        foundation,
                        shelter,
                        sediment,
                        required,
                        columns,
                        receivers,
                        h.water,
                        host.id,
                        continents,
                        oceanBed);
        return new Decision(shape, family, "ACCEPT", columns, receivers);
    }

    public void apply(double x, double z, Hydro.Sample out, Decision decision) {
        if (Math.hypot(x - geometry.x, z - geometry.z) > geometry.supportRadius) return;
        out.coastShape = decision;
        Column scratch = out.coastGeometry;
        if (out.wet)
            return; // The certified earlier wet reservation remains exact, including owner and bed.
        continents.sample(x, z, scratch.ocean);
        double parent = foundation;
        if (scratch.ocean.marine) {
            oceanBed.apply(x, z, scratch.ocean, false, scratch.seabed);
            parent = scratch.seabed.height;
        }
        geometry.sample(
                x,
                z,
                parent,
                scratch.ocean.marine,
                out.wet,
                out.waterY,
                out.floor,
                scratch.geometry);
        EstuaryKernels.Sample sample = scratch.geometry;
        if (sample.earlierReservation) return;
        if ((sample.wet || (scratch.ocean.marine && sample.weight > 0)) && sample.height < waterY) {
            out.wet = true;
            out.floor = sample.height;
            out.waterY = waterY;
            out.head = waterY;
            out.weight = 1;
            out.kind = Hydro.Kind.LAGOON;
            out.id = ownerId;
            out.bed = Hydro.Bed.SAND;
        } else if (scratch.ocean.marine && sample.weight > 0) {
            // A geometric marine label alone does not make a solid shoal into occupied water.
            out.bank = sample.height;
            out.weight = 1;
            out.head = waterY;
            out.kind = Hydro.Kind.LAGOON;
            out.id = ownerId;
            out.bed = Hydro.Bed.SAND;
        } else if (sample.foundationWeight > 0) {
            if (out.weight >= sample.foundationWeight) out.bank = Math.max(out.bank, sample.height);
            else {
                out.bank = sample.height;
                out.weight = sample.foundationWeight;
            }
            out.head = waterY;
            out.kind = Hydro.Kind.LAGOON;
            out.id = ownerId;
            out.bed = Hydro.Bed.SAND;
        }
    }
}
