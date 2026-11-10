package cc.orthrus.riven.worldgen;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;

/**
 * How often each climate shows up in a world (terrain-v129, riven-climate.json coverage): the
 * fewest separate land patches, of at least minimumPatchSquareKilometres, that any of the 15
 * temperature x moisture combinations forms on a grid of gridCells per side over the world
 * square, read from the intent (effective climate after province offsets and the lapse rate). The
 * maps draw the climate's phase until every combination reaches minimumPatches (ClimateDraw in
 * GenerationMaps); a pure function of seed and definitions, so the kept draw never changes.
 */
public final class ClimateCoverage {
    private static final JsonObject COVERAGE =
            Rules.load("riven-climate.json", 1).json.getAsJsonObject("coverage");

    /** Draws the climate's phase at most this many times. */
    public static final int ATTEMPTS = (int) Rules.number(COVERAGE, "attempts", 1, 32);

    /** Patches every combination should form. */
    public static final int MIN_PATCHES = (int) Rules.number(COVERAGE, "minimumPatches", 1, 64);

    private static final int GRID = (int) Rules.number(COVERAGE, "gridCells", 16, 1024);
    private static final double MIN_AREA =
            Rules.number(COVERAGE, "minimumPatchSquareKilometres", 0, 1024) * 1e6;

    private ClimateCoverage() {}

    /** Bounds and stopping rules of a stored selection, without measuring its intent grid. */
    static boolean validSelection(int attempt, int draws, int fewest) {
        int regions = TemperatureBand.values().length * MoistureBand.values().length;
        return attempt >= 0
                && attempt < draws
                && draws >= 1
                && draws <= ATTEMPTS
                && fewest >= 0
                && fewest <= GRID * GRID / regions
                && (fewest >= MIN_PATCHES ? draws == attempt + 1 : draws == ATTEMPTS);
    }

    /** The fewest patches any combination forms in this world. */
    static int fewest(TerrainMap terrain, WorldScale world, BuildPool pool) {
        int n = GRID, regions = TemperatureBand.values().length * MoistureBand.values().length;
        double step = world.side() / n, half = world.side() / 2;
        int minCells = Math.max(1, (int) Math.ceil(MIN_AREA / (step * step)));
        byte[] region = new byte[n * n];
        pool.forEach(
                n,
                r -> {
                    TerrainMap.BaseSample b = new TerrainMap.BaseSample();
                    for (int c = 0; c < n; c++) {
                        terrain.intent(-half + (c + .5) * step, -half + (r + .5) * step, b);
                        region[r * n + c] =
                                b.ocean.marine
                                        ? -1
                                        : (byte)
                                                (TemperatureBand.at(b.climate.celsius).ordinal()
                                                                * MoistureBand.values().length
                                                        + MoistureBand.at(b.climate.moisture)
                                                                .ordinal());
                    }
                });
        int[] patches = new int[regions];
        boolean[] seen = new boolean[n * n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int k = 0; k < n * n; k++) {
            if (region[k] < 0 || seen[k]) continue;
            int size = 0, id = region[k];
            seen[k] = true;
            queue.add(k);
            while (!queue.isEmpty()) {
                int q = queue.poll(), qc = q % n;
                size++;
                int[] next = {q - n, q + n, qc > 0 ? q - 1 : -1, qc < n - 1 ? q + 1 : -1};
                for (int m : next)
                    if (m >= 0 && m < n * n && !seen[m] && region[m] == id) {
                        seen[m] = true;
                        queue.add(m);
                    }
            }
            if (size >= minCells) patches[id]++;
        }
        int fewest = Integer.MAX_VALUE;
        for (int p : patches) fewest = Math.min(fewest, p);
        return fewest;
    }
}
