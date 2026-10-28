package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded constructive coastal catchments. Only earlier intent is queried.
 * The optional memo holds immutable geometry, never terrain, jobs or world objects.
 * Misses may duplicate bounded construction; no caller waits for a preparer.
 */
public final class Hydro
        implements BoundedWaterClearance, OwnerEdgeWaterClearance, SurfaceWaterPlanSampler {
    private static final Rules RULES = Rules.load("riven-hydrology.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    public enum Kind {
        NONE,
        LAKE,
        RIVER,
        WETLAND,
        OASIS,
        LAGOON,
        POND
    }

    public enum Bed {
        GRAVEL,
        SAND,
        DIRT
    }

    public static final class Sample {
        public Kind kind = Kind.NONE;
        public boolean wet;
        public int waterY = Integer.MIN_VALUE;
        public long id;
        public double floor, bank, weight, head;

        /** 1 at a river's ocean mouth, fading to 0 at the mouth radius; 0 elsewhere. */
        public double mouth;

        /** Wet only by a drainage stream too narrow to be a river: the land keeps its biome. */
        public boolean stream;

        /**
         * The nearest drainage channel whose bank or valley reaches a dry column: its water level,
         * the distance beyond its edge and its bank span. The hydrology constraint cuts the valley
         * from the column's own height.
         */
        public boolean channel;

        public double channelHead, channelBeyond, channelBank;

        /** The highest water beside a dry column within Drainage.LEVEE of a bed. */
        public double channelLevee;

        /** A dry bar at a channel's water level (a braided bed's or a point bar), or null. */
        public Bed bar;

        public Bed bed = Bed.GRAVEL;
        public int aquiferMin = Integer.MAX_VALUE, aquiferMax = Integer.MIN_VALUE, aquiferRoof;
        public long aquiferId;

        /**
         * An underground lake: water up to aquiferLevel inside [aquiferMin, aquiferMax], air above;
         * carving leaves rock in [aquiferShellLo, aquiferShellHi] around it below the waterline.
         */
        public int aquiferLevel = Integer.MIN_VALUE,
                aquiferShellLo = Integer.MAX_VALUE,
                aquiferShellHi = Integer.MIN_VALUE;

        final cc.orthrus.riven.contrib.lakes.Lakes.Sample lakeGeometry =
                new cc.orthrus.riven.contrib.lakes.Lakes.Sample();
        final cc.orthrus.riven.contrib.river.Rivers.Sample riverGeometry =
                new cc.orthrus.riven.contrib.river.Rivers.Sample();
        public RiverShape.Decision riverShape;
        public CoastShape.Decision coastShape;
        final CoastShape.Column coastGeometry = new CoastShape.Column();

        void clear() {
            kind = Kind.NONE;
            bed = Bed.GRAVEL;
            wet = false;
            waterY = Integer.MIN_VALUE;
            id = aquiferId = 0;
            floor = bank = weight = head = mouth = 0;
            stream = channel = false;
            bar = null;
            channelHead = channelBeyond = channelBank = channelLevee = 0;
            aquiferMin = Integer.MAX_VALUE;
            aquiferMax = Integer.MIN_VALUE;
            aquiferRoof = 0;
            aquiferLevel = Integer.MIN_VALUE;
            aquiferShellLo = Integer.MAX_VALUE;
            aquiferShellHi = Integer.MIN_VALUE;
            riverShape = null;
            coastShape = null;
        }
    }

    /** Immutable plan inspection. Arrays are private; clients cannot mutate cached geometry. */
    public static final class Plan {
        public final long id;
        public final double lakeX, lakeZ, radius, depth, lakeHead, supply;
        public final int probes;
        private final Basin[] basins;
        public final Lens lens;
        public final LakeShape.Decision lakeShape;
        public final RiverShape rivers;
        private final double[] x, z, level, width;

        /** Meander radius at each node: the channel's, fading to zero through an estuary. */
        private final double[] meander;

        /** Valley half-width beyond the bank at each node: valleyRatio times the local cut. */
        private final double[] valley;

        private final double minX, maxX, minZ, maxZ, cos, sin, aspect;

        /** Where the route first reaches sea level; NaN without a network. */
        public final double mouthX, mouthZ;

        /**
         * The owner cell's further networks, each a complete lake and route without basins; the
         * primary network above is the one basins and coast shapes attach to.
         */
        private final Plan[] others;

        private Plan() {
            id = 0;
            lakeX =
                    lakeZ =
                            radius =
                                    depth = lakeHead = supply = minX = maxX = minZ = maxZ = sin = 0;
            cos = aspect = 1;
            mouthX = mouthZ = Double.NaN;
            probes = 0;
            x = z = level = width = meander = valley = new double[0];
            basins = new Basin[0];
            others = new Plan[0];
            lens = null;
            lakeShape = null;
            rivers = null;
        }

        private Plan(
                long id,
                double[] x,
                double[] z,
                double[] level,
                double[] width,
                double[] meander,
                double[] valley,
                double radius,
                double depth,
                double head,
                double supply,
                double angle,
                double aspect,
                int probes,
                double bankScale,
                double meanderReach,
                LakeShape.Decision lakeShape) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.level = level;
            this.width = width;
            this.meander = meander;
            this.valley = valley;
            lakeX = x[0];
            lakeZ = z[0];
            this.radius = radius;
            this.depth = depth;
            lakeHead = head;
            this.supply = supply;
            cos = Math.cos(angle);
            sin = Math.sin(angle);
            this.aspect = aspect;
            this.probes = probes;
            basins = new Basin[0];
            others = new Plan[0];
            lens = null;
            this.lakeShape = lakeShape;
            rivers = null;
            double margin = radius * 1.5 + bankScale * width[0];
            double a = lakeX - margin, b = lakeX + margin, c = lakeZ - margin, d = lakeZ + margin;
            int mouth = x.length - 1;
            while (mouth > 0 && level[mouth - 1] <= level[x.length - 1] + 1e-9) mouth--;
            mouthX = x[mouth];
            mouthZ = z[mouth];
            for (int i = 0; i < x.length; i++) {
                margin = width[i] * (1 + bankScale) + 4 + meanderReach + valley[i];
                a = Math.min(a, x[i] - margin);
                b = Math.max(b, x[i] + margin);
                c = Math.min(c, z[i] - margin);
                d = Math.max(d, z[i] + margin);
            }
            minX = a;
            maxX = b;
            minZ = c;
            maxZ = d;
        }

        private Plan(Plan network, Basin[] basins, Lens lens) {
            this(network, basins, lens, network.rivers, network.others);
        }

        private Plan(Plan network, Basin[] basins, Lens lens, RiverShape rivers) {
            this(network, basins, lens, rivers, network.others);
        }

        private Plan(Plan network, Basin[] basins, Lens lens, RiverShape rivers, Plan[] others) {
            id = network.id;
            x = network.x;
            z = network.z;
            level = network.level;
            width = network.width;
            meander = network.meander;
            valley = network.valley;
            lakeX = network.lakeX;
            lakeZ = network.lakeZ;
            radius = network.radius;
            depth = network.depth;
            lakeHead = network.lakeHead;
            supply = network.supply;
            probes = network.probes;
            cos = network.cos;
            sin = network.sin;
            aspect = network.aspect;
            mouthX = network.mouthX;
            mouthZ = network.mouthZ;
            this.basins = basins;
            this.others = others;
            this.lens = lens;
            lakeShape = network.lakeShape;
            this.rivers = rivers;
            double a = network.present() ? network.minX : Double.POSITIVE_INFINITY,
                    b = network.present() ? network.maxX : Double.NEGATIVE_INFINITY;
            double c = network.present() ? network.minZ : Double.POSITIVE_INFINITY,
                    d = network.present() ? network.maxZ : Double.NEGATIVE_INFINITY;
            for (Basin basin : basins) {
                double r = basin.support();
                a = Math.min(a, basin.x - r);
                b = Math.max(b, basin.x + r);
                c = Math.min(c, basin.z - r);
                d = Math.max(d, basin.z + r);
            }
            for (Plan other : others) {
                a = Math.min(a, other.minX);
                b = Math.max(b, other.maxX);
                c = Math.min(c, other.minZ);
                d = Math.max(d, other.maxZ);
            }
            minX = a;
            maxX = b;
            minZ = c;
            maxZ = d;
        }

        public boolean present() {
            return x.length > 0;
        }

        public int nodes() {
            return x.length;
        }

        public double x(int node) {
            return x[node];
        }

        public double z(int node) {
            return z[node];
        }

        public double head(int node) {
            return level[node];
        }

        public double width(int node) {
            return width[node];
        }

        public int basins() {
            return basins.length;
        }

        public Basin basin(int index) {
            return basins[index];
        }

        /** The owner cell's further networks (lake and route only). */
        public int others() {
            return others.length;
        }

        public Plan other(int index) {
            return others[index];
        }
    }

    private static final Plan EMPTY = new Plan();

    private static final class Entry {
        final long key;
        final Plan plan;

        Entry(long key, Plan plan) {
            this.key = key;
            this.plan = plan;
        }
    }

    private final IntentSampler intent;
    private final GroundwaterPotential groundwater;
    private final ContinentalMap continents;
    private final OceanBed oceanBed;
    private final EarlierNetworkSampler earlierNetworkSampler;
    private final TerrainHeightReference heights;
    private final long seed;
    public final int cellBlocks, sourceAttempts, directions, segments, cacheCapacity;
    private final int networksPerCell;
    public final double maxRoute, maxCut, maxFill, bankScale, freeboard;
    private final int sets, ways;
    private final double minimumMoisture;
    private final int patchAttempts, roof;
    private final double wetMoisture, oasisMoisture, oasisTemperature, lensChance;
    private final double lakeMoisture, lakeDome, lakeShell, lakeLowest, lakeBelowFlood;
    private final double meanderHalfWave, meanderAmplitude, meanderReach, mouthRadius;
    private final double headHigh, headMiddle, headUplift;
    private final TerrainHeightReference.Feature lake, river;
    private final AtomicReferenceArray<Entry> memo;
    private final SharedBuild<Plan> builds = new SharedBuild<>();

    private final ContinentalOutlineMap outline;

    /** Drainage networks (hydro-v8): every continent's streams and rivers, memoized per continent. */
    final Drainage drainage;

    private static final class DrainageQuery {
        final Drainage.Hit wet = new Drainage.Hit(), near = new Drainage.Hit();
        final double[] mouth = new double[1];
        final Sample receiving = new Sample();
    }

    private final ThreadLocal<DrainageQuery> drainageQuery =
            ThreadLocal.withInitial(DrainageQuery::new);

    /**
     * Funnel estuary of trunks and network rivers: its length from the mouth and the widest flare,
     * in channel widths.
     */
    private final double estuaryLength, estuaryFlare;

    private final double valleyRatio;
    private final AtomicIntegerArray next;

    Hydro(
            long seed,
            TerrainHeightReference heights,
            IntentSampler intent,
            GroundwaterPotential groundwater,
            ContinentalMap continents,
            OceanBed oceanBed) {
        this.seed = SeededNoise.mix(seed ^ Rules.seed(RULES.json, "variationSeed"));
        this.heights = heights;
        this.intent = intent;
        this.groundwater = groundwater;
        this.continents = continents;
        this.oceanBed = oceanBed;
        this.outline = continents.outline;
        JsonObject j = RULES.json;
        cellBlocks = integer(j, "cellBlocks", 2048, 8192);
        // aquiferIntersects reads one owner per cube, so cubes must never straddle owners.
        if (cellBlocks % 16 != 0)
            throw new IllegalStateException("Hydro cells must align with 16-block cubes");
        sourceAttempts = integer(j, "sourceAttempts", 1, 64);
        networksPerCell = integer(j, "networksPerCell", 1, 8);
        directions = integer(j, "directions", 4, 12);
        segments = integer(j, "routeSegments", 8, 24);
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        cacheCapacity = sets * ways;
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Hydro cache sets must be a power of two");
        minimumMoisture = Rules.number(j, "minimumMoisture", 0, 1);
        maxRoute = Rules.number(j, "maximumRouteBlocks", 256, cellBlocks * .6);
        maxCut = Rules.number(j, "maximumCutBlocks", 1, 112);
        maxFill = Rules.number(j, "maximumFillBlocks", 0, 32);
        bankScale = Rules.number(j, "bankWidthInRiverWidths", 1, 4);
        freeboard = Rules.number(j, "freeboardBlocks", 1, 4);
        JsonObject layers = j.getAsJsonObject("layers");
        patchAttempts = integer(layers, "attempts", 1, 8);
        roof = integer(layers, "aquiferRoofBlocks", 4, 32);
        wetMoisture = Rules.number(layers, "wetlandMoisture", .4, 1);
        oasisMoisture = Rules.number(layers, "oasisMaximumMoisture", 0, .4);
        oasisTemperature = Rules.number(layers, "oasisMinimumCelsius", 15, 40);
        lensChance = Rules.number(layers, "aquiferChance", 0, 1);
        JsonObject lakes = layers.getAsJsonObject("lakes");
        lakeMoisture = Rules.number(lakes, "minimumMoisture", 0, 1);
        lakeDome = Rules.number(lakes, "domeScale", 0.5, 4);
        lakeShell = Rules.number(lakes, "shellBlocks", 1, 8);
        lakeLowest = Rules.number(lakes, "lowestY", heights.minimumY, heights.maximumYExclusive);
        lakeBelowFlood = Rules.number(lakes, "belowFloodBlocks", 0, 256);
        JsonObject meander = j.getAsJsonObject("meander");
        meanderHalfWave = Rules.number(meander, "halfWaveInChannelRadii", 2, 64);
        meanderAmplitude = Rules.number(meander, "amplitudeInChannelRadii", 0, 8);
        JsonObject heads = j.getAsJsonObject("riverHeads");
        headHigh = Rules.number(heads, "highWeight", 0, 4);
        headMiddle = Rules.number(heads, "middleWeight", 0, 4);
        headUplift = Rules.number(heads, "upliftWeight", 0, 4);
        mouthRadius = Rules.number(j, "mouthRadiusBlocks", 0, 1024);
        lake = heights.feature("lake_bed");
        river = heights.feature("river_bed");
        // Largest channel radius is river.width.max * .5 * 1.2 (network widths below).
        meanderReach = meanderAmplitude * river.width.max * .6;
        memo = new AtomicReferenceArray<>(cacheCapacity);
        next = new AtomicIntegerArray(sets);
        earlierNetworkSampler = this::earlierNetwork;
        JsonObject drains = j.getAsJsonObject("drainage");
        valleyRatio = Rules.number(drains, "valleyInCutDepths", 0, 8);
        JsonObject estuary = j.getAsJsonObject("estuary");
        estuaryLength = Rules.number(estuary, "lengthBlocks", 0, 1024);
        estuaryFlare = Rules.number(estuary, "flareInWidths", 1, 8);
        drainage =
                new Drainage(
                        seed,
                        drains,
                        heights,
                        intent,
                        continents,
                        outline,
                        this::receivingWater,
                        bankScale,
                        maxCut,
                        estuaryLength,
                        estuaryFlare);
    }

    private static int integer(JsonObject j, String key, int min, int max) {
        double n = Rules.number(j, key, min, max);
        if (n != (int) n) throw new IllegalStateException(key);
        return (int) n;
    }

    public Plan plan(int cellX, int cellZ) {
        long key = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.plan;
        }
        // Sediment and rocky plans of one owner cell miss it together; build it once for all.
        Plan built = builds.get(key, () -> build(cellX, cellZ));
        // Publish only after construction. An eviction cannot alter a sample's geometry.
        for (int i = 0; i < ways; i++) {
            Entry e = memo.get(first + i);
            if (e != null && e.key == key) return e.plan;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    private Plan build(int cellX, int cellZ) {
        double originX = cellX * (double) cellBlocks, originZ = cellZ * (double) cellBlocks;
        // Neighbor clearance queries may request owners outside the sampling domain.
        if (originX < -29999984
                || originZ < -29999984
                || originX + cellBlocks > 29999984
                || originZ + cellBlocks > 29999984) return EMPTY;
        Plan[] networks = networks(cellX, cellZ);
        Plan network = networks.length == 0 ? EMPTY : networks[0];
        if (networks.length > 1) {
            Plan[] others = new Plan[networks.length - 1];
            for (int i = 1; i < networks.length; i++)
                others[i - 1] =
                        new Plan(
                                networks[i],
                                new Basin[0],
                                null,
                                RiverShape.prepare(
                                        seed,
                                        networks[i],
                                        intent,
                                        heights,
                                        bankScale,
                                        freeboard,
                                        maxCut,
                                        maxFill,
                                        cellX,
                                        cellZ,
                                        cellBlocks),
                                new Plan[0]);
            network = new Plan(network, network.basins, network.lens, network.rivers, others);
        }
        java.util.ArrayList<Basin> basins = new java.util.ArrayList<>(3);
        TerrainMap.BaseSample scratch = new TerrainMap.BaseSample();
        long cell = SeededNoise.cell(seed ^ 0x424153494e533032L, cellX, cellZ);
        if (network.present()) {
            intent.sample(network.lakeX, network.lakeZ, scratch);
            if (scratch.climate.moisture >= wetMoisture
                    && scratch.climate.celsius > 5
                    && scratch.land.low > .5) {
                double vx = network.x[1] - network.x[0],
                        vz = network.z[1] - network.z[0],
                        length = Math.hypot(vx, vz);
                double radius = heights.feature("wetland_pool").width.targetAverage * 1.6;
                double x = network.lakeX - vz / length * network.radius,
                        z = network.lakeZ + vx / length * network.radius;
                Basin wet =
                        Basin.create(
                                Kind.WETLAND,
                                Basin.Supply.STORAGE,
                                SeededNoise.mix(cell + 1),
                                x,
                                z,
                                (int) network.lakeHead,
                                radius,
                                heights.feature("wetland_pool").magnitude.max,
                                river.width.targetAverage,
                                Math.atan2(vz, vx));
                if (admit(wet, cellX, cellZ, scratch, true)) basins.add(wet);
            }
            // Last land-side receiver provides both the fresh inlet and the seaward opening.
            for (int i = network.nodes() - 2; i >= 2; i--) {
                if (Math.floor(network.head(i)) != heights.waterTopY) continue;
                intent.sample(network.x(i), network.z(i), scratch);
                if (scratch.ocean.marine
                        || scratch.shore.proximity < .5
                        || scratch.shore.rocky()
                        || scratch.land.low < .5) continue;
                double angle =
                        Math.atan2(
                                network.z(i + 1) - network.z(i), network.x(i + 1) - network.x(i));
                Basin lagoon =
                        Basin.create(
                                Kind.LAGOON,
                                Basin.Supply.OCEAN,
                                SeededNoise.mix(cell + 2),
                                network.x(i),
                                network.z(i),
                                heights.waterTopY,
                                lake.width.targetAverage * .22,
                                river.magnitude.targetAverage,
                                heights.feature("beach").width.targetAverage,
                                angle);
                if (lagoonReceiver(lagoon, cellX, cellZ, scratch, network)) basins.add(lagoon);
                break;
            }
        }
        // Sheltered sea-fed lagoons do not require a coincident inland lake/river lottery.
        boolean hasLagoon = false;
        for (Basin basin : basins) if (basin.kind == Kind.LAGOON) hasLagoon = true;
        if (!hasLagoon)
            for (int attempt = 0; attempt < patchAttempts; attempt++) {
                long id = SeededNoise.mix(cell ^ 0x53484f52454c4147L ^ attempt);
                double x = (cellX + .12 + .76 * unit(id, 1)) * cellBlocks,
                        z = (cellZ + .12 + .76 * unit(id, 2)) * cellBlocks;
                intent.sample(x, z, scratch);
                if (!Double.isFinite(scratch.shore.distance)
                        || Math.abs(scratch.shore.distance) > cellBlocks * .2) continue;
                double nx = scratch.shore.normalX, nz = scratch.shore.normalZ;
                double radius = lake.width.targetAverage * .22;
                x += nx * (radius * .15 - scratch.shore.distance);
                z += nz * (radius * .15 - scratch.shore.distance);
                if (!inside(x, z, originX, originZ, 0)) continue;
                intent.sample(x, z, scratch);
                if (scratch.ocean.marine
                        || scratch.land.low < .5
                        || scratch.shore.rocky()
                        || scratch.shore.shelter < .18
                        || scratch.climate.celsius < 5) continue;
                Basin lagoon =
                        Basin.create(
                                Kind.LAGOON,
                                Basin.Supply.OCEAN,
                                id,
                                x,
                                z,
                                heights.waterTopY,
                                radius,
                                river.magnitude.targetAverage,
                                heights.feature("beach").width.targetAverage,
                                Math.atan2(-scratch.shore.normalZ, -scratch.shore.normalX));
                if (network.present() && nearNetwork(lagoon, network)) continue;
                boolean overlaps = false;
                for (Basin other : basins)
                    if (Math.hypot(x - other.x, z - other.z) < lagoon.support() + other.support())
                        overlaps = true;
                if (!overlaps && lagoonReceiver(lagoon, cellX, cellZ, scratch, network)) {
                    basins.add(lagoon);
                    break;
                }
            }
        // A single independent basin candidate wins. Its eligibility reads only earlier maps.
        for (int attempt = 0; attempt < patchAttempts && basins.size() < 3; attempt++) {
            long id = SeededNoise.mix(cell + attempt * 0x9e3779b97f4a7c15L);
            double x = (cellX + .18 + .64 * unit(id, 1)) * cellBlocks,
                    z = (cellZ + .18 + .64 * unit(id, 2)) * cellBlocks;
            intent.sample(x, z, scratch);
            if (scratch.shore.marsh > .15) {
                double offset =
                        heights.feature("coastal_plain").width.targetAverage * .35
                                - scratch.shore.distance;
                x += scratch.shore.normalX * offset;
                z += scratch.shore.normalZ * offset;
                if (!inside(x, z, originX, originZ, 0)) continue;
                intent.sample(x, z, scratch);
            }
            if (scratch.ocean.marine
                    || scratch.shore.sandy
                    || scratch.shore.rocky()
                    || scratch.land.low < .5
                    || scratch.land.rugged > .35) continue;
            double head = groundwater.potential(x, z, scratch), gap = scratch.shore.height - head;
            if (gap < 1 || gap > heights.feature("hollow_basin").magnitude.max) continue;
            Kind kind =
                    scratch.climate.celsius >= oasisTemperature
                                    && scratch.climate.moisture <= oasisMoisture
                            ? Kind.OASIS
                            : (scratch.climate.moisture >= wetMoisture || scratch.shore.marsh > .15)
                                            && scratch.climate.celsius > 5
                                    ? Kind.WETLAND
                                    : scratch.climate.moisture >= .5 && unit(id, 8) < .3
                                            ? Kind.POND
                                            : Kind.NONE;
            if (kind == Kind.NONE) continue;
            TerrainHeightReference.Feature pond = heights.feature("pond_bed"),
                    wet = heights.feature("wetland_pool");
            double radius =
                    (kind == Kind.WETLAND
                            ? wet.width.targetAverage * 1.6
                            : pond.width.targetAverage * (.5 + .5 * unit(id, 4)));
            double depth = kind == Kind.WETLAND ? wet.magnitude.max : pond.magnitude.targetAverage;
            Basin basin =
                    Basin.create(
                            kind,
                            Basin.Supply.GROUNDWATER,
                            id,
                            x,
                            z,
                            (int) Math.floor(head),
                            radius,
                            depth,
                            heights.feature("hollow_basin").width.targetAverage * .3,
                            unit(id, 5) * Math.PI * 2);
            if (network.present() && nearNetwork(basin, network)) continue;
            boolean overlaps = false;
            for (Basin other : basins)
                if (Math.hypot(x - other.x, z - other.z) < basin.support() + other.support())
                    overlaps = true;
            if (overlaps || !admit(basin, cellX, cellZ, scratch, true)) continue;
            if (kind == Kind.POND)
                basin =
                        basin.withShape(
                                LakeShape.prepare(
                                        seed,
                                        id,
                                        intent,
                                        heights,
                                        kind,
                                        x,
                                        z,
                                        radius,
                                        depth,
                                        basin.waterY,
                                        basin.skirt,
                                        freeboard,
                                        maxCut,
                                        maxFill,
                                        cellX,
                                        cellZ,
                                        cellBlocks));
            basins.add(basin);
            break;
        }
        for (int i = 0; i < basins.size(); i++) {
            Basin basin = basins.get(i);
            if (basin.kind == Kind.LAGOON)
                basins.set(
                        i,
                        basin.withCoastShape(
                                CoastShape.prepare(
                                        seed,
                                        basin,
                                        basins,
                                        network,
                                        earlierNetworkSampler,
                                        intent,
                                        continents,
                                        oceanBed,
                                        heights,
                                        cellX,
                                        cellZ,
                                        cellBlocks,
                                        freeboard,
                                        maxCut,
                                        maxFill,
                                        bankScale)));
        }
        Lens lens = null;
        // Groundwater bodies and sealed pockets share their source head, without a dependency
        // cycle.
        Basin supplied = null;
        for (Basin basin : basins)
            if (basin.supply == Basin.Supply.GROUNDWATER) {
                supplied = basin;
                break;
            }
        double x = supplied != null ? supplied.x : (cellX + .2 + .6 * unit(cell, 41)) * cellBlocks;
        double z = supplied != null ? supplied.z : (cellZ + .2 + .6 * unit(cell, 42)) * cellBlocks;
        intent.sample(x, z, scratch);
        if (supplied != null
                || (!scratch.ocean.marine
                        && scratch.climate.moisture >= lakeMoisture
                        && unit(cell, 43) < lensChance)) {
            int head =
                    supplied != null
                            ? supplied.waterY
                            : (int) Math.floor(groundwater.potential(x, z, scratch));
            double vertical =
                    lake.magnitude.min
                            + (lake.magnitude.targetAverage - lake.magnitude.min) * unit(cell, 44);
            // A lake sits clear of the magma chambers below and of every flood band above.
            double low = Math.max(heights.minimumY + roof, lakeLowest) + vertical + 1,
                    high = head - FloodMap.BAND - lakeBelowFlood - lakeDome * vertical - 1;
            if (high > low)
                lens =
                        new Lens(
                                SeededNoise.mix(cell ^ 0x4c454e5330303031L),
                                x,
                                z,
                                low + (high - low) * unit(cell, 45),
                                lake.width.targetAverage * (.12 + .08 * unit(cell, 46)),
                                vertical,
                                lakeDome * vertical,
                                lakeShell,
                                head,
                                roof);
        }
        Plan complete =
                basins.isEmpty() && lens == null
                        ? network
                        : new Plan(network, basins.toArray(new Basin[0]), lens);
        if (!complete.present()) return complete;
        return new Plan(
                complete,
                complete.basins,
                complete.lens,
                RiverShape.prepare(
                        seed,
                        complete,
                        intent,
                        heights,
                        bankScale,
                        freeboard,
                        maxCut,
                        maxFill,
                        cellX,
                        cellZ,
                        cellBlocks));
    }

    private boolean lagoonReceiver(
            Basin basin, int cx, int cz, TerrainMap.BaseSample scratch, Plan network) {
        if (continents.scaffoldClearance(basin.x, basin.z) <= basin.support() + 2) return false;
        if (!inside(
                basin.x,
                basin.z,
                cx * (double) cellBlocks,
                cz * (double) cellBlocks,
                basin.support() + 4)) return false;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            intent.sample(
                    basin.x + Math.cos(a) * basin.support(),
                    basin.z + Math.sin(a) * basin.support(),
                    scratch);
            if (!scratch.ocean.marine
                    && (scratch.shore.rocky()
                            || scratch.shore.height - basin.waterY > maxCut
                            || basin.waterY + freeboard - scratch.shore.height > maxFill))
                return false;
        }
        // Bounded checks include the end of the reserved mouth and the first outside ocean blocks.
        Sample water = new Sample();
        for (int i = -4; i <= 4; i += 4) {
            double x = basin.x + basin.cos * (basin.support() + i),
                    z = basin.z + basin.sin * (basin.support() + i);
            intent.sample(x, z, scratch);
            if (!scratch.ocean.marine || !scratch.ocean.supplied) return false;
            // Original marine ownership alone cannot prove access through an existing river bank.
            earlierNetwork(x, z, network, water);
            if (water.weight > 0 && (!water.wet || water.waterY != heights.waterTopY)) return false;
        }
        return true;
    }

    private boolean nearNetwork(Basin basin, Plan network) {
        for (Plan other : network.others) if (nearNetwork(basin, other)) return true;
        if (Math.hypot(basin.x - network.lakeX, basin.z - network.lakeZ)
                < basin.support() + network.radius + 32) return true;
        for (int i = 0; i < network.nodes() - 1; i++) {
            double dx = network.x[i + 1] - network.x[i], dz = network.z[i + 1] - network.z[i];
            double t =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    ((basin.x - network.x[i]) * dx + (basin.z - network.z[i]) * dz)
                                            / (dx * dx + dz * dz)));
            if (Math.hypot(basin.x - network.x[i] - t * dx, basin.z - network.z[i] - t * dz)
                    < basin.support() + river.width.max * (1 + bankScale)) return true;
        }
        return false;
    }

    private boolean admit(
            Basin basin, int cx, int cz, TerrainMap.BaseSample scratch, boolean inland) {
        if (continents.scaffoldClearance(basin.x, basin.z) <= basin.support() + 2) return false;
        if (!inside(
                basin.x,
                basin.z,
                cx * (double) cellBlocks,
                cz * (double) cellBlocks,
                basin.support() + 4)) return false;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            intent.sample(
                    basin.x + Math.cos(angle) * basin.support(),
                    basin.z + Math.sin(angle) * basin.support(),
                    scratch);
            if (inland && (scratch.ocean.marine || scratch.shore.sandy || scratch.shore.rocky()))
                return false;
            if (Math.abs(scratch.shore.height - basin.waterY) > maxCut) return false;
        }
        return true;
    }

    public boolean aquiferIntersects(int cubeX, int cubeY, int cubeZ) {
        Plan p = plan(Math.floorDiv(cubeX * 16, cellBlocks), Math.floorDiv(cubeZ * 16, cellBlocks));
        return p.lens != null && p.lens.intersects(cubeX, cubeY, cubeZ);
    }

    /**
     * Up to networksPerCell lake-to-sea networks of one owner cell, in upland order: each source
     * takes its first valid route that keeps clear of the networks already taken, so the set is a
     * pure function of the cell.
     */
    private Plan[] networks(int cellX, int cellZ) {
        java.util.List<Plan> found = new java.util.ArrayList<>(networksPerCell);
        long cell = SeededNoise.cell(seed, cellX, cellZ);
        TerrainMap.BaseSample scratch = new TerrainMap.BaseSample();
        double originX = (double) cellX * cellBlocks, originZ = (double) cellZ * cellBlocks;
        double margin = lake.width.targetAverage * .75 + river.width.max * (1 + bankScale) + 8;
        int probes = 0;
        // River heads prefer mountains: try source attempts in descending upland score, so the
        // first valid source is the most mountainous one. Ties keep attempt order.
        int[] order = new int[sourceAttempts];
        double[] score = new double[sourceAttempts];
        ContinentalOutlineMap.Sample crestProbe = new ContinentalOutlineMap.Sample();
        for (int attempt = 0; attempt < sourceAttempts; attempt++) {
            long hash = SeededNoise.mix(cell + attempt * 0x9e3779b97f4a7c15L);
            intent.sample(
                    originX + cellBlocks * (.22 + .56 * unit(hash, 1)),
                    originZ + cellBlocks * (.22 + .56 * unit(hash, 2)),
                    scratch);
            probes++;
            score[attempt] =
                    scratch.ocean.marine
                            ? Double.NEGATIVE_INFINITY
                            : headHigh * scratch.land.high
                                    + headMiddle * scratch.land.middle
                                    + headUplift * scratch.uplift;
            int k = attempt;
            while (k > 0 && score[order[k - 1]] < score[attempt]) {
                order[k] = order[k - 1];
                k--;
            }
            order[k] = attempt;
        }
        sources:
        for (int rank = 0; rank < sourceAttempts; rank++) {
            int attempt = order[rank];
            long hash = SeededNoise.mix(cell + attempt * 0x9e3779b97f4a7c15L);
            double sx = originX + cellBlocks * (.22 + .56 * unit(hash, 1)),
                    sz = originZ + cellBlocks * (.22 + .56 * unit(hash, 2));
            intent.sample(sx, sz, scratch);
            probes++;
            if (scratch.ocean.marine
                    || scratch.climate.moisture < minimumMoisture
                    || scratch.shore.height < heights.seaLevel + 8) continue;
            // Crest land drains through its continent's trunks; cell networks keep the rest.
            outline.sampleCrest(sx, sz, crestProbe);
            if (crestProbe.crest() > 0) continue;
            double supply =
                    scratch.climate.moisture
                            * (1 - .12 * Math.max(0, scratch.climate.celsius) / 40);
            double radius = lake.width.targetAverage * (.24 + .2 * unit(hash, 3));
            if (!inside(
                    sx, sz, originX, originZ, radius + river.width.targetAverage * bankScale + 4))
                continue;
            if (continents.scaffoldClearance(sx, sz)
                    <= radius + river.width.targetAverage * bankScale + 2) continue;
            double lakeDepth =
                    lake.magnitude.min
                            + (lake.magnitude.targetAverage - lake.magnitude.min) * unit(hash, 4);
            double head = Math.floor(scratch.shore.height - lakeDepth * .35);
            if (head < heights.seaLevel + 4) continue;
            boolean contained = true;
            for (int rim = 0; rim < 16; rim++) {
                double a = 2 * Math.PI * rim / 16,
                        r = radius + river.width.targetAverage * bankScale + 4;
                intent.sample(sx + Math.cos(a) * r, sz + Math.sin(a) * r, scratch);
                probes++;
                if (scratch.ocean.marine
                        || scratch.shore.sandy
                        || Math.abs(scratch.shore.height - head) > maxCut) {
                    contained = false;
                    break;
                }
            }
            if (!contained) continue;
            for (int ray = 0; ray < directions; ray++) {
                double angle = 2 * Math.PI * (ray + unit(hash, 5)) / directions,
                        dx = Math.cos(angle),
                        dz = Math.sin(angle);
                // March out to the first supplied sea within the route reach, so the river ends
                // one segment past the coast instead of at a fixed length far out at sea (whose
                // wet channel ran on across the seabed, and which mostly left the owner cell).
                double reach = maxRoute * (.65 + .35 * unit(hash, 6)), step = reach / segments;
                double coast = -1;
                for (int k = 1; k <= segments && coast < 0; k++) {
                    double px = sx + dx * step * k, pz = sz + dz * step * k;
                    if (!inside(px, pz, originX, originZ, margin)) break;
                    intent.sample(px, pz, scratch);
                    probes++;
                    if (scratch.ocean.marine) {
                        if (!scratch.ocean.supplied) break;
                        coast = step * k;
                    }
                }
                if (coast < 0) continue;
                // About one node per reach step, at least four, so a short route keeps segments
                // long enough to fit its lake's outlet.
                int count = Math.max(4, Math.min(segments, (int) Math.round(coast / step) + 1));
                double distance = coast * count / (count - 1.0);
                double ex = sx + dx * distance, ez = sz + dz * distance;
                if (!inside(ex, ez, originX, originZ, margin)) continue;
                intent.sample(ex, ez, scratch);
                probes++;
                if (!scratch.ocean.marine || !scratch.ocean.supplied) continue;
                double[] xs = new double[count + 1],
                        zs = new double[count + 1],
                        levels = new double[count + 1],
                        widths = new double[count + 1],
                        ground = new double[count + 1];
                boolean valid = true;
                int firstMarine = count;
                double bend = (unit(hash, 7) - .5) * distance * .14;
                for (int i = 0; i <= count; i++) {
                    double t = i / (double) count, offset = Math.sin(t * Math.PI) * bend;
                    xs[i] = sx + dx * distance * t - dz * offset;
                    zs[i] = sz + dz * distance * t + dx * offset;
                    if (!inside(xs[i], zs[i], originX, originZ, margin)) {
                        valid = false;
                        break;
                    }
                    intent.sample(xs[i], zs[i], scratch);
                    probes++;
                    if (scratch.ocean.marine && !scratch.ocean.supplied) {
                        valid = false;
                        break;
                    }
                    if (scratch.ocean.marine) firstMarine = Math.min(firstMarine, i);
                    else if (firstMarine < i) {
                        valid = false;
                        break;
                    }
                    widths[i] = river.width.targetAverage * .5 * (.8 + .4 * unit(hash, 30 + i));
                    ground[i] = scratch.ocean.marine ? heights.waterTopY : scratch.shore.height;
                }
                if (!valid || firstMarine < 3) continue;
                // Like a trunk, the river holds the running minimum of the ground less one block
                // from its lake head, so it never fills: it crosses a hollow at its floor and cuts
                // the next rise as a gorge within the cut limit, and steep falls read as cascades.
                // (A preset smooth profile failed most straight routes on cut, fill or fall rate.)
                // Nodes on count that touch the lake and its bank form the flat outlet: they hold
                // the lake head, filling up to the fill limit, so the lake shape keeps its outlet.
                boolean[] held = new boolean[count + 1];
                double outer =
                        radius
                                + Math.min(
                                        river.width.targetAverage * bankScale,
                                        river.width.targetAverage);
                for (int i = 0; i < count; i++)
                    if (segment(sx, sz, xs[i], zs[i], xs[i + 1], zs[i + 1])
                            < outer + Math.max(widths[i], widths[i + 1]) * (1 + bankScale) + 2)
                        held[i] = held[i + 1] = true;
                double run = head;
                for (int i = 0; i < firstMarine && valid; i++) {
                    if (held[i]) {
                        levels[i] = head;
                        valid = head - ground[i] <= maxFill && ground[i] - head <= maxCut;
                    } else {
                        run = Math.max(heights.waterTopY, Math.min(run, ground[i] - 1));
                        levels[i] = run;
                        valid = ground[i] - run <= maxCut;
                    }
                }
                if (!valid) continue;
                for (int i = firstMarine; i <= count; i++) levels[i] = heights.waterTopY;
                // A shoaling coast drowns the mouth into a funnel estuary: within the estuary
                // length, and clear of the lake outlet, the river holds the sea's level wherever
                // the cut limit allows. Reflective coasts keep a plain mouth.
                intent.sample(xs[firstMarine], zs[firstMarine], scratch);
                probes++;
                double flare = 1 + (estuaryFlare - 1) * Rules.smooth(scratch.ocean.shoaling);
                double spacing = distance / count, outlet = radius * 1.5 + river.width.max;
                int tidal = firstMarine;
                while (flare > 1
                        && tidal > 1
                        && (firstMarine - tidal + 1) * spacing <= estuaryLength
                        && ground[tidal - 1] - heights.waterTopY <= maxCut
                        && !held[tidal - 1]
                        && Math.hypot(xs[tidal - 1] - sx, zs[tidal - 1] - sz) > outlet) tidal--;
                for (int i = tidal; i < firstMarine; i++) levels[i] = heights.waterTopY;
                double span = (firstMarine - tidal) * spacing;
                double[] meanders = new double[count + 1], valleys = new double[count + 1];
                for (int i = 0; i <= count; i++) {
                    double toMouth = Math.max(0, firstMarine - i) * spacing;
                    double drowned =
                            span > 0 && toMouth < span ? Rules.smooth(1 - toMouth / span) : 0;
                    // The outlet runs straight, so it meets the lake's own straight outlet channel.
                    meanders[i] = held[i] ? 0 : widths[i] * (1 - drowned);
                    widths[i] *= 1 + (flare - 1) * drowned;
                    valleys[i] =
                            i < firstMarine ? valleyRatio * Math.max(0, ground[i] - levels[i]) : 0;
                }
                if (valid) {
                    boolean flatOutlet = true;
                    for (int i = 0; i < count; i++) {
                        double vx = xs[i + 1] - xs[i], vz = zs[i + 1] - zs[i];
                        double t =
                                Math.max(
                                        0,
                                        Math.min(
                                                1,
                                                ((sx - xs[i]) * vx + (sz - zs[i]) * vz)
                                                        / (vx * vx + vz * vz)));
                        double distanceToLake =
                                Math.hypot(xs[i] + t * vx - sx, zs[i] + t * vz - sz);
                        double support = Math.max(widths[i], widths[i + 1]) * (1 + bankScale) + 2;
                        if (distanceToLake < outer + support
                                && (levels[i] != head || levels[i + 1] != head)) {
                            flatOutlet = false;
                            break;
                        }
                    }
                    // Spacing reads geometry only, so it is checked before the costly lake shape.
                    if (!clear(
                            new Plan(
                                    hash,
                                    xs,
                                    zs,
                                    levels,
                                    widths,
                                    meanders,
                                    valleys,
                                    radius,
                                    lakeDepth,
                                    head,
                                    supply,
                                    angle,
                                    1,
                                    probes,
                                    bankScale,
                                    meanderReach,
                                    null),
                            found)) continue;
                    LakeShape.Decision shape =
                            flatOutlet
                                    ? LakeShape.prepare(
                                            seed,
                                            hash,
                                            intent,
                                            heights,
                                            Kind.LAKE,
                                            sx,
                                            sz,
                                            radius,
                                            lakeDepth,
                                            (int) head,
                                            river.width.targetAverage * bankScale,
                                            freeboard,
                                            maxCut,
                                            maxFill,
                                            cellX,
                                            cellZ,
                                            cellBlocks,
                                            supply,
                                            new LakeShape.Receiver(
                                                    xs[0],
                                                    zs[0],
                                                    xs[1],
                                                    zs[1],
                                                    levels[0],
                                                    levels[1],
                                                    widths[0],
                                                    widths[1],
                                                    river.magnitude.min))
                                    : LakeShape.reject("OUTLET_HEAD", 0);
                    // A lake that cannot take a natural shape on its own ground (a slope it would
                    // cut or fill past the limits, or a sandy or rocky shore) is not taken: the
                    // legacy ellipse stood in steep walls on hillsides. The source tries its next
                    // route.
                    if (shape.shape == null) continue;
                    Plan made =
                            new Plan(
                                    hash,
                                    xs,
                                    zs,
                                    levels,
                                    widths,
                                    meanders,
                                    valleys,
                                    radius,
                                    lakeDepth,
                                    head,
                                    supply,
                                    angle,
                                    .7 + .3 * unit(hash, 8),
                                    probes,
                                    bankScale,
                                    meanderReach,
                                    shape);
                    found.add(made);
                    if (found.size() == networksPerCell) break sources;
                    continue sources;
                }
            }
        }
        return found.toArray(new Plan[0]);
    }

    /** Lake, bank and meander reach of a network's lake, as its plan bounds use. */
    private double lakeReach(Plan p) {
        return p.radius * 1.5 + river.width.targetAverage * bankScale;
    }

    /** Bed, bank and meander reach of a network's route segment. */
    private double routeReach(Plan p, int i) {
        return Math.max(p.width[i], p.width[i + 1]) * (1 + bankScale)
                + 2
                + meanderReach
                + Math.max(p.valley[i], p.valley[i + 1]);
    }

    /** Whether a network keeps clear of every network already taken in its cell. */
    private boolean clear(Plan a, java.util.List<Plan> taken) {
        double gap = river.width.targetAverage * 2;
        for (Plan b : taken) {
            if (Math.hypot(a.lakeX - b.lakeX, a.lakeZ - b.lakeZ)
                    < lakeReach(a) + lakeReach(b) + gap) return false;
            for (int i = 0; i < b.nodes() - 1; i++)
                if (segment(a.lakeX, a.lakeZ, b.x[i], b.z[i], b.x[i + 1], b.z[i + 1])
                        < lakeReach(a) + routeReach(b, i) + gap) return false;
            for (int i = 0; i < a.nodes() - 1; i++) {
                if (segment(b.lakeX, b.lakeZ, a.x[i], a.z[i], a.x[i + 1], a.z[i + 1])
                        < lakeReach(b) + routeReach(a, i) + gap) return false;
                for (int k = 0; k < b.nodes() - 1; k++)
                    if (segments(
                                    a.x[i],
                                    a.z[i],
                                    a.x[i + 1],
                                    a.z[i + 1],
                                    b.x[k],
                                    b.z[k],
                                    b.x[k + 1],
                                    b.z[k + 1])
                            < routeReach(a, i) + routeReach(b, k) + gap) return false;
            }
        }
        return true;
    }

    /** Distance from a point to a segment. */
    private static double segment(
            double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az, l = vx * vx + vz * vz;
        double t = l > 0 ? Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l)) : 0;
        return Math.hypot(px - ax - t * vx, pz - az - t * vz);
    }

    /** Distance between two segments: zero where they cross, else the nearest endpoint gap. */
    private static double segments(
            double ax,
            double az,
            double bx,
            double bz,
            double cx,
            double cz,
            double dx,
            double dz) {
        double d1 = (bx - ax) * (cz - az) - (bz - az) * (cx - ax),
                d2 = (bx - ax) * (dz - az) - (bz - az) * (dx - ax),
                d3 = (dx - cx) * (az - cz) - (dz - cz) * (ax - cx),
                d4 = (dx - cx) * (bz - cz) - (dz - cz) * (bx - cx);
        if (d1 * d2 < 0 && d3 * d4 < 0) return 0;
        return Math.min(
                Math.min(segment(ax, az, cx, cz, dx, dz), segment(bx, bz, cx, cz, dx, dz)),
                Math.min(segment(cx, cz, ax, az, bx, bz), segment(dx, dz, ax, az, bx, bz)));
    }

    private boolean inside(double x, double z, double ox, double oz, double margin) {
        return x > ox + margin
                && x < ox + cellBlocks - margin
                && z > oz + margin
                && z < oz + cellBlocks - margin;
    }

    private static double unit(long hash, int salt) {
        return SeededNoise.unit(SeededNoise.mix(hash + salt * 0xd1b54a32d192ed03L));
    }

    public void sample(double x, double z, Sample out) {
        out.clear();
        // The earlier connected ocean scaffold retains its natural head and bed; later banks cannot
        // replace it.
        if (continents.scaffoldClearance(x, z) <= 0) return;
        long stream = drainageSample(x, z, out);
        Plan p = plan((int) Math.floor(x / cellBlocks), (int) Math.floor(z / cellBlocks));
        sampleOwner(x, z, p, out);
        // A stream's water keeps the land's biome unless a river or lake wets the column too.
        out.stream = out.wet && stream != 0 && out.id == stream;
    }

    private void sampleOwner(double x, double z, Plan p, Sample out) {
        if (p.lens != null) p.lens.sample(x, z, out);
        if (mouthRadius > 0 && !Double.isNaN(p.mouthX)) {
            double d = Math.hypot(x - p.mouthX, z - p.mouthZ);
            if (d < mouthRadius) out.mouth = Math.max(out.mouth, 1 - Rules.smooth(d / mouthRadius));
        }
        for (Plan other : p.others)
            if (mouthRadius > 0 && !Double.isNaN(other.mouthX)) {
                double d = Math.hypot(x - other.mouthX, z - other.mouthZ);
                if (d < mouthRadius)
                    out.mouth = Math.max(out.mouth, 1 - Rules.smooth(d / mouthRadius));
            }
        if (x < p.minX || x > p.maxX || z < p.minZ || z > p.maxZ) return;
        if (p.present()) networkSample(x, z, p, out);
        for (Plan other : p.others)
            if (x >= other.minX && x <= other.maxX && z >= other.minZ && z <= other.maxZ)
                networkSample(x, z, other, out);
        for (Basin basin : p.basins) basin.apply(x, z, out, freeboard);
    }

    /** Conservative distance outside all surface-water bed/bank reservations.
     * The owner-edge bound also protects unseen neighboring owners without
     * constructing their plans. Sealed underground lenses do not reserve surface.
     */
    public double surfaceClearance(double x, double z) {
        int cx = (int) Math.floor(x / cellBlocks), cz = (int) Math.floor(z / cellBlocks);
        double ox = cx * (double) cellBlocks, oz = cz * (double) cellBlocks;
        double clearance =
                Math.min(
                        Math.min(x - ox, ox + cellBlocks - x),
                        Math.min(z - oz, oz + cellBlocks - z));
        clearance = surfaceClearance(plan(cx, cz), x, z, clearance);
        return Math.min(drainage.clearance(x, z, clearance), continents.scaffoldClearance(x, z));
    }

    /** Bounded wide-feature query: resolve all owners that can affect this finite skirt.
     * Neighbor plans read only earlier intent, never this query or later mountain geometry.
     */
    @Override
    public double surfaceClearance(double x, double z, double limit) {
        if (!Double.isFinite(x)
                || !Double.isFinite(z)
                || !Double.isFinite(limit)
                || limit <= 0
                || limit > cellBlocks)
            throw new IllegalArgumentException(
                    "Surface clearance requires a finite skirt within one owner width");
        int cx = (int) Math.floor(x / cellBlocks), cz = (int) Math.floor(z / cellBlocks);
        double clearance = limit;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                double ox = (cx + dx) * (double) cellBlocks, oz = (cz + dz) * (double) cellBlocks;
                double qx = Math.max(0, Math.max(ox - x, x - ox - cellBlocks)),
                        qz = Math.max(0, Math.max(oz - z, z - oz - cellBlocks));
                if (qx * qx + qz * qz >= limit * limit) continue;
                clearance = surfaceClearance(plan(cx + dx, cz + dz), x, z, clearance);
            }
        clearance = drainage.clearance(x, z, clearance);
        return Math.min(clearance, continents.scaffoldClearance(x, z));
    }

    private double surfaceClearance(Plan p, double x, double z, double clearance) {
        if (p.present()) {
            clearance =
                    Math.min(
                            clearance,
                            Math.hypot(x - p.lakeX, z - p.lakeZ)
                                    - p.radius
                                    - river.width.targetAverage * bankScale);
            clearance = routeClearance(x, z, p.x, p.z, p.width, p.valley, clearance);
        }
        for (Plan other : p.others) clearance = surfaceClearance(other, x, z, clearance);
        for (Basin basin : p.basins) {
            double support = basin.support();
            if (basin.kind == Kind.LAGOON) support = Math.hypot(support, basin.skirt * .25);
            clearance = Math.min(clearance, Math.hypot(x - basin.x, z - basin.z) - support);
        }
        return clearance;
    }

    /** Distance outside one route's bed, bank and meander reservation, capped at clearance. */
    private double routeClearance(
            double x,
            double z,
            double[] xs,
            double[] zs,
            double[] widths,
            double[] valleys,
            double clearance) {
        for (int i = 0; i < xs.length - 1; i++) {
            double vx = xs[i + 1] - xs[i], vz = zs[i + 1] - zs[i];
            double t =
                    Math.max(
                            0,
                            Math.min(
                                    1,
                                    ((x - xs[i]) * vx + (z - zs[i]) * vz) / (vx * vx + vz * vz)));
            double support =
                    Math.max(widths[i], widths[i + 1]) * (1 + bankScale)
                            + 2
                            + meanderReach
                            + (valleys == null ? 0 : Math.max(valleys[i], valleys[i + 1]));
            clearance =
                    Math.min(
                            clearance,
                            Math.hypot(x - xs[i] - t * vx, z - zs[i] - t * vz) - support);
        }
        return clearance;
    }

    /** Lateral channel offset: k seeded half-waves per segment, zero at both nodes. */
    private double meander(long id, int segment, double t, double length, double radius) {
        if (meanderAmplitude <= 0) return 0;
        long h = SeededNoise.mix(id ^ (segment * 0x9e3779b97f4a7c15L) ^ 0x4d45414e44455231L);
        int halves = Math.max(1, (int) Math.round(length / (meanderHalfWave * radius)));
        double amplitude = meanderAmplitude * radius * (.6 + .4 * unit(h, 1));
        // Alternate sides along the river from a seeded start, giving S-curves.
        double sign =
                ((segment & 1) == 0) == (unit(SeededNoise.mix(id ^ 0x5349444531L), 1) < .5)
                        ? -1
                        : 1;
        return sign * amplitude * StrictMath.sin(Math.PI * halves * t);
    }

    private void networkSample(double x, double z, Plan p, Sample out) {
        double dx = x - p.lakeX, dz = z - p.lakeZ;
        double u = (dx * p.cos + dz * p.sin) / p.radius,
                v = (-dx * p.sin + dz * p.cos) / (p.radius * p.aspect);
        double r = Math.sqrt(u * u + v * v);
        double lakeBank = river.width.targetAverage * bankScale;
        if (p.lakeShape != null && p.lakeShape.shape != null) {
            p.lakeShape.shape.apply(x, z, Kind.LAKE, p.id, out);
            // Keep the legacy river from cutting through source islands or replacing its outlet.
            if (!p.lakeShape.shape.allowsRiver(x, z)) return;
        } else if (r < 1 + lakeBank / p.radius) {
            if (r <= 1)
                wet(
                        out,
                        p,
                        Kind.LAKE,
                        p.lakeHead,
                        p.lakeHead - p.depth * (.35 + .65 * (1 - r * r)));
            else bank(out, p, p.lakeHead, 1 - Rules.smooth((r - 1) * p.radius / lakeBank));
        }
        if (p.rivers != null && p.rivers.apply(x, z, p.id, out)) return;
        routeSample(x, z, p.id, p.x, p.z, p.level, p.width, p.valley, p.meander, out);
    }

    /** River bed or bank of one route (cell network or continent trunk) at a column. */
    private void routeSample(
            double x,
            double z,
            long id,
            double[] xs,
            double[] zs,
            double[] levels,
            double[] widths,
            double[] valleys,
            double[] meanders,
            Sample out) {
        double nearest = Double.POSITIVE_INFINITY, riverHead = 0, nearWidth = 0, nearValley = 0;
        double levee = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length - 1; i++) {
            double support =
                    Math.max(widths[i], widths[i + 1]) * (1 + bankScale)
                            + 2
                            + meanderReach
                            + (valleys == null ? 0 : Math.max(valleys[i], valleys[i + 1]));
            if (x < Math.min(xs[i], xs[i + 1]) - support
                    || x > Math.max(xs[i], xs[i + 1]) + support
                    || z < Math.min(zs[i], zs[i + 1]) - support
                    || z > Math.max(zs[i], zs[i + 1]) + support) continue;
            double vx = xs[i + 1] - xs[i], vz = zs[i + 1] - zs[i];
            double length = Math.sqrt(vx * vx + vz * vz);
            double raw = ((x - xs[i]) * vx + (z - zs[i]) * vz) / (length * length);
            double t = Math.max(0, Math.min(1, raw));
            double width = widths[i] + t * (widths[i + 1] - widths[i]);
            // Low-cost meander: a lateral offset that is zero at every node, so lakes, mouths and
            // RiverShape bridges stay joined. Distance is measured to the offset centreline.
            double lateral = ((x - xs[i]) * vz - (z - zs[i]) * vx) / length;
            double radius =
                    meanders == null ? width : meanders[i] + t * (meanders[i + 1] - meanders[i]);
            double dist =
                    Math.hypot(lateral - meander(id, i, t, length, radius), (raw - t) * length);
            if (dist - width <= Drainage.LEVEE) {
                // The water beside a bank column may stand a step higher two blocks along.
                double a = Math.max(0, t - 2 / length), b = Math.min(1, t + 2 / length);
                levee =
                        Math.max(
                                levee,
                                Math.max(
                                        levels[i] + a * (levels[i + 1] - levels[i]),
                                        levels[i] + b * (levels[i + 1] - levels[i])));
            }
            if (dist / width < nearest) {
                nearest = dist / width;
                riverHead = levels[i] + t * (levels[i + 1] - levels[i]);
                nearWidth = width;
                nearValley = valleys == null ? 0 : valleys[i] + t * (valleys[i + 1] - valleys[i]);
            }
        }
        if (nearest <= 1)
            wet(
                    out,
                    id,
                    Kind.RIVER,
                    riverHead,
                    Math.floor(riverHead)
                            - river.magnitude.min
                            - (river.magnitude.targetAverage - river.magnitude.min)
                                    * (1 - nearest * nearest));
        else {
            // Flush banks hold the highest water beside them.
            double beyond = (nearest - 1) * nearWidth;
            double head = beyond <= Drainage.LEVEE ? Math.max(riverHead, levee) : riverHead;
            if (valleys == null) {
                if (nearest < 1 + bankScale)
                    bank(out, id, head, 1 - Rules.smooth((nearest - 1) / bankScale));
            } else {
                // A trunk's bank widens into a valley as deep as its cut is, so a gorge through a
                // ridge has sloping walls instead of standing as a slot.
                double span = bankScale * nearWidth + nearValley;
                if (beyond < span) bank(out, id, head, 1 - Rules.smooth(beyond / span));
            }
        }
    }

    /** Earlier immutable network only; never constructs or looks up another owner. */
    void earlierNetwork(double x, double z, Plan network, Sample out) {
        out.clear();
        if (continents.scaffoldClearance(x, z) <= 0) return;
        if (network.present()) networkSample(x, z, network, out);
        for (Plan other : network.others) networkSample(x, z, other, out);
    }

    private void wet(Sample out, Plan p, Kind kind, double head, double floor) {
        wet(out, p.id, kind, head, floor);
    }

    private void wet(Sample out, long id, Kind kind, double head, double floor) {
        int water = (int) Math.floor(head);
        if (!out.wet) {
            out.floor = Math.min(floor, water - 1);
            out.waterY = water;
            out.kind = kind;
        } else {
            out.floor = Math.min(out.floor, floor);
            out.waterY = Math.max(out.waterY, water);
        }
        out.wet = true;
        out.weight = 1;
        out.id = id;
        out.head = out.waterY;
    }

    private void bank(Sample out, Plan p, double head, double weight) {
        bank(out, p.id, head, weight);
    }

    private void bank(Sample out, long id, double head, double weight) {
        if (out.wet) return;
        if (weight > out.weight) {
            // Flush banks: the ground beside the water stands level with its surface.
            out.bank = Math.floor(head) + .5;
            out.weight = weight;
            out.head = head;
            out.id = id;
        }
    }

    /**
     * A drainage channel at a column: its water where the channel wets it (a stream flag for
     * channels too narrow to be rivers), else the nearest channel whose bank or valley reaches it.
     * Returns the id of a stream that wets the column, else 0.
     */
    private long drainageSample(double x, double z, Sample out) {
        DrainageQuery q = drainageQuery.get();
        Drainage.Hit hit = drainage.nearest(x, z, q.wet, q.near, mouthRadius, q.mouth);
        out.mouth = Math.max(out.mouth, q.mouth[0]);
        if (hit == null) return 0;
        if (hit.wet) {
            double n = hit.distance / hit.radius;
            wet(
                    out,
                    hit.id,
                    Kind.RIVER,
                    hit.level,
                    Math.floor(hit.level) - 1 - (hit.depth - 1) * (1 - n * n));
            out.bed = hit.steep ? Bed.GRAVEL : Bed.SAND;
            return hit.river ? 0 : hit.id;
        }
        // A bar inside a braided bed stands flush with the water; banks ease out from it.
        out.channel = true;
        out.channelHead = hit.level;
        out.channelBeyond = Math.max(0, hit.distance - hit.radius);
        out.channelBank = 2 * hit.radius * bankScale;
        out.channelLevee = hit.levee;
        out.bar = hit.bar;
        return 0;
    }

    /** Water level of a network lake or route at a column, or NaN: drainage streams end there. */
    private double receivingWater(double x, double z) {
        Sample s = drainageQuery.get().receiving;
        s.clear();
        if (continents.scaffoldClearance(x, z) <= 0) return Double.NaN;
        Plan p = plan((int) Math.floor(x / cellBlocks), (int) Math.floor(z / cellBlocks));
        if (x < p.minX || x > p.maxX || z < p.minZ || z > p.maxZ) return Double.NaN;
        if (p.present()) networkSample(x, z, p, s);
        for (Plan other : p.others)
            if (x >= other.minX && x <= other.maxX && z >= other.minZ && z <= other.maxZ)
                networkSample(x, z, other, s);
        return s.wet ? s.waterY : Double.NaN;
    }

    /**
     * Apply last: neither a later cut nor a high local landform can breach this bed/bank. A
     * drainage channel's bank and valley are cut from the column's own height: the valley widens
     * by valleyInCutDepths per block the ground stands above the water (at most
     * streamValleyMaximumBlocks), and its walls ease from the water's level out to the ground,
     * giving way to a network lake's or route's bank; the blocks touching the water stand level
     * with the highest water beside them, so it cannot spill.
     */
    public double constrain(double height, Sample water) {
        if (water.wet) return water.floor;
        double h = height + (water.bank - height) * water.weight;
        if (!water.channel) return h;
        double span =
                water.channelBank
                        + Math.min(
                                drainage.valleyCap,
                                drainage.valleyRatio * Math.max(0, h - water.channelHead));
        if (water.channelBeyond >= span) return h;
        // Flush banks: the ground beside the water stands level with its surface, which holds it.
        double bank = Math.floor(water.channelHead) + .5;
        // A valley never cuts into a network lake's or route's bank.
        double w = (1 - Rules.smooth(water.channelBeyond / span)) * (1 - water.weight);
        h += (bank - h) * w;
        // The blocks beside the water hold it: never below the highest water they touch.
        if (water.channelBeyond > Drainage.LEVEE) return h;
        return Math.max(h, Math.floor(Math.max(water.channelHead, water.channelLevee)) + .5);
    }
}
