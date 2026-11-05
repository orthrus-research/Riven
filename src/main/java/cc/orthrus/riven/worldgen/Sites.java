package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

/**
 * Shared factory for sparse major-feature sites, independent of terrain queries. Every lane draws
 * its own anchor in every cell from a lane-salted key with that lane's density (laneDensity in
 * riven-terrain.json), and only same-lane neighbours compete, so supports of different lanes may
 * overlap and compose in stage order.
 */
public final class Sites {
    public enum Lane {
        LANDMARK(0),
        CARVER(1),
        COLD(4),
        VOLCANIC(5),
        CUTS(6);

        /** A stable salt for the lane's anchors, kept when lanes come and go. */
        final int salt;

        Lane(int salt) {
            this.salt = salt;
        }
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
    private final double probability, coldRadius, volcanicRadius;

    /** Each lane's anchor density per cell. */
    private final double[] density = new double[Lane.values().length];

    private final long[] laneSeeds = new long[Lane.values().length];

    public static Sites create(long worldSeed, TerrainHeightReference heights, JsonObject json) {
        return new Sites(worldSeed, heights, json);
    }

    private Sites(long worldSeed, TerrainHeightReference heights, JsonObject json) {
        long seed = SeededNoise.mix(worldSeed ^ Rules.seed(json, "variationSeed"));
        spacing =
                heights.feature("inland_plain").width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(json, "spacingInPlainWidths", 0.1, 4);
        probability = Rules.number(json, "anchorProbability", 0, 1);
        maximumRadius = spacing * Rules.number(json, "maximumRadiusInCells", 0.1, 1);
        coldRadius =
                heights.feature("cirque").width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(json, "maximumColdRadiusInCirqueWidths", 1, 8);
        volcanicRadius =
                heights.feature("volcanic_cone").width.targetAverage
                        * TerrainScale.HORIZONTAL
                        * Rules.number(json, "maximumVolcanicRadiusInConeWidths", .5, 4);
        searchRadius = (int) Math.ceil(maximumRadius / spacing);
        certificateStep = (int) Rules.number(json, "certificateLatticeBlocks", 1, 32);
        certificateMargin = Rules.number(json, "certificateHeightMarginBlocks", 0, 64);
        JsonObject d = json.getAsJsonObject("laneDensity");
        for (Lane lane : Lane.values()) {
            density[lane.ordinal()] = Rules.number(d, lane.name(), 0, 1);
            laneSeeds[lane.ordinal()] =
                    SeededNoise.mix(seed ^ (0x4c414e4553454544L + lane.salt * 0x9e3779b97f4a7c15L));
        }
    }

    /** The first lattice node at or above {@code min}. */
    public int firstNode(int min) {
        return Math.floorDiv(min + certificateStep - 1, certificateStep) * certificateStep;
    }

    /** Half the node diagonal: every column lies within this distance of a node. */
    public double certificateReach() {
        return certificateStep == 1 ? 0 : certificateStep * .7072;
    }

    /** The lane's anchor key for a cell. */
    public long key(Lane lane, int x, int z) {
        return SeededNoise.cell(laneSeeds[lane.ordinal()], x, z);
    }

    public double x(int cell, long key) {
        return (cell + 0.12 + 0.76 * random(key, 2)) * spacing;
    }

    public double z(int cell, long key) {
        return (cell + 0.12 + 0.76 * random(key, 3)) * spacing;
    }

    public double maximumRadius(Lane lane) {
        return lane == Lane.COLD
                ? coldRadius
                : lane == Lane.VOLCANIC ? volcanicRadius : maximumRadius;
    }

    public int searchRadius(Lane lane) {
        return (int) Math.ceil(maximumRadius(lane) / spacing);
    }

    /** An anchor's own admission from earlier facts; it must never read another site. */
    public interface Eligibility {
        boolean test(int cx, int cz, long key);
    }

    /** Whether the cell's anchor exists in the lane, before any competition. */
    public boolean inLane(long key, Lane lane) {
        return random(key, 1) < probability && random(key, 0x4c414e45) < density[lane.ordinal()];
    }

    /** A higher-priority same-lane neighbour vetoes this site; it never asks whether it won. */
    public boolean admits(int cx, int cz, long key, Lane lane) {
        return admits(cx, cz, key, lane, null);
    }

    /**
     * As {@link #admits(int, int, long, Lane)}, but only neighbours whose own anchor passes
     * {@code eligible} can veto, so anchors the lane could never build do not suppress buildable
     * ones. Eligibility reads only that anchor's earlier facts, never another site's acceptance,
     * so there is no recursion.
     */
    public boolean admits(int cx, int cz, long key, Lane lane, Eligibility eligible) {
        if (!inLane(key, lane)) return false;
        double reach = 2 * maximumRadius(lane);
        int competitionRadius = (int) Math.floor(reach / spacing + 0.76);
        double ax = x(cx, key), az = z(cz, key), priority = random(key, 101);
        for (int dz = -competitionRadius; dz <= competitionRadius; dz++)
            for (int dx = -competitionRadius; dx <= competitionRadius; dx++) {
                if (dx == 0 && dz == 0) continue;
                long other = key(lane, cx + dx, cz + dz);
                if (!inLane(other, lane)) continue;
                double rank = random(other, 101);
                if (rank > priority || (rank == priority && (dx > 0 || (dx == 0 && dz > 0))))
                    continue;
                double px = x(cx + dx, other) - ax, pz = z(cz + dz, other) - az;
                if (px * px + pz * pz < reach * reach
                        && (eligible == null || eligible.test(cx + dx, cz + dz, other)))
                    return false;
            }
        return true;
    }

    public static double random(long key, int salt) {
        return SeededNoise.unit(SeededNoise.mix(key + salt * 0x9e3779b97f4a7c15L));
    }
}
