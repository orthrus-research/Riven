package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/** Shared factory for sparse, exclusive major-feature sites, independent of terrain queries. */
public final class Sites {
    public enum Lane {
        LANDMARK,
        CARVER,
        HILLS,
        MOUNTAINS,
        PLATEAUS,
        COLD,
        VOLCANIC,
        CUTS,
        SEDIMENT,
        ROCKY,
        WONDER
    }

    /** SHARED: any higher-priority raw neighbour vetoes. LANE: only same-lane neighbours do. */
    public enum Competition {
        SHARED,
        LANE
    }

    public final double spacing, maximumRadius;

    /**
     * Shared site-certificate lattice (riven-terrain.json sites): support certificates read the
     * earlier parent at world-aligned nodes every certificateStep blocks instead of every integer
     * column, so a decision costs the same at any requested resolution and native cubes and the DH
     * preview share it. Heights between nodes are covered by certificateMargin blocks on every
     * headroom test and parent extreme; water by the analytic clearance over certificateReach().
     * Step 1 and margin 0 reproduce the exhaustive integer certificate.
     */
    public final int certificateStep;

    public final double certificateMargin;
    public final int searchRadius;
    public final Competition competition;
    private final long seed;
    private final double probability,
            carverShare,
            cutsShare,
            hillsShare,
            mountainShare,
            plateauShare,
            coldShare,
            volcanicShare,
            sedimentShare,
            rockyShare,
            separation,
            mountainRadius,
            plateauRadius,
            coldRadius,
            volcanicRadius,
            sedimentRadius,
            rockyRadius,
            wonderRadius,
            largestRadius;

    /**
     * Independent per-lane anchors (laneDensity): each lane draws its own anchor in every cell from
     * a lane-salted key with that lane's density, so later lanes are not starved by a cascade.
     * Null keeps the cascade (laneOf).
     */
    private final double[] density;

    private final long[] laneSeeds = new long[Lane.values().length];

    public static Sites create(long worldSeed, TerrainHeightReference heights, JsonObject json) {
        return new Sites(worldSeed, heights, json);
    }

