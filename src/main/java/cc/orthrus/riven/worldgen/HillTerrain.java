package cc.orthrus.riven.worldgen;

import cc.orthrus.riven.contrib.hills.Hills;

import com.google.gson.JsonObject;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Finite hills selected by the shared major-feature budget, with immutable plan memoization.
 * Coastal support is allowed; TerrainMap bounds hill raises with the shore's coastal envelope.
 */
public final class HillTerrain {
    private static final Rules RULES = Rules.load("riven-hills.json", 1);
    public static final String FINGERPRINT = RULES.fingerprint;

    public enum HeadingPolicy {
        LEGACY,
        REGIONAL
    }

    public static HeadingPolicy configuredHeadingPolicy() {
        return HeadingPolicy.valueOf(RULES.json.get("skewHeadingPolicy").getAsString());
    }

    private static final Hills.Family[] FAMILIES = {
        Hills.Family.SKEW_HILLS,
        Hills.Family.BRANCH_SPURS,
        Hills.Family.DISSECTED_UPLAND,
        Hills.Family.WARPED_SHOULDERS
    };
    private final long seed;
    private final Sites sites;
    private final IntentSampler earlierIntent;
    private final MajorFeatureParent earlierTerrain;
    private final OwnerEdgeWaterClearance waterClearance;
    private final RegionalFabricMap fabric;
    private final HeadingPolicy headingPolicy;
    private final Hills.Reference[] references = new Hills.Reference[FAMILIES.length];
    private final Hills.Tuning[] tunings = new Hills.Tuning[FAMILIES.length];
    private final double[] weights = new double[FAMILIES.length];
    private final int sets, ways, maximumColumns;
    private final AtomicReferenceArray<Entry> memo;
    private final AtomicIntegerArray next;

    HillTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            MajorFeatureParent earlierTerrain,
            OwnerEdgeWaterClearance waterClearance) {
        this(
                worldSeed,
                heights,
                sites,
                earlierIntent,
                earlierTerrain,
                waterClearance,
                new RegionalFabricMap(worldSeed, heights));
    }

    HillTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            MajorFeatureParent earlierTerrain,
            OwnerEdgeWaterClearance waterClearance,
            RegionalFabricMap fabric) {
        this(
                worldSeed,
                heights,
                sites,
                earlierIntent,
                earlierTerrain,
                waterClearance,
                fabric,
                configuredHeadingPolicy());
    }

    HillTerrain(
            long worldSeed,
            TerrainHeightReference heights,
            Sites sites,
            IntentSampler earlierIntent,
            MajorFeatureParent earlierTerrain,
            OwnerEdgeWaterClearance waterClearance,
            RegionalFabricMap fabric,
            HeadingPolicy headingPolicy) {
        if (fabric == null || headingPolicy == null) throw new NullPointerException();
        this.fabric = fabric;
        this.headingPolicy = headingPolicy;
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(RULES.json, "variationSeed"));
        this.sites = sites;
        this.earlierIntent = earlierIntent;
        this.earlierTerrain = earlierTerrain;
        this.waterClearance = waterClearance;
        JsonObject j = RULES.json;
        sets = integer(j, "cacheSets", 16, 256);
        ways = integer(j, "cacheWays", 1, 4);
        if ((sets & (sets - 1)) != 0)
            throw new IllegalStateException("Hill cache sets must be a power of two");
        maximumColumns = integer(j, "maximumCertificateColumns", 1024, 400000);
        memo = new AtomicReferenceArray<>(sets * ways);
        next = new AtomicIntegerArray(sets);
        for (int i = 0; i < FAMILIES.length; i++) {
            Hills.Family f = FAMILIES[i];
            TerrainHeightReference.Feature feature = heights.feature(f.referenceId);
            references[i] =
                    new Hills.Reference(
                            heights.seaLevel,
                            feature.elevationOffset.min,
                            feature.elevationOffset.targetAverage,
                            feature.elevationOffset.max,
                            feature.relief.targetAverage,
                            feature.width.min,
                            feature.width.targetAverage,
                            feature.width.max,
                            TerrainScale.HORIZONTAL,
                            heights.lowestSurface,
                            heights.highestSurface,
                            heights.fingerprint);
            JsonObject p = j.getAsJsonObject("families").getAsJsonObject(f.name());
            weights[i] = Rules.number(p, "weight", 0, 10);
            tunings[i] =
                    new Hills.Tuning(
                            Rules.number(p, "widthFactor", .5, 2),
                            Rules.number(p, "gain", .001, 2),
                            Rules.number(p, "aspect", 1, 2.5),
                            Rules.number(p, "occurrence", 0, 1),
                            Rules.number(p, "directionJitter", 0, Math.PI),
                            Rules.number(p, "roughness", 0, .2),
                            Rules.number(p, "skirt", .1, .5));
            if (Hills.maxRadius(references[i], tunings[i]) > sites.maximumRadius)
                throw new IllegalStateException("Hill family exceeds shared support budget: " + f);
        }
    }

    private static int integer(JsonObject json, String key, int min, int max) {
        double value = Rules.number(json, key, min, max);
        if (value != (int) value) throw new IllegalStateException(key);
        return (int) value;
    }

    private static final class Entry {
        final long key;
        final Decision decision;

        Entry(long key, Decision decision) {
            this.key = key;
            this.decision = decision;
        }
    }

    public static final class Decision {
        public final Hills.Family family;
        public final Hills.Site site;
        public final String reason;
        public final int certificateColumns;

        private Decision(Hills.Family family, Hills.Site site, String reason, int columns) {
            this.family = family;
            this.site = site;
            this.reason = reason;
            certificateColumns = columns;
        }
    }

    /** Read-only plan construction. Eviction or duplicate concurrent construction cannot change selection. */
    public Decision plan(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        int set = (int) SeededNoise.mix(key) & (sets - 1), first = set * ways;
        for (int i = 0; i < ways; i++) {
            Entry entry = memo.get(first + i);
            if (entry != null && entry.key == key) return entry.decision;
        }
        Decision built = build(cx, cz);
        for (int i = 0; i < ways; i++) {
            Entry entry = memo.get(first + i);
            if (entry != null && entry.key == key) return entry.decision;
        }
        memo.set(first + Math.floorMod(next.getAndIncrement(set), ways), new Entry(key, built));
        return built;
    }

    private Decision build(int cx, int cz) {
        long owner = sites.key(Sites.Lane.HILLS, cx, cz);
        if (!sites.admits(cx, cz, owner, Sites.Lane.HILLS))
            return new Decision(null, null, "SHARED_BUDGET", 0);
        double x = sites.x(cx, owner), z = sites.z(cz, owner);
        if (Math.abs(x) + sites.maximumRadius + 1 > 29999984
                || Math.abs(z) + sites.maximumRadius + 1 > 29999984)
            return new Decision(null, null, "WORLD_BOUND", 0);
        TerrainMap.BaseSample anchor = new TerrainMap.BaseSample();
        earlierIntent.sample(x, z, anchor);
        if (anchor.ocean.marine) return new Decision(null, null, "MARINE", 0);
        double hill = anchor.land.middle + .35 * anchor.land.low,
                upland = anchor.land.middle + anchor.land.high;
        double adjacency = anchor.land.high, reach = sites.maximumRadius;
        TerrainMap.BaseSample neighbor = new TerrainMap.BaseSample();
        for (int i = 0; i < 4; i++) {
            earlierIntent.sample(
                    x + (i == 0 ? reach : i == 1 ? -reach : 0),
                    z + (i == 2 ? reach : i == 3 ? -reach : 0),
                    neighbor);
            adjacency = Math.max(adjacency, neighbor.land.high);
        }
        double[] fit = new double[FAMILIES.length];
        for (int i = 0; i < FAMILIES.length; i++) {
            Hills.Family family = FAMILIES[i];
            double affinity = family == Hills.Family.DISSECTED_UPLAND ? upland : hill;
            boolean foothill =
                    family == Hills.Family.BRANCH_SPURS || family == Hills.Family.WARPED_SHOULDERS;
            if (affinity >= .3
                    && (!foothill || adjacency >= .3)
                    && anchor.baseHeight >= references[i].low
                    && anchor.baseHeight <= references[i].high) fit[i] = weights[i] * affinity;
        }
        // Family choice must use a different draw from the Sites hill-lane gate.
        int choice = Rules.choose(fit, Sites.random(owner, 0x46414d36));
        if (choice < 0) return new Decision(null, null, "WRONG_INTENT", 0);
        double direction;
        if (headingPolicy == HeadingPolicy.REGIONAL
                && FAMILIES[choice] == Hills.Family.SKEW_HILLS) {
            RegionalFabricMap.Sample axis = new RegionalFabricMap.Sample();
            fabric.sample(x, z, axis);
            direction = fabric.heading(axis, Sites.random(owner, 57) * 2 * Math.PI);
        } else {
            direction =
                    Math.hypot(anchor.broadDx, anchor.broadDz) > 1e-8
                            ? Math.atan2(anchor.broadDz, anchor.broadDx)
                            : Sites.random(owner, 57) * 2 * Math.PI;
        }
        Certificate certificate =
                new Certificate(
                        references[choice],
                        tunings[choice],
                        FAMILIES[choice],
                        hill,
                        upland,
                        adjacency,
                        direction);
        Hills.Selection selected =
                Hills.prepareSelected(
                        seed,
                        cx,
                        cz,
                        x,
                        z,
                        FAMILIES[choice],
                        references[choice],
                        tunings[choice],
                        certificate);
        String reason = certificate.failure == null ? selected.reason.name() : certificate.failure;
        return new Decision(FAMILIES[choice], selected.site, reason, certificate.columns);
    }

    /** No plateau, mountain, hill, landmark or carver is sampled here. Only the earlier parent is read. */
    private final class Certificate implements Hills.Earlier {
        final Hills.Reference reference;
        final double hill, upland, adjacency, direction, raise, cut;
        final TerrainMap.Sample scratch = new TerrainMap.Sample();
        int columns;
        String failure;
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, anchorY;

        Certificate(
                Hills.Reference reference,
                Hills.Tuning tuning,
                Hills.Family family,
                double hill,
                double upland,
                double adjacency,
                double direction) {
            this.reference = reference;
            this.hill = hill;
            this.upland = upland;
            this.adjacency = adjacency;
            this.direction = direction;
            raise = reference.relief * tuning.gain;
            cut = raise * Hills.negativeBound(family);
        }

        public Hills.Facts facts(double x, double z, double radius) {
            double halo = radius + 1;
            if (Math.abs(x) + halo > 29999984 || Math.abs(z) + halo > 29999984) {
                failure = "WORLD_BOUND";
                return null;
            }
            int minX = (int) Math.floor(x - halo), maxX = (int) Math.ceil(x + halo);
            int minZ = (int) Math.floor(z - halo), maxZ = (int) Math.ceil(z + halo);
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) + 1 > maximumColumns) {
                failure = "WORK_BOUND";
                return null;
            }
            if (!column(x, z)) return null;
            if (scratch.ocean.marine) {
                failure = "MARINE_ANCHOR";
                return null;
            }
            anchorY = scratch.height;
            int step = sites.certificateStep;
            for (int pz = sites.firstNode(minZ); pz <= maxZ; pz += step)
                for (int px = sites.firstNode(minX); px <= maxX; px += step) {
                    double dx = px - x, dz = pz - z;
                    if (dx * dx + dz * dz > halo * halo) continue;
                    if (!column(px, pz)) return null;
                }
            // Distances and parent extrema are certified on the occupied column lattice.
            // No assertion about unsampled fractional terrain is made by this adapter.
            return new Hills.Facts(
                    false,
                    false,
                    false,
                    low - sites.certificateMargin,
                    high + sites.certificateMargin,
                    anchorY,
                    hill,
                    upland,
                    adjacency,
                    direction,
                    radius,
                    radius);
        }

        private boolean column(double x, double z) {
            earlierTerrain.sample(x, z, scratch);
            columns++;
            // The coastal envelope keeps the shore (TerrainMap); marine support columns are never
            // raised, so they neither reject the site nor constrain its land extrema.
            if (scratch.ocean.marine) return true;
            if (waterClearance.surfaceClearance(x, z) <= sites.certificateReach()) {
                failure = "RESERVED_WATER_SUPPORT";
                return false;
            }
            if (!Double.isFinite(scratch.height)) {
                failure = "INVALID_PARENT";
                return false;
            }
            double margin = sites.certificateMargin;
            if (scratch.height - cut - margin < Math.max(reference.low, scratch.minimumY)
                    || scratch.height + raise + margin
                            > Math.min(reference.high, scratch.maximumY)) {
                failure = "HEADROOM_SUPPORT";
                return false;
            }
            low = Math.min(low, scratch.height);
            high = Math.max(high, scratch.height);
            return true;
        }
    }

    public static final class Result implements StageResult {
        public double height, delta, weight, anchorX, anchorZ;
        public int candidates, contributors, certificateColumns;
        public Hills.Family dominant;
        public long id;
        public String reason;

        public void clear(double parent) {
            height = parent;
            delta = weight = anchorX = anchorZ = 0;
            candidates = contributors = certificateColumns = 0;
            dominant = null;
            id = 0;
            reason = "NO_SITE";
        }
    }

    public void apply(double x, double z, double parent, Result out) {
        out.clear(parent);
        int ix = (int) Math.floor(x / sites.spacing), iz = (int) Math.floor(z / sites.spacing);
        double largest = 0;
        for (int dz = -sites.searchRadius; dz <= sites.searchRadius; dz++)
            for (int dx = -sites.searchRadius; dx <= sites.searchRadius; dx++) {
                int cx = ix + dx, cz = iz + dz;
                long owner = sites.key(Sites.Lane.HILLS, cx, cz);
                double ax = sites.x(cx, owner), az = sites.z(cz, owner), px = x - ax, pz = z - az;
                if (px * px + pz * pz >= sites.maximumRadius * sites.maximumRadius
                        || !sites.admits(cx, cz, owner, Sites.Lane.HILLS)) continue;
                Decision decision = plan(cx, cz);
                out.candidates++;
                out.reason = decision.reason;
                out.certificateColumns += decision.certificateColumns;
                if (decision.site == null) continue;
                Hills.Site site = decision.site;
                double weight = site.weight(x, z), delta = site.delta(x, z);
                if (weight <= 0) continue;
                out.weight += weight;
                out.delta += delta;
                out.contributors++;
                if (weight > largest) {
                    largest = weight;
                    out.dominant = site.family;
                    out.id = site.key;
                    out.anchorX = site.x;
                    out.anchorZ = site.z;
                }
            }
        out.delta /= Math.max(1, out.weight);
        out.height = parent + out.delta;
    }
}