    private Sites(long worldSeed, TerrainHeightReference heights, JsonObject json) {
        seed = SeededNoise.mix(worldSeed ^ Rules.seed(json, "variationSeed"));
        spacing =
                heights.feature("inland_plain").width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(json, "spacingInPlainWidths", 0.1, 4);
        probability = Rules.number(json, "anchorProbability", 0, 1);
        carverShare = Rules.number(json, "carverShare", 0, 1);
        cutsShare = json.has("cutsShare") ? Rules.number(json, "cutsShare", 0, 1) : 0;
        hillsShare = json.has("hillsShare") ? Rules.number(json, "hillsShare", 0, 1) : 0;
        mountainShare = json.has("mountainShare") ? Rules.number(json, "mountainShare", 0, 1) : 0;
        plateauShare = json.has("plateauShare") ? Rules.number(json, "plateauShare", 0, 1) : 0;
        coldShare = json.has("coldShare") ? Rules.number(json, "coldShare", 0, 1) : 0;
        volcanicShare = json.has("volcanicShare") ? Rules.number(json, "volcanicShare", 0, 1) : 0;
        sedimentShare = json.has("sedimentShare") ? Rules.number(json, "sedimentShare", 0, 1) : 0;
        rockyShare = json.has("rockyShare") ? Rules.number(json, "rockyShare", 0, 1) : 0;
        maximumRadius = spacing * Rules.number(json, "maximumRadiusInCells", 0.1, 1);
        mountainRadius =
                json.has("maximumMountainRadiusInReferenceWidths")
                        ? heights.feature("mountains").width.targetAverage
                                * TerrainScale.HORIZONTAL
                                * Rules.number(
                                        json, "maximumMountainRadiusInReferenceWidths", .5, 4)
                        : maximumRadius;
        plateauRadius =
                json.has("maximumPlateauRadiusInReferenceWidths")
                        ? heights.feature("plateau").width.targetAverage
                                * TerrainScale.HORIZONTAL
                                * Rules.number(
                                        json, "maximumPlateauRadiusInReferenceWidths", .25, 4)
                        : maximumRadius;
        coldRadius =
                json.has("maximumColdRadiusInCirqueWidths")
                        ? heights.feature("cirque").width.targetAverage
                                * TerrainScale.HORIZONTAL
                                * Rules.number(json, "maximumColdRadiusInCirqueWidths", 1, 8)
                        : maximumRadius;
        volcanicRadius =
                json.has("maximumVolcanicRadiusInConeWidths")
                        ? heights.feature("volcanic_cone").width.targetAverage
                                * TerrainScale.HORIZONTAL
                                * Rules.number(json, "maximumVolcanicRadiusInConeWidths", .5, 4)
                        : maximumRadius;
        sedimentRadius =
                json.has("maximumSedimentRadiusInCoastalPlainWidths")
                        ? heights.feature("coastal_plain").width.targetAverage
                                * TerrainScale.HORIZONTAL
                                * Rules.number(
                                        json, "maximumSedimentRadiusInCoastalPlainWidths", 1, 8)
                        : maximumRadius;
        rockyRadius =
                json.has("maximumRockyRadiusInScarpWidths")
                        ? heights.feature("scarp").width.max
                                * TerrainScale.HORIZONTAL
                                * Rules.number(json, "maximumRockyRadiusInScarpWidths", 4, 16)
                        : maximumRadius;
        // Wonders are rare set pieces, larger than a carver but sized in site cells.
        wonderRadius =
                json.has("maximumWonderRadiusInCells")
                        ? spacing * Rules.number(json, "maximumWonderRadiusInCells", .5, 3)
                        : maximumRadius;
        largestRadius =
                Math.max(
                        wonderRadius,
                        Math.max(
                                rockyRadius,
                                Math.max(
                                        sedimentRadius,
                                        Math.max(
                                                volcanicRadius,
                                                Math.max(
                                                        maximumRadius,
                                                        Math.max(
                                                                coldRadius,
                                                                Math.max(
                                                                        mountainRadius,
                                                                        plateauRadius)))))));
        separation = spacing * Rules.number(json, "minimumSeparationInCells", 0.1, 4);
        if (separation < 2 * maximumRadius)
            throw new IllegalStateException("Major feature supports must not overlap");
        searchRadius = (int) Math.ceil(maximumRadius / spacing);
        certificateStep =
                json.has("certificateLatticeBlocks")
                        ? (int) Rules.number(json, "certificateLatticeBlocks", 1, 32)
                        : 1;
        certificateMargin =
                json.has("certificateHeightMarginBlocks")
                        ? Rules.number(json, "certificateHeightMarginBlocks", 0, 64)
                        : 0;
        competition =
                json.has("competition")
                        ? Competition.valueOf(json.get("competition").getAsString())
                        : Competition.SHARED;
        if (json.has("laneDensity")) {
            if (competition != Competition.LANE)
                throw new IllegalStateException(
                        "Independent lane anchors require LANE competition");
            JsonObject d = json.getAsJsonObject("laneDensity");
            density = new double[Lane.values().length];
            for (Lane lane : Lane.values())
                density[lane.ordinal()] = Rules.number(d, lane.name(), 0, 1);
        } else density = null;
        for (Lane lane : Lane.values())
            laneSeeds[lane.ordinal()] =
                    SeededNoise.mix(
                            seed ^ (0x4c414e4553454544L + lane.ordinal() * 0x9e3779b97f4a7c15L));
    }

    /** The first lattice node at or above {@code min}. */
    public int firstNode(int min) {
        return Math.floorDiv(min + certificateStep - 1, certificateStep) * certificateStep;
    }

    /** Half the node diagonal: every column lies within this distance of a node. */
    public double certificateReach() {
        return certificateStep == 1 ? 0 : certificateStep * .7072;
    }

    public long key(int x, int z) {
        return SeededNoise.cell(seed, x, z);
    }

    /** The lane's anchor key for a cell: lane-salted with independent anchors, else shared. */
    public long key(Lane lane, int x, int z) {
        return density == null ? key(x, z) : SeededNoise.cell(laneSeeds[lane.ordinal()], x, z);
    }

    public double x(int cell, long key) {
        return (cell + 0.12 + 0.76 * random(key, 2)) * spacing;
    }

    public double z(int cell, long key) {
        return (cell + 0.12 + 0.76 * random(key, 3)) * spacing;
    }

    public double maximumRadius(Lane lane) {
        return lane == Lane.MOUNTAINS
                ? mountainRadius
                : lane == Lane.PLATEAUS
                        ? plateauRadius
                        : lane == Lane.COLD
                                ? coldRadius
                                : lane == Lane.VOLCANIC
                                        ? volcanicRadius
                                        : lane == Lane.SEDIMENT
                                                ? sedimentRadius
                                                : lane == Lane.ROCKY
                                                        ? rockyRadius
                                                        : lane == Lane.WONDER
                                                                ? wonderRadius
                                                                : maximumRadius;
    }

    public int searchRadius(Lane lane) {
        return (int) Math.ceil(maximumRadius(lane) / spacing);
    }

    private Lane laneOf(long key) {
        if (random(key, 4) < carverShare)
            return random(key, 0x43555453) < cutsShare ? Lane.CUTS : Lane.CARVER;
        if (random(key, 0x48494c4c) < hillsShare) return Lane.HILLS;
        if (random(key, 0x4d544e53) < mountainShare) return Lane.MOUNTAINS;
        if (random(key, 0x504c4154) < plateauShare) return Lane.PLATEAUS;
        if (random(key, 0x434f4c44) < coldShare) return Lane.COLD;
        if (random(key, 0x564f4c43) < volcanicShare) return Lane.VOLCANIC;
        if (random(key, 0x53454449) < sedimentShare) return Lane.SEDIMENT;
        return random(key, 0x524f434b) < rockyShare ? Lane.ROCKY : Lane.LANDMARK;
    }

    /** An anchor's own admission from earlier facts; it must never read another site. */
    public interface Eligibility {
        boolean test(int cx, int cz, long key);
    }

    /** Whether the cell's anchor exists and belongs to the lane, before any competition. */
    public boolean inLane(long key, Lane lane) {
        if (random(key, 1) >= probability) return false;
        return density == null
                ? laneOf(key) == lane
                : random(key, 0x4c414e45) < density[lane.ordinal()];
    }

    /** A higher-priority raw neighbor vetoes this site; it never asks whether that neighbor won. */
    public boolean admits(int cx, int cz, long key, Lane lane) {
        return admits(cx, cz, key, lane, null);
    }

    /**
     * As {@link #admits(int, int, long, Lane)}, but in LANE competition only neighbours whose own
     * anchor passes {@code eligible} can veto, so anchors the lane could never build no longer
     * suppress buildable ones. Eligibility reads only that anchor's earlier facts, never another
     * site's acceptance, so there is no recursion.
     */
    public boolean admits(int cx, int cz, long key, Lane lane, Eligibility eligible) {
        if (!inLane(key, lane)) return false;
        double radius = maximumRadius(lane);
        boolean shared = competition == Competition.SHARED;
        // In LANE mode supports of different lanes may overlap and compose in stage order.
        double reach = shared ? Math.max(separation, radius + largestRadius) : 2 * radius;
        int competitionRadius = (int) Math.floor(reach / spacing + 0.76);
        double ax = x(cx, key), az = z(cz, key), priority = random(key, 101);
        for (int dz = -competitionRadius; dz <= competitionRadius; dz++)
            for (int dx = -competitionRadius; dx <= competitionRadius; dx++) {
                if (dx == 0 && dz == 0) continue;
                long other = key(lane, cx + dx, cz + dz);
                if (random(other, 1) >= probability) continue;
                if (!shared && !inLane(other, lane)) continue;
                double rank = random(other, 101);
                if (rank > priority || (rank == priority && (dx > 0 || (dx == 0 && dz > 0))))
                    continue;
                double px = x(cx + dx, other) - ax, pz = z(cz + dz, other) - az;
                double required =
                        shared
                                ? Math.max(separation, radius + maximumRadius(laneOf(other)))
                                : 2 * radius;
                if (px * px + pz * pz < required * required
                        && (shared || eligible == null || eligible.test(cx + dx, cz + dz, other)))
                    return false;
            }
        return true;
    }

    public static double random(long key, int salt) {
        return SeededNoise.unit(SeededNoise.mix(key + salt * 0x9e3779b97f4a7c15L));
    }
}
